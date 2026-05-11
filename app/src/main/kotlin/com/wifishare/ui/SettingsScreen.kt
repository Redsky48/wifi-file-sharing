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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by viewModel.settings.collectAsState()
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimization(context)) }

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val display = friendlyName(uri)
        viewModel.setFolder(uri, display)
    }

    var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }

    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Section("Download folder") {
            Text(
                settings.folderDisplay.ifBlank { "Not selected" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { pickFolder.launch(null) }) {
                Text(if (settings.folderUri == null) "Pick folder" else "Change folder")
            }
        }

        Section("Port") {
            OutlinedTextField(
                value = portText,
                onValueChange = { v ->
                    portText = v.filter { it.isDigit() }.take(5)
                    portText.toIntOrNull()
                        ?.takeIf { it in 1024..65535 }
                        ?.let(viewModel::setPort)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Range: 1024-65535",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Quick Settings tile") {
            Text(
                "Add WiFi Share to the pull-down Quick Settings panel for one-tap start/stop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(onClick = { requestAddQuickSettingsTile(context) }) {
                    Text("Add to Quick Settings")
                }
            } else {
                Text(
                    "Pull down Quick Settings → Edit (pencil icon) → drag the WiFi Share tile up to add it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Section("Background streaming") {
            Text(
                if (batteryUnrestricted)
                    "Battery optimization is off — streaming continues with screen off."
                else
                    "Android may pause network when screen is off. Tap to allow background streaming.",
                style = MaterialTheme.typography.bodySmall,
                color = if (batteryUnrestricted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    requestIgnoreBatteryOptimization(context)
                    // Re-check after returning from settings
                    batteryUnrestricted = isIgnoringBatteryOptimization(context)
                },
                enabled = !batteryUnrestricted,
            ) {
                Text(if (batteryUnrestricted) "Already allowed" else "Allow background streaming")
            }
        }

        Section("Sharing") {
            ToggleRow(
                "Allow uploads",
                "PC can drop files into this folder",
                settings.allowUploads,
                viewModel::setAllowUploads,
            )
            ToggleRow(
                "Allow delete",
                "PC can delete files in this folder",
                settings.allowDelete,
                viewModel::setAllowDelete,
            )
        }

        Section("Security") {
            ToggleRow(
                "Require PIN",
                "Clients enter a 6-digit PIN to connect",
                settings.passwordEnabled,
                viewModel::setPasswordEnabled,
            )
            if (settings.passwordEnabled) {
                PinInput(
                    value = settings.password,
                    onChange = viewModel::setPassword,
                )
                Spacer(Modifier.height(4.dp))
                when {
                    settings.password.length < 6 -> Text(
                        "Enter all 6 digits — clients can connect without a PIN until then.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(
                        "Saved. Clients will be asked for this PIN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PinInput(value: String, onChange: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Six dots showing how much of the PIN has been entered
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            repeat(6) { i ->
                val filled = i < value.length
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(
                            if (filled) MaterialTheme.colorScheme.primary
                            else Color.Transparent,
                        )
                        .border(
                            2.dp,
                            if (filled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // 3x4 keypad: 1 2 3 / 4 5 6 / 7 8 9 / · 0 ⌫
        val keys = listOf(
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "", "0", "⌫",
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            keys.chunked(3).forEach { rowKeys ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowKeys.forEach { k ->
                        when {
                            k.isEmpty() -> Spacer(Modifier.size(78.dp))
                            k == "⌫" -> KeypadKey(
                                text = "",
                                icon = Icons.Default.Backspace,
                                onClick = {
                                    if (value.isNotEmpty()) onChange(value.dropLast(1))
                                },
                            )
                            else -> KeypadKey(
                                text = k,
                                onClick = {
                                    if (value.length < 6) onChange(value + k)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadKey(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(78.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = "Backspace",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

private fun friendlyName(uri: Uri): String {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
    val parts = docId.split(":")
    return if (parts.size == 2) "${parts[0]}: ${parts[1].ifBlank { "/" }}" else docId
}

private fun isIgnoringBatteryOptimization(context: Context): Boolean {
    return runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)
}

@SuppressLint("WrongConstant")
private fun requestAddQuickSettingsTile(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    runCatching {
        val sbm = context.getSystemService(android.app.StatusBarManager::class.java) ?: return
        sbm.requestAddTileService(
            android.content.ComponentName(context, com.wifishare.WifiShareTileService::class.java),
            "WiFi Share",
            android.graphics.drawable.Icon.createWithResource(context, com.wifishare.R.drawable.ic_qs_tile),
            { runnable -> runnable.run() },
            { _ -> /* result code ignored — Android shows its own feedback */ },
        )
    }
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
        // Fallback to the general settings page
        runCatching {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
