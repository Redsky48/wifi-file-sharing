package com.wifishare.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton registry of recently active clients. Records every HTTP
 * request by remote IP, and lets the Windows/Mac companion register a
 * stable clientId + friendly hostname for nicer display.
 *
 * Anonymous clients (browsers, raw HTTP probes) get an auto-generated id
 * and are still listed — they just lack a friendly name.
 */
object Clients {

    private const val ACTIVE_WINDOW_MS = 5L * 60_000L // 5 minutes

    data class Client(
        val clientId: String,
        val name: String,           // friendly name or "Browser"/"Anonymous"
        val address: String,        // IP
        val firstSeen: Long,
        val lastSeen: Long,
        val transfers: Int,
        val userAgent: String?,
        val registered: Boolean,    // true if /api/clients/register was called
    )

    private val byAddress = ConcurrentHashMap<String, Client>()

    /** Dedupes "transfer" counts so a single playing media file (which
     *  emits hundreds of Range GETs) only bumps the counter once. */
    private val recentTransfers = ConcurrentHashMap<String, Long>()
    private const val TRANSFER_DEDUPE_MS = 30_000L

    private val _state = MutableStateFlow<List<Client>>(emptyList())
    val state: StateFlow<List<Client>> = _state.asStateFlow()

    /** Called from FileServer.serve() for every request. */
    fun touch(address: String, userAgent: String?, isTransfer: Boolean) {
        if (address.isBlank() || isLoopback(address)) return
        val now = System.currentTimeMillis()
        byAddress.compute(address) { _, existing ->
            if (existing == null) {
                Client(
                    clientId = UUID.randomUUID().toString(),
                    name = guessName(userAgent),
                    address = address,
                    firstSeen = now,
                    lastSeen = now,
                    transfers = if (isTransfer) 1 else 0,
                    userAgent = userAgent,
                    registered = false,
                )
            } else {
                existing.copy(
                    lastSeen = now,
                    transfers = existing.transfers + (if (isTransfer) 1 else 0),
                    userAgent = userAgent ?: existing.userAgent,
                )
            }
        }
        publish()
    }

    /**
     * Returns true only the first time we've seen this transfer key
     * (e.g. URL path) within the dedupe window. Subsequent hits to the
     * same file from the same IP within 30s return false — that way
     * audio/video players spamming Range GETs only bump the visible
     * counter once per playback session.
     */
    fun isUniqueTransfer(address: String, transferKey: String): Boolean {
        if (address.isBlank() || transferKey.isBlank()) return false
        val key = "$address|$transferKey"
        val now = System.currentTimeMillis()
        val prev = recentTransfers.put(key, now)

        // Cheap periodic cleanup
        if (recentTransfers.size > 500) {
            val cutoff = now - TRANSFER_DEDUPE_MS * 2
            recentTransfers.entries.removeAll { it.value < cutoff }
        }

        return prev == null || (now - prev) >= TRANSFER_DEDUPE_MS
    }

    /** Called from POST /api/clients/register — promotes anon entry to named. */
    fun register(address: String, providedClientId: String?, name: String): Client {
        val now = System.currentTimeMillis()
        val wasRegistered = byAddress[address]?.registered == true
        val finalClientId = providedClientId?.takeIf { it.isNotBlank() }
            ?: byAddress[address]?.clientId
            ?: UUID.randomUUID().toString()

        val updated = byAddress.compute(address) { _, existing ->
            (existing?.copy(
                clientId = finalClientId,
                name = name,
                lastSeen = now,
                registered = true,
            )) ?: Client(
                clientId = finalClientId,
                name = name,
                address = address,
                firstSeen = now,
                lastSeen = now,
                transfers = 0,
                userAgent = null,
                registered = true,
            )
        }!!
        publish()
        if (!wasRegistered) {
            // Don't spam re-registers as new connections — only first promote
            PhoneEvents.push("client.connected", mapOf(
                "clientId" to updated.clientId,
                "name" to updated.name,
                "address" to updated.address,
            ))
        }
        return updated
    }

    fun activeNow(): List<Client> {
        val cutoff = System.currentTimeMillis() - ACTIVE_WINDOW_MS
        return byAddress.values.filter { it.lastSeen >= cutoff }
            .sortedByDescending { it.lastSeen }
    }

    fun byClientId(clientId: String): Client? =
        byAddress.values.firstOrNull { it.clientId == clientId }

    /** Drop entries older than the active window (called periodically from UI). */
    fun prune() {
        val cutoff = System.currentTimeMillis() - ACTIVE_WINDOW_MS
        val toRemove = byAddress.entries.filter { it.value.lastSeen < cutoff }
        toRemove.forEach { byAddress.remove(it.key) }
        if (toRemove.isNotEmpty()) {
            publish()
            toRemove.forEach { (_, c) ->
                PhoneEvents.push("client.disconnected", mapOf(
                    "clientId" to c.clientId,
                    "name" to c.name,
                    "address" to c.address,
                ))
            }
        }
    }

    fun clear() {
        byAddress.clear()
        publish()
    }

    private fun publish() {
        _state.value = activeNow()
    }

    private fun guessName(userAgent: String?): String {
        if (userAgent == null) return "Anonymous"
        return when {
            userAgent.contains("Microsoft-WebDAV", ignoreCase = true) -> "Windows Explorer"
            userAgent.contains("WiFiShareTray", ignoreCase = true) -> "WiFi Share companion"
            userAgent.contains("Mozilla", ignoreCase = true) -> "Browser"
            userAgent.contains("curl", ignoreCase = true) -> "curl"
            else -> userAgent.take(40)
        }
    }

    private fun isLoopback(address: String): Boolean =
        address == "127.0.0.1" || address == "::1" || address.startsWith("::ffff:127.")
}
