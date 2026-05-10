package com.wifishare.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Advertises the running file server over mDNS so the Windows companion app
 * (or other devices on the LAN) can discover it without typing an IP.
 *
 * Registers two service types so existing tooling on the LAN can spot us too:
 *   - _wifishare._tcp  (custom; companion app filter)
 *   - _webdav._tcp     (so generic WebDAV browsers see it)
 */
class MdnsAdvertiser(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    private val listeners = mutableListOf<NsdManager.RegistrationListener>()

    fun start(port: Int, deviceName: String, authRequired: Boolean) {
        stop()
        // One service type only — registering multiple under the same instance
        // name confuses several mDNS browsers (they show the device 3 times).
        register(port, "WiFi Share - $deviceName", "_wifishare._tcp.", authRequired)
    }

    fun stop() {
        listeners.forEach {
            runCatching { nsd.unregisterService(it) }
        }
        listeners.clear()
    }

    private fun register(port: Int, name: String, type: String, authRequired: Boolean) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            // TXT records — companion apps look at "auth" to know whether
            // they need to prompt for a password or can connect anonymously.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                setAttribute("auth", if (authRequired) "required" else "none")
                setAttribute("ver", "1")
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        }
        runCatching {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            listeners += listener
        }
    }
}
