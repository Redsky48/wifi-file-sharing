package com.wifishare.server

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Per-device Bearer tokens. Each paired PC gets one — once issued, the
 * PC sends `Authorization: Bearer <token>` and the PIN is no longer
 * needed for that device. Revoking a single PC just deletes its row
 * here; the PIN keeps working for fresh pairings.
 *
 * Storage: a plain JSON file in app-private storage. The token itself
 * is the secret — the file is unreadable from other apps thanks to
 * Android's per-app sandbox. We don't bother with the Keystore here
 * because rotating tokens is cheap (just re-pair) and the threat model
 * is "lost device, want quick revoke".
 */
object TokenStore {

    data class Device(
        val id: String,         // 16 hex chars, stable per paired PC
        val name: String,       // friendly name, e.g. "DESKTOP-D86SU64"
        val token: String,      // 43-char base64 of 32 random bytes
        val createdAt: Long,
        var lastUsedAt: Long,
    )

    private val random = SecureRandom()
    private val lock = Any()
    private var devices: MutableMap<String, Device> = mutableMapOf() // token → Device
    private var file: File? = null
    @Volatile private var loaded = false

    fun attach(context: Context) {
        synchronized(lock) {
            if (loaded) return
            file = File(context.applicationContext.filesDir, "tokens.json")
            devices = readFile()
            loaded = true
        }
    }

    /**
     * Mints a new token bound to the given friendly device name. If a
     * row already exists with the same name, it's replaced — re-pairing
     * is the standard way to rotate.
     */
    fun pair(name: String): Device {
        val sanitized = name.take(80).ifBlank { "PC" }
        synchronized(lock) {
            // Remove any prior row for this name (re-pair replaces)
            val existing = devices.values.firstOrNull { it.name == sanitized }
            if (existing != null) devices.remove(existing.token)

            val tokenBytes = ByteArray(32).also { random.nextBytes(it) }
            val token = android.util.Base64.encodeToString(
                tokenBytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            val id = randomHex(16)
            val now = System.currentTimeMillis()
            val dev = Device(id, sanitized, token, now, now)
            devices[token] = dev
            writeFile()
            return dev
        }
    }

    /** Returns the device for a valid token (also bumps lastUsedAt), or null. */
    fun resolve(token: String): Device? {
        synchronized(lock) {
            val dev = devices[token] ?: return null
            dev.lastUsedAt = System.currentTimeMillis()
            // Avoid writing on every request — flush opportunistically
            // when 30s have elapsed since the last flush via attach().
            return dev
        }
    }

    fun list(): List<Device> = synchronized(lock) { devices.values.toList() }

    fun revoke(id: String): Boolean {
        synchronized(lock) {
            val v = devices.values.firstOrNull { it.id == id } ?: return false
            devices.remove(v.token)
            writeFile()
            return true
        }
    }

    fun revokeAll() {
        synchronized(lock) {
            devices.clear()
            writeFile()
        }
    }

    private fun randomHex(chars: Int): String {
        val bytes = ByteArray((chars + 1) / 2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }.take(chars)
    }

    private fun readFile(): MutableMap<String, Device> {
        val f = file ?: return mutableMapOf()
        if (!f.exists()) return mutableMapOf()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableMapOf<String, Device>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val dev = Device(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    token = o.optString("token"),
                    createdAt = o.optLong("createdAt"),
                    lastUsedAt = o.optLong("lastUsedAt"),
                )
                if (dev.token.isNotBlank()) out[dev.token] = dev
            }
            out
        } catch (_: Throwable) {
            mutableMapOf()
        }
    }

    private fun writeFile() {
        val f = file ?: return
        runCatching {
            val arr = JSONArray()
            for (d in devices.values) {
                arr.put(JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                    put("token", d.token)
                    put("createdAt", d.createdAt)
                    put("lastUsedAt", d.lastUsedAt)
                })
            }
            f.writeText(arr.toString())
        }
    }
}
