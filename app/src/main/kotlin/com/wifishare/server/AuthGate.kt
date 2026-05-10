package com.wifishare.server

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-IP failed-auth counter. After too many bad PINs in a short window,
 * any further attempts from that IP are rejected without even checking
 * the PIN — turning a 6-digit-PIN brute force from "doable in hours" into
 * "infeasible". A correct PIN clears the counter.
 *
 * Kept simple and in-memory: lockouts disappear when the server stops,
 * which is the right behaviour (the user who restarted the server is the
 * legitimate owner of the device).
 */
object AuthGate {

    private const val MAX_FAILURES = 5
    private const val WINDOW_MS = 60_000L      // count failures within last minute
    private const val LOCKOUT_MS = 30_000L     // lockout duration after threshold

    private data class Entry(val failures: ArrayDeque<Long>, var lockoutUntil: Long)

    private val byIp = ConcurrentHashMap<String, Entry>()

    fun isLockedOut(ip: String): Boolean {
        if (ip.isBlank()) return false
        val now = System.currentTimeMillis()
        val e = byIp[ip] ?: return false
        synchronized(e) {
            return e.lockoutUntil > now
        }
    }

    fun noteFailure(ip: String) {
        if (ip.isBlank()) return
        val now = System.currentTimeMillis()
        val e = byIp.computeIfAbsent(ip) { Entry(ArrayDeque(), 0) }
        synchronized(e) {
            // Drop failures older than the rolling window
            val cutoff = now - WINDOW_MS
            while (e.failures.isNotEmpty() && e.failures.first() < cutoff) e.failures.removeFirst()
            e.failures.addLast(now)
            if (e.failures.size >= MAX_FAILURES) {
                e.lockoutUntil = now + LOCKOUT_MS
                e.failures.clear()
            }
        }
    }

    fun noteSuccess(ip: String) {
        if (ip.isBlank()) return
        byIp.remove(ip)
    }

    fun clear() {
        byIp.clear()
    }
}
