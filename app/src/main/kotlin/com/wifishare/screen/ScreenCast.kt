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
        val isH264: Boolean = false,
        val bitrateKbps: Int = 0,
    ) {
        Fast("Fast", 768, 18, 60),                                    // tiny CPU, low fps — for slow networks
        Balanced("Balanced", 1024, 24, 62),                           // sweet spot for static UIs
        Smooth("Smooth", 1280, 32, 0, isH264 = true, bitrateKbps = 4_000),   // H.264 hardware, screen-share default
        Ultra("Ultra", 1280, 60, 0, isH264 = true, bitrateKbps = 8_000),     // gamers / animations
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

    // ── H.264 broadcast plumbing ────────────────────────────────────
    // Active during H264 mode. Subscribers register their sink and get
    // called once for the codec config (SPS+PPS) on subscribe, then for
    // every access unit. CopyOnWriteArraySet so we can add/remove from
    // any thread while the encoder drain loop iterates.

    fun interface H264Sink {
        fun onAccessUnit(data: ByteArray, isKeyframe: Boolean, ptsUs: Long)
    }

    private val sinks = java.util.concurrent.CopyOnWriteArraySet<H264Sink>()

    @Volatile
    var h264Config: ByteArray? = null     // SPS + PPS in annex-B
        internal set
    @Volatile
    var h264Width: Int = 0
        internal set
    @Volatile
    var h264Height: Int = 0
        internal set

    /** Latest IDR frame, replayed to newly-subscribed clients so they don't have to wait for the next keyframe. */
    @Volatile
    var lastKeyframe: ByteArray? = null
        internal set

    internal fun broadcastAccessUnit(data: ByteArray, isKeyframe: Boolean, ptsUs: Long) {
        if (isKeyframe) lastKeyframe = data
        for (s in sinks) {
            runCatching { s.onAccessUnit(data, isKeyframe, ptsUs) }
        }
    }

    fun registerSink(sink: H264Sink) { sinks.add(sink) }
    fun unregisterSink(sink: H264Sink) { sinks.remove(sink) }

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
