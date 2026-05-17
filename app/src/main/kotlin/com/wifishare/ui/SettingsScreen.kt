package com.wifishare.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifishare.screen.ScreenCast

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val mode by ScreenCast.mode.collectAsState()
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimization(context)) }
    var changePinOpen by remember { mutableStateOf(false) }
    var portEditOpen by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.setFolder(uri, friendlyName(uri))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsHeader(onBack)

        // ── CONNECTION ────────────────────────────────────────────
        SectionLabel("CONNECTION")
        SettingsCard {
            NavRow(
                icon = Icons.Default.Folder,
                title = "Download folder",
                value = settings.folderDisplay.ifBlank { "Not selected" },
                onClick = { pickFolder.launch(null) },
            )
            Divider()
            NavRow(
                icon = Icons.Default.Cable,
                title = "Port",
                value = settings.port.toString(),
                onClick = { portEditOpen = true },
            )
            Divider()
            ToggleRow(
                icon = Icons.Default.QrCode2,
                title = "Quick connect",
                subtitle = "Show QR code and connection info",
                checked = settings.quickConnectVisible,
                onChange = viewModel::setQuickConnectVisible,
            )
        }

        // ── SHARING ───────────────────────────────────────────────
        SectionLabel("SHARING")
        SettingsCard {
            ToggleRow(
                icon = Icons.Default.Upload,
                title = "Allow uploads",
                subtitle = "PC can drop files into this folder",
                checked = settings.allowUploads,
                onChange = viewModel::setAllowUploads,
            )
            Divider()
            ToggleRow(
                icon = Icons.Default.Delete,
                title = "Allow delete",
                subtitle = "PC can delete files in this folder",
                checked = settings.allowDelete,
                onChange = viewModel::setAllowDelete,
            )
        }

        // ── SECURITY ──────────────────────────────────────────────
        SectionLabel("SECURITY")
        SettingsCard {
            ToggleRow(
                icon = Icons.Default.Lock,
                title = "Require PIN",
                subtitle = "Clients enter a 6-digit PIN to connect",
                checked = settings.passwordEnabled,
                onChange = viewModel::setPasswordEnabled,
            )
            if (settings.passwordEnabled) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        color = WiFiShareColors.SurfaceVariant,
                    ) {
                        Row(
                            Modifier.padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            repeat(6) { i ->
                                val filled = i < settings.password.length
                                Box(
                                    Modifier
                                        .padding(horizontal = 6.dp)
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (filled) WiFiShareColors.Primary
                                            else WiFiShareColors.OnSurfaceMuted.copy(alpha = 0.25f),
                                        ),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { changePinOpen = true },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            "Change PIN",
                            color = WiFiShareColors.Primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Divider()
            ToggleRow(
                icon = Icons.Default.Fingerprint,
                title = "Use biometric to connect",
                subtitle = "Use fingerprint to connect from this device",
                checked = settings.useBiometric,
                onChange = viewModel::setUseBiometric,
            )
        }

        // ── STREAMING ─────────────────────────────────────────────
        SectionLabel("STREAMING")
        SettingsCard {
            NavRow(
                icon = Icons.Default.Videocam,
                title = "Codec",
                value = if (mode.isH264) "H.264 (hardware)" else "MJPEG (software)",
                onClick = { /* picker — for now read-only, mode chip on home screen */ },
            )
            Divider()
            NavRow(
                icon = Icons.Default.Monitor,
                title = "Resolution",
                value = "${mode.longEdge}p",
            )
            Divider()
            NavRow(
                icon = Icons.Default.Speed,
                title = "FPS",
                value = "${mode.targetFps} fps",
            )
            Divider()
            NavRow(
                icon = Icons.Default.GraphicEq,
                title = "Bitrate",
                value = if (mode.isH264) "${mode.bitrateKbps / 1000} Mbps" else "JPEG q${mode.jpegQuality}",
            )
            Divider()
            ToggleRow(
                icon = Icons.Default.Tune,
                title = "Adaptive bitrate",
                subtitle = "Automatically adjust quality",
                checked = settings.adaptiveBitrate,
                onChange = viewModel::setAdaptiveBitrate,
            )
        }

        // ── GENERAL ───────────────────────────────────────────────
        SectionLabel("GENERAL")
        SettingsCard {
            NavRow(
                icon = Icons.Default.Rocket,
                title = "Background streaming",
                subtitle = "Keep streaming when screen is off",
                value = if (batteryUnrestricted) "Allowed" else "Restricted",
                valueColor = if (batteryUnrestricted) WiFiShareColors.Primary
                    else Color(0xFFD17C00),
                onClick = {
                    requestIgnoreBatteryOptimization(context)
                    batteryUnrestricted = isIgnoringBatteryOptimization(context)
                },
            )
            Divider()
            ToggleRow(
                icon = Icons.Default.Notifications,
                title = "Notifications",
                subtitle = "Connection and transfer alerts",
                checked = settings.notificationsEnabled,
                onChange = viewModel::setNotificationsEnabled,
            )
        }

        Spacer(Modifier.height(12.dp))
        VersionFooter()
    }

    if (portEditOpen) {
        PortEditDialog(
            current = settings.port,
            onDismiss = { portEditOpen = false },
            onSave = { newPort ->
                viewModel.setPort(newPort)
                portEditOpen = false
            },
        )
    }
    if (changePinOpen) {
        ChangePinDialog(
            current = settings.password,
            onDismiss = { changePinOpen = false },
            onSave = { newPin ->
                viewModel.setPassword(newPin)
                changePinOpen = false
            },
        )
    }
}

// ── Header ────────────────────────────────────────────────────────

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
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
                Icons.AutoMirrored.Filled.ArrowBack,
                null,
                Modifier.size(20.dp),
                tint = WiFiShareColors.Primary,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "Settings",
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = WiFiShareColors.OnSurface,
            )
            Text(
                "Customize your WiFi Share experience",
                color = WiFiShareColors.OnSurfaceMuted,
                fontSize = 14.sp,
            )
        }
    }
}

// ── Section building blocks ──────────────────────────────────────

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
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
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
            .padding(start = 60.dp, end = 14.dp)
            .height(1.dp)
            .background(WiFiShareColors.OutlineSoft.copy(alpha = 0.6f)),
    )
}

/** Icon-tinted rounded-square avatar that prefixes every settings row. */
@Composable
private fun RowIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(WiFiShareColors.PrimaryFaint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = WiFiShareColors.PrimaryDeep)
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (subtitle != null) Text(
                subtitle,
                color = WiFiShareColors.OnSurfaceMuted,
                fontSize = 12.sp,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = WiFiShareColors.Primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = WiFiShareColors.OnSurfaceMuted.copy(alpha = 0.4f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = WiFiShareColors.OnSurfaceMuted,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (subtitle != null) Text(
                subtitle,
                color = WiFiShareColors.OnSurfaceMuted,
                fontSize = 12.sp,
            ) else if (value != null) Text(
                value,
                color = WiFiShareColors.OnSurfaceMuted,
                fontSize = 13.sp,
            )
        }
        if (subtitle != null && value != null) {
            Text(
                value,
                color = valueColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(6.dp))
        }
        if (onClick != null) {
            Icon(
                Icons.Default.ChevronRight,
                null,
                Modifier.size(20.dp),
                tint = WiFiShareColors.OnSurfaceMuted,
            )
        }
    }
}

// ── PIN keypad dialog ─────────────────────────────────────────────

@Composable
private fun ChangePinDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var entered by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change PIN", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(6) { i ->
                        val filled = i < entered.length
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(
                                    if (filled) WiFiShareColors.Primary
                                    else WiFiShareColors.OnSurfaceMuted.copy(alpha = 0.25f),
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Keypad(
                    onDigit = { d ->
                        if (entered.length < 6) entered += d
                    },
                    onBackspace = { if (entered.isNotEmpty()) entered = entered.dropLast(1) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(entered) },
                enabled = entered.length == 6,
            ) { Text("Save", color = WiFiShareColors.Primary, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = WiFiShareColors.OnSurfaceMuted)
            }
        },
    )
}

@Composable
private fun PortEditDialog(
    current: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var text by remember(current) { mutableStateOf(current.toString()) }
    val valid = text.toIntOrNull() in 1024..65535
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Port", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(5) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Range: 1024–65535",
                    color = WiFiShareColors.OnSurfaceMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { text.toIntOrNull()?.let(onSave) },
                enabled = valid,
            ) { Text("Save", color = WiFiShareColors.Primary, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = WiFiShareColors.OnSurfaceMuted)
            }
        },
    )
}

@Composable
private fun Keypad(onDigit: (String) -> Unit, onBackspace: () -> Unit) {
    val keys = listOf("1","2","3","4","5","6","7","8","9","","0","⌫")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        keys.chunked(3).forEach { rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowKeys.forEach { k ->
                    when {
                        k.isEmpty() -> Spacer(Modifier.size(68.dp))
                        k == "⌫" -> KeyButton(onClick = onBackspace) {
                            Icon(Icons.Default.Backspace, null, tint = WiFiShareColors.PrimaryDeep)
                        }
                        else -> KeyButton(onClick = { onDigit(k) }) {
                            Text(k, fontSize = 24.sp, color = WiFiShareColors.OnSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(WiFiShareColors.PrimaryFaint)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

// ── Footer ────────────────────────────────────────────────────────

@Composable
private fun VersionFooter() {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Favorite,
                null,
                Modifier.size(14.dp),
                tint = WiFiShareColors.Primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Made with care for a better sharing experience",
                color = WiFiShareColors.OnSurfaceMuted,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "v" + (com.wifishare.BuildConfig.VERSION_NAME ?: "0"),
            color = WiFiShareColors.OnSurfaceMuted,
            fontSize = 12.sp,
        )
    }
}

// ── ColumnScope alias for SettingsCard content ───────────────────

private typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope

// ── Helpers ──────────────────────────────────────────────────────

private fun friendlyName(uri: Uri): String {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?: return uri.toString()
    val parts = docId.split(":")
    return if (parts.size == 2) "${parts[0]}: ${parts[1].ifBlank { "/" }}" else docId
}

private fun isIgnoringBatteryOptimization(context: Context): Boolean {
    return runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)
}

@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimization(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
