package com.wifishare.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FileServer(
    private val context: Context,
    port: Int,
    private val config: Config,
) : NanoWSD(port) {

    data class Config(
        val folderUri: Uri,
        val allowUploads: Boolean,
        val allowDelete: Boolean,
        val password: String,
    )

    private val webdav = WebDavHandler(context, config)

    /**
     * NanoWSD requires a separate hook for non-WS HTTP requests. The
     * superclass's serve() checks for the Upgrade header and routes
     * websocket-style requests to openWebSocket() — everything else
     * comes here.
     */
    override fun serveHttp(session: IHTTPSession): Response = handleHttp(session)

    /**
     * NanoWSD calls this for any request with `Upgrade: websocket`. We
     * route by path; the only WS endpoint we expose is /ws/events, which
     * pushes the same envelope stream as the SSE endpoint.
     *
     * Auth: same Basic-auth scheme as REST — return an immediately-closing
     * socket if the client didn't authenticate. Browsers can't send Basic
     * auth on WS handshakes from JS, so they need to either embed creds
     * in the URL (ws://user:pin@host) or hit /api/events instead.
     */
    override fun openWebSocket(handshake: IHTTPSession): NanoWSD.WebSocket {
        if (!isAuthorized(handshake)) {
            return RejectedWebSocket(handshake, reason = "unauthorized")
        }
        val path = handshake.uri.trimEnd('/').ifEmpty { "/" }
        return when (path) {
            "/ws/events" -> EventsWebSocket(handshake)
            else -> RejectedWebSocket(handshake, reason = "unknown path")
        }
    }

    private fun handleHttp(session: IHTTPSession): Response {
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
                path == "/docs" ||
                path == "/api/health" ||
                path == "/api/info" ||
                path.startsWith("/static/") ||
                path.startsWith("/download/")

            if (!isPublic && !isAuthorized(session)) {
                return unauthorized(rawPath)
            }

            val isTransferRequest = path == "/api/upload" || path == "/api/download" ||
                (path == "/api/files" && session.method.name.equals("PUT", true)) ||
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
                path == "/docs" -> serveAsset("web/docs.html", "text/html; charset=utf-8")
                rawPath == "/dav" || rawPath.startsWith("/dav/") -> webdav.handle(session)
                path.startsWith("/static/") -> serveStatic(path.removePrefix("/static/"))
                path == "/download/WiFiShareTray.exe" -> serveAppDownload()
                path == "/api/files" -> when (session.method.name.uppercase()) {
                    "PUT" -> putRawFile(session)
                    else -> listFiles(session)
                }
                path == "/api/download" -> downloadFile(session)
                path == "/api/upload" -> uploadFiles(session)
                path == "/api/delete" -> deleteFile(session)
                path == "/api/clients" -> listClients()
                path == "/api/clients/register" -> registerClient(session, ip)
                path.startsWith("/api/queue/") -> handleQueue(session, path)
                path == "/api/health" -> json(Response.Status.OK, """{"status":"ok"}""")
                path == "/api/info" -> infoEndpoint()
                path == "/api/storage" -> storageEndpoint()
                path == "/api/file" -> fileInfoEndpoint(session)
                path == "/api/search" -> searchEndpoint(session)
                path == "/api/mkdir" -> mkdirEndpoint(session)
                path == "/api/move" -> moveEndpoint(session)
                path == "/api/copy" -> copyEndpoint(session)
                path == "/api/notify" -> notifyEndpoint(session)
                path == "/api/events" -> sseEventsEndpoint(session)
                path == "/api/manifest" -> manifestEndpoint()
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

    /**
     * Streamed raw-body upload — the curl-friendly cousin of /api/upload.
     *
     *   curl -u user:PIN --upload-file foo.jpg \
     *        "http://phone:8080/api/files?path=Inbox/foo.jpg"
     *
     * Body is the file content (no multipart wrapper). `path` query
     * parameter is the destination, relative to the shared root. Folders
     * along the path must already exist. Filename collisions trigger
     * auto-rename (foo.jpg → foo (1).jpg) just like the web drag-drop.
     */
    private fun putRawFile(session: IHTTPSession): Response {
        if (!config.allowUploads) return forbidden("Uploads disabled")

        val pathParam = session.parameters["path"]?.firstOrNull()?.trim('/')
            ?: return badRequest("path query parameter required")
        val parts = pathParam.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return badRequest("path must include a filename")

        val root = root() ?: return error("Folder not accessible")
        val parentParts = parts.dropLast(1)
        val rawName = parts.last()
        val parent = if (parentParts.isEmpty()) root
            else navigateTo(root, parentParts.joinToString("/"))
                ?: return notFound()
        if (!parent.isDirectory) return badRequest("Parent is not a folder")

        val safeName = sanitize(rawName)
        val finalName = uniqueName(parent, safeName)
        val mime = guessMime(finalName)
        val target = parent.createFile(mime, finalName) ?: return error("Create failed")

        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: -1L
        try {
            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                val input = session.inputStream
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    val toRead = if (contentLength >= 0) {
                        if (written >= contentLength) break
                        minOf(buffer.size.toLong(), contentLength - written).toInt()
                    } else buffer.size
                    val n = input.read(buffer, 0, toRead)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                    written += n
                }
                output.flush()
            } ?: run {
                target.delete()
                return error("Cannot open output")
            }
        } catch (e: IOException) {
            target.delete()
            return error("Write error: ${e.message}")
        }

        Transfers.emit(TransferEvent.Uploaded(finalName))

        val savedPath = if (parentParts.isEmpty()) finalName
            else parentParts.joinToString("/") + "/" + finalName
        val payload = JSONObject()
            .put("saved", finalName)
            .put("path", savedPath)
            .put("size", target.length())
        return json(Response.Status.CREATED, payload.toString())
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

    // ---- Management API endpoints --------------------------------------

    private fun infoEndpoint(): Response {
        val payload = JSONObject().apply {
            put("name", "WiFi Share")
            put("version", com.wifishare.BuildConfig.VERSION_NAME)
            put("device", android.os.Build.MODEL ?: "Android")
            put("manufacturer", android.os.Build.MANUFACTURER ?: "")
            put("sdk", android.os.Build.VERSION.SDK_INT)
            put("authRequired", config.password.isNotEmpty())
            put("allowUploads", config.allowUploads)
            put("allowDelete", config.allowDelete)
        }
        return json(Response.Status.OK, payload.toString())
    }

    private fun storageEndpoint(): Response {
        return try {
            val path = android.os.Environment.getExternalStorageDirectory().absolutePath
            val stat = android.os.StatFs(path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val available = stat.availableBlocksLong * stat.blockSizeLong
            val payload = JSONObject()
                .put("total", total)
                .put("used", total - available)
                .put("available", available)
            json(Response.Status.OK, payload.toString())
        } catch (_: Throwable) {
            error("Storage info unavailable")
        }
    }

    private fun fileInfoEndpoint(session: IHTTPSession): Response {
        val pathParam = session.parameters["path"]?.firstOrNull()
            ?: return badRequest("path required")
        val root = root() ?: return error("Folder not accessible")
        val file = resolveFile(root, pathParam) ?: return notFound()
        val payload = JSONObject().apply {
            put("name", file.name ?: "?")
            put("path", pathParam.trim('/'))
            put("type", if (file.isDirectory) "folder" else "file")
            put("modified", file.lastModified())
            if (file.isFile) {
                put("size", file.length())
                put("mime", file.type ?: "application/octet-stream")
            }
        }
        return json(Response.Status.OK, payload.toString())
    }

    private fun searchEndpoint(session: IHTTPSession): Response {
        val query = session.parameters["q"]?.firstOrNull()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return badRequest("q required")
        val pathParam = session.parameters["path"]?.firstOrNull()?.trim('/').orEmpty()
        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 1000) ?: 200

        val root = root() ?: return error("Folder not accessible")
        val searchRoot = if (pathParam.isEmpty()) root
            else navigateTo(root, pathParam) ?: return notFound()

        val results = JSONArray()
        var count = 0

        fun walk(folder: DocumentFile, relative: String) {
            if (count >= limit) return
            folder.listFiles().forEach { f ->
                if (count >= limit) return@forEach
                val name = f.name ?: return@forEach
                val relPath = if (relative.isEmpty()) name else "$relative/$name"
                if (name.lowercase().contains(query)) {
                    results.put(JSONObject().apply {
                        put("name", name)
                        put("path", relPath)
                        put("type", if (f.isDirectory) "folder" else "file")
                        put("modified", f.lastModified())
                        if (f.isFile) {
                            put("size", f.length())
                            put("mime", f.type ?: "application/octet-stream")
                        }
                    })
                    count++
                }
                if (f.isDirectory) walk(f, relPath)
            }
        }
        walk(searchRoot, pathParam)

        return json(Response.Status.OK, JSONObject()
            .put("query", query)
            .put("results", results)
            .put("truncated", count >= limit)
            .toString())
    }

    private fun mkdirEndpoint(session: IHTTPSession): Response {
        if (session.method != Method.POST) return badRequest("POST required")
        if (!config.allowUploads) return forbidden("Uploads disabled (needed for mkdir)")

        val pathParam = session.parameters["path"]?.firstOrNull()?.trim('/')
            ?: return badRequest("path required")
        val parts = pathParam.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return badRequest("path must be non-empty")

        val root = root() ?: return error("Folder not accessible")
        var current = root
        for (part in parts) {
            val safe = sanitize(part)
            val existing = current.findFile(safe)
            current = if (existing != null) {
                if (!existing.isDirectory) return badRequest("'$safe' already exists as a file")
                existing
            } else {
                current.createDirectory(safe) ?: return error("createDirectory failed")
            }
        }
        return json(Response.Status.CREATED, JSONObject().put("path", parts.joinToString("/")).toString())
    }

    private fun moveEndpoint(session: IHTTPSession): Response {
        if (session.method != Method.POST) return badRequest("POST required")
        if (!config.allowUploads) return forbidden("Uploads disabled (needed for move)")

        val from = session.parameters["from"]?.firstOrNull()?.trim('/')
            ?: return badRequest("from required")
        val to = session.parameters["to"]?.firstOrNull()?.trim('/')
            ?: return badRequest("to required")

        val fromParts = from.split('/').filter { it.isNotEmpty() }
        val toParts = to.split('/').filter { it.isNotEmpty() }
        if (fromParts.isEmpty() || toParts.isEmpty()) return badRequest("paths must be non-empty")

        if (fromParts.dropLast(1) != toParts.dropLast(1)) {
            return badRequest("Cross-folder move not supported; use /api/copy + /api/delete")
        }

        val root = root() ?: return error("Folder not accessible")
        val src = resolveFile(root, from) ?: return notFound()
        val newName = sanitize(toParts.last())
        return if (src.renameTo(newName))
            json(Response.Status.OK, JSONObject()
                .put("path", (toParts.dropLast(1) + newName).joinToString("/"))
                .toString())
        else error("Rename failed")
    }

    private fun copyEndpoint(session: IHTTPSession): Response {
        if (session.method != Method.POST) return badRequest("POST required")
        if (!config.allowUploads) return forbidden("Uploads disabled (needed for copy)")

        val from = session.parameters["from"]?.firstOrNull()?.trim('/')
            ?: return badRequest("from required")
        val to = session.parameters["to"]?.firstOrNull()?.trim('/')
            ?: return badRequest("to required")

        val root = root() ?: return error("Folder not accessible")
        val src = resolveFile(root, from) ?: return notFound()
        if (!src.isFile) return badRequest("Only file copies supported")

        val toParts = to.split('/').filter { it.isNotEmpty() }
        if (toParts.isEmpty()) return badRequest("to must include a filename")
        val toName = toParts.last()
        val toParentPath = toParts.dropLast(1).joinToString("/")
        val toParent = if (toParentPath.isEmpty()) root
            else navigateTo(root, toParentPath) ?: return notFound()

        val safeName = sanitize(toName)
        val finalName = uniqueName(toParent, safeName)
        val mime = src.type ?: "application/octet-stream"
        val target = toParent.createFile(mime, finalName) ?: return error("Create failed")

        try {
            context.contentResolver.openInputStream(src.uri)?.use { input ->
                context.contentResolver.openOutputStream(target.uri)?.use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                target.delete(); return error("Cannot open source")
            }
        } catch (e: IOException) {
            target.delete()
            return error("Copy failed: ${e.message}")
        }

        val savedPath = if (toParentPath.isEmpty()) finalName else "$toParentPath/$finalName"
        return json(Response.Status.CREATED, JSONObject()
            .put("saved", finalName)
            .put("path", savedPath)
            .put("size", target.length())
            .toString())
    }

    private fun notifyEndpoint(session: IHTTPSession): Response {
        if (session.method != Method.POST) return badRequest("POST required")

        val files = HashMap<String, String>()
        try { session.parseBody(files) } catch (_: Exception) { /* raw bodies handled below */ }
        val body = files["postData"]
            ?: files["content"]?.let { File(it).readText() }
            ?: ""

        val title: String
        val text: String
        try {
            val obj = JSONObject(body)
            title = obj.optString("title").ifBlank {
                return badRequest("title required")
            }
            text = obj.optString("text", "")
        } catch (_: Exception) {
            return badRequest("Invalid JSON body — expected {\"title\":\"…\",\"text\":\"…\"}")
        }

        Notifications.emit(title, text)
        return json(Response.Status.OK, JSONObject().put("sent", true).toString())
    }

    // ---- existing endpoints --------------------------------------------

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

    /**
     * Server-Sent Events stream of PhoneEvents envelopes. Same source as
     * the /ws/events WebSocket; this endpoint exists so curl / shell
     * agents can subscribe without a WebSocket client:
     *
     *     curl -N -u user:PIN http://phone:8080/api/events
     */
    private fun sseEventsEndpoint(session: IHTTPSession): Response {
        val stream = EventInputStream()
        // We can't subscribe to PhoneEvents.events directly (it's a
        // SharedFlow) from a non-coroutine context. Use a launching helper
        // tied to the stream's lifetime. The InputStream's close() cancels
        // the collection.
        stream.subscribeOnNewThread()

        // Hello frame so curl sees something immediately and confirms the
        // upgrade. SSE comments start with ':' and clients must ignore them.
        stream.pushLine(": hello ${System.currentTimeMillis()}\n\n")

        val resp = newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", stream)
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("Connection", "keep-alive")
        resp.addHeader("X-Accel-Buffering", "no")
        return resp
    }

    /**
     * JSON manifest matching the tray's /api/manifest — lists every
     * endpoint with a short description so AI agents can fetch one file
     * and know the API surface.
     */
    private fun manifestEndpoint(): Response {
        val endpoints = JSONArray()
        listOf(
            "GET /api/health" to "Liveness probe.",
            "GET /api/info" to "Server identity (device name, version).",
            "GET /api/storage" to "Free / total bytes on the shared storage.",
            "GET /api/files" to "List files in the shared folder.",
            "PUT /api/files?path=<name>" to "Upload a file with the raw bytes as body.",
            "POST /api/upload" to "Multipart upload (alternative).",
            "GET /api/download?name=<file>" to "Download a single file.",
            "POST /api/delete?path=<file>" to "Delete a file (requires Allow delete).",
            "GET /api/file?path=<file>" to "Single-file stat (size, modified, mime).",
            "GET /api/search?q=<text>" to "Recursive search by filename.",
            "POST /api/mkdir?path=<dir>" to "Create a folder (idempotent).",
            "POST /api/move?from=&to=" to "Rename a file within the same parent.",
            "POST /api/copy?from=&to=" to "Server-side copy.",
            "POST /api/notify" to "Pop a system notification on the phone — body: {title, text}.",
            "GET /api/clients" to "Active clients (recent IPs + companion registrations).",
            "POST /api/clients/register" to "Register a stable clientId / friendly name.",
            "GET /api/queue/{clientId}" to "Pull next queued file for this client.",
            "WS  /ws/events" to "WebSocket push channel — same source as /api/events.",
            "GET /api/events" to "Server-Sent Events: live stream of every event envelope.",
            "GET /docs" to "Human-readable HTML docs for all of the above.",
        ).forEach { (path, desc) ->
            endpoints.put(JSONObject().put("path", path).put("description", desc))
        }
        val out = JSONObject()
            .put("name", "WiFi Share phone API")
            .put("description", "REST + push API served from the Android phone for AI agents / scripts.")
            .put("eventTypes", JSONArray().apply {
                listOf(
                    "transfer.uploaded",
                    "transfer.downloaded",
                    "transfer.deleted",
                    "queue.changed",
                    "client.connected",
                    "client.disconnected",
                    "notification",
                ).forEach { put(it) }
            })
            .put("endpoints", endpoints)
        return json(Response.Status.OK, out.toString(2))
    }

    /**
     * Custom InputStream that NanoHTTPD reads in its chunked-response
     * loop. Internally backed by a BlockingQueue of pre-serialised SSE
     * lines. close() unsubscribes from PhoneEvents.events and unblocks
     * the reader.
     */
    private inner class EventInputStream : InputStream() {
        private val queue = LinkedBlockingQueue<ByteArray>(256)
        private var pending: ByteArray? = null
        private var pendingPos = 0
        private val closed = AtomicBoolean(false)
        private var subscriberThread: Thread? = null

        fun pushLine(line: String) {
            if (closed.get()) return
            queue.offer(line.toByteArray(Charsets.UTF_8))
        }

        fun subscribeOnNewThread() {
            // We run a tiny dedicated thread that walks PhoneEvents.events
            // (collected via collectLatest in a runBlocking on this thread)
            // so we avoid pulling in CoroutineScope machinery here.
            subscriberThread = Thread({
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        PhoneEvents.events.collect { ev ->
                            if (closed.get()) return@collect
                            val json = JSONObject().apply {
                                put("type", ev.type)
                                put("ts", ev.ts)
                                put("seq", ev.seq)
                                put("data", JSONObject(ev.data))
                            }.toString()
                            pushLine("data: $json\n\n")
                        }
                    }
                }
            }, "phone-sse-subscriber").also { it.isDaemon = true; it.start() }

            // Heartbeat thread so dead clients are detected within ~20 s.
            Thread({
                while (!closed.get()) {
                    Thread.sleep(20_000)
                    pushLine(": ping ${System.currentTimeMillis()}\n\n")
                }
            }, "phone-sse-heartbeat").also { it.isDaemon = true; it.start() }
        }

        override fun read(): Int {
            while (true) {
                val current = pending
                if (current != null && pendingPos < current.size) {
                    return current[pendingPos++].toInt() and 0xff
                }
                if (closed.get()) return -1
                val next = queue.poll(2, TimeUnit.SECONDS) ?: continue
                if (next.isEmpty() && closed.get()) return -1
                pending = next
                pendingPos = 0
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            // Block for at least one byte; copy what's locally buffered.
            val first = read()
            if (first < 0) return -1
            b[off] = first.toByte()
            var written = 1
            val current = pending
            if (current != null) {
                val avail = current.size - pendingPos
                val toCopy = minOf(avail, len - written)
                if (toCopy > 0) {
                    System.arraycopy(current, pendingPos, b, off + written, toCopy)
                    pendingPos += toCopy
                    written += toCopy
                }
            }
            return written
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                queue.offer(ByteArray(0))
                subscriberThread?.interrupt()
            }
        }
    }
}

/**
 * WebSocket pushed to clients hitting /ws/events. Forwards every
 * PhoneEvents envelope as a JSON text frame; sends a hello on connect
 * and a ping every 20 s.
 */
private class EventsWebSocket(handshake: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(handshake) {

    private val subscriberThread: Thread = Thread {
        runCatching {
            kotlinx.coroutines.runBlocking {
                PhoneEvents.events.collect { ev ->
                    val json = JSONObject().apply {
                        put("type", ev.type)
                        put("ts", ev.ts)
                        put("seq", ev.seq)
                        put("data", JSONObject(ev.data))
                    }.toString()
                    runCatching { send(json) }.onFailure {
                        throw kotlinx.coroutines.CancellationException("ws closed")
                    }
                }
            }
        }
    }.apply { isDaemon = true; name = "phone-ws-subscriber" }

    private val pingThread: Thread = Thread {
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(20_000)
                runCatching { ping(byteArrayOf(0)) }
            }
        } catch (_: InterruptedException) { /* normal */ }
    }.apply { isDaemon = true; name = "phone-ws-ping" }

    override fun onOpen() {
        runCatching {
            send("""{"type":"hello","ts":${System.currentTimeMillis()}}""")
        }
        subscriberThread.start()
        pingThread.start()
    }

    override fun onClose(
        code: fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode?,
        reason: String?,
        initiatedByRemote: Boolean,
    ) {
        subscriberThread.interrupt()
        pingThread.interrupt()
    }

    override fun onMessage(message: fi.iki.elonen.NanoWSD.WebSocketFrame?) {
        // We don't accept client commands over the socket. Ignore.
    }

    override fun onPong(pong: fi.iki.elonen.NanoWSD.WebSocketFrame?) { /* no-op */ }

    override fun onException(exception: IOException?) {
        subscriberThread.interrupt()
        pingThread.interrupt()
    }
}

/**
 * Stand-in WebSocket for unauthorized / unknown-path WS handshakes —
 * closes the connection immediately so the client doesn't sit there
 * thinking it's connected to a real event stream.
 */
private class RejectedWebSocket(
    handshake: NanoHTTPD.IHTTPSession,
    private val reason: String,
) : NanoWSD.WebSocket(handshake) {
    override fun onOpen() {
        runCatching {
            close(
                fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode.PolicyViolation,
                reason,
                false,
            )
        }
    }
    override fun onClose(
        code: fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode?,
        reason: String?,
        initiatedByRemote: Boolean,
    ) { /* no-op */ }
    override fun onMessage(message: fi.iki.elonen.NanoWSD.WebSocketFrame?) { /* no-op */ }
    override fun onPong(pong: fi.iki.elonen.NanoWSD.WebSocketFrame?) { /* no-op */ }
    override fun onException(exception: IOException?) { /* no-op */ }
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

