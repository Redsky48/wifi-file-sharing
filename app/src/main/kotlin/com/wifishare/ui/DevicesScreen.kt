package com.wifishare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifishare.server.Clients

@Composable
fun DevicesScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit = {},
) {
    val clients by viewModel.clients.collectAsState()
    val pendingQueue by viewModel.pendingQueue.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DevicesHeader(onBack = onBack)

        SectionLabel("CONNECTED NOW")
        if (clients.isEmpty()) {
            EmptyDeviceCard(
                icon = Icons.Default.Devices,
                title = "No devices connected",
                body = "Open the URL from the Status page on a PC or scan the QR code.",
            )
        } else {
            DeviceListCard {
                clients.forEachIndexed { i, c ->
                    if (i > 0) Divider()
                    DeviceRow(c)
                }
            }
        }

        if (pendingQueue.isNotEmpty()) {
            SectionLabel("PENDING UPLOADS")
            DeviceListCard {
                pendingQueue.forEachIndexed { i, item ->
                    if (i > 0) Divider()
                    PendingRow(item, clients)
                }
            }
        }

        SectionLabel("HOW THIS WORKS")
        InfoCard(
            body = "Devices that open the WiFi Share URL (or pair via QR code) show up here. " +
                "PCs running the companion tray also auto-register so they can receive files even when offline."
        )

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun DevicesHeader(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, WiFiShareColors.OutlineSoft, CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, null,
                Modifier.size(20.dp),
                tint = WiFiShareColors.Primary,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Devices", fontWeight = FontWeight.Bold, fontSize = 28.sp,
                color = WiFiShareColors.OnSurface)
            Text("PCs and phones sharing this folder",
                color = WiFiShareColors.OnSurfaceMuted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 4.dp),
        color = WiFiShareColors.OnSurfaceMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
    )
}

@Composable
private fun DeviceListCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, WiFiShareColors.OutlineSoft),
    ) {
        Column(content = content)
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 76.dp, end = 14.dp)
            .height(1.dp)
            .background(WiFiShareColors.OutlineSoft.copy(alpha = 0.6f)),
    )
}

@Composable
private fun DeviceRow(client: Clients.Client) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(WiFiShareColors.PrimaryFaint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Computer, null,
                Modifier.size(22.dp),
                tint = WiFiShareColors.PrimaryDeep,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(client.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                LiveBadgePill()
            }
            Text(
                client.address,
                color = WiFiShareColors.OnSurfaceMuted,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (client.transfers > 0 || client.registered) {
                Text(
                    buildString {
                        if (client.registered) append("Companion app  ·  ")
                        if (client.transfers > 0) {
                            append(client.transfers).append(" transfer")
                            if (client.transfers > 1) append("s")
                        }
                    }.trim().trimEnd('·', ' '),
                    color = WiFiShareColors.OnSurfaceMuted,
                    fontSize = 12.sp,
                )
            }
        }
        Icon(
            Icons.Default.SignalCellularAlt, null,
            Modifier.size(22.dp),
            tint = WiFiShareColors.Primary,
        )
    }
}

@Composable
private fun PendingRow(item: com.wifishare.server.Queue.Item, clients: List<Clients.Client>) {
    val targetName = clients.firstOrNull { it.clientId == item.clientId }?.name
        ?: item.clientId.take(8)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(WiFiShareColors.PrimaryFaint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Devices, null,
                Modifier.size(22.dp),
                tint = WiFiShareColors.PrimaryDeep,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("→ $targetName", color = WiFiShareColors.OnSurfaceMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LiveBadgePill() {
    Surface(
        shape = RoundedCornerShape(50),
        color = WiFiShareColors.LiveGreenBg,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.FiberManualRecord, null,
                Modifier.size(8.dp),
                tint = WiFiShareColors.LiveGreen,
            )
            Spacer(Modifier.width(4.dp))
            Text("LIVE",
                color = WiFiShareColors.LiveGreen,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyDeviceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, WiFiShareColors.OutlineSoft),
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(WiFiShareColors.PrimaryFaint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(28.dp), tint = WiFiShareColors.PrimaryDeep)
            }
            Spacer(Modifier.height(4.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                body,
                color = WiFiShareColors.OnSurfaceMuted,
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun InfoCard(body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = WiFiShareColors.PrimaryFaint,
    ) {
        Text(
            body,
            Modifier.padding(14.dp),
            color = WiFiShareColors.OnSurface,
            fontSize = 13.sp,
        )
    }
}
