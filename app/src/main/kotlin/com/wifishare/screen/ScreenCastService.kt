package com.wifishare.screen

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.wifishare.MainActivity
import com.wifishare.R
import com.wifishare.WifiShareApp
import com.wifishare.server.PhoneEvents
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service that captures the phone's screen via MediaProjection
 * and pushes JPEG frames into [ScreenCast]. The HTTP server then streams
 * those frames as MJPEG to browser viewers.
 *
 * Lifecycle:
 *   1. Activity asks for the MediaProjection permission, gets a result
 *      Intent.
 *   2. Activity calls [start] with that result. The intent is passed
 *      through to onStartCommand, which boots us as a foreground service
 *      with the MEDIA_PROJECTION type (Android 14+ requirement).
 *   3. We get a [MediaProjection] from the result, build a [VirtualDisplay]
 *      backed by an [ImageReader], and grab a new frame every ~67ms (~15 fps).
 *   4. Stop via [stop] or when the user revokes via the system UI — the
 *      MediaProjection callback fires and we self-stop.
 */
class ScreenCastService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: Thread? = null
    private var encoderThread: Thread? = null

    // Display metrics captured at start so rotations don't crash us mid-cast.
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0

    private var activeMode: ScreenCast.Mode = ScreenCast.Mode.Balanced

    // Pre-allocated bitmaps so the capture/encode loop never goes through
    // GC pressure. The padded bitmap mirrors ImageReader's row layout
    // (with potential row padding); the cropped bitmap is what we hand
    // to JPEG.compress(). Both stay alive for the duration of the cast.
    private var paddedBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var lastPaddedWidth = 0

    // Hand-off slot from capture thread → encoder thread. Capture writes
    // the next-frame bitmap reference; encoder takes ownership, encodes,
    // and clears the slot. One frame in flight at a time (the next
    // capture overwrites if encoder is still busy).
    private val pendingFrame = AtomicReference<Bitmap?>(null)
    private val frameReady = Semaphore(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: run {
            stopSelfSafely()
            return
        }
        val modeName = intent.getStringExtra(EXTRA_MODE)
        activeMode = runCatching { ScreenCast.Mode.valueOf(modeName ?: "") }
            .getOrDefault(ScreenCast.Mode.Balanced)
        ScreenCast.setMode(activeMode)

        startForegroundCompat()

        // Determine the target capture dimensions. We pick the natural
        // display size scaled to the mode's long-edge cap.
        val (w, h, density) = computeMetrics(activeMode.longEdge)
        captureWidth = w
        captureHeight = h
        captureDensity = density

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(resultCode, data) ?: run {
            stopSelfSafely()
            return
        }
        projection = proj

        // MediaProjection.Callback is mandatory on API 34+ — we get a
        // stop event when the user revokes permission from the system UI.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelfSafely()
            }
        }, null)

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = proj.createVirtualDisplay(
            "WiFiShareScreenCast",
            w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )

        ScreenCast.setRunning(true)
        PhoneEvents.push("screen.started", mapOf("width" to w, "height" to h))

        // We grab frames on a dedicated thread instead of using
        // ImageReader.OnImageAvailableListener — the listener fires per
        // V-sync which is way more than we need (and the CPU cost of
        // compressing every frame to JPEG would blow up).
        startCaptureLoop(reader)
    }

    private fun startCaptureLoop(reader: ImageReader) {
        val mode = activeMode
        val frameIntervalMs = 1000L / mode.targetFps
        val jpegQuality = mode.jpegQuality

        // ── Encoder thread ────────────────────────────────────────────
        // Receives bitmaps from the capture thread via [pendingFrame],
        // JPEG-encodes them, and publishes to ScreenCast. Runs in parallel
        // with capture so we can overlap copy + encode + next acquire.
        val baos = ByteArrayOutputStream(96 * 1024)
        val fpsWindowMs = 1000L
        var windowStart = System.currentTimeMillis()
        var framesInWindow = 0
        encoderThread = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    frameReady.acquire()
                    val bmp = pendingFrame.getAndSet(null) ?: continue
                    baos.reset()
                    bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                    ScreenCast.publishFrame(baos.toByteArray(), bmp.width, bmp.height)

                    framesInWindow++
                    val now = System.currentTimeMillis()
                    val elapsed = now - windowStart
                    if (elapsed >= fpsWindowMs) {
                        ScreenCast.setMeasuredFps(framesInWindow * 1000f / elapsed)
                        framesInWindow = 0
                        windowStart = now
                    }
                }
            } catch (_: InterruptedException) { /* normal */ }
            catch (_: Throwable) { stopSelfSafely() }
        }, "wifishare-encoder").apply { isDaemon = true; start() }

        // ── Capture thread ───────────────────────────────────────────
        // Pulls the latest frame from ImageReader, copies into our
        // pre-allocated bitmap (creating it on first use / on dimension
        // change), and signals the encoder. If the encoder is still busy
        // we just overwrite — the encoder always sees the newest frame.
        captureThread = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val cycleStart = System.currentTimeMillis()
                    val img = runCatching { reader.acquireLatestImage() }.getOrNull()
                    if (img != null) {
                        try {
                            val plane = img.planes[0]
                            val rowStride = plane.rowStride
                            val pixelStride = plane.pixelStride
                            val rowPadding = rowStride - pixelStride * img.width
                            val paddedWidth = img.width + rowPadding / pixelStride

                            // Allocate / reallocate bitmaps lazily — they
                            // only change if the device's display geometry
                            // changes mid-cast, which is rare.
                            if (paddedBitmap == null ||
                                lastPaddedWidth != paddedWidth ||
                                paddedBitmap?.height != img.height
                            ) {
                                paddedBitmap?.recycle()
                                paddedBitmap = Bitmap.createBitmap(
                                    paddedWidth, img.height, Bitmap.Config.ARGB_8888,
                                )
                                lastPaddedWidth = paddedWidth
                                croppedBitmap?.recycle()
                                croppedBitmap = if (paddedWidth != img.width) {
                                    Bitmap.createBitmap(
                                        img.width, img.height, Bitmap.Config.ARGB_8888,
                                    )
                                } else null
                            }

                            val padded = paddedBitmap!!
                            padded.copyPixelsFromBuffer(plane.buffer)

                            val source = if (croppedBitmap != null) {
                                // Manual pixel copy is faster than
                                // Bitmap.createBitmap-with-crop because
                                // the latter always allocates.
                                val pixels = IntArray(img.width * img.height)
                                padded.getPixels(
                                    pixels, 0, img.width,
                                    0, 0, img.width, img.height,
                                )
                                croppedBitmap!!.setPixels(
                                    pixels, 0, img.width,
                                    0, 0, img.width, img.height,
                                )
                                croppedBitmap!!
                            } else padded

                            // Publish to encoder. If a frame is already
                            // queued, drop it (we'll catch up next tick).
                            if (pendingFrame.compareAndSet(null, source)) {
                                frameReady.release()
                            } else {
                                pendingFrame.set(source)
                                // Don't release — semaphore is already >0
                            }
                        } finally {
                            img.close()
                        }
                    }
                    val elapsed = System.currentTimeMillis() - cycleStart
                    val sleep = frameIntervalMs - elapsed
                    if (sleep > 0) Thread.sleep(sleep)
                }
            } catch (_: InterruptedException) { /* normal */ }
            catch (_: Throwable) { stopSelfSafely() }
        }, "wifishare-capture").apply { isDaemon = true; start() }
    }

    @Suppress("DEPRECATION")
    private fun computeMetrics(maxEdge: Int): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density: Int
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.maximumWindowMetrics
            w = metrics.bounds.width()
            h = metrics.bounds.height()
            density = resources.displayMetrics.densityDpi
        } else {
            val display: Display = wm.defaultDisplay
            val dm = DisplayMetrics()
            display.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            density = dm.densityDpi
        }
        // Scale down so we never push huge frames over WiFi — bandwidth +
        // JPEG-compress CPU cost grow with pixel count. The cap comes
        // from the active ScreenCast.Mode (Quality 1280 / Balanced 1024
        // / Smooth 768).
        val long = maxOf(w, h)
        return if (long > maxEdge) {
            val scale = maxEdge.toFloat() / long
            Triple((w * scale).toInt(), (h * scale).toInt(), density)
        } else {
            Triple(w, h, density)
        }
    }

    private fun handleStop() {
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        try { captureThread?.interrupt() } catch (_: Throwable) {}
        captureThread = null
        try { encoderThread?.interrupt() } catch (_: Throwable) {}
        encoderThread = null
        // Drain semaphore so a lingering acquire in the encoder doesn't
        // deadlock past the interrupt() — interrupt() should be enough,
        // but cheap belt + suspenders.
        try { frameReady.release() } catch (_: Throwable) {}
        pendingFrame.set(null)
        try { paddedBitmap?.recycle() } catch (_: Throwable) {}
        paddedBitmap = null
        try { croppedBitmap?.recycle() } catch (_: Throwable) {}
        croppedBitmap = null
        lastPaddedWidth = 0
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        if (ScreenCast.state.value == ScreenCast.State.Running) {
            ScreenCast.setRunning(false)
            PhoneEvents.push("screen.stopped", emptyMap())
        }
        stopForegroundCompat()
        stopSelf()
    }

    private fun startForegroundCompat() {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, WifiShareApp.CHANNEL_SERVER)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.screencast_notification_title))
            .setContentText(getString(R.string.screencast_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .addAction(0, getString(R.string.screencast_stop), stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // We deliberately keep the same canvas through rotations — the
        // VirtualDisplay scales the rotated frame to fit our buffer.
    }

    companion object {
        private const val ACTION_START = "com.wifishare.screen.START"
        private const val ACTION_STOP = "com.wifishare.screen.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_MODE = "mode"
        private const val NOTIF_ID = 2002

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            mode: ScreenCast.Mode = ScreenCast.Mode.Balanced,
        ) {
            val intent = Intent(context, ScreenCastService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_MODE, mode.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCastService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
