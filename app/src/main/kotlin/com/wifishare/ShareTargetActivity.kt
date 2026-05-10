package com.wifishare

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.wifishare.server.Clients
import com.wifishare.server.Queue
import com.wifishare.server.ServerService
import com.wifishare.ui.WiFiShareTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Receives ACTION_SEND / ACTION_SEND_MULTIPLE from the system share sheet.
 * Lets the user pick one of the currently connected clients and pushes
 * the file(s) into that client's queue. The companion app picks them up
 * within a few seconds.
 */
class ShareTargetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractIncomingUris(intent)
        setContent {
            WiFiShareTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ShareTargetScreen(
                        incoming = uris,
                        onPicked = { client -> sendTo(client, uris) },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    private fun extractIncomingUris(intent: Intent): List<Uri> {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                list.orEmpty()
            }
            else -> emptyList()
        }
    }

    private fun sendTo(client: Clients.Client, uris: List<Uri>) {
        if (uris.isEmpty()) {
            finish()
            return
        }
        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) { stage(uris) }
            staged.forEach { meta ->
                Queue.enqueue(
                    clientId = client.clientId,
                    name = meta.name,
                    size = meta.size,
                    mime = meta.mime,
                    tempFile = meta.file,
                )
            }
            finish()
        }
    }

    private data class Staged(val name: String, val size: Long, val mime: String, val file: File)

    private fun stage(uris: List<Uri>): List<Staged> {
        val outDir = File(cacheDir, "outbox").apply { mkdirs() }
        return uris.mapNotNull { uri -> stageOne(uri, outDir) }
    }

    private fun stageOne(uri: Uri, outDir: File): Staged? {
        val resolver = contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = resolveName(uri, mime)
        val temp = File.createTempFile("queue-", "-${sanitize(name)}", outDir)

        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Staged(name = name, size = temp.length(), mime = mime, file = temp)
        }.getOrNull()
    }

    /**
     * Resolves a sensible original filename from a share-intent URI, with
     * graceful fallbacks: DISPLAY_NAME → last path segment → MIME-based
     * extension. Always returns something with a useful extension so the
     * receiving side opens the file with the right app.
     */
    private fun resolveName(uri: Uri, mime: String): String {
        // 1. Best source: SAF / MediaStore DISPLAY_NAME
        queryDisplayName(uri)?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. URI's own last segment (e.g. file:// URIs)
        val segment = uri.lastPathSegment?.let { Uri.decode(it) }?.trim()?.trim('/')
        if (!segment.isNullOrBlank()) {
            return if (segment.contains('.')) segment else segment + extensionForMime(mime)
        }

        // 3. Last-resort timestamp + extension
        return "shared-${System.currentTimeMillis()}${extensionForMime(mime)}"
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull()
    }

    private fun extensionForMime(mime: String): String {
        val main = mime.substringBefore(';').trim().lowercase()
        return when (main) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "image/heic" -> ".heic"
            "image/bmp" -> ".bmp"
            "video/mp4" -> ".mp4"
            "video/quicktime" -> ".mov"
            "video/webm" -> ".webm"
            "video/3gpp" -> ".3gp"
            "audio/mpeg" -> ".mp3"
            "audio/x-wav", "audio/wav" -> ".wav"
            "audio/ogg" -> ".ogg"
            "audio/flac" -> ".flac"
            "audio/mp4" -> ".m4a"
            "application/pdf" -> ".pdf"
            "application/zip" -> ".zip"
            "application/json" -> ".json"
            "text/plain" -> ".txt"
            "text/csv" -> ".csv"
            "text/html" -> ".html"
            else -> ""
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
}

@Composable
private fun ShareTargetScreen(
    incoming: List<Uri>,
    onPicked: (Clients.Client) -> Unit,
    onCancel: () -> Unit,
) {
    val serverState by ServerService.state.collectAsState()
    val clients by Clients.state.collectAsState()
    val running = serverState is ServerService.State.Running

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Send to…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            if (incoming.size == 1) "1 file"
            else "${incoming.size} files",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        when {
            !running -> Text(
                "Server is not running. Start it from the WiFi Share app first.",
                color = MaterialTheme.colorScheme.error,
            )
            clients.isEmpty() -> Text(
                "No connected clients. Open the URL on a PC first " +
                    "(or run WiFiShareTray.exe).",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> clients.forEach { client ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPicked(client) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            client.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            client.address +
                                if (client.registered) "  ·  ✓ companion app" else "  ·  browser",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = onCancel) { Text("Cancel") }
        }
    }
}
