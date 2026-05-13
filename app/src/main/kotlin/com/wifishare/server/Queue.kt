package com.wifishare.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory queue of files the phone wants to push to a specific
 * connected client. The companion app polls /api/queue/{clientId} and
 * downloads pending entries one by one. Files are stored in the app's
 * cache dir so they don't bloat shared storage.
 *
 * Exposes a [state] StateFlow with the current snapshot so the UI can
 * render pending items and the foreground notification can update its
 * count when items are added / acked / cancelled.
 */
object Queue {

    data class Item(
        val id: String,
        val clientId: String,
        val name: String,
        val size: Long,
        val mime: String,
        val tempFile: File,
        val createdAt: Long,
    )

    private val items = ConcurrentHashMap<String, Item>()
    private val byClient = ConcurrentHashMap<String, MutableList<String>>()
    private val lock = Any()

    private val _state = MutableStateFlow<List<Item>>(emptyList())
    val state: StateFlow<List<Item>> = _state.asStateFlow()

    fun enqueue(
        clientId: String,
        name: String,
        size: Long,
        mime: String,
        tempFile: File,
    ): Item {
        val id = UUID.randomUUID().toString()
        val item = Item(
            id = id,
            clientId = clientId,
            name = name,
            size = size,
            mime = mime,
            tempFile = tempFile,
            createdAt = System.currentTimeMillis(),
        )
        synchronized(lock) {
            items[id] = item
            byClient.getOrPut(clientId) { mutableListOf() }.add(id)
        }
        publish()
        return item
    }

    /** First pending item for the client, or null. */
    fun peek(clientId: String): Item? = synchronized(lock) {
        val ids = byClient[clientId] ?: return null
        ids.firstNotNullOfOrNull { items[it] }
    }

    fun pendingCount(clientId: String): Int = synchronized(lock) {
        byClient[clientId]?.count { items.containsKey(it) } ?: 0
    }

    fun get(clientId: String, id: String): Item? = synchronized(lock) {
        val item = items[id] ?: return null
        if (item.clientId != clientId) return null
        item
    }

    fun ack(clientId: String, id: String): Boolean {
        val acked = synchronized(lock) {
            val item = items.remove(id) ?: return false
            if (item.clientId != clientId) {
                items[id] = item
                return false
            }
            byClient[clientId]?.remove(id)
            runCatching { item.tempFile.delete() }
            true
        }
        if (acked) publish()
        return acked
    }

    /** Cancel a queued item by id, regardless of client. */
    fun cancel(id: String): Boolean {
        val removed = synchronized(lock) {
            val item = items.remove(id) ?: return false
            byClient[item.clientId]?.remove(id)
            runCatching { item.tempFile.delete() }
            true
        }
        if (removed) publish()
        return removed
    }

    fun clearForClient(clientId: String) {
        val any = synchronized(lock) {
            val ids = byClient.remove(clientId) ?: return
            ids.forEach { id -> items.remove(id)?.tempFile?.delete() }
            ids.isNotEmpty()
        }
        if (any) publish()
    }

    fun clearAll() {
        val any = synchronized(lock) {
            val hadAny = items.isNotEmpty()
            items.values.forEach { runCatching { it.tempFile.delete() } }
            items.clear()
            byClient.clear()
            hadAny
        }
        if (any) publish()
    }

    private fun publish() {
        _state.value = synchronized(lock) { items.values.toList() }
    }
}
