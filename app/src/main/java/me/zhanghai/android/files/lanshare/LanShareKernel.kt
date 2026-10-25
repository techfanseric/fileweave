package me.zhanghai.android.files.lanshare

import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder

/** A read-only, scoped browser share. The unguessable URL is the access grant. */
class LanShareKernel(private val token: String, private val backend: Backend) {
    data class Entry(val name: String, val directory: Boolean, val size: Long)
    interface Backend {
        fun stat(path: List<String>): Entry?
        fun list(path: List<String>): List<Entry>
        fun open(path: List<String>, offset: Long): InputStream
    }
    @Volatile var active = true

    fun handle(input: InputStream, output: OutputStream) {
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < 8192) {
                val ch = input.read()
                if (ch < 0 || ch == 10) return bytes.toString("UTF-8").trimEnd('\r')
                bytes.write(ch)
            }
            throw java.io.IOException("Header too long")
        }
        val request = line().split(' ')
        val headers = mutableMapOf<String, String>()
        var total = 0
        while (true) {
            val value = line()
            if (value.isEmpty()) break
            total += value.length
            require(total <= 32768)
            val colon = value.indexOf(':')
            require(colon > 0)
            headers[value.substring(0, colon).lowercase()] = value.substring(colon + 1).trim()
        }
        fun reply(code: Int, body: String) {
            val bytes = body.toByteArray()
            head(output, code, "text/html; charset=utf-8", bytes.size.toLong())
            if (request.firstOrNull() != "HEAD") output.write(bytes)
        }
        if (!active) { reply(410, "Share ended"); return }
        if (request.size != 3 || request[0] !in listOf("GET", "HEAD")) {
            reply(405, "Read-only share"); return
        }
        val raw = request[1].substringBefore('?')
        val prefix = "/$token/"
        if (!raw.startsWith(prefix)) { reply(404, "Not found"); return }
        val parts = try {
            raw.removePrefix(prefix).trimEnd('/').takeIf { it.isNotEmpty() }?.split('/')?.map {
                URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")
            }.orEmpty()
        } catch (_: Exception) { reply(400, "Invalid path"); return }
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.any { c -> c == '/' || c == '\\' || c.code < 32 } }) {
            reply(400, "Invalid path"); return
        }
        val entry = try { backend.stat(parts) } catch (_: Exception) { null }
        if (entry == null) { reply(404, "Not found"); return }
        if (entry.directory) {
            val entries = try { backend.list(parts) } catch (_: Exception) { reply(403, "Unavailable"); return }
            val base = prefix + parts.joinToString("/") { encode(it) }.let { if (it.isEmpty()) it else "$it/" }
            val parent = if (parts.isEmpty()) "" else "<a class=entry href=\"${prefix + parts.dropLast(1).joinToString("/") { encode(it) }}/\">← 上一级 / Back</a>"
            val rows = entries.sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase() }).joinToString("") {
                val href = base + encode(it.name) + if (it.directory) "/" else ""
                "<a class=entry href=\"$href\"${if (it.directory) "" else " download"}><span>${if (it.directory) "📁" else "📄"} ${escape(it.name)}</span><small>${if (it.directory) "打开 / Open" else size(it.size) + " · 下载 / Download"}</small></a>"
            }
            reply(200, """<!doctype html><html lang="zh"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FileWeave</title><style>body{font:16px system-ui;background:#f5f7fa;color:#17263a;margin:0;padding:28px 16px}main{max-width:800px;margin:auto}h1{font-size:24px}p,small{color:#65758a}section{background:white;border-radius:16px;overflow:hidden}.entry{display:flex;gap:20px;justify-content:space-between;align-items:center;padding:18px;color:inherit;text-decoration:none;border-bottom:1px solid #edf0f4}.entry span{overflow-wrap:anywhere}.entry small{flex-shrink:0;font-size:12px}.entry:hover{background:#edf5ff}@media(max-width:480px){.entry{align-items:start;flex-direction:column;gap:6px}}</style><main><h1>FileWeave</h1><p>${escape(entry.name)} · 局域网只读分享 / Read-only share</p><section>$parent${rows.ifEmpty { "<p style='padding:18px'>空文件夹 / Empty folder</p>" }}</section><p>手机停止分享后，此地址立即失效。</p></main></html>""")
            return
        }
        val length = entry.size
        var start = 0L
        var end = length - 1
        val range = headers["range"]
        if (range != null) {
            val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range)
            if (match == null || length <= 0) { head(output, 416, "text/plain", 0, "Content-Range: bytes */$length\r\n"); return }
            val first = match.groupValues[1]; val last = match.groupValues[2]
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull()
                if (suffix == null || suffix <= 0) { head(output, 416, "text/plain", 0, "Content-Range: bytes */$length\r\n"); return }
                start = (length - suffix).coerceAtLeast(0)
            } else {
                start = first.toLongOrNull() ?: -1
                if (last.isNotEmpty()) end = last.toLongOrNull() ?: -1
                end = end.coerceAtMost(length - 1)
            }
            if (start < 0 || start >= length || end < start) { head(output, 416, "text/plain", 0, "Content-Range: bytes */$length\r\n"); return }
        }
        if (length < 0) { reply(403, "Unknown file size"); return }
        val count = (end - start + 1).coerceAtLeast(0)
        val extra = "Accept-Ranges: bytes\r\nContent-Disposition: attachment; filename*=UTF-8''${encode(entry.name)}\r\n" +
            if (range != null) "Content-Range: bytes $start-$end/$length\r\n" else ""
        if (request[0] == "HEAD") { head(output, if (range == null) 200 else 206, "application/octet-stream", count, extra); return }
        val stream = try { backend.open(parts, start) } catch (_: Exception) { reply(403, "Unavailable"); return }
        stream.use {
            head(output, if (range == null) 200 else 206, "application/octet-stream", count, extra)
            val buffer = ByteArray(64 * 1024)
            var left = count
            while (left > 0 && active && !Thread.currentThread().isInterrupted) {
                val read = it.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                left -= read
            }
        }
    }

    private fun head(out: OutputStream, code: Int, type: String, length: Long, extra: String = "") {
        val reason = mapOf(200 to "OK", 206 to "Partial Content", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 410 to "Gone", 416 to "Range Not Satisfiable")[code]
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: $length\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'\r\n$extra\r\n").toByteArray())
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun size(value: Long) = when { value >= 1048576 -> "%.1f MB".format(java.util.Locale.ROOT, value / 1048576.0); value >= 1024 -> "%.1f KB".format(java.util.Locale.ROOT, value / 1024.0); else -> "$value B" }
}
