package com.wifishare

import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.wifishare.data.SettingsRepository
import com.wifishare.server.Clients
import com.wifishare.server.ServerService
import com.wifishare.util.WifiMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Quick Settings tile — pull-down panel toggle showing server status and
 * client count, one tap to start/stop. User adds it manually via Edit
 * Tiles, or we offer requestAddTileService() on API 33+.
 */
@RequiresApi(Build.VERSION_CODES.N)
class WifiShareTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope = s

        // Update tile whenever the server state OR the connected-client
        // count changes while the panel is open.
        combine(ServerService.state, Clients.state, WifiMonitor.state) { server, clients, wifi ->
            Triple(server, clients.size, wifi)
        }
            .onEach { (server, count, wifi) -> render(server, count, wifi) }
            .launchIn(s)
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val current = ServerService.state.value
        if (current is ServerService.State.Running) {
            ServerService.stop(applicationContext)
            return
        }
        // Not running — start it. We need settings to do so.
        startServerFromSettings()
    }

    private fun startServerFromSettings() {
        val ctx = applicationContext
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val settings = SettingsRepository(ctx).settings.first()

            if (settings.folderUri == null) {
                // Nothing to serve yet — open the app for first-run setup
                launchActivity()
                return@launch
            }
            if (WifiMonitor.currentIp() == null) {
                launchActivity() // user needs to connect to WiFi
                return@launch
            }

            val password = if (settings.passwordEnabled && settings.password.length == 6)
                settings.password else ""

            ServerService.start(
                ctx,
                Uri.parse(settings.folderUri),
                settings.port,
                settings.allowUploads,
                settings.allowDelete,
                password,
            )
        }
    }

    private fun launchActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ requires PendingIntent variant
            val pi = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(
        server: ServerService.State,
        clientCount: Int,
        wifi: WifiMonitor.State,
    ) {
        val tile = qsTile ?: return
        tile.label = "WiFi Share"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)

        when {
            server is ServerService.State.Running -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = when {
                    clientCount == 0 -> "Active"
                    clientCount == 1 -> "1 connected"
                    else -> "$clientCount connected"
                }
            }
            wifi !is WifiMonitor.State.Connected -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "No WiFi"
            }
            server is ServerService.State.Error -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Error · Tap"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Tap to start"
            }
        }
        tile.updateTile()
    }
}
