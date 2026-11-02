package me.zhanghai.android.files.lanshare

import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/** A read-only, scoped browser share. The unguessable URL is the access grant. */
class LanShareKernel(
    private val token: String,
    private val backend: Backend,
    private val manualAccess: LanShareManualAccess? = null
) {
    enum class RootTitle { SHARED_FILES, PHONE_STORAGE }
    data class Entry(val name: String, val directory: Boolean, val size: Long, val rootTitle: RootTitle? = null)
    interface Backend {
        fun stat(path: List<String>): Entry?
        fun list(path: List<String>): List<Entry>
        fun open(path: List<String>, offset: Long): InputStream
    }
    @Volatile var active = true
    /** An explicit foreground snapshot, never a callback into Android's clipboard. */
    @Volatile var clipboardText: String? = null

    fun renewManualAccess(code: String): Boolean {
        val access = manualAccess ?: return false
        access.renew(code)
        return true
    }

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
            headers[value.substring(0, colon).lowercase(Locale.ROOT)] = value.substring(colon + 1).trim()
        }
        val target = request.getOrNull(1).orEmpty()
        val raw = target.substringBefore('?')
        val originalQuery = target.substringAfter('?', "")
        val languageTag = originalQuery.split('&').singleOrNull { it.startsWith("lang=") }
            ?.substringAfter('=')?.takeIf { it == "en" || it == "zh-CN" }
        val cookieLanguage = headers["cookie"]?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("fileweave_language=") }?.substringAfter('=')
        val language = LanSharePage.Language.fromTag(languageTag ?: cookieLanguage)
        fun text(english: String, chinese: String) = language.text(english, chinese)
        val query = originalQuery.split('&').filterNot {
            languageTag != null && it == "lang=$languageTag"
        }.joinToString("&")
        val languageCookie = languageTag?.let {
            "Set-Cookie: fileweave_language=$it; Path=/; Max-Age=31536000; SameSite=Strict; HttpOnly\r\n"
        }.orEmpty()
        fun page(title: String, body: String) = LanSharePage.render(title, body, language, target)
        fun title(entry: Entry) = when (entry.rootTitle) {
            RootTitle.SHARED_FILES -> text("Shared files", "共享文件")
            RootTitle.PHONE_STORAGE -> text("Phone storage", "手机存储")
            null -> entry.name
        }
        fun reply(code: Int, body: String, extra: String = "") {
            val bytes = body.toByteArray(Charsets.UTF_8)
            head(output, code, "text/html; charset=utf-8", bytes.size.toLong(), extra + languageCookie)
            if (request.firstOrNull() != "HEAD" && (active || code == 410)) output.write(bytes)
        }
        if (!active) { reply(410, text("Share ended", "分享已结束")); return }
        if (request.size != 3 || request[0] !in listOf("GET", "HEAD", "POST")) {
            reply(405, text("Read-only share", "只读分享")); return
        }
        val prefix = "/$token/"
        if (raw == "/" && manualAccess != null) {
            fun login(message: String = "") = page(text("Enter access code", "输入访问码"),
                "<p>${text("Find the six-digit access code on the phone sharing screen.", "在手机分享页查看六位访问码。")}</p>$message" +
                "<form method=post action=\"/\"><label for=code>${text("Access code", "访问码")}</label><input id=code name=code type=text inputmode=numeric autocomplete=one-time-code pattern=\"[0-9]{6}\" maxlength=6 required><button type=submit>${text("Open share", "打开分享")}</button></form>")
            // HEAD never redeems a code or exposes the capability to link probes.
            if (request[0] == "HEAD" || (request[0] == "GET" && query.isEmpty())) { reply(200, login()); return }
            val length = headers["content-length"]?.toIntOrNull()
            val form = if (request[0] == "GET") query else if (length != null && length in 1..64 && headers["transfer-encoding"] == null &&
                headers["content-type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) == "application/x-www-form-urlencoded") {
                val bytes = ByteArray(length)
                var read = 0
                while (read < bytes.size) {
                    val count = input.read(bytes, read, bytes.size - read)
                    if (count < 0) break
                    read += count
                }
                if (read == length) bytes.toString(Charsets.UTF_8) else ""
            } else ""
            val candidate = Regex("code=([0-9]{6})").matchEntire(form)?.groupValues?.get(1)
            when (manualAccess.authorize(candidate)) {
                LanShareManualAccess.Result.GRANTED -> {
                    if (!active) { reply(410, text("Share ended", "分享已结束")); return }
                    head(output, 303, "text/plain", 0, "Location: $prefix\r\n$languageCookie")
                }
                LanShareManualAccess.Result.INVALID -> reply(403, login("<p>${text("Invalid access code.", "访问码不正确。")}</p>"))
                LanShareManualAccess.Result.THROTTLED -> reply(429, login("<p>${text("Too many attempts. Try again in one minute.", "尝试次数过多，请一分钟后重试。")}</p>"), "Retry-After: 60\r\n")
            }
            return
        }
        if (request[0] == "POST") { reply(405, text("Read-only share", "只读分享")); return }
        if (!raw.startsWith(prefix)) { reply(404, text("Not found", "未找到")); return }
        val parts = try {
            raw.removePrefix(prefix).trimEnd('/').takeIf { it.isNotEmpty() }?.split('/')?.map {
                URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")
            }.orEmpty()
        } catch (_: Exception) { reply(400, text("Invalid path", "无效路径")); return }
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.any { c -> c == '/' || c == '\\' || c.code < 32 || c.code == 127 } }) {
            reply(400, text("Invalid path", "无效路径")); return
        }
        if (query == "clipboard=1" && parts.isEmpty()) {
            val snapshot = clipboardText
            val body = if (snapshot == null) "<p>${text("No clipboard text shared yet.", "尚未分享剪贴板。")}</p><p>${text("Choose Clipboard only in the phone sharing scope, or attach text from the More menu.", "请在手机的分享范围中选择仅剪贴板，或在更多菜单中附加文本。")}</p>" else
                "<p>${text("Select the text below to copy. This is a snapshot explicitly shared from the phone.", "选择下方文本即可复制。这是手机主动分享的快照。")}</p><textarea readonly aria-label=\"${text("Shared clipboard text", "已分享的剪贴板文本")}\">${escape(snapshot)}</textarea>"
            reply(200, page(text("Clipboard", "剪贴板"), "<nav><a href=\"$prefix\">← ${text("Shared files", "共享文件")}</a><a href=\"$prefix?clipboard=1\">${text("Refresh", "刷新")}</a></nav>$body"))
            return
        }
        val entry = try { backend.stat(parts) } catch (_: Exception) { null }
        if (entry == null) { reply(404, text("Not found", "未找到")); return }
        val encodedPath = prefix + parts.joinToString("/") { encode(it) }
        if (entry.directory) {
            val entries = try { backend.list(parts) } catch (_: Exception) { reply(403, text("Unavailable", "暂不可用")); return }
            val onlyText = clipboardText
            if (parts.isEmpty() && entries.isEmpty() && onlyText != null) {
                reply(200, page(text("Clipboard", "剪贴板"), "<p>${text("Select the text below to copy.", "选择下方文本即可复制。")}</p><textarea readonly aria-label=\"${text("Shared clipboard text", "已分享的剪贴板文本")}\">${escape(onlyText)}</textarea><nav><a href=\"$prefix\">${text("Refresh", "刷新")}</a></nav>"))
                return
            }
            val base = encodedPath.let { if (it.endsWith('/')) it else "$it/" }
            val parent = if (parts.isEmpty()) "" else "<a href=\"${prefix + parts.dropLast(1).joinToString("/") { encode(it) }}${if (parts.size > 1) "/" else ""}\">← ${text("Parent folder", "上一级")}</a>"
            val rows = entries.sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase(Locale.ROOT) }).joinToString("") {
                val href = base + encode(it.name) + if (it.directory) "/" else ""
                val preview = !it.directory && imageType(it.name) != null
                val mainHref = href + if (preview) "?preview=1" else ""
                "<div class=entry><a class=filename href=\"$mainHref\"${if (it.directory || preview) "" else " download"}><span>${escape(it.name)}</span><small>${if (it.directory) text("Folder", "文件夹") else size(it.size) + if (preview) text(" · Image", " · 图片") else ""}</small></a>${if (it.directory) "" else "<a class=download href=\"$href\" download>${text("Download", "下载")}</a>"}</div>"
            }
            val clipboard = "<a href=\"$prefix?clipboard=1\">${if (clipboardText == null) text("Clipboard", "剪贴板") else text("Clipboard · Shared", "剪贴板 · 已分享")}</a>"
            reply(200, page(title(entry), "<nav>$parent$clipboard</nav><section>${rows.ifEmpty { "<p class=empty>${text("Empty folder", "空文件夹")}</p>" }}</section>"))
            return
        }
        val imageType = imageType(entry.name)
        if (query == "preview=1") {
            if (imageType == null) { reply(415, text("Image preview unavailable", "无法预览此图片")); return }
            val parent = prefix + parts.dropLast(1).joinToString("/") { encode(it) } + if (parts.size > 1) "/" else ""
            reply(200, page(title(entry), "<nav><a href=\"$parent\">← ${text("Back", "返回")}</a><a href=\"$encodedPath\" download>${text("Download original", "下载原图")}</a></nav><section class=preview><img src=\"$encodedPath?inline=1\" alt=\"${escape(entry.name)}\"></section>"))
            return
        }
        if (query == "inline=1" && imageType == null) { reply(415, text("Image preview unavailable", "无法预览此图片")); return }
        val length = entry.size
        if (length < 0) { reply(403, text("Unknown file size", "文件大小未知")); return }
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
        val count = (end - start + 1).coerceAtLeast(0)
        val inline = query == "inline=1" && imageType != null
        val type = if (inline) imageType!! else "application/octet-stream"
        val extra = "Accept-Ranges: bytes\r\nContent-Disposition: ${if (inline) "inline" else "attachment"}; filename*=UTF-8''${encode(entry.name)}\r\n" +
            if (range != null) "Content-Range: bytes $start-$end/$length\r\n" else ""
        if (request[0] == "HEAD") { head(output, if (range == null) 200 else 206, type, count, extra); return }
        val stream = try { backend.open(parts, start) } catch (_: Exception) { reply(403, text("Unavailable", "暂不可用")); return }
        stream.use {
            if (!active) { reply(410, text("Share ended", "分享已结束")); return }
            head(output, if (range == null) 200 else 206, type, count, extra)
            val buffer = ByteArray(64 * 1024)
            var left = count
            while (left > 0 && active && !Thread.currentThread().isInterrupted) {
                val read = it.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (read < 0) break
                if (!active || Thread.currentThread().isInterrupted) break
                output.write(buffer, 0, read)
                left -= read
            }
        }
    }

    private fun head(out: OutputStream, code: Int, type: String, length: Long, extra: String = "") {
        val reason = mapOf(200 to "OK", 303 to "See Other", 429 to "Too Many Requests", 206 to "Partial Content", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 410 to "Gone", 415 to "Unsupported Media Type", 416 to "Range Not Satisfiable")[code]
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: $length\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n$extra\r\n").toByteArray())
    }
    // SVG/HTML remain downloads: untrusted active documents are never served inline.
    private fun imageType(name: String): String? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        else -> null
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun size(value: Long) = when { value >= 1048576 -> "%.1f MB".format(Locale.ROOT, value / 1048576.0); value >= 1024 -> "%.1f KB".format(Locale.ROOT, value / 1024.0); else -> "$value B" }
}
