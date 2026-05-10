package com.wifishare.server

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal WebDAV server that exposes the SAF tree as a flat collection at /dav/.
 *
 * Implements the verbs the Windows WebClient mini-redirector actually calls,
 * including the LOCK/UNLOCK/PROPPATCH dance that Windows demands before it
 * will write a file (returns success without real locking).
 *
 * Subdirectories are not supported — the tree URI is presented as a single
 * flat collection.
 */
class WebDavHandler(
    private val context: Context,
    private val config: FileServer.Config,
    private val basePath: String = "/dav",
) {

    fun handle(session: IHTTPSession): Response {
        val rawUri = session.uri
        val relative = rawUri.removePrefix(basePath).ifEmpty { "/" }
        val name = decodePath(relative).trimStart('/')

        return when (session.method.name.uppercase()) {
            "OPTIONS" -> options()
            "PROPFIND" -> propfind(session, name)
            "GET" -> get(session, name)
            "HEAD" -> head(name)
            "PUT" -> put(session, name)
            "DELETE" -> delete(name)
            "MKCOL" -> mkcol()
            "MOVE" -> move(session, name)
            "COPY" -> notImplemented()
            "PROPPATCH" -> proppatch(session, name)
            "LOCK" -> lock(session, name)
            "UNLOCK" -> noContent()
            else -> methodNotAllowed()
        }
    }

    private fun root(): DocumentFile? = DocumentFile.fromTreeUri(context, config.folderUri)

    private fun options(): Response {
        val r = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
        r.addCommonDavHeaders()
        r.addHeader(
            "Allow",
            "OPTIONS, GET, HEAD, PROPFIND, PROPPATCH, PUT, DELETE, MKCOL, MOVE, COPY, LOCK, UNLOCK",
        )
        return r
    }

    private fun propfind(session: IHTTPSession, name: String): Response {
        val root = root() ?: return error("Folder not accessible")
        val depth = session.headers["depth"] ?: "1"

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.append("""<D:multistatus xmlns:D="DAV:">""")

        if (name.isEmpty() || name == "/") {
            // Collection itself
            appendCollection(sb, basePath + "/", root.lastModified())
            if (depth != "0") {
                root.listFiles().forEach { f ->
                    if (f.isFile && f.name != null) appendFile(sb, f)
                }
            }
        } else {
            val file = root.findFile(name)
            if (file == null || !file.isFile) {
                sb.setLength(0)
                return notFound()
            }
            appendFile(sb, file)
        }

        sb.append("</D:multistatus>")

        val r = newFixedLengthResponse(
            Response.Status.lookup(207) ?: Response.Status.OK,
            "application/xml; charset=utf-8",
            sb.toString(),
        )
        r.addCommonDavHeaders()
        return r
    }

    private fun appendCollection(sb: StringBuilder, href: String, lastModified: Long) {
        val (used, available) = storageQuota()
        sb.append("<D:response>")
        sb.append("<D:href>").append(escapeXml(href)).append("</D:href>")
        sb.append("<D:propstat><D:prop>")
        sb.append("<D:displayname>WiFi Share</D:displayname>")
        sb.append("<D:resourcetype><D:collection/></D:resourcetype>")
        sb.append("<D:getcontentlength>0</D:getcontentlength>")
        sb.append("<D:getlastmodified>").append(httpDate(lastModified)).append("</D:getlastmodified>")
        sb.append("<D:creationdate>").append(iso8601(lastModified)).append("</D:creationdate>")
        sb.append("<D:quota-available-bytes>").append(available).append("</D:quota-available-bytes>")
        sb.append("<D:quota-used-bytes>").append(used).append("</D:quota-used-bytes>")
        sb.append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>")
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
        sb.append("</D:response>")
    }

    /**
     * Returns (used, available) bytes for the storage volume that contains
     * the shared folder. Windows uses these to draw the progress bar on the
     * mounted drive — without them it falls back to the *local* C:\ stats.
     */
    private fun storageQuota(): Pair<Long, Long> {
        return try {
            val path = Environment.getExternalStorageDirectory().absolutePath
            val stat = StatFs(path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val available = stat.availableBlocksLong * stat.blockSizeLong
            (total - available) to available
        } catch (_: Throwable) {
            0L to 0L
        }
    }

    private fun appendFile(sb: StringBuilder, file: DocumentFile) {
        val name = file.name ?: return
        val href = "$basePath/" + encodePath(name)
        sb.append("<D:response>")
        sb.append("<D:href>").append(escapeXml(href)).append("</D:href>")
        sb.append("<D:propstat><D:prop>")
        sb.append("<D:displayname>").append(escapeXml(name)).append("</D:displayname>")
        sb.append("<D:resourcetype/>")
        sb.append("<D:getcontentlength>").append(file.length()).append("</D:getcontentlength>")
        sb.append("<D:getcontenttype>").append(escapeXml(file.type ?: "application/octet-stream"))
            .append("</D:getcontenttype>")
        sb.append("<D:getlastmodified>").append(httpDate(file.lastModified())).append("</D:getlastmodified>")
        sb.append("<D:creationdate>").append(iso8601(file.lastModified())).append("</D:creationdate>")
        sb.append("<D:getetag>").append(escapeXml(etag(file))).append("</D:getetag>")
        sb.append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>")
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
        sb.append("</D:response>")
    }

    private fun get(session: IHTTPSession, name: String): Response {
        val root = root() ?: return error("Folder not accessible")
        if (name.isEmpty()) {
            return newFixedLengthResponse(Response.Status.OK, "text/html", "<h1>WebDAV root</h1>")
        }
        val file = root.findFile(name) ?: return notFound()
        if (!file.isFile) return notFound()

        val total = file.length()
        val mime = file.type ?: "application/octet-stream"
        val rawInput = context.contentResolver.openInputStream(file.uri)
            ?: return error("Cannot open file")

        val rangeHeader = session.headers["range"]
        val range = parseRange(rangeHeader, total)

        // Range request → almost always a Windows Explorer thumbnail or
        // chunked copy. Don't fire a "Sent" notification for these — only
        // emit when the full file is streamed in one GET.
        return if (range == null) {
            val tracking = TrackingInputStream(rawInput, total) { fullRead ->
                if (fullRead) Transfers.emit(TransferEvent.Downloaded(name))
            }
            val r = newFixedLengthResponse(Response.Status.OK, mime, tracking, total)
            r.addHeader("ETag", etag(file))
            r.addHeader("Last-Modified", httpDate(file.lastModified()))
            r.addHeader("Accept-Ranges", "bytes")
            r
        } else {
            val (start, end) = range
            // Skip to start
            var skipped = 0L
            while (skipped < start) {
                val n = rawInput.skip(start - skipped)
                if (n <= 0) break
                skipped += n
            }
            val length = end - start + 1
            val limited = LimitedInputStream(rawInput, length)
            val r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, limited, length)
            r.addHeader("Content-Range", "bytes $start-$end/$total")
            r.addHeader("ETag", etag(file))
            r.addHeader("Last-Modified", httpDate(file.lastModified()))
            r.addHeader("Accept-Ranges", "bytes")
            r
        }
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null || total <= 0) return null
        val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim()) ?: return null
        val (startStr, endStr) = match.destructured
        val start: Long
        val end: Long
        when {
            startStr.isEmpty() -> {
                val suffix = endStr.toLongOrNull() ?: return null
                start = (total - suffix).coerceAtLeast(0)
                end = total - 1
            }
            endStr.isEmpty() -> {
                start = startStr.toLong()
                end = total - 1
            }
            else -> {
                start = startStr.toLong()
                end = endStr.toLong().coerceAtMost(total - 1)
            }
        }
        if (start < 0 || start >= total || end < start) return null
        return start to end
    }

    private fun head(name: String): Response {
        val root = root() ?: return error("Folder not accessible")
        if (name.isEmpty()) {
            val r = newFixedLengthResponse(Response.Status.OK, "text/html", "")
            r.addCommonDavHeaders()
            return r
        }
        val file = root.findFile(name) ?: return notFound()
        if (!file.isFile) return notFound()
        // Use the (status, mime, stream, length) constructor so that
        // Content-Length advertises the *real* file size. NanoHTTPD knows
        // the request is HEAD and skips writing the body, but still emits
        // Content-Length from the constructor's `length` argument — which
        // is what clients like MPC-HC need to decide the file is openable.
        val r = newFixedLengthResponse(
            Response.Status.OK,
            file.type ?: "application/octet-stream",
            ByteArrayInputStream(ByteArray(0)),
            file.length(),
        )
        r.addHeader("ETag", etag(file))
        r.addHeader("Last-Modified", httpDate(file.lastModified()))
        r.addHeader("Accept-Ranges", "bytes")
        return r
    }

    private fun put(session: IHTTPSession, name: String): Response {
        if (!config.allowUploads) return forbidden()
        if (name.isEmpty()) return badRequest()
        val root = root() ?: return error("Folder not accessible")

        val safeName = sanitize(name)
        val existing = root.findFile(safeName)
        val isOverwrite = existing != null
        existing?.delete()

        val mime = guessMime(safeName)
        val target = root.createFile(mime, safeName) ?: return error("Create failed")

        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: -1L

        // Stream body straight from the socket into the SAF output stream.
        // Bypasses NanoHTTPD.parseBody, which would buffer the whole upload
        // to a temp file first (ram + disk + slow + Windows times out).
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

        Transfers.emit(TransferEvent.Uploaded(safeName))

        val status = if (isOverwrite) Response.Status.NO_CONTENT else Response.Status.CREATED
        val r = newFixedLengthResponse(status, "text/plain", "")
        r.addHeader("ETag", etag(target))
        r.addHeader("Location", "$basePath/" + encodePath(safeName))
        r.addHeader("Last-Modified", httpDate(target.lastModified()))
        return r
    }

    private fun delete(name: String): Response {
        if (!config.allowDelete) return forbidden()
        if (name.isEmpty()) return badRequest()
        val root = root() ?: return error("Folder not accessible")
        val file = root.findFile(name) ?: return notFound()
        return if (file.delete()) {
            Transfers.emit(TransferEvent.Deleted(name))
            noContent()
        } else error("Delete failed")
    }

    private fun mkcol(): Response =
        // Subdirectories aren't supported in our flat model
        newFixedLengthResponse(Response.Status.lookup(403) ?: Response.Status.FORBIDDEN, "text/plain", "")

    private fun move(session: IHTTPSession, name: String): Response {
        if (!config.allowUploads) return forbidden()
        val root = root() ?: return error("Folder not accessible")
        val file = root.findFile(name) ?: return notFound()

        val destinationHeader = session.headers["destination"] ?: return badRequest()
        val destName = decodePath(destinationHeader.substringAfterLast('/')).trimStart('/')
        if (destName.isEmpty()) return badRequest()

        val newName = sanitize(destName)
        if (file.renameTo(newName)) {
            Transfers.emit(TransferEvent.Uploaded(newName))
            return noContent()
        }
        return error("Rename failed")
    }

    /** Windows demands LOCK to grant write tokens; we always grant. */
    private fun lock(session: IHTTPSession, name: String): Response {
        val href = if (name.isEmpty()) "$basePath/" else "$basePath/" + encodePath(name)
        val token = "opaquelocktoken:" + java.util.UUID.randomUUID().toString()
        val body = """<?xml version="1.0" encoding="utf-8"?>
<D:prop xmlns:D="DAV:">
  <D:lockdiscovery>
    <D:activelock>
      <D:locktype><D:write/></D:locktype>
      <D:lockscope><D:exclusive/></D:lockscope>
      <D:depth>infinity</D:depth>
      <D:owner><D:href>${escapeXml(session.headers["user-agent"] ?: "client")}</D:href></D:owner>
      <D:timeout>Second-3600</D:timeout>
      <D:locktoken><D:href>${escapeXml(token)}</D:href></D:locktoken>
      <D:lockroot><D:href>${escapeXml(href)}</D:href></D:lockroot>
    </D:activelock>
  </D:lockdiscovery>
</D:prop>"""
        val r = newFixedLengthResponse(Response.Status.OK, "application/xml; charset=utf-8", body)
        r.addHeader("Lock-Token", "<$token>")
        return r
    }

    /** PROPPATCH from Windows is mostly setting timestamps — silently accept. */
    private fun proppatch(session: IHTTPSession, name: String): Response {
        val href = if (name.isEmpty()) "$basePath/" else "$basePath/" + encodePath(name)
        val body = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>${escapeXml(href)}</D:href>
    <D:propstat>
      <D:prop/>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""
        return newFixedLengthResponse(
            Response.Status.lookup(207) ?: Response.Status.OK,
            "application/xml; charset=utf-8",
            body,
        )
    }

    // -- helpers -----------------------------------------------------------

    private fun Response.addCommonDavHeaders() {
        addHeader("DAV", "1, 2")
        addHeader("MS-Author-Via", "DAV")
    }

    private fun etag(file: DocumentFile): String =
        "\"${file.length()}-${file.lastModified()}\""

    private fun httpDate(epoch: Long): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        return sdf.format(Date(epoch))
    }

    private fun iso8601(epoch: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        return sdf.format(Date(epoch))
    }

    private fun encodePath(part: String): String =
        URLEncoder.encode(part, "UTF-8").replace("+", "%20")

    private fun decodePath(part: String): String =
        try { URLDecoder.decode(part, "UTF-8") } catch (_: Exception) { part }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun sanitize(name: String): String {
        val cleaned = name.replace("\\", "/").substringAfterLast('/')
        return cleaned.replace(Regex("[<>:\"|?*\\u0000-\\u001F]"), "_").ifBlank { "file" }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
    private fun forbidden() = newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "")
    private fun badRequest() = newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "")
    private fun noContent() = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
    private fun notImplemented() =
        newFixedLengthResponse(Response.Status.lookup(501) ?: Response.Status.INTERNAL_ERROR, "text/plain", "")
    private fun methodNotAllowed() =
        newFixedLengthResponse(Response.Status.lookup(405) ?: Response.Status.INTERNAL_ERROR, "text/plain", "")
    private fun error(msg: String) =
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", msg)
}

/** Counts bytes streamed; reports whether the full file was actually read. */
private class TrackingInputStream(
    delegate: InputStream,
    private val expected: Long,
    private val onComplete: (fullRead: Boolean) -> Unit,
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
            onComplete(read >= expected && expected > 0)
        }
    }
}

/** Caps the total readable bytes — used for HTTP Range responses. */
private class LimitedInputStream(
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
        val capped = minOf(len.toLong(), remaining).toInt()
        val n = super.read(b, off, capped)
        if (n > 0) remaining -= n
        return n
    }

    override fun available(): Int =
        minOf(super.available().toLong(), remaining).toInt()
}
