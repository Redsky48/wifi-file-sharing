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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.wifishare.util.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Receives ACTION_SEND / ACTION_SEND_MULTIPLE from the system share sheet.
 * Lets the user pick one of the currently connected clients and pushes
 * the file(s) into that client's queue. The companion app picks them up
 * within a few seconds.
 *
 * If any of the incoming items is an image, a compression preset selector
 * is shown: Original / 70% / Halved / 25% / Custom. Selected preset is
 * applied (scale + WebP re-encode) before the file is enqueued.
 */
class ShareTargetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = extractIncoming(intent)
        val uris = incoming.uris
        val sharedText = incoming.text
        val sharedSubject = incoming.subject
        val hasImages = uris.any { ImageCompressor.isImageMime(contentResolver.getType(it)) }

        setContent {
            WiFiShareTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var preset by rememberSaveable { mutableStateOf(ImageCompressor.Preset.ORIGINAL) }
                    var customScale by rememberSaveable { mutableFloatStateOf(0.5f) }
                    var customQuality by rememberSaveable { mutableIntStateOf(75) }

                    ShareTargetScreen(
                        uris = uris,
                        sharedText = sharedText,
                        hasImages = hasImages,
                        preset = preset,
                        customScale = customScale,
                        customQuality = customQuality,
                        onPresetChange = { preset = it },
                        onCustomChange = { s, q -> customScale = s; customQuality = q },
                        onPicked = { client ->
                            val (scale, quality) = when (preset) {
                                ImageCompressor.Preset.CUSTOM -> customScale to customQuality
                                else -> preset.scale to preset.quality
                            }
                            sendTo(client, uris, sharedText, sharedSubject, preset, scale, quality)
                        },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    private data class IncomingShare(
        val uris: List<Uri>,
        val text: String?,
        val subject: String?,
    )

    /**
     * Pulls everything useful out of the share intent: file URIs
     * (EXTRA_STREAM / single + multiple) AND plain text / URLs shared via
     * EXTRA_TEXT. Subject is kept around so we can use it as the filename
     * for wrapped text — e.g. a browser shares EXTRA_SUBJECT="Page title".
     */
    private fun extractIncoming(intent: Intent): IncomingShare {
        val uris: List<Uri> = when (intent.action) {
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
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
        return IncomingShare(uris = uris, text = text, subject = subject)
    }

    private fun sendTo(
        client: Clients.Client,
        uris: List<Uri>,
        sharedText: String?,
        sharedSubject: String?,
        preset: ImageCompressor.Preset,
        scale: Float,
        quality: Int,
    ) {
        if (uris.isEmpty() && sharedText.isNullOrEmpty()) {
            finish()
            return
        }
        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) {
                stage(uris, sharedText, sharedSubject, preset, scale, quality)
            }
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

    private fun stage(
        uris: List<Uri>,
        sharedText: String?,
        sharedSubject: String?,
        preset: ImageCompressor.Preset,
        scale: Float,
        quality: Int,
    ): List<Staged> {
        val outDir = File(cacheDir, "outbox").apply { mkdirs() }
        val staged = uris.mapNotNull { uri -> stageOne(uri, outDir, preset, scale, quality) }
            .toMutableList()
        if (!sharedText.isNullOrEmpty()) {
            stageText(sharedText, sharedSubject, outDir)?.let { staged += it }
        }
        return staged
    }

    /**
     * Wraps shared plain text / URL into a temp .txt file so the receiving
     * Windows tray opens it via ShellExecute (default editor: Notepad).
     * Filename comes from EXTRA_SUBJECT if present (e.g. browser page title),
     * else "shared-text-<timestamp>.txt".
     */
    private fun stageText(text: String, subject: String?, outDir: File): Staged? {
        val base = subject?.let { sanitize(it) }?.takeIf { it.isNotBlank() }
            ?: "shared-text-${System.currentTimeMillis()}"
        val file = File(outDir, "$base.txt")
        return runCatching {
            file.writeText(text, Charsets.UTF_8)
            Staged(name = file.name, size = file.length(), mime = "text/plain", file = file)
        }.getOrNull()
    }

    private fun stageOne(
        uri: Uri,
        outDir: File,
        preset: ImageCompressor.Preset,
        scale: Float,
        quality: Int,
    ): Staged? {
        val resolver = contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = resolveName(uri, mime)

        // Image path: apply compression preset (unless Original).
        val compressIt = preset != ImageCompressor.Preset.ORIGINAL &&
            ImageCompressor.isImageMime(mime)
        if (compressIt) {
            val webp = runCatching {
                ImageCompressor.compress(this, uri, scale, quality, outDir, name)
            }.getOrNull()
            if (webp != null && webp.length() > 0) {
                return Staged(
                    name = webp.name,
                    size = webp.length(),
                    mime = "image/webp",
                    file = webp,
                )
            }
            // Compression failed → fall through to raw copy so the user
            // doesn't lose the share entirely.
        }

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareTargetScreen(
    uris: List<Uri>,
    sharedText: String?,
    hasImages: Boolean,
    preset: ImageCompressor.Preset,
    customScale: Float,
    customQuality: Int,
    onPresetChange: (ImageCompressor.Preset) -> Unit,
    onCustomChange: (Float, Int) -> Unit,
    onPicked: (Clients.Client) -> Unit,
    onCancel: () -> Unit,
) {
    val serverState by ServerService.state.collectAsState()
    val clients by Clients.state.collectAsState()
    val running = serverState is ServerService.State.Running

    var showCustomDialog by remember { mutableStateOf(false) }

    val summary = buildString {
        if (uris.isNotEmpty()) {
            append(if (uris.size == 1) "1 file" else "${uris.size} files")
        }
        if (!sharedText.isNullOrEmpty()) {
            if (isNotEmpty()) append(" + ")
            append("1 text snippet")
        }
    }.ifEmpty { "Nothing to send" }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Send to…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (!sharedText.isNullOrEmpty()) {
            Spacer(Modifier.height(4.dp))
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = sharedText.take(300) + if (sharedText.length > 300) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (hasImages) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Image compression",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageCompressor.Preset.entries.forEach { p ->
                    val label = if (p == ImageCompressor.Preset.CUSTOM)
                        "Custom (${(customScale * 100).toInt()}% · q${customQuality})"
                    else p.label
                    FilterChip(
                        selected = preset == p,
                        onClick = {
                            onPresetChange(p)
                            if (p == ImageCompressor.Preset.CUSTOM) showCustomDialog = true
                        },
                        label = { Text(label) },
                    )
                }
            }
        }

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

    if (showCustomDialog) {
        CustomCompressionDialog(
            initialScale = customScale,
            initialQuality = customQuality,
            onConfirm = { s, q ->
                onCustomChange(s, q)
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false },
        )
    }
}

@Composable
private fun CustomCompressionDialog(
    initialScale: Float,
    initialQuality: Int,
    onConfirm: (Float, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(initialScale) }
    var quality by remember { mutableIntStateOf(initialQuality) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom compression") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scale: ${(scale * 100).toInt()}%")
                Slider(
                    value = scale,
                    onValueChange = { scale = it },
                    valueRange = 0.1f..1.0f,
                )
                Text("Quality: $quality")
                Slider(
                    value = quality.toFloat(),
                    onValueChange = { quality = it.toInt() },
                    valueRange = 10f..100f,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(scale, quality) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
