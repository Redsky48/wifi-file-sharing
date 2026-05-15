package com.wifishare.server

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide event broadcaster, mirror of the Windows tray's
 * LocalEvents. Anything interesting that happens server-side gets
 * Push'd here; WebSocket / SSE endpoints on /ws/events and /api/events
 * forward every envelope to subscribed clients.
 *
 * Use a SharedFlow with replay=0 so subscribers only see events that
 * happen after they connect (no replay storm on reconnect). Buffer of
 * 64 with DROP_OLDEST means a slow consumer loses old events instead
 * of blocking the producer.
 */
object PhoneEvents {

    data class Envelope(val type: String, val data: Map<String, Any?>, val ts: Long, val seq: Long)

    private val _seq = AtomicLong(0)
    private val _events = MutableSharedFlow<Envelope>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Envelope> = _events.asSharedFlow()

    fun push(type: String, data: Map<String, Any?>) {
        val ev = Envelope(
            type = type,
            data = data,
            ts = System.currentTimeMillis(),
            seq = _seq.incrementAndGet(),
        )
        _events.tryEmit(ev)
    }
}
