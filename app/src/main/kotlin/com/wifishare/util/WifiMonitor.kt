package com.wifishare.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

object WifiMonitor {

    sealed class State {
        data object Disconnected : State()
        data class Connected(val ip: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var attached = false

    fun attach(context: Context) {
        if (attached) return
        attached = true

        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refresh(cm, network, null)
            }

            override fun onLost(network: Network) {
                _state.value = State.Disconnected
            }

            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
                refresh(cm, network, props)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    _state.value = State.Disconnected
                } else {
                    refresh(cm, network, null)
                }
            }
        })
    }

    fun currentIp(): String? = (state.value as? State.Connected)?.ip

    private fun refresh(cm: ConnectivityManager, network: Network, hint: LinkProperties?) {
        val link = hint ?: cm.getLinkProperties(network) ?: return
        val ip = link.linkAddresses
            .asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
        _state.value = if (ip != null) State.Connected(ip) else State.Disconnected
    }
}
