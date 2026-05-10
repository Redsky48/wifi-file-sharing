package com.wifishare.server

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory queue of files the phone wants to push to a specific
 * connected client. The companion app polls /api/queue/{clientId} and
 * downloads pending entries one by one. Files are stored in the app's
 * cache dir so they don't bloat shared storage.
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

    fun ack(clientId: String, id: String): Boolean = synchronized(lock) {
        val item = items.remove(id) ?: return false
        if (item.clientId != clientId) {
            // Put it back if client mismatch (shouldn't happen)
            items[id] = item
            return false
        }
        byClient[clientId]?.remove(id)
        runCatching { item.tempFile.delete() }
        true
    }

    fun clearForClient(clientId: String) {
        synchronized(lock) {
            val ids = byClient.remove(clientId) ?: return
            ids.forEach { id ->
                items.remove(id)?.tempFile?.delete()
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            items.values.forEach { runCatching { it.tempFile.delete() } }
            items.clear()
            byClient.clear()
        }
    }
}
