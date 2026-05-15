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

    // Display metrics captured at start so rotations don't crash us mid-cast.
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0

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

        startForegroundCompat()

        // Determine the target capture dimensions. We pick the natural
        // display size; rotations after cast starts will keep the same
        // canvas (rotated content rendered inside).
        val (w, h, density) = computeMetrics()
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
        val targetFps = 12
        val frameIntervalMs = 1000L / targetFps
        val jpegQuality = 70

        captureThread = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val cycleStart = System.currentTimeMillis()
                    val img = runCatching { reader.acquireLatestImage() }.getOrNull()
                    if (img != null) {
                        try {
                            val plane = img.planes[0]
                            val buffer = plane.buffer
                            val rowStride = plane.rowStride
                            val pixelStride = plane.pixelStride
                            val rowPadding = rowStride - pixelStride * img.width

                            // ImageReader gives us RGBA8888 with potential
                            // row padding. Bitmap.createBitmap with the
                            // padded width then crops back avoids copying
                            // each row manually.
                            val paddedWidth = img.width + rowPadding / pixelStride
                            val bmp = Bitmap.createBitmap(
                                paddedWidth, img.height, Bitmap.Config.ARGB_8888
                            )
                            bmp.copyPixelsFromBuffer(buffer)

                            val cropped = if (paddedWidth != img.width) {
                                val c = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
                                bmp.recycle()
                                c
                            } else bmp

                            val baos = ByteArrayOutputStream(64 * 1024)
                            cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                            cropped.recycle()
                            ScreenCast.publishFrame(baos.toByteArray(), img.width, img.height)
                        } finally {
                            img.close()
                        }
                    }
                    val elapsed = System.currentTimeMillis() - cycleStart
                    val sleep = frameIntervalMs - elapsed
                    if (sleep > 0) Thread.sleep(sleep)
                }
            } catch (_: InterruptedException) {
                // Normal shutdown path
            } catch (_: Throwable) {
                // Don't let the capture thread kill the process; just stop.
                stopSelfSafely()
            }
        }, "wifishare-screencast").apply {
            isDaemon = true
            start()
        }
    }

    @Suppress("DEPRECATION")
    private fun computeMetrics(): Triple<Int, Int, Int> {
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
        // Scale down so we never push huge frames over WiFi — capping the
        // long edge at 1280 keeps bandwidth manageable on a phone hotspot
        // and the JPEG-compress CPU cost in check.
        val maxEdge = 1280
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
        private const val NOTIF_ID = 2002

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCastService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
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
