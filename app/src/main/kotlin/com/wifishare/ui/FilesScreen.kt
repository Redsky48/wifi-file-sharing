package com.wifishare.ui

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wifishare.util.IntentHelpers

@Composable
fun FilesScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val files by viewModel.files.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    settings.folderDisplay.ifBlank { "No folder picked" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (files.isEmpty()) "Empty"
                    else "${files.size} item" + if (files.size == 1) "" else "s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { viewModel.refreshFiles() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
            settings.folderUri?.let { uri ->
                OutlinedButton(onClick = {
                    IntentHelpers.openFolder(context, Uri.parse(uri))
                }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Open")
                }
            }
        }

        HorizontalDivider()

        if (settings.folderUri == null) {
            EmptyState("Pick a folder in Settings to see files here.")
        } else if (files.isEmpty()) {
            EmptyState("No files in this folder yet.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = { it.uri.toString() }) { file ->
                    FileRow(
                        item = file,
                        onClick = { IntentHelpers.openFile(context, file.uri, file.mime) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun FileRow(item: FileItem, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(Formatter.formatShortFileSize(context, item.size))
                    if (item.modified > 0) {
                        append("  ·  ")
                        append(
                            DateUtils.getRelativeTimeSpanString(
                                item.modified,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            )
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

