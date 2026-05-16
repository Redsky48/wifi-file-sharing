package com.wifishare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.wifishare.MainActivity
import com.wifishare.screen.ScreenCast
import com.wifishare.server.Clients
import com.wifishare.server.ServerService
import com.wifishare.util.WifiMonitor

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val serverState by viewModel.serverState.collectAsState()
    val wifiState by viewModel.wifiState.collectAsState()
    val currentIp by viewModel.currentIp.collectAsState()
    val clients by viewModel.clients.collectAsState()
    val pending by viewModel.pendingQueue.collectAsState()

    val wifiConnected = wifiState is WifiMonitor.State.Connected
    val hasNetwork = currentIp != null
    val onHotspot = hasNetwork && !wifiConnected

    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusCard(serverState)

        if (!hasNetwork) {
            WarningCard(
                title = "No network",
                body = "Connect to a WiFi network or turn on the phone's hotspot, then tap Start.",
            )
        } else if (onHotspot) {
            InfoRow("Mode", "Hotspot (PCs connect to this phone's AP)")
        }

        if (settings.folderUri == null) {
            WarningCard(
                title = "Folder not set",
                body = "Pick a download folder in Settings before starting the server.",
            )
        } else {
            InfoRow("Folder", settings.folderDisplay.ifBlank { "(picked)" })
            InfoRow("Port", settings.port.toString())
            InfoRow("Uploads", if (settings.allowUploads) "Allowed" else "Read-only")
            InfoRow("Delete", if (settings.allowDelete) "Allowed" else "Disabled")
        }

        if (serverState is ServerService.State.Running) {
            ConnectedClientsCard(clients)
        }

        if (pending.isNotEmpty()) {
            PendingQueueCard(
                pending = pending,
                clients = clients,
                onCancel = viewModel::cancelPending,
                onCancelAll = viewModel::cancelAllPending,
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val running = serverState is ServerService.State.Running
            val canStart = settings.folderUri != null && hasNetwork
            Button(
                onClick = {
                    if (running) viewModel.stopServer() else viewModel.startServer()
                },
                enabled = canStart || running,
                modifier = Modifier.weight(1f),
                colors = if (running) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ) else ButtonDefaults.buttonColors(),
            ) {
                Icon(
                    if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (running) "Stop" else "Start")
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onOpenFiles,
                modifier = Modifier.weight(1f),
                enabled = settings.folderUri != null,
            ) {
                Text("Browse files")
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                Text("Settings")
            }
        }

        // Screen cast — phone → browser viewer (video only, no audio).
        // Only useful when the server is running, since the cast feed is
        // served via the same HTTP endpoint.
        val castState by ScreenCast.state.collectAsState()
        val castRunning = castState == ScreenCast.State.Running
        val castMode by ScreenCast.mode.collectAsState()
        val ctx = LocalContext.current

        OutlinedButton(
            onClick = {
                val act = (ctx as? MainActivity) ?: return@OutlinedButton
                if (castRunning) act.stopScreenCast() else act.requestScreenCast(castMode)
            },
            enabled = serverState is ServerService.State.Running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (castRunning) "Stop screen cast" else "Cast screen to browser")
        }

        // Mode chips — pick before starting, or switch during cast (the
        // tap will stop+restart the cast with the new mode).
        ScreenCastModeChips(
            selected = castMode,
            castRunning = castRunning,
            onSelect = { mode ->
                ScreenCast.setMode(mode)
                val act = ctx as? MainActivity ?: return@ScreenCastModeChips
                if (castRunning) {
                    // Restart with new mode — the system MediaProjection
                    // permission is one-shot, so we need to re-prompt the
                    // user. They probably expect that anyway.
                    act.stopScreenCast()
                    act.requestScreenCast(mode)
                }
            },
        )

        if (castRunning && serverState is ServerService.State.Running) {
            val running = serverState as ServerService.State.Running
            // recompose every second so measuredFps stays live
            val fpsTick by produceState(0L) {
                while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
            }
            val fps = remember(fpsTick) { ScreenCast.measuredFps }
            Text(
                "Open ${running.url}/screen in any browser on the LAN. " +
                    if (fps > 0) "Now: %.1f fps · ${castMode.label} · ${castMode.longEdge}px".format(fps)
                    else "Encoding…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScreenCastModeChips(
    selected: ScreenCast.Mode,
    castRunning: Boolean,
    onSelect: (ScreenCast.Mode) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenCast.Mode.entries.forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = {
                    Text(
                        "${mode.label} · ${mode.targetFps} fps",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (castRunning) {
        Text(
            "Switching mode restarts the cast — Android will ask for permission again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PendingQueueCard(
    pending: List<com.wifishare.server.Queue.Item>,
    clients: List<Clients.Client>,
    onCancel: (String) -> Unit,
    onCancelAll: () -> Unit,
) {
    val byClient = pending.groupBy { it.clientId }
    val clientName: (String) -> String = { id ->
        clients.firstOrNull { it.clientId == id }?.name ?: "PC (offline)"
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Pending — waiting for PC", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${pending.size}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            byClient.forEach { (clientId, items) ->
                Text(
                    "${clientName(clientId)} — ${items.size} file${if (items.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                items.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, maxLines = 1)
                            Text(
                                formatBytes(item.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onCancel(item.id) }) {
                            Text("Cancel")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (pending.size > 1) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onCancelAll,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Cancel all (${pending.size})")
                }
            }
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> "${b / 1024} KB"
    b < 1024L * 1024 * 1024 -> "${b / 1024 / 1024} MB"
    else -> String.format("%.1f GB", b / 1024.0 / 1024.0 / 1024.0)
}

@Composable
private fun ConnectedClientsCard(clients: List<Clients.Client>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Connected", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${clients.size}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (clients.isEmpty()) {
                Text(
                    "No clients yet. Open the URL on your PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                clients.forEach { client ->
                    ClientRow(client)
                }
            }
        }
    }
}

@Composable
private fun ClientRow(client: Clients.Client) {
    val ageMs = System.currentTimeMillis() - client.lastSeen
    val agoText = when {
        ageMs < 30_000 -> "now"
        ageMs < 60_000 -> "${ageMs / 1000}s ago"
        ageMs < 3_600_000 -> "${ageMs / 60_000}m ago"
        else -> "${ageMs / 3_600_000}h ago"
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row {
                Text(client.name, fontWeight = FontWeight.Medium)
                if (client.registered) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "✓",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                "${client.address}  ·  $agoText" +
                    if (client.transfers > 0) "  ·  ${client.transfers} transfers" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WarningCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatusCard(state: ServerService.State) {
    val (label, body, color) = when (state) {
        is ServerService.State.Running -> Triple(
            "Running",
            state.url,
            MaterialTheme.colorScheme.primary,
        )
        is ServerService.State.Stopped -> Triple(
            "Stopped",
            "Tap Start to share",
            MaterialTheme.colorScheme.outline,
        )
        is ServerService.State.Error -> Triple(
            "Error",
            state.message,
            MaterialTheme.colorScheme.error,
        )
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state is ServerService.State.Running) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Open this URL on PC in the same WiFi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
