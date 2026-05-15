package com.wifishare.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Enumerates the device's reachable IPv4 addresses across *all* network
 * interfaces — not just the WiFi STA one. This lets the server keep
 * working when the phone is acting as a WiFi hotspot (PC connects to
 * the phone's AP) or USB tether, even with WiFi STA disabled.
 *
 * NanoHTTPD already binds to 0.0.0.0, so the server is reachable on
 * every interface as soon as it starts; we just need to know *which*
 * address to display/advertise.
 */
object NetIp {

    data class Iface(val name: String, val ip: String)

    /**
     * All usable IPv4 addresses on currently-up interfaces.
     * Excludes loopback, link-local (169.254/16), and any-local.
     */
    fun allLocalIpv4(): List<Iface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp }.getOrDefault(false) && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter {
                        !it.isLoopbackAddress &&
                            !it.isLinkLocalAddress &&
                            !it.isAnyLocalAddress
                    }
                    .mapNotNull { it.hostAddress?.let { ip -> Iface(iface.name, ip) } }
            }
            .distinctBy { it.ip }
    }.getOrDefault(emptyList())

    /**
     * Picks the best address to show as the canonical URL. Order:
     *   1. Explicit wifiStaHint (current STA from WifiMonitor)
     *   2. wlan0 (standard WiFi STA name on most Android devices)
     *   3. Softap-looking interfaces (ap*, softap*, swlan*, wlan1)
     *   4. USB / rndis tether
     *   5. Any other non-loopback IPv4
     */
    fun preferredIp(wifiStaHint: String? = null): String? {
        if (!wifiStaHint.isNullOrBlank()) return wifiStaHint
        val all = allLocalIpv4()
        return all.firstOrNull { it.name.equals("wlan0", ignoreCase = true) }?.ip
            ?: all.firstOrNull { isSoftap(it.name) }?.ip
            ?: all.firstOrNull { it.name.startsWith("rndis", ignoreCase = true) }?.ip
            ?: all.firstOrNull()?.ip
    }

    private fun isSoftap(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("ap") ||
            lower.startsWith("softap") ||
            lower.startsWith("swlan") ||
            lower == "wlan1"
    }
}
