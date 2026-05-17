package com.wifishare.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifishare.MainActivity
import com.wifishare.screen.ScreenCast
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
    val castState by ScreenCast.state.collectAsState()
    val castMode by ScreenCast.mode.collectAsState()

    val ctx = LocalContext.current
    val running = serverState is ServerService.State.Running
    val wifiConnected = wifiState is WifiMonitor.State.Connected
    val hasNetwork = currentIp != null
    val onHotspot = hasNetwork && !wifiConnected
    val url = (serverState as? ServerService.State.Running)?.url

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCard(
            running = running,
            url = url,
            mode = castMode,
            clientsConnected = clients.size,
            fps = ScreenCast.measuredFps,
            onCopy = {
                if (url != null) copyToClipboard(ctx, url, "WiFi Share URL copied")
            },
        )

        if (running) {
            ConnectedDeviceList(clients = clients)
        } else if (!hasNetwork) {
            HintCard(
                icon = Icons.Default.Wifi,
                tint = Color(0xFFD17C00),
                bg = Color(0xFFFFF4E1),
                title = "No network",
                body = "Connect to a WiFi network or turn on the phone's hotspot, then tap Start.",
            )
        } else if (settings.folderUri == null) {
            HintCard(
                icon = Icons.Default.Folder,
                tint = WiFiShareColors.PrimaryDeep,
                bg = WiFiShareColors.PrimaryFaint,
                title = "Folder not set",
                body = "Pick a download folder in Settings before starting the server.",
            )
        }

        if (onHotspot && hasNetwork) {
            HintCard(
                icon = Icons.Default.SignalCellularAlt,
                tint = WiFiShareColors.PrimaryDeep,
                bg = WiFiShareColors.PrimaryFaint,
                title = "Hotspot mode",
                body = "PCs join this phone's hotspot to reach the server.",
            )
        }

        BigActionButton(
            running = running,
            enabled = settings.folderUri != null && (hasNetwork || running),
            onClick = { if (running) viewModel.stopServer() else viewModel.startServer() },
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftOutlinedButton(
                icon = Icons.Default.Folder,
                label = "Browse files",
                onClick = onOpenFiles,
                enabled = settings.folderUri != null,
                modifier = Modifier.weight(1f),
            )
            SoftOutlinedButton(
                icon = Icons.Default.Settings,
                label = "Settings",
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
        }

        StreamingQualityCard(
            selected = castMode,
            castRunning = castState == ScreenCast.State.Running,
            serverRunning = running,
            onSelect = { mode ->
                ScreenCast.setMode(mode)
                val act = ctx as? MainActivity ?: return@StreamingQualityCard
                if (castState == ScreenCast.State.Running) {
                    act.stopScreenCast()
                    act.requestScreenCast(mode)
                }
            },
            onToggleCast = {
                val act = ctx as? MainActivity ?: return@StreamingQualityCard
                if (castState == ScreenCast.State.Running) act.stopScreenCast()
                else act.requestScreenCast(castMode)
            },
        )

        if (pending.isNotEmpty()) {
            PendingQueueCard(
                pending = pending,
                clients = clients,
                onCancel = viewModel::cancelPending,
                onCancelAll = viewModel::cancelAllPending,
            )
        }

        FooterTip(
            text = if (onHotspot)
                "PCs need to join this phone's hotspot to see it."
            else
                "Make sure your phone and PC are on the same WiFi network.",
        )

        Spacer(Modifier.height(8.dp))
    }
}

// ── Hero card ─────────────────────────────────────────────────────

@Composable
private fun HeroCard(
    running: Boolean,
    url: String?,
    mode: ScreenCast.Mode,
    clientsConnected: Int,
    fps: Float,
    onCopy: () -> Unit,
) {
    var showQr by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)),
        color = Color.Transparent,
    ) {
        Box(
            Modifier
                .background(
                    Brush.linearGradient(
                        listOf(WiFiShareColors.SoftLavender, WiFiShareColors.SoftPeach),
                    ),
                )
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LiveBadge(live = running)
                        Text(
                            if (running) "WiFi Share is running" else "Tap Start to share",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = WiFiShareColors.OnSurface,
                        )
                        if (running && url != null) {
                            Column {
                                Text(
                                    "Open on your PC",
                                    color = WiFiShareColors.OnSurfaceMuted,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    url,
                                    fontWeight = FontWeight.Bold,
                                    color = WiFiShareColors.Primary,
                                    fontSize = 18.sp,
                                )
                            }
                        }
                    }
                    if (running && url != null) {
                        QrCode(value = url, modifier = Modifier.size(120.dp))
                    }
                }

                if (running && url != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onCopy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WiFiShareColors.Primary,
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                        ) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = { showQr = !showQr },
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, WiFiShareColors.OutlineSoft),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.White,
                                contentColor = WiFiShareColors.OnSurface,
                            ),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                        ) {
                            Icon(Icons.Default.QrCode2, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("QR Code", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (running) {
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.6f)))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        StatItem(
                            icon = Icons.Default.Smartphone,
                            label = when (clientsConnected) {
                                0 -> "No devices"
                                1 -> "1 device connected"
                                else -> "$clientsConnected devices connected"
                            },
                        )
                        StatItem(
                            icon = Icons.Default.Bolt,
                            label = if (fps > 0) "%.1f fps".format(fps) else "—",
                        )
                        StatItem(
                            icon = Icons.Default.Monitor,
                            label = "${mode.longEdge}p · ${if (mode.isH264) "H.264" else "MJPEG"}",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBadge(live: Boolean) {
    val (bg, fg, text) = if (live)
        Triple(WiFiShareColors.LiveGreenBg, WiFiShareColors.LiveGreen, "LIVE")
    else
        Triple(Color.White.copy(alpha = 0.6f), WiFiShareColors.OnSurfaceMuted, "OFFLINE")
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(fg))
            Spacer(Modifier.width(6.dp))
            Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatItem(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = WiFiShareColors.OnSurfaceMuted)
        Spacer(Modifier.width(6.dp))
        Text(label, color = WiFiShareColors.OnSurface, fontSize = 13.sp,
            fontWeight = FontWeight.Medium)
    }
}

// ── Connected device list ─────────────────────────────────────────

@Composable
private fun ConnectedDeviceList(clients: List<com.wifishare.server.Clients.Client>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, WiFiShareColors.OutlineSoft),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Connected device" + if (clients.size > 1) "s" else "",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = WiFiShareColors.PrimaryFaint,
                ) {
                    Text(
                        clients.size.toString(),
                        Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        color = WiFiShareColors.PrimaryDeep,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (clients.isEmpty()) {
                Text(
                    "Waiting for a PC to open the URL above…",
                    color = WiFiShareColors.OnSurfaceMuted,
                    fontSize = 14.sp,
                )
            } else {
                clients.forEach { ConnectedDeviceRow(it) }
            }
        }
    }
}

@Composable
private fun ConnectedDeviceRow(client: com.wifishare.server.Clients.Client) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(WiFiShareColors.PrimaryFaint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Monitor,
                null,
                Modifier.size(22.dp),
                tint = WiFiShareColors.PrimaryDeep,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    client.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.width(8.dp))
                LiveBadge(live = true)
            }
            Text(
                client.address +
                    if (client.transfers > 0) "  ·  ${client.transfers} transfers" else "",
                color = WiFiShareColors.OnSurfaceMuted,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Icon(
            Icons.Default.SignalCellularAlt,
            null,
            Modifier.size(22.dp),
            tint = WiFiShareColors.Primary,
        )
    }
}

// ── Big action button ─────────────────────────────────────────────

@Composable
private fun BigActionButton(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val brush = if (running)
        Brush.horizontalGradient(listOf(Color(0xFFE85A8E), Color(0xFFE54D6F)))
    else
        Brush.horizontalGradient(listOf(WiFiShareColors.Primary, Color(0xFF8E6FFC)))
    val text = if (running) "Stop sharing" else "Start sharing"
    val icon = if (running) Icons.Default.Stop else Icons.Default.PlayArrow
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = Color.Transparent,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(if (enabled) brush else Brush.horizontalGradient(
                    listOf(Color(0xFFB8B6C3), Color(0xFFA8A6B5))))
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

// ── Soft outlined buttons (Browse files / Settings) ──────────────

@Composable
private fun SoftOutlinedButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, WiFiShareColors.OutlineSoft),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon, null,
                Modifier.size(20.dp),
                tint = if (enabled) WiFiShareColors.Primary else WiFiShareColors.OnSurfaceMuted,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = if (enabled) WiFiShareColors.Primary else WiFiShareColors.OnSurfaceMuted,
            )
        }
    }
}

// ── Streaming quality card ────────────────────────────────────────

@Composable
private fun StreamingQualityCard(
    selected: ScreenCast.Mode,
    castRunning: Boolean,
    serverRunning: Boolean,
    onSelect: (ScreenCast.Mode) -> Unit,
    onToggleCast: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, WiFiShareColors.OutlineSoft),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Streaming quality", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Row {
                    Text("Current: ", color = WiFiShareColors.OnSurfaceMuted, fontSize = 13.sp)
                    Text(selected.label, color = WiFiShareColors.Primary,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScreenCast.Mode.entries.forEach { mode ->
                    QualityChip(
                        mode = mode,
                        selected = mode == selected,
                        onClick = { onSelect(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(WiFiShareColors.OutlineSoft))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatColumn("Codec", if (selected.isH264) "H.264 (HW)" else "MJPEG")
                StatColumn("Resolution", "${selected.longEdge}p")
                StatColumn("FPS", selected.targetFps.toString())
                StatColumn(
                    "Bitrate",
                    if (selected.isH264) "${selected.bitrateKbps / 1000} Mbps" else "q${selected.jpegQuality}",
                )
            }

            // Embedded toggle that doubles as "Cast screen to browser"
            // — exposed here so the user doesn't hunt for a separate
            // button. Disabled when the share server itself isn't up.
            Button(
                onClick = onToggleCast,
                enabled = serverRunning,
                colors = if (castRunning) ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE85A8E),
                    contentColor = Color.White,
                ) else ButtonDefaults.buttonColors(
                    containerColor = WiFiShareColors.PrimaryFaint,
                    contentColor = WiFiShareColors.PrimaryDeep,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                Icon(
                    if (castRunning) Icons.Default.Stop else Icons.Default.PlayCircle,
                    null,
                    Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (castRunning) "Stop screen cast" else "Cast screen to browser",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun QualityChip(
    mode: ScreenCast.Mode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = when (mode) {
        ScreenCast.Mode.Fast -> Icons.Default.Bolt
        ScreenCast.Mode.Balanced -> Icons.Default.Scale
        ScreenCast.Mode.Smooth -> Icons.Default.PlayCircle
        ScreenCast.Mode.Ultra -> Icons.Default.Rocket
    }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .let {
                if (selected) it.border(
                    width = 2.dp,
                    color = WiFiShareColors.Primary,
                    shape = RoundedCornerShape(14.dp),
                ) else it.border(
                    width = 1.dp,
                    color = WiFiShareColors.OutlineSoft,
                    shape = RoundedCornerShape(14.dp),
                )
            },
        color = if (selected) WiFiShareColors.PrimaryFaint else MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .padding(vertical = 12.dp, horizontal = 6.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                icon, null, Modifier.size(20.dp),
                tint = if (selected) WiFiShareColors.PrimaryDeep else WiFiShareColors.OnSurfaceMuted,
            )
            Text(
                mode.label,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = if (selected) WiFiShareColors.PrimaryDeep else WiFiShareColors.OnSurface,
            )
            Text(
                "${mode.targetFps} fps",
                fontSize = 11.sp,
                color = WiFiShareColors.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = WiFiShareColors.OnSurfaceMuted, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            color = WiFiShareColors.OnSurface)
    }
}

// ── Pending queue + hints + footer ────────────────────────────────

@Composable
private fun PendingQueueCard(
    pending: List<com.wifishare.server.Queue.Item>,
    clients: List<com.wifishare.server.Clients.Client>,
    onCancel: (String) -> Unit,
    onCancelAll: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, WiFiShareColors.OutlineSoft),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Pending uploads", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (pending.size > 1) {
                    Text(
                        "Cancel all",
                        color = WiFiShareColors.Primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(onClick = onCancelAll),
                    )
                }
            }
            pending.forEach { item ->
                val clientName = clients.firstOrNull { it.clientId == item.clientId }?.name
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            "→ ${clientName ?: item.clientId.take(8)}",
                            color = WiFiShareColors.OnSurfaceMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Icon(
                        Icons.Default.Cancel,
                        null,
                        Modifier
                            .size(20.dp)
                            .clickable { onCancel(item.id) },
                        tint = WiFiShareColors.OnSurfaceMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun HintCard(
    icon: ImageVector,
    tint: Color,
    bg: Color,
    title: String,
    body: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = bg,
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = tint)
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = tint)
                Spacer(Modifier.height(2.dp))
                Text(body, fontSize = 13.sp, color = WiFiShareColors.OnSurface)
            }
        }
    }
}

@Composable
private fun FooterTip(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = WiFiShareColors.PrimaryFaint,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Wifi, null, Modifier.size(18.dp),
                tint = WiFiShareColors.PrimaryDeep)
            Text(text, fontSize = 13.sp, color = WiFiShareColors.OnSurface)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────

private fun copyToClipboard(ctx: Context, value: String, toast: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("WiFi Share", value))
    Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
}
