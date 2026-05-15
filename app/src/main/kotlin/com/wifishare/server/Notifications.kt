package com.wifishare.server

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Stream of custom system notifications requested via /api/notify.
 * The ServerService subscribes and posts each event into the Transfers
 * notification channel. Lets external automations (CI jobs, scripts,
 * webhooks) ping the phone — e.g. "Build done", "PR review needed".
 */
object Notifications {

    data class Custom(val title: String, val body: String)

    private val _events = MutableSharedFlow<Custom>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Custom> = _events.asSharedFlow()

    fun emit(title: String, body: String) {
        _events.tryEmit(Custom(title, body))
        PhoneEvents.push("notification", mapOf(
            "title" to title,
            "body" to body,
        ))
    }
}
