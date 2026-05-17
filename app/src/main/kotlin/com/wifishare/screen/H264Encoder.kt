package com.wifishare.screen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Hardware H.264 encoder backed by a Surface — exactly what
 * `VirtualDisplay.createVirtualDisplay(surface=...)` wants. The
 * VirtualDisplay sends each rendered frame straight into the encoder
 * via the input surface, no Bitmap or memcpy.
 *
 * Drains output buffers on a dedicated thread. On the first output we
 * grab the codec-config blob (SPS + PPS in annex-B) and stash it on
 * [ScreenCast.h264Config] — broadcast subscribers replay it on join so
 * a viewer can configure its WebCodecs decoder without waiting for the
 * next mid-stream keyframe.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitrateBps: Int,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
    val inputSurface: Surface
    private var drainThread: Thread? = null
    @Volatile private var running = false

    // Some encoders reject odd dimensions or sizes that aren't multiples
    // of 16 — round up to 16 to be safe. This means the captured frame
    // may be slightly larger than the requested size, but the surface
    // accepts any source dimensions and scales internally.
    private val codecWidth = alignUp(width, 16)
    private val codecHeight = alignUp(height, 16)

    init {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, codecWidth, codecHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            // Keyframe every second — keeps mid-join latency low and the
            // bitrate budget reasonable on screen content (which has long
            // static stretches where I-frames don't hurt).
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Variable bitrate so the encoder can ride out high-motion
            // moments without blowing the average target.
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // Note: deliberately *not* setting KEY_PROFILE/KEY_LEVEL.
            // Some OEM encoders crash at codec.start() if the requested
            // profile isn't supported. Letting the encoder pick its own
            // is more robust, and the output is still Baseline-compatible
            // in practice.
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    /**
     * Must be called BEFORE the VirtualDisplay is attached to
     * [inputSurface] — otherwise the display starts rendering into a
     * codec that hasn't been started yet, which OEM implementations
     * handle inconsistently (some crash with IllegalStateException).
     */
    fun start() {
        if (running) return
        running = true
        codec.start()
        ScreenCast.h264Width = codecWidth
        ScreenCast.h264Height = codecHeight
        drainThread = Thread(::drainLoop, "wifishare-h264-drain").apply {
            isDaemon = true
            start()
        }
    }

    private fun alignUp(value: Int, multiple: Int): Int =
        ((value + multiple - 1) / multiple) * multiple

    fun stop() {
        running = false
        try { drainThread?.interrupt() } catch (_: Throwable) {}
        drainThread = null
        try { codec.stop() } catch (_: Throwable) {}
        try { codec.release() } catch (_: Throwable) {}
        try { inputSurface.release() } catch (_: Throwable) {}
        ScreenCast.h264Config = null
        ScreenCast.lastKeyframe = null
        ScreenCast.h264Width = 0
        ScreenCast.h264Height = 0
    }

    /**
     * Pulls encoded access units out of MediaCodec and forwards them to
     * [ScreenCast.broadcastAccessUnit]. Runs until [stop] is called or
     * an unrecoverable codec error happens.
     */
    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        val fpsWindowMs = 1000L
        var windowStart = System.currentTimeMillis()
        var framesInWindow = 0
        while (running && !Thread.currentThread().isInterrupted) {
            val idx = try {
                codec.dequeueOutputBuffer(info, 50_000) // 50 ms
            } catch (_: IllegalStateException) {
                break
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (idx < 0) continue

            val buf: ByteBuffer? = try {
                codec.getOutputBuffer(idx)
            } catch (_: IllegalStateException) {
                break
            }
            if (buf == null) {
                runCatching { codec.releaseOutputBuffer(idx, false) }
                continue
            }
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            val data = ByteArray(info.size)
            buf.get(data)

            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

            if (isConfig) {
                // SPS+PPS — stash for later subscribers. Don't broadcast
                // as a regular AU; subscribers receive config on join.
                ScreenCast.h264Config = data
            } else {
                ScreenCast.broadcastAccessUnit(data, isKey, info.presentationTimeUs)
                framesInWindow++
                val now = System.currentTimeMillis()
                val elapsed = now - windowStart
                if (elapsed >= fpsWindowMs) {
                    ScreenCast.setMeasuredFps(framesInWindow * 1000f / elapsed)
                    framesInWindow = 0
                    windowStart = now
                }
            }

            try { codec.releaseOutputBuffer(idx, false) } catch (_: IllegalStateException) {}
        }
    }

    companion object {
        const val MIME_TYPE = "video/avc"
    }
}
