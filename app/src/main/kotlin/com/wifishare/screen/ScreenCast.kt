package com.wifishare.screen

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide screen-cast state.
 *
 * Pattern: the MediaProjection-backed ScreenCastService writes the most
 * recent compressed JPEG frame into [latestJpeg]; HTTP handlers
 * (/api/screen MJPEG and /api/screen.jpg) read it. A short SharedFlow
 * [frameTicks] wakes blocked readers when a new frame is available — no
 * polling needed.
 *
 * No audio is captured. The user explicitly asked for video-only.
 */
object ScreenCast {

    enum class State { Stopped, Running }

    /**
     * Quality/throughput presets. Tuned for LAN-only use over a phone
     * hotspot or WiFi — bigger numbers chew more CPU on the phone, more
     * bandwidth on the wire, and lower fps on the viewer.
     */
    enum class Mode(
        val label: String,
        val longEdge: Int,
        val targetFps: Int,
        val jpegQuality: Int,
    ) {
        Quality("Quality", 1280, 18, 72),       // sharper text, slower fps
        Balanced("Balanced", 1024, 24, 62),     // default — readable + responsive
        Smooth("Smooth", 768, 32, 55),          // fps-first, softer image
    }

    private val _state = MutableStateFlow(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _mode = MutableStateFlow(Mode.Balanced)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    internal fun setMode(m: Mode) { _mode.value = m }

    @Volatile
    var measuredFps: Float = 0f
        private set
    internal fun setMeasuredFps(v: Float) { measuredFps = v }

    @Volatile
    private var _latestJpeg: ByteArray? = null

    /** Latest fully-encoded JPEG frame, or null if cast is stopped / no frame yet. */
    val latestJpeg: ByteArray?
        get() = _latestJpeg

    @Volatile
    var frameCount: Long = 0
        private set

    @Volatile
    var width: Int = 0
        private set

    @Volatile
    var height: Int = 0
        private set

    /**
     * Emitted whenever a new frame is published. Replay 1 so a reader
     * joining mid-stream immediately wakes once for the current frame.
     * Buffer 1 + DROP_OLDEST so we don't accumulate stale ticks for slow
     * readers — they just see the next-most-recent frame next time.
     */
    private val _frameTicks = MutableSharedFlow<Long>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frameTicks: SharedFlow<Long> = _frameTicks.asSharedFlow()

    internal fun publishFrame(jpeg: ByteArray, w: Int, h: Int) {
        _latestJpeg = jpeg
        width = w
        height = h
        frameCount++
        _frameTicks.tryEmit(frameCount)
    }

    internal fun setRunning(running: Boolean) {
        _state.value = if (running) State.Running else State.Stopped
        if (!running) {
            _latestJpeg = null
            frameCount = 0
            width = 0
            height = 0
            measuredFps = 0f
        }
    }
}
