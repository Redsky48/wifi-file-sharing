package com.wifishare.server

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class TransferEvent {
    abstract val name: String
    data class Uploaded(override val name: String) : TransferEvent()
    data class Downloaded(override val name: String) : TransferEvent()
    data class Deleted(override val name: String) : TransferEvent()
}

object Transfers {
    private val _events = MutableSharedFlow<TransferEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    fun emit(event: TransferEvent) {
        _events.tryEmit(event)
        val type = when (event) {
            is TransferEvent.Uploaded -> "transfer.uploaded"
            is TransferEvent.Downloaded -> "transfer.downloaded"
            is TransferEvent.Deleted -> "transfer.deleted"
        }
        PhoneEvents.push(type, mapOf("name" to event.name))
    }
}
