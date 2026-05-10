package com.wifishare.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class FileServer(
    private val context: Context,
    port: Int,
    private val config: Config,
) : NanoHTTPD(port) {

    data class Config(
        val folderUri: Uri,
        val allowUploads: Boolean,
        val allowDelete: Boolean,
        val password: String,
    )

    private val webdav = WebDavHandler(context, config)

    override fun serve(session: IHTTPSession): Response {
        return try {
            // Server is in graceful shutdown — tell clients to give up.
            if (ShutdownSignal.armed) {
                val r = newFixedLengthResponse(
                    Response.Status.lookup(503) ?: Response.Status.INTERNAL_ERROR,
                    "application/json; charset=utf-8",
                    """{"error":"shutting down","reason":"server_stopping"}""",
                )
                r.addHeader("Connection", "close")
                r.addHeader("X-WiFiShare-Shutdown", "1")
                return r
            }

            val rawPath = session.uri
            val path = rawPath.trimEnd('/').ifEmpty { "/" }
            val ip = session.remoteIpAddress.orEmpty()
            val ua = session.headers["user-agent"]

            // Public paths skip auth so the login page can render and the
            // companion installer is reachable before the user types in
            // the PIN. WebDAV's OPTIONS at root is *not* public — Windows
            // mini-redirector needs to authenticate same as the rest.
            val isHtmlGet = path == "/" && session.method.name.equals("GET", true)
            val isPublic = isHtmlGet ||
                path == "/favicon.ico" ||
                path.startsWith("/static/") ||
                path.startsWith("/download/")

            if (!isPublic && !isAuthorized(session)) {
                return unauthorized(rawPath)
            }

            val isTransferRequest = path == "/api/upload" || path == "/api/download" ||
                (rawPath.startsWith("/dav/") &&
                    session.method.name in listOf("GET", "PUT", "POST"))

            // A media player streaming an audio file fires a Range GET per
            // chunk — could be hundreds for a 5-minute song. Dedupe so the
            // counter reflects user-visible "transfers" rather than wire
            // requests. Per-IP + per-resource within a rolling window.
            val isTransfer = if (isTransferRequest) {
                val transferKey = when {
                    path == "/api/upload" -> "upload-${System.currentTimeMillis() / 30_000L}"
                    path == "/api/download" ->
                        "/api/download/" + (session.parameters["name"]?.firstOrNull() ?: "")
                    else -> rawPath
                }
                Clients.isUniqueTransfer(ip, transferKey)
            } else false

            Clients.touch(ip, ua, isTransfer)

            when {
                path == "/" -> serveRoot(session)
                rawPath == "/dav" || rawPath.startsWith("/dav/") -> webdav.handle(session)
                path.startsWith("/static/") -> serveStatic(path.removePrefix("/static/"))
                path == "/download/WiFiShareTray.exe" -> serveAppDownload()
                path == "/api/files" -> listFiles(session)
                path == "/api/download" -> downloadFile(session)
                path == "/api/upload" -> uploadFiles(session)
                path == "/api/delete" -> deleteFile(session)
                path == "/api/clients" -> listClients()
                path == "/api/clients/register" -> registerClient(session, ip)
                path.startsWith("/api/queue/") -> handleQueue(session, path)
                else -> notFound()
            }
        } catch (t: Throwable) {
            error("Server error: ${t.message}")
        }
    }

    /** Browser GET / → web UI. WebDAV OPTIONS at root → DAV headers (Windows discovery). */
    private fun serveRoot(session: IHTTPSession): Response {
        return if (session.method.name.uppercase() == "OPTIONS") {
            webdav.handle(session)
        } else {
            serveAsset("web/index.html", "text/html; charset=utf-8")
        }
    }

    private fun root(): DocumentFile? = DocumentFile.fromTreeUri(context, config.folderUri)

    private fun listFiles(session: IHTTPSession): Response {
        val root = root() ?: return error("Folder not accessible")
        val rel = session.parameters["path"]?.firstOrNull()?.trim('/') ?: ""
        val current = if (rel.isEmpty()) root else navigateTo(root, rel)
            ?: return notFound()
        if (!current.isDirectory) return badRequest("not a folder")

        val entries = JSONArray()
        current.listFiles()
            .sortedWith(
                compareByDescending<DocumentFile> { it.isDirectory }
                    .thenBy { it.name?.lowercase() ?: "" }
            )
            .forEach { f ->
                val obj = JSONObject().apply {
                    put("name", f.name ?: "?")
                    put("type", if (f.isDirectory) "folder" else "file")
                    put("modified", f.lastModified())
                    if (f.isFile) {
                        put("size", f.length())
                        put("mime", f.type ?: "application/octet-stream")
                    }
                }
                entries.put(obj)
            }

        val payload = JSONObject().apply {
            put("path", rel)
            put("entries", entries)
            // Backwards-compat: include "files" with only files for old clients
            val onlyFiles = JSONArray()
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                if (e.optString("type") == "file") onlyFiles.put(e)
            }
            put("files", onlyFiles)
            put("allowUploads", config.allowUploads)
            put("allowDelete", config.allowDelete)
        }
        return json(Response.Status.OK, payload.toString())
    }

    private fun navigateTo(root: DocumentFile, relativePath: String): DocumentFile? {
        val parts = relativePath.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        var current: DocumentFile? = root
        for (p in parts) {
            current = current?.findFile(p)
            if (current == null) return null
        }
        return current
    }

    private fun resolveFile(root: DocumentFile, fullPath: String): DocumentFile? {
        val parts = fullPath.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return null
        val parentParts = parts.dropLast(1)
        val name = parts.last()
        val parent = if (parentParts.isEmpty()) root else navigateTo(root, parentParts.joinToString("/"))
        return parent?.findFile(name)
    }

    private fun downloadFile(session: IHTTPSession): Response {
        val pathParam = session.parameters["path"]?.firstOrNull()
            ?: session.parameters["name"]?.firstOrNull()
            ?: return badRequest("path required")
        val inline = session.parameters["inline"]?.firstOrNull() == "1"

        val root = root() ?: return error("Folder not accessible")
        val file = resolveFile(root, pathParam) ?: return notFound()
        if (!file.isFile) return notFound()

        val total = file.length()
        val mime = file.type ?: "application/octet-stream"
        val name = file.name ?: pathParam.substringAfterLast('/')
        val rangeHeader = session.headers["range"]
        val range = parseRange(rangeHeader, total)

        val rawInput = context.contentResolver.openInputStream(file.uri)
            ?: return error("Cannot open file")

        val response = if (range == null) {
            // Full file. Track read bytes — only count as a "download" if the
            // client actually pulled the whole thing (skips partial probes).
            val tracking = TransferTrackingInputStream(rawInput, total) {
                Transfers.emit(TransferEvent.Downloaded(name))
            }
            newFixedLengthResponse(Response.Status.OK, mime, tracking, total).also {
                it.addHeader("Accept-Ranges", "bytes")
            }
        } else {
            val (start, end) = range
            var skipped = 0L
            while (skipped < start) {
                val n = rawInput.skip(start - skipped)
                if (n <= 0) break
                skipped += n
            }
            val length = end - start + 1
            val limited = RangeBoundedStream(rawInput, length)
            val r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, limited, length)
            r.addHeader("Content-Range", "bytes $start-$end/$total")
            r.addHeader("Accept-Ranges", "bytes")
            r
        }
        response.addHeader(
            "Content-Disposition",
            (if (inline) "inline" else "attachment") +
                "; filename*=UTF-8''" + encodeFilename(name),
        )
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null || total <= 0) return null
        val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim()) ?: return null
        val (startStr, endStr) = match.destructured
        val start: Long; val end: Long
        when {
            startStr.isEmpty() -> {
                val suffix = endStr.toLongOrNull() ?: return null
                start = (total - suffix).coerceAtLeast(0); end = total - 1
            }
            endStr.isEmpty() -> { start = startStr.toLong(); end = total - 1 }
            else -> { start = startStr.toLong(); end = endStr.toLong().coerceAtMost(total - 1) }
        }
        if (start < 0 || start >= total || end < start) return null
        return start to end
    }

    private fun uploadFiles(session: IHTTPSession): Response {
        if (!config.allowUploads) return forbidden("Uploads disabled")
        if (session.method != Method.POST) return badRequest("POST required")

        val root = root() ?: return error("Folder not accessible")
        val targetDir = session.parameters["path"]?.firstOrNull()?.trim('/').orEmpty()
        val dst = if (targetDir.isEmpty()) root else navigateTo(root, targetDir)
            ?: return notFound()
        if (!dst.isDirectory) return badRequest("not a folder")

        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: IOException) {
            return error("Read error: ${e.message}")
        } catch (e: NanoHTTPD.ResponseException) {
            return error("Body error: ${e.message}")
        }

        val params = session.parameters
        val saved = JSONArray()

        params.forEach { (field, originalNames) ->
            val tempPath = files[field] ?: return@forEach
            val temp = File(tempPath)
            if (!temp.exists()) return@forEach

            val originalName = originalNames.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: "upload-${System.currentTimeMillis()}"
            val safeName = sanitize(originalName)
            val finalName = uniqueName(dst, safeName)

            val mime = guessMime(finalName)
            val target = dst.createFile(mime, finalName) ?: return@forEach

            FileInputStream(temp).use { input ->
                context.contentResolver.openOutputStream(target.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            saved.put(finalName)
            Transfers.emit(TransferEvent.Uploaded(finalName))
        }

        val payload = JSONObject().put("saved", saved)
        return json(Response.Status.OK, payload.toString())
    }

    private fun deleteFile(session: IHTTPSession): Response {
        if (!config.allowDelete) return forbidden("Delete disabled")
        val pathParam = session.parameters["path"]?.firstOrNull()
            ?: session.parameters["name"]?.firstOrNull()
            ?: return badRequest("path required")
        val root = root() ?: return error("Folder not accessible")
        val file = resolveFile(root, pathParam) ?: return notFound()
        val name = file.name ?: pathParam.substringAfterLast('/')
        return if (file.delete()) {
            Transfers.emit(TransferEvent.Deleted(name))
            json(Response.Status.OK, """{"deleted":true}""")
        } else error("Delete failed")
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (config.password.isEmpty()) return true

        val ip = session.remoteIpAddress.orEmpty()
        if (AuthGate.isLockedOut(ip)) return false

        // Accept either Authorization header (browser fetch / WebDAV /
        // companion app) or a wifishare_auth cookie (browser <img>/<video>
        // tags can't add headers but DO send cookies, which is what makes
        // inline streaming work).
        val supplied = parseBasicPassword(session.headers["authorization"])
            ?: parseCookieAuth(session.headers["cookie"])
        if (supplied == null) {
            // No header (or junk) → don't count as a brute-force attempt;
            // the JS client probes with no header on first load.
            return false
        }

        val ok = constantTimeEquals(supplied, config.password)
        if (ok) AuthGate.noteSuccess(ip) else AuthGate.noteFailure(ip)
        return ok
    }

    private fun parseBasicPassword(header: String?): String? {
        if (header == null || !header.startsWith("Basic ", ignoreCase = true)) return null
        return try {
            val decoded = String(
                android.util.Base64.decode(header.substring(6).trim(), android.util.Base64.DEFAULT)
            )
            decoded.substringAfter(':', "")
        } catch (_: Exception) { null }
    }

    private fun parseCookieAuth(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        val token = cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("wifishare_auth=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val decoded = String(android.util.Base64.decode(token, android.util.Base64.DEFAULT))
            decoded.substringAfter(':', "")
        } catch (_: Exception) { null }
    }

    /** Length-leak-free string compare. Always touches every char. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private fun unauthorized(path: String): Response {
        val r = newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            "text/plain; charset=utf-8",
            "Authentication required\n",
        )
        // WWW-Authenticate triggers the browser's ugly native sign-in
        // popup — we only want it on /dav so Windows' WebDAV mini-redirector
        // knows to send credentials. Other endpoints (favicon.ico, /api/*)
        // get a plain 401 and the JS layer handles it.
        if (path.startsWith("/dav")) {
            r.addHeader("WWW-Authenticate", "Basic realm=\"WiFi Share\"")
        }
        return r
    }

    private fun listClients(): Response {
        Clients.prune()
        val arr = JSONArray()
        Clients.activeNow().forEach { c ->
            arr.put(JSONObject().apply {
                put("clientId", c.clientId)
                put("name", c.name)
                put("address", c.address)
                put("firstSeen", c.firstSeen)
                put("lastSeen", c.lastSeen)
                put("transfers", c.transfers)
                put("registered", c.registered)
            })
        }
        return json(Response.Status.OK, JSONObject().put("clients", arr).toString())
    }

    private fun registerClient(session: IHTTPSession, address: String): Response {
        if (session.method != Method.POST) return badRequest("POST required")
        val files = HashMap<String, String>()
        try { session.parseBody(files) } catch (_: Exception) { /* ignore for raw bodies */ }

        val body = files["postData"] ?: files["content"]?.let { File(it).readText() } ?: ""
        val name: String
        val providedId: String?
        try {
            val obj = JSONObject(body)
            name = obj.optString("name").ifBlank { "Companion" }
            providedId = obj.optString("clientId").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            return badRequest("Invalid JSON")
        }
        val client = Clients.register(address, providedId, name)
        return json(
            Response.Status.OK,
            JSONObject()
                .put("clientId", client.clientId)
                .put("name", client.name)
                .toString(),
        )
    }

    private fun handleQueue(session: IHTTPSession, path: String): Response {
        // /api/queue/{clientId} or /api/queue/{clientId}/{itemId}
        val parts = path.removePrefix("/api/queue/").split("/")
        val clientId = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: return badRequest("clientId required")
        val itemId = parts.getOrNull(1)

        return when (session.method.name.uppercase()) {
            "GET" -> {
                if (itemId == null) queuePeek(clientId)
                else queueDownload(clientId, itemId)
            }
            "DELETE" -> {
                if (itemId == null) return badRequest("itemId required")
                if (Queue.ack(clientId, itemId))
                    json(Response.Status.OK, """{"acked":true}""")
                else notFound()
            }
            else -> badRequest("Method not allowed")
        }
    }

    private fun queuePeek(clientId: String): Response {
        val item = Queue.peek(clientId)
            ?: return json(Response.Status.NO_CONTENT, "")
        val obj = JSONObject().apply {
            put("id", item.id)
            put("name", item.name)
            put("size", item.size)
            put("mime", item.mime)
            put("createdAt", item.createdAt)
            put("pending", Queue.pendingCount(clientId))
        }
        return json(Response.Status.OK, obj.toString())
    }

    private fun queueDownload(clientId: String, itemId: String): Response {
        val item = Queue.get(clientId, itemId) ?: return notFound()
        val input = item.tempFile.inputStream()
        val r = newFixedLengthResponse(
            Response.Status.OK,
            item.mime,
            input,
            item.size,
        )
        r.addHeader(
            "Content-Disposition",
            "attachment; filename*=UTF-8''" +
                java.net.URLEncoder.encode(item.name, "UTF-8").replace("+", "%20"),
        )
        return r
    }

    private fun serveAsset(path: String, mime: String): Response {
        return try {
            val bytes = context.assets.open(path).use { it.readBytes() }
            newFixedLengthResponse(
                Response.Status.OK,
                mime,
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        } catch (e: IOException) {
            notFound()
        }
    }

    private fun serveAppDownload(): Response {
        return try {
            val bytes = context.assets.open("win/WiFiShareTray.exe").use { it.readBytes() }
            val r = newFixedLengthResponse(
                Response.Status.OK,
                "application/octet-stream",
                ByteArrayInputStream(bytes),
                bytes.size.toLong(),
            )
            r.addHeader("Content-Disposition", "attachment; filename=\"WiFiShareTray.exe\"")
            r.addHeader("Cache-Control", "no-store")
            r
        } catch (e: IOException) {
            notFound()
        }
    }

    private fun serveStatic(name: String): Response {
        if (name.contains("..") || name.contains('/')) return notFound()
        val mime = when {
            name.endsWith(".css") -> "text/css; charset=utf-8"
            name.endsWith(".js") -> "application/javascript; charset=utf-8"
            name.endsWith(".html") -> "text/html; charset=utf-8"
            name.endsWith(".svg") -> "image/svg+xml"
            else -> "application/octet-stream"
        }
        return serveAsset("web/$name", mime)
    }

    private fun uniqueName(root: DocumentFile, desired: String): String {
        if (root.findFile(desired) == null) return desired
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (root.findFile(candidate) == null) return candidate
            i++
        }
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace("\\", "/").substringAfterLast('/')
        return cleaned.replace(Regex("[<>:\"|?*\\u0000-\\u001F]"), "_").ifBlank { "file" }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun encodeFilename(name: String): String =
        java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun json(status: Response.Status, body: String) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private fun notFound() = json(Response.Status.NOT_FOUND, """{"error":"not found"}""")
    private fun badRequest(msg: String) =
        json(Response.Status.BAD_REQUEST, JSONObject().put("error", msg).toString())

    private fun forbidden(msg: String) =
        json(Response.Status.FORBIDDEN, JSONObject().put("error", msg).toString())

    private fun error(msg: String) =
        json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", msg).toString())
}

private class TransferTrackingInputStream(
    delegate: InputStream,
    private val expected: Long,
    private val onComplete: () -> Unit,
) : FilterInputStream(delegate) {
    private var read = 0L
    private var fired = false

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) read++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) read += n
        return n
    }

    override fun close() {
        super.close()
        if (!fired) {
            fired = true
            // Only count it as "sent" if the client actually pulled the
            // whole file (cancelled / aborted / preview reads don't notify).
            if (expected > 0 && read >= expected) onComplete()
        }
    }
}

private class RangeBoundedStream(
    delegate: InputStream,
    private var remaining: Long,
) : FilterInputStream(delegate) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) remaining--
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }
}

