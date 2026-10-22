package me.zhanghai.android.files.folderbridge

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * Hand-written minimal HTTPS server serving the chosen folder to the paired Mac, in the spirit of
 * the old recbridge HttpBridge (no third-party server library).
 *
 * All paths exchanged with the Mac are relative to the chosen SAF tree root; there is no device
 * absolute-path prefix anymore (that was the old /sdcard/Sounds model).
 */
class FolderBridgeServer(
    private val sslContext: SSLContext,
    private val port: Int,
    private val kernel: FolderBridgeKernel
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    // Bounded to the thread count on purpose: a stalled or hostile connection must not be able
    // to pile up accepted-but-unhandled sockets. Extra connections are closed immediately;
    // the paired Mac retries on its own schedule.
    private val pool: ThreadPoolExecutor = ThreadPoolExecutor(
        POOL_THREADS, POOL_THREADS, 30L, TimeUnit.SECONDS,
        SynchronousQueue(), { runnable ->
            Thread(runnable, "FileWeave-folder-bridge-connection").apply { isDaemon = true }
        })

    @Volatile
    private var running = false

    val isRunning: Boolean
        get() = running

    @Throws(IOException::class)
    fun start() {
        val server = sslContext.serverSocketFactory.createServerSocket() as ServerSocket
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        serverSocket = server
        running = true
        acceptThread = Thread({
            var consecutiveFailures = 0
            while (running) {
                val socket = try {
                    server.accept()
                } catch (error: Exception) {
                    if (running) {
                        // Back off so a persistent failure (EMFILE, network gone) cannot
                        // spin this thread at 100% CPU. The owner's onError only hears about
                        // persistent failure, never single transient hiccups.
                        if (++consecutiveFailures >= 3) {
                            onError?.invoke(error)
                            Thread.sleep(500)
                        }
                    }
                    continue
                }
                consecutiveFailures = 0
                try {
                    pool.execute { handle(socket) }
                } catch (_: RejectedExecutionException) {
                    try {
                        socket.close()
                    } catch (_: Exception) { }
                }
            }
        }, "FileWeave-folder-bridge-accept").apply {
            isDaemon = true
            start()
        }
        pool.allowCoreThreadTimeOut(true)
    }

    var onError: ((Exception) -> Unit)? = null

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            kernel.handle(input, output)
            output.flush()
        } catch (_: Exception) {
            // A malformed or truncated request simply gets the connection closed.
        } finally {
            try {
                socket.close()
            } catch (_: Exception) { }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) { }
        serverSocket = null
        pool.shutdownNow()
    }

    companion object {
        const val DEFAULT_PORT = 8791
        private const val SOCKET_TIMEOUT_MILLIS = 20_000
        private const val POOL_THREADS = 4
    }
}

/** Pure Kotlin HTTP core: request parsing, bearer authentication, routing and Range handling. */
class FolderBridgeKernel(
    private val backend: FolderBridgeBackend,
    private val token: String,
    private val model: String
) {
    private val tokenBytes = token.toByteArray(Charsets.UTF_8)

    /** Files counted by the most recent scan; -1 when the last scan failed or has not run. */
    var lastScanCount: Int = -1
        private set

    /** Invoked on the connection thread after every scan attempt with the resulting count. */
    var onScan: ((Int) -> Unit)? = null

    fun handle(input: InputStream, output: OutputStream) {
        val requestLine = readLine(input) ?: return
        if (requestLine.text.isEmpty()) {
            return
        }
        var headerBytes = requestLine.bytes
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.text.isEmpty()) {
                break
            }
            headerBytes += line.bytes
            if (headerBytes > MAX_HEADER_BYTES) {
                respondJson(output, 400, errorJson("Header section too large"))
                return
            }
            val colon = line.text.indexOf(':')
            if (colon > 0) {
                headers[line.text.substring(0, colon).trim { it <= ' ' }
                    .lowercase(Locale.ROOT)] = line.text.substring(colon + 1).trim { it <= ' ' }
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
            respondJson(output, 413, errorJson("Request body too large"))
            return
        }
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val count = input.read(body, read, contentLength - read)
            if (count < 0) {
                break
            }
            read += count
        }

        val parts = requestLine.text.split(" ")
        val method = parts.getOrNull(0) ?: return
        val target = parts.getOrNull(1) ?: "/"
        val question = target.indexOf('?')
        val path = if (question >= 0) target.substring(0, question) else target
        val query = if (question >= 0) target.substring(question + 1) else ""

        // Every endpoint, including /v1/status, requires the paired Mac's bearer credential.
        if (!isAuthorized(headers["authorization"])) {
            respondJson(output, 401, errorJson("Pairing required"))
            return
        }

        when (path) {
            "/v1/status" -> dispatch(output, method, "GET") { respondJson(output, 200, statusJson()) }
            "/v1/tree" -> dispatch(output, method, "GET") { respondJson(output, 200, treeJson()) }
            "/v1/file" -> dispatch(output, method, "GET") { serveFile(query, headers, output) }
            "/v1/trash" -> dispatch(output, method, "POST") {
                respondJson(output, 200, trashJson(body))
            }
            "/v1/restore" -> dispatch(output, method, "POST") {
                respondJson(output, 200, restoreJson(body))
            }
            else -> respondJson(output, 404, errorJson("Unknown endpoint"))
        }
    }

    private inline fun dispatch(output: OutputStream, method: String, expected: String,
                                action: () -> Unit) {
        if (method != expected) {
            respondJson(output, 405, errorJson("Method not allowed"), allow = expected)
            return
        }
        action()
    }

    private fun isAuthorized(authorization: String?): Boolean {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false
        }
        val actual = authorization.substring(7).toByteArray(Charsets.UTF_8)
        // Constant-time comparison, matching the Mac side's hmac.compare_digest style.
        return MessageDigest.isEqual(tokenBytes, actual)
    }

    // ---------------------------------------------------------------- Routing

    private fun statusJson(): String {
        val items = scanReporting()
        val files = items?.size ?: -1
        return "{\"proto\":1,\"model\":" + jsonQuote(model) + ",\"files\":" + files +
            ",\"root\":" + jsonQuote(backend.rootName) + "}"
    }

    private fun treeJson(): String {
        val items = scanReporting() ?: return errorJson("Scan failed")
        return try {
            val builder = StringBuilder("{\"ok\":true,\"items\":[")
            var first = true
            for (item in items) {
                if (!first) {
                    builder.append(',')
                }
                first = false
                builder.append("{\"id\":").append(jsonQuote(item.id))
                    .append(",\"path\":").append(jsonQuote(item.path))
                    .append(",\"size\":").append(item.size)
                    .append(",\"mtime\":").append(item.mtime)
                if (item.trashPath != null) {
                    builder.append(",\"trashPath\":").append(jsonQuote(item.trashPath))
                    // Absent when the batch directory name is not a readable stamp; the Mac
                    // treats a missing (or zero) value as "unknown", never as 1970-01-01.
                    if (item.movedAt != null) {
                        builder.append(",\"movedAt\":").append(item.movedAt)
                    }
                }
                builder.append('}')
            }
            builder.append("]}")
            builder.toString()
        } catch (error: Exception) {
            errorJson(errorMessage(error))
        }
    }

    /** Runs one scan, updates lastScanCount and reports it; null means the scan failed (-1). */
    private fun scanReporting(): List<FolderBridgeItem>? {
        return try {
            val items = backend.listFiles()
            lastScanCount = items.size
            onScan?.invoke(items.size)
            items
        } catch (_: Exception) {
            lastScanCount = -1
            onScan?.invoke(-1)
            null
        }
    }

    private fun serveFile(query: String, headers: Map<String, String>, output: OutputStream) {
        val path = queryParam(query, "path")
        if (path.isNullOrEmpty() || !FolderBridgePaths.isSafeRelativePath(path)) {
            respondJson(output, 400, errorJson("Invalid path"))
            return
        }
        val opened = try {
            backend.openFile(path)
        } catch (error: FolderBridgeNotFound) {
            respondJson(output, 404, errorJson("File unavailable"))
            return
        }
        opened.input.use { input ->
            val size = opened.size
            var start = 0L
            var end = size - 1
            var status = 200
            var contentRange: String? = null
            val range = headers["range"]
            if (range != null && size >= 0) {
                // Single range only, same semantics as the Mac bridge.py server.
                val match = RANGE_REGEX.matchEntire(range.trim { it <= ' ' })
                if (match == null) {
                    respondJson(output, 416, errorJson("Unsupported range"))
                    return
                }
                start = match.groupValues[1].toLongOrNull() ?: run {
                    respondJson(output, 416, errorJson("Unsupported range"))
                    return
                }
                if (match.groupValues[2].isNotEmpty()) {
                    end = minOf(match.groupValues[2].toLongOrNull() ?: run {
                        respondJson(output, 416, errorJson("Unsupported range"))
                        return
                    }, end)
                }
                if (start > end || start >= size) {
                    respondJson(output, 416, errorJson("Range exceeds file"))
                    return
                }
                status = 206
                contentRange = "bytes $start-$end/$size"
            }
            val length = if (size >= 0) end - start + 1 else -1L
            val builder = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
                .append("Content-Type: audio/mp4\r\n")
                .append("Accept-Ranges: bytes\r\n")
            if (length >= 0) {
                builder.append("Content-Length: ").append(length).append("\r\n")
            }
            builder.append("Cache-Control: no-store\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
            if (contentRange != null) {
                builder.append("Content-Range: ").append(contentRange).append("\r\n")
            }
            builder.append("Connection: close\r\n\r\n")
            output.write(builder.toString().toByteArray(Charsets.UTF_8))
            output.flush()

            skipFully(input, start)
            if (length >= 0) {
                var remaining = length
                val buffer = ByteArray(FILE_BUFFER_BYTES)
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count < 0) {
                        break
                    }
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            } else {
                // Unknown size: close-delimited body.
                val buffer = ByteArray(FILE_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        break
                    }
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
        }
    }

    private fun trashJson(body: ByteArray): String {
        return try {
            val text = String(body, Charsets.UTF_8)
            val id = jsonField(text, "id")
            val path = jsonField(text, "path")
            if (id == null || path == null) {
                return errorJson("Missing id or path")
            }
            if (!isPlausibleDocumentId(id) || !FolderBridgePaths.isSafeRelativePath(path)) {
                return errorJson("Invalid path")
            }
            val trashPath = backend.trash(id, path)
            "{\"ok\":true,\"trashPath\":" + jsonQuote(trashPath) + "}"
        } catch (error: Exception) {
            errorJson(errorMessage(error))
        }
    }

    private fun restoreJson(body: ByteArray): String {
        return try {
            val text = String(body, Charsets.UTF_8)
            val id = jsonField(text, "id")
            val path = jsonField(text, "path")
            val to = jsonField(text, "to")
            if (id == null || path == null || to == null) {
                return errorJson("Missing id / path / to")
            }
            if (!isPlausibleDocumentId(id) || !FolderBridgePaths.isSafeRelativePath(path) ||
                !FolderBridgePaths.isSafeRelativePath(to)) {
                return errorJson("Invalid path")
            }
            backend.restore(id, path, to)
            "{\"ok\":true}"
        } catch (error: Exception) {
            errorJson(errorMessage(error))
        }
    }

    // ---------------------------------------------------------------- IO

    private fun respondJson(output: OutputStream, code: Int, body: String, allow: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val builder = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
            .append("Content-Type: application/json; charset=utf-8\r\n")
            .append("Content-Length: ").append(bytes.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
        if (allow != null) {
            builder.append("Allow: ").append(allow).append("\r\n")
        }
        builder.append("Connection: close\r\n\r\n")
        output.write(builder.toString().toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun errorMessage(error: Exception): String =
        error.message ?: error.javaClass.simpleName

    companion object {
        const val MAX_HEADER_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 4 * 1024
        const val FILE_BUFFER_BYTES = 256 * 1024
        private val RANGE_REGEX = Regex("bytes=([0-9]+)-([0-9]*)")

        private fun reason(code: Int): String = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            416 -> "Range Not Satisfiable"
            else -> "Error"
        }
    }
}

/** Thrown by backends when a requested path does not exist. */
class FolderBridgeNotFound(path: String) : Exception("File not found: $path")

/** Filesystem abstraction injected into the kernel so the HTTP core stays JVM-testable. */
interface FolderBridgeBackend {
    /** Last segment of the shared directory's identity, shown to the Mac in /v1/status. */
    val rootName: String

    fun listFiles(): List<FolderBridgeItem>

    @Throws(FolderBridgeNotFound::class)
    fun openFile(path: String): FolderBridgeOpenFile

    /** Moves the file into .Trash and returns its trash-relative path. */
    fun trash(id: String, path: String): String

    /** Restores a trashed file to the given relative path. */
    fun restore(id: String, path: String, to: String)
}

class FolderBridgeItem(
    val id: String,
    /** Identity: the file's original location, relative to the tree root; survives moves. */
    val path: String,
    val size: Long,
    /** Epoch milliseconds. */
    val mtime: Long,
    /** Non-null when the file currently sits in .Trash. */
    val trashPath: String? = null,
    val movedAt: Long? = null
)

class FolderBridgeOpenFile(val size: Long, val input: InputStream)

/** Pure path/trash helpers shared by the kernel and the SAF backend; unit-tested on the JVM. */
object FolderBridgePaths {
    const val TRASH_DIR = ".Trash"

    private val AUDIO_EXTENSIONS = listOf("m4a", "mp3", "aac", "amr", "wav")

    fun isAudio(name: String?, mime: String?): Boolean {
        if (mime != null && mime.startsWith("audio/")) {
            return true
        }
        if (name == null) {
            return false
        }
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in AUDIO_EXTENSIONS
    }

    /** True for non-empty relative paths without traversal, absolute prefixes or separators. */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.contains('\\') ||
            path.contains('\u0000') || path.endsWith("/")) {
            return false
        }
        return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }

    /** ".Trash/<batch>/<original relative path>" back to the original path; null if malformed. */
    fun originalPathFromTrash(trashPath: String): String? {
        if (!trashPath.startsWith("$TRASH_DIR/")) {
            return null
        }
        val rest = trashPath.substring(TRASH_DIR.length + 1)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.length - 1) {
            return null
        }
        val original = rest.substring(slash + 1)
        return if (isSafeRelativePath(original)) original else null
    }

    /** Batch directories are epoch-millisecond stamps; unreadable ones count as 0. */
    fun parseStamp(batchName: String): Long =
        try {
            batchName.toLong()
        } catch (_: NumberFormatException) {
            0L
        }

    /** "primary:Sounds/Recordings" or "/sdcard/Sounds" style identifiers to their last segment. */
    fun rootNameOf(documentId: String): String =
        documentId.split(':', '/').lastOrNull { it.isNotEmpty() } ?: documentId
}

// ---------------------------------------------------------------- Private helpers

/** One header line plus the exact number of raw bytes it consumed (the cap counts bytes). */
private class HeaderLine(val text: String, val bytes: Int)

private fun readLine(input: InputStream): HeaderLine? {
    val buffer = StringBuilder()
    var byteCount = 0
    while (byteCount <= FolderBridgeKernel.MAX_HEADER_BYTES) {
        val c = input.read()
        if (c < 0) {
            return if (buffer.isEmpty()) null else HeaderLine(buffer.toString(), byteCount)
        }
        ++byteCount
        if (c == '\n'.code) {
            break
        }
        if (c != '\r'.code) {
            buffer.append(c.toChar())
        }
    }
    return HeaderLine(buffer.toString(), byteCount)
}

private fun skipFully(input: InputStream, count: Long) {
    var remaining = count
    val buffer = ByteArray(FolderBridgeKernel.FILE_BUFFER_BYTES)
    while (remaining > 0) {
        val skipped = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        if (skipped < 0) {
            throw IOException("EOF while seeking")
        }
        remaining -= skipped
    }
}

private fun queryParam(query: String, key: String): String? {
    for (pair in query.split('&')) {
        val index = pair.indexOf('=')
        if (index > 0 && pair.substring(0, index) == key) {
            return percentDecode(pair.substring(index + 1))
        }
    }
    return null
}

/**
 * Decodes %XX only. Unlike URLDecoder this is query-string semantics, where '+' is a literal
 * character: a Mac client sending a file name with '+' must not have it turned into a space.
 */
private fun percentDecode(value: String): String {
    if (!value.contains('%')) {
        return value
    }
    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val c = value[index]
        if (c == '%' && index + 2 < value.length) {
            val hex = value.substring(index + 1, index + 3)
            val code = hex.toIntOrNull(16)
            if (code != null) {
                builder.append(code.toChar())
                index += 3
                continue
            }
        }
        builder.append(c)
        ++index
    }
    return builder.toString()
}

private fun errorJson(message: String): String = "{\"ok\":false,\"error\":" + jsonQuote(message)

/**
 * Cheap sanity gate for write operations: the documentId is provider-owned, but it must be
 * non-blank, NUL-free and have no ".." path segment. Which tree a document belongs to is still
 * enforced by the provider itself; this only blocks obviously hostile shapes before we build
 * URIs. A literal ".." inside a file name ("a..b.m4a") stays allowed.
 */
private fun isPlausibleDocumentId(id: String): Boolean =
    id.isNotBlank() && !id.contains('\u0000') &&
        id.split('/', ':').none { it == ".." }

private fun jsonQuote(value: String?): String {
    if (value == null) {
        return "null"
    }
    val builder = StringBuilder("\"")
    for (c in value) {
        when (c) {
            '"' -> builder.append("\\\"")
            '\\' -> builder.append("\\\\")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            else -> if (c < ' ') {
                builder.append(String.format(Locale.ROOT, "\\u%04x", c.code))
            } else {
                builder.append(c)
            }
        }
    }
    return builder.append('"').toString()
}

/**
 * Reads one top-level string field from a small JSON object. The requests come from our own Mac
 * client, so a full parser is unnecessary.
 */
internal fun jsonField(json: String, key: String): String? {
    val needle = "\"$key\""
    val start = json.indexOf(needle)
    if (start < 0) {
        return null
    }
    val colon = json.indexOf(':', start + needle.length)
    if (colon < 0) {
        return null
    }
    var index = colon + 1
    while (index < json.length && json[index].isWhitespace()) {
        ++index
    }
    if (index >= json.length || json[index] != '"') {
        return null
    }
    val builder = StringBuilder()
    ++index
    while (index < json.length) {
        val c = json[index]
        if (c == '\\' && index + 1 < json.length) {
            val next = json[++index]
            when (next) {
                'n' -> builder.append('\n')
                't' -> builder.append('\t')
                'r' -> builder.append('\r')
                'u' -> if (index + 4 < json.length) {
                    builder.append(json.substring(index + 1, index + 5).toInt(16).toChar())
                    index += 4
                } else {
                    return null
                }
                else -> builder.append(next)
            }
        } else if (c == '"') {
            return builder.toString()
        } else {
            builder.append(c)
        }
        ++index
    }
    return null
}
