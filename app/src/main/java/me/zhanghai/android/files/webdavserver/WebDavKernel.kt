package me.zhanghai.android.files.webdavserver

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 纯 Kotlin 的最小 WebDAV 协议核（RFC 4918 的 Class 1 子集），不依赖任何 Android 或
 * 第三方 API，可在 JVM 上完整测试。
 *
 * 为什么自研而不是引库：调研结论是 Android 可嵌入的成熟 WebDAV 服务端库不存在
 * （nanohttpd 无 WebDAV 模块；Milton 过重且双授权；webdav-servlet 需要 servlet 容器；
 * Sardine 只有客户端），而仓库里 folderbridge 已经验证过“手写最小 HTTP kernel + 假后端
 * JVM 测试”这条路线，本文件沿用同一风格（请求行/Header 限额、路径安全、单 Range、
 * 有界线程模型见 WebDavServer）。
 *
 * 支持的方法：OPTIONS / PROPFIND（Depth 0、1）/ GET / HEAD（含单 Range）/ PUT / DELETE /
 * MKCOL / MOVE / COPY。LOCK/UNLOCK 不支持（返回 405，DAV 头只声明 Class 1），只读挂载与
 * 上传下载场景不需要写锁；macOS Finder、Windows 资源管理器与 Sardine 系客户端均可在此
 * 子集上完成浏览、下载、上传、改名、删除。
 *
 * 认证是 HTTP Basic（RFC 7617）。与 FTP 服务器同等的威胁模型：仅在局域网明文传输，凭据
 * 与文件内容和 FTP 一样暴露于同一段 Wi-Fi；不做 HTTPS（证书分发超出本功能范围），设置页
 * 文案如实说明。匿名模式下不校验凭据。
 */
@OptIn(ExperimentalEncodingApi::class)
class WebDavKernel(
    private val backend: WebDavBackend,
    private val username: String?,
    private val password: String?,
    private val writable: Boolean
) {
    private val usernameBytes = username?.toByteArray(StandardCharsets.UTF_8)
    private val passwordBytes = password?.toByteArray(StandardCharsets.UTF_8)

    /**
     * 处理一条 keep-alive 连接：循环读取请求直到对端关闭、解析失败或需要断开。
     * 任何异常直接上抛，由 WebDavServer 关闭 socket；已写出半截响应时没有补救手段。
     */
    fun handleConnection(input: InputStream, output: OutputStream) {
        while (true) {
            val request = parseRequest(input) ?: return
            if (!handleRequest(request, output)) {
                return
            }
        }
    }

    internal class Request(
        val method: String,
        val path: List<String>,
        val version: String,
        val headers: Map<String, String>,
        val body: InputStream,
        val contentLength: Long
    ) {
        fun header(name: String): String? = headers[name]
    }

    /** 解析一条请求。返回 null 表示对端正常关闭；畸形请求抛 IOException 交给上层断连。 */
    private fun parseRequest(input: InputStream): Request? {
        var requestLine = readLine(input) ?: return null
        // RFC 9112 允许请求行前有少量空行，容忍之，但以头部预算为限。
        while (requestLine.isEmpty()) {
            requestLine = readLine(input) ?: return null
        }
        val headerMap = HashMap<String, String>()
        var headerBytes = requestLine.length
        while (true) {
            val line = readLine(input) ?: throw IOException("Connection closed in headers")
            if (line.isEmpty()) {
                break
            }
            headerBytes += line.length
            // 头部（含请求行）的总字节上限，与 folderbridge 同量级，防恶意慢速超大头。
            if (headerBytes > MAX_HEADER_BYTES) {
                throw IOException("Header section too large")
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                headerMap[line.substring(0, colon).trim().lowercase(Locale.ROOT)] =
                    line.substring(colon + 1).trim()
            }
        }
        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            throw IOException("Malformed request line")
        }
        val contentLength = headerMap["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength < 0) {
            throw IOException("Invalid content length")
        }
        // 含 ".." 等穿越段的目标直接断连：合法客户端不会发出，无需礼貌应答。
        val path = decodeTarget(parts[1]) ?: throw IOException("Malformed request target")
        return Request(parts[0], path, parts[2], headerMap, input, contentLength)
    }

    /**
     * 处理单个请求并写回响应。返回连接是否可继续复用。
     *
     * 响应前必须先决定请求体怎么办：要么已消费（PUT 直接流走），要么小到被丢弃，
     * 否则响应后必须关闭连接，避免下一个请求把残留字节当成请求行。
     */
    internal fun handleRequest(request: Request, output: OutputStream): Boolean {
        // OPTIONS 不要求认证：挂载协商阶段客户端往往还没有凭据（macOS Finder 先发
        // OPTIONS，再带凭据 PROPFIND）。
        if (request.method != "OPTIONS" && !isAuthorized(request)) {
            return respond(
                output, 401, request,
                listOf("WWW-Authenticate" to "Basic realm=\"FileWeave\", charset=\"UTF-8\""),
                plainBody("Unauthorized"), keepAlive = drainBody(request)
            )
        }
        return when (request.method) {
            "OPTIONS" -> doOptions(request, output)
            "PROPFIND" -> doPropfind(request, output)
            "GET" -> doGet(request, output, sendBody = true)
            "HEAD" -> doGet(request, output, sendBody = false)
            "PUT" -> doPut(request, output)
            "DELETE" -> doDelete(request, output)
            "MKCOL" -> doMkcol(request, output)
            "MOVE" -> doMoveCopy(request, output, isCopy = false)
            "COPY" -> doMoveCopy(request, output, isCopy = true)
            // 认识但不支持的方法（写属性、锁、表单提交等）统一 405 并给出 Allow。
            "POST", "TRACE", "PROPPATCH", "LOCK", "UNLOCK" -> respond(
                output, 405, request, listOf("Allow" to ALLOWED_METHODS),
                plainBody("Method not allowed"), keepAlive = drainBody(request)
            )
            else -> respond(
                output, 501, request, listOf("Allow" to ALLOWED_METHODS),
                plainBody("Not implemented"), keepAlive = drainBody(request)
            )
        }
    }

    // ------------------------------------------------------------- 方法实现

    private fun doOptions(request: Request, output: OutputStream): Boolean =
        respond(
            output, 200, request,
            listOf(
                // 只声明 Class 1（无锁）。谎报 Class 2 会让 Finder 试着 LOCK 然后失败。
                "DAV" to "1",
                "Allow" to ALLOWED_METHODS,
                // Windows 客户端识别 WebDAV 作者信息的非标准头。
                "MS-Author-Via" to "DAV"
            ),
            null, keepAlive = drainBody(request)
        )

    private fun doPropfind(request: Request, output: OutputStream): Boolean {
        val drainOk = drainBody(request)
        val depth = request.header("depth")?.trim()?.lowercase(Locale.ROOT)
        if (depth != "0" && depth != "1") {
            // 无限深度枚举等于让客户端一次拉走整棵树，是 DoS 向量；RFC 允许拒绝。
            return respond(
                output, 403, request, emptyList(), plainBody("Depth infinity not supported"),
                keepAlive = drainOk
            )
        }
        val resource = try {
            backend.stat(request.path)
        } catch (error: IOException) {
            return respondError(output, request, error, drainOk)
        } ?: return respond(
            output, 404, request, emptyList(), plainBody("Not found"), keepAlive = drainOk
        )
        val entries = ArrayList<PropfindEntry>()
        entries += PropfindEntry(request.path, resource)
        if (depth == "1" && resource.isDirectory) {
            val children = try {
                backend.list(request.path)
            } catch (error: IOException) {
                return respondError(output, request, error, drainOk)
            }
            if (children != null) {
                // 按名字排序，输出稳定，便于测试与客户端对照。
                children.sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { child ->
                    entries += PropfindEntry(request.path + child.name, child)
                }
            }
        }
        val xml = buildMultistatus(entries)
        return respond(
            output, 207, request,
            listOf("Content-Type" to "application/xml; charset=utf-8"),
            xml.toByteArray(StandardCharsets.UTF_8), keepAlive = drainOk
        )
    }

    private fun doGet(request: Request, output: OutputStream, sendBody: Boolean): Boolean {
        val drainOk = drainBody(request)
        if (request.path.isEmpty()) {
            // 根是集合；GET 集合没有意义，也不提供 HTML 目录列表（保持最小实现）。
            return respond(
                output, 404, request, emptyList(), plainBody("Not found"), keepAlive = drainOk
            )
        }
        val resource = try {
            backend.stat(request.path)
        } catch (error: IOException) {
            return respondError(output, request, error, drainOk)
        } ?: return respond(
            output, 404, request, emptyList(), plainBody("Not found"), keepAlive = drainOk
        )
        if (resource.isDirectory) {
            return respond(
                output, 404, request, emptyList(), plainBody("Not found"), keepAlive = drainOk
            )
        }
        // 协议约定后端对文件 stat 必须给出尺寸；没有尺寸就无法写定长响应与 Range。
        val size = resource.size
        if (size < 0) {
            return respond(
                output, 500, request, emptyList(), plainBody("Unknown file size"),
                keepAlive = drainOk
            )
        }
        val range = resolveRange(request, size)
        if (range is RangeResult.Unsatisfiable) {
            // 416 响应必须带 Content-Range: bytes */size，否则部分客户端死循环重试。
            return respond(
                output, 416, request,
                listOf("Content-Range" to "bytes */$size", "Accept-Ranges" to "bytes"),
                plainBody("Range not satisfiable"), keepAlive = drainOk
            )
        }
        val satisfiable = range as? RangeResult.Satisfiable
        val start = satisfiable?.start ?: 0L
        return try {
            backend.openRead(request.path, start).use { opened ->
                streamResponse(
                    output, request, resource, size, satisfiable, opened.input, sendBody
                )
            }
        } catch (error: IOException) {
            respondError(output, request, error, keepAlive = false)
        }
    }

    private sealed class RangeResult {
        object None : RangeResult()
        class Satisfiable(val start: Long, val end: Long) : RangeResult()
        object Unsatisfiable : RangeResult()
    }

    /** 单 Range 解析；多 Range（含逗号）按 RFC 9110 允许的方式忽略返回 200 全量。 */
    private fun resolveRange(request: Request, size: Long): RangeResult {
        val rangeHeader = request.header("range") ?: return RangeResult.None
        if (rangeHeader.contains(',')) {
            return RangeResult.None
        }
        val match = RANGE_REGEX.matchEntire(rangeHeader.trim()) ?: return RangeResult.None
        val startText = match.groupValues[1]
        val endText = match.groupValues[2]
        var start: Long
        var end: Long
        when {
            // "500-" 开区间。
            startText.isNotEmpty() -> {
                start = startText.toLong()
                end = if (endText.isNotEmpty()) minOf(endText.toLong(), size - 1) else size - 1
            }
            // "-500" 后缀区间：最后 N 字节。
            endText.isNotEmpty() -> {
                start = maxOf(0L, size - endText.toLong())
                end = size - 1
            }
            // 仅 "bytes=-"：非法，忽略 Range，按 200 全量返回。
            else -> return RangeResult.None
        }
        if (start > end || start >= size) {
            return RangeResult.Unsatisfiable
        }
        return RangeResult.Satisfiable(start, end)
    }

    /** GET/HEAD 的定长响应写出；按偏移打开的流直接顺序读，不再读跳过。 */
    private fun streamResponse(
        output: OutputStream,
        request: Request,
        resource: WebDavResource,
        size: Long,
        range: RangeResult.Satisfiable?,
        input: InputStream,
        sendBody: Boolean
    ): Boolean {
        val status = if (range != null) 206 else 200
        val length = (range?.let { it.end - it.start + 1 }) ?: size
        val headers = ArrayList<Pair<String, String>>()
        headers += "Content-Type" to contentTypeFor(resource.name)
        headers += "Accept-Ranges" to "bytes"
        headers += "ETag" to etagFor(size, resource.lastModifiedMillis)
        if (resource.lastModifiedMillis > 0) {
            headers += "Last-Modified" to formatHttpDate(resource.lastModifiedMillis)
        }
        if (range != null) {
            headers += "Content-Range" to "bytes ${range.start}-${range.end}/$size"
        }
        val keepAlive = wantsKeepAlive(request)
        writeHead(output, status, headers, length, keepAlive)
        if (sendBody && length > 0) {
            var remaining = length
            val buffer = ByteArray(FILE_BUFFER_BYTES)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (count < 0) {
                    // 文件在响应头之后被缩短：只能断开连接，绝不能静默补零。
                    throw IOException("EOF before content length")
                }
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        output.flush()
        return keepAlive
    }

    private fun doPut(request: Request, output: OutputStream): Boolean {
        if (!writable) {
            return respond(
                output, 403, request, emptyList(), plainBody("Read only"), keepAlive = false
            )
        }
        if (request.path.isEmpty()) {
            return respond(
                output, 405, request, emptyList(), plainBody("Cannot PUT the root"),
                keepAlive = drainBody(request)
            )
        }
        if (request.header("transfer-encoding") != null) {
            // chunked 上传需要增量解析，最小实现不做；curl 与桌面客户端 PUT 都带长度。
            return respond(
                output, 501, request, emptyList(), plainBody("Chunked body not supported"),
                keepAlive = false
            )
        }
        val existed: Boolean
        try {
            val existing = backend.stat(request.path)
            if (existing != null && existing.isDirectory) {
                return respond(
                    output, 405, request, emptyList(), plainBody("Is a directory"),
                    keepAlive = false
                )
            }
            existed = existing != null
        } catch (error: IOException) {
            return respondError(output, request, error, keepAlive = false)
        }
        // 大文件 PUT 时 curl 会等 100-continue；不发的话它静等一秒才传体。
        sendContinueIfNeeded(request, output)
        return try {
            backend.openWrite(request.path).use { fileOutput ->
                copyExactly(request.body, fileOutput, request.contentLength)
            }
            respond(output, if (existed) 204 else 201, request, emptyList(), null, keepAlive = true)
        } catch (error: IOException) {
            // 409（父目录不存在）与真正的 IO 错误要区分开。
            val status = if (error.isNoSuchFile()) 409 else 500
            respond(
                output, status, request, emptyList(),
                plainBody(error.message ?: "PUT failed"), keepAlive = false
            )
        }
    }

    private fun doDelete(request: Request, output: OutputStream): Boolean {
        val drainOk = drainBody(request)
        if (!writable) {
            return respond(
                output, 403, request, emptyList(), plainBody("Read only"), keepAlive = false
            )
        }
        if (request.path.isEmpty()) {
            return respond(
                output, 403, request, emptyList(), plainBody("Cannot delete the root"),
                keepAlive = drainOk
            )
        }
        return try {
            if (backend.stat(request.path) == null) {
                return respond(
                    output, 404, request, emptyList(), plainBody("Not found"), keepAlive = drainOk
                )
            }
            backend.delete(request.path)
            respond(output, 204, request, emptyList(), null, keepAlive = drainOk)
        } catch (error: IOException) {
            respondError(output, request, error, drainOk)
        }
    }

    private fun doMkcol(request: Request, output: OutputStream): Boolean {
        if (!writable) {
            return respond(
                output, 403, request, emptyList(), plainBody("Read only"), keepAlive = false
            )
        }
        if (request.path.isEmpty()) {
            return respond(
                output, 405, request, emptyList(), plainBody("Already exists"),
                keepAlive = drainBody(request)
            )
        }
        if (request.contentLength > 0) {
            // RFC 4918 9.3.1：不支持的 MKCOL 请求体必须 415；没有客户端会真的带体。
            return respond(
                output, 415, request, emptyList(), plainBody("MKCOL body not supported"),
                keepAlive = false
            )
        }
        return try {
            backend.createDirectory(request.path)
            respond(output, 201, request, emptyList(), null, keepAlive = true)
        } catch (error: IOException) {
            when {
                error is WebDavConflictException || error.isAlreadyExists() -> respond(
                    output, 405, request, emptyList(), plainBody("Already exists"),
                    keepAlive = true
                )
                error.isNoSuchFile() -> respond(
                    output, 409, request, emptyList(), plainBody("Parent does not exist"),
                    keepAlive = true
                )
                else -> respondError(output, request, error, keepAlive = true)
            }
        }
    }

    private fun doMoveCopy(request: Request, output: OutputStream, isCopy: Boolean): Boolean {
        if (!writable) {
            return respond(
                output, 403, request, emptyList(), plainBody("Read only"), keepAlive = false
            )
        }
        val drainOk = drainBody(request)
        val destinationHeader = request.header("destination")
        if (destinationHeader == null) {
            return respond(
                output, 400, request, emptyList(), plainBody("Missing Destination"),
                keepAlive = drainOk
            )
        }
        val destination = decodeTarget(destinationPathOf(destinationHeader))
            ?: return respond(
                output, 400, request, emptyList(), plainBody("Malformed Destination"),
                keepAlive = drainOk
            )
        // 源与目标相同按 RFC 4918 9.9.4 拒绝；目标在源之内会形成循环，一并词法拦下。
        if (destination == request.path ||
            (destination.size > request.path.size &&
                destination.subList(0, request.path.size) == request.path)
        ) {
            return respond(
                output, 403, request, emptyList(), plainBody("Invalid destination"),
                keepAlive = drainOk
            )
        }
        val overwrite = request.header("overwrite")?.trim()?.uppercase(Locale.ROOT) != "F"
        return try {
            val source = backend.stat(request.path) ?: return respond(
                output, 404, request, emptyList(), plainBody("Not found"), keepAlive = drainOk
            )
            val existingDestination = backend.stat(destination)
            if (existingDestination != null && !overwrite) {
                return respond(
                    output, 412, request, emptyList(), plainBody("Destination exists"),
                    keepAlive = drainOk
                )
            }
            val overwrote = existingDestination != null
            // 注意：先删目标再移动/复制，若随后操作失败，旧目标已丢失。多数最小实现
            // 都是这个语义；客户端拿到的错误状态足以发现失败。
            if (overwrote) {
                backend.delete(destination)
            }
            if (isCopy) {
                copyRecursively(
                    request.path, destination, source,
                    depthZero = request.header("depth")?.trim() == "0"
                )
            } else {
                backend.move(request.path, destination)
            }
            respond(
                output, if (overwrote) 204 else 201, request, emptyList(), null, keepAlive = drainOk
            )
        } catch (error: IOException) {
            respondError(output, request, error, drainOk)
        }
    }

    /** 目录复制通过后端原语递归完成；文件复制交给后端（可能有一层更快的实现）。 */
    private fun copyRecursively(
        from: List<String>,
        to: List<String>,
        resource: WebDavResource,
        depthZero: Boolean
    ) {
        if (!resource.isDirectory) {
            backend.copyFile(from, to)
            return
        }
        backend.createDirectory(to)
        if (depthZero) {
            // RFC 4918 9.8.3：Depth 0 的集合复制只复制集合本身（空目录）。
            return
        }
        val children = backend.list(from) ?: return
        for (child in children) {
            val childName = child.name
            copyRecursively(from + childName, to + childName, child, depthZero = false)
        }
    }

    // ------------------------------------------------------------- 认证与公共

    /**
     * Basic 认证校验。匿名模式（username == null）直接放行——与 FTP 服务器的匿名登录同一
     * 语义，只是这里默认关闭匿名，需要用户显式打开。
     */
    private fun isAuthorized(request: Request): Boolean {
        val expectedUser = usernameBytes ?: return true
        val expectedPass = passwordBytes ?: ByteArray(0)
        val authorization = request.header("authorization") ?: return false
        val prefix = "Basic "
        if (authorization.length <= prefix.length ||
            !authorization.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)
        ) {
            return false
        }
        val decoded = try {
            Base64.decode(authorization.substring(prefix.length).trim())
        } catch (error: IllegalArgumentException) {
            return false
        }
        val separator = decoded.indexOfFirst { it == ':'.code.toByte() }
        if (separator < 0) {
            return false
        }
        val user = decoded.copyOfRange(0, separator)
        val pass = decoded.copyOfRange(separator + 1, decoded.size)
        // 常数时间比较，避免计时侧信道逐位猜口令（与 folderbridge 的 token 比较同款）。
        return MessageDigest.isEqual(user, expectedUser) &&
            MessageDigest.isEqual(pass, expectedPass)
    }

    /**
     * 丢弃尚未消费的请求体。小于限额则读完并返回 true（连接可复用）；超过限额返回
     * false（响应后关连接，不读，避免被恶意大 body 拖住线程）。
     */
    private fun drainBody(request: Request): Boolean {
        if (request.contentLength <= 0) {
            return true
        }
        if (request.contentLength > MAX_DRAIN_BYTES) {
            return false
        }
        return try {
            copyExactly(request.body, NullOutputStream, request.contentLength)
            true
        } catch (error: IOException) {
            false
        }
    }

    private fun sendContinueIfNeeded(request: Request, output: OutputStream) {
        val expect = request.header("expect") ?: return
        if (expect.contains("100-continue", ignoreCase = true)) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
        }
    }

    // ------------------------------------------------------------- 序列化

    private class PropfindEntry(
        val path: List<String>,
        val resource: WebDavResource
    )

    /** 生成 207 multistatus XML。href 必须是 UTF-8 百分号编码，集合 href 以 / 结尾。 */
    private fun buildMultistatus(entries: List<PropfindEntry>): String {
        val builder = StringBuilder()
        builder.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        builder.append("<D:multistatus xmlns:D=\"DAV:\">\n")
        for (entry in entries) {
            builder.append("<D:response>\n<D:href>")
                .append(encodeHref(entry.path, entry.resource.isDirectory))
                .append("</D:href>\n<D:propstat><D:prop>\n")
            builder.append("<D:displayname>")
                .append(xmlEscape(entry.path.lastOrNull() ?: "/"))
                .append("</D:displayname>\n")
            if (entry.resource.isDirectory) {
                builder.append("<D:resourcetype><D:collection/></D:resourcetype>\n")
            } else {
                builder.append("<D:resourcetype/>\n")
                if (entry.resource.size >= 0) {
                    builder.append("<D:getcontentlength>")
                        .append(entry.resource.size)
                        .append("</D:getcontentlength>\n")
                }
                builder.append("<D:getcontenttype>")
                    .append(contentTypeFor(entry.resource.name))
                    .append("</D:getcontenttype>\n")
                builder.append("<D:getetag>")
                    .append(xmlEscape(etagFor(entry.resource.size,
                        entry.resource.lastModifiedMillis)))
                    .append("</D:getetag>\n")
            }
            if (entry.resource.lastModifiedMillis > 0) {
                builder.append("<D:getlastmodified>")
                    .append(formatHttpDate(entry.resource.lastModifiedMillis))
                    .append("</D:getlastmodified>\n")
            }
            builder.append("</D:prop>\n<D:status>HTTP/1.1 200 OK</D:status>\n")
                .append("</D:propstat>\n</D:response>\n")
        }
        builder.append("</D:multistatus>\n")
        return builder.toString()
    }

    private fun respondError(
        output: OutputStream,
        request: Request,
        error: IOException,
        keepAlive: Boolean
    ): Boolean {
        val message = error.message ?: error.javaClass.simpleName
        return respond(output, 500, request, emptyList(), plainBody(message), keepAlive)
    }

    private fun plainBody(message: String): ByteArray =
        message.toByteArray(StandardCharsets.UTF_8)

    private fun respond(
        output: OutputStream,
        status: Int,
        request: Request,
        headers: List<Pair<String, String>>,
        body: ByteArray?,
        keepAlive: Boolean
    ): Boolean {
        val connectionKeepAlive = keepAlive && wantsKeepAlive(request)
        writeHead(output, status, headers, body?.size?.toLong() ?: 0L, connectionKeepAlive)
        if (body != null && body.isNotEmpty() && request.method != "HEAD") {
            output.write(body)
        }
        output.flush()
        return connectionKeepAlive
    }

    private fun writeHead(
        output: OutputStream,
        status: Int,
        headers: List<Pair<String, String>>,
        contentLength: Long,
        keepAlive: Boolean
    ) {
        val builder = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
        for ((name, value) in headers) {
            builder.append(name).append(": ").append(value).append("\r\n")
        }
        // RFC 9110：204 不得携带 Content-Length（1xx/304 同理，此处只可能产生 204）。
        if (status != 204) {
            builder.append("Content-Length: ").append(contentLength).append("\r\n")
        }
        builder.append("Cache-Control: no-store\r\n")
        builder.append("X-Content-Type-Options: nosniff\r\n")
        builder.append("Connection: ").append(if (keepAlive) "keep-alive" else "close")
            .append("\r\n\r\n")
        output.write(builder.toString().toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun wantsKeepAlive(request: Request): Boolean {
        val connection = request.header("connection")?.lowercase(Locale.ROOT)
        return when {
            connection?.contains("close") == true -> false
            request.version.equals("HTTP/1.0", ignoreCase = true) ->
                connection?.contains("keep-alive") == true
            else -> true
        }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(FILE_BUFFER_BYTES)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (count < 0) {
                throw IOException("Client closed before body was complete")
            }
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    companion object {
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_DRAIN_BYTES = 64 * 1024
        const val FILE_BUFFER_BYTES = 256 * 1024
        const val ALLOWED_METHODS = "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, MKCOL, MOVE, COPY"

        private val RANGE_REGEX = Regex("bytes=([0-9]*)-([0-9]*)")

        private val MIME_TYPES = mapOf(
            "aac" to "audio/aac", "apk" to "application/vnd.android.package-archive",
            "avi" to "video/x-msvideo", "bmp" to "image/bmp", "css" to "text/css",
            "csv" to "text/csv", "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "flac" to "audio/flac", "gif" to "image/gif", "gz" to "application/gzip",
            "htm" to "text/html", "html" to "text/html", "ico" to "image/x-icon",
            "jar" to "application/java-archive", "jpeg" to "image/jpeg", "jpg" to "image/jpeg",
            "js" to "text/javascript", "json" to "application/json", "log" to "text/plain",
            "m3u" to "audio/x-mpegurl", "m4a" to "audio/mp4", "m4v" to "video/mp4",
            "md" to "text/markdown", "mkv" to "video/x-matroska", "mov" to "video/quicktime",
            "mp3" to "audio/mpeg", "mp4" to "video/mp4", "mpeg" to "video/mpeg",
            "mpg" to "video/mpeg", "ogg" to "audio/ogg", "opus" to "audio/opus",
            "pdf" to "application/pdf", "png" to "image/png",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "rar" to "application/vnd.rar", "svg" to "image/svg+xml",
            "swf" to "application/x-shockwave-flash", "tar" to "application/x-tar",
            "tif" to "image/tiff", "tiff" to "image/tiff", "txt" to "text/plain",
            "wav" to "audio/wav", "webm" to "video/webm", "webp" to "image/webp",
            "wma" to "audio/x-ms-wma", "wmv" to "video/x-ms-wmv",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xml" to "application/xml", "zip" to "application/zip",
            "7z" to "application/x-7z-compressed"
        )

        /** 内置常见扩展名映射，避免依赖 Android 的 MimeTypeMap（协议核必须纯 JVM）。 */
        fun contentTypeFor(name: String): String {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return MIME_TYPES[extension] ?: "application/octet-stream"
        }

        /** 强 ETag：尺寸+修改时间，内容变化必然改变；不含需转义字符。 */
        fun etagFor(size: Long, lastModifiedMillis: Long): String = "\"$size-$lastModifiedMillis\""

        private val HTTP_DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
        }

        fun formatHttpDate(millis: Long): String =
            HTTP_DATE_FORMAT.get()!!.format(Date(millis))

        private fun reason(code: Int): String = when (code) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            206 -> "Partial Content"
            207 -> "Multi-Status"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            412 -> "Precondition Failed"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            416 -> "Range Not Satisfiable"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            else -> "Error"
        }
    }
}

/**
 * 以类名匹配 NoSuchFileException：应用运行时抛的是 java8.nio.file 反移植版的异常，而 JVM
 * 测试抛的是 java.nio.file 版，两者类名一致；不能直接引用类（API 23 上 java.nio.file
 * 不存在，直接引用会在老设备上 NoClassDefFoundError）。
 */
internal fun IOException.isNoSuchFile(): Boolean =
    javaClass.simpleName == "NoSuchFileException"

/** FileAlreadyExistsException 的类名匹配，理由同上。 */
internal fun IOException.isAlreadyExists(): Boolean =
    javaClass.simpleName == "FileAlreadyExistsException"

private object NullOutputStream : OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}

// ------------------------------------------------------------- 路径编解码

/**
 * 解析请求行/Destination 中的路径为段列表。返回 null 表示形态非法（不以 / 开头或含词法
 * 穿越段）。请求目标非法时直接断连；Destination 非法时由调用方回 400。
 *
 * 百分号解码按字节进行再整体 UTF-8，而不是像 folderbridge 的查询串解码那样按字符，
 * 因为路径里的非 ASCII 文件名必须以多字节序列解码才正确。
 */
internal fun decodeTarget(target: String): List<String>? {
    val path = if (target.startsWith("http://", ignoreCase = true) ||
        target.startsWith("https://", ignoreCase = true)
    ) {
        destinationPathOf(target)
    } else {
        target
    }.substringBefore('?')
    if (!path.startsWith("/")) {
        return null
    }
    val segments = path.split('/').filter { it.isNotEmpty() }.map { percentDecodeSegment(it) }
    return if (WebDavPathSafety.isSafePath(segments)) segments else null
}

/** Destination 头可能是绝对 URI（http://host:port/path）或绝对路径，这里取其路径部分。 */
internal fun destinationPathOf(destination: String): String {
    val schemeIndex = destination.indexOf("://")
    if (schemeIndex < 0) {
        return destination
    }
    val pathStart = destination.indexOf('/', schemeIndex + 3)
    return if (pathStart < 0) "/" else destination.substring(pathStart)
}

private fun percentDecodeSegment(segment: String): String {
    if (!segment.contains('%')) {
        return segment
    }
    val bytes = ArrayList<Byte>(segment.length)
    var index = 0
    while (index < segment.length) {
        val c = segment[index]
        if (c == '%' && index + 2 < segment.length) {
            val code = segment.substring(index + 1, index + 3).toIntOrNull(16)
            if (code != null) {
                bytes.add(code.toByte())
                index += 3
                continue
            }
        }
        // 头部按 ISO-8859-1 逐字节读入，ASCII 字符直接落字节。
        bytes.add(c.code.toByte())
        index++
    }
    return String(bytes.toByteArray(), StandardCharsets.UTF_8)
}

/** href 编码：UTF-8 字节逐个百分号编码，保留 '/' 与 URL 安全字符；集合补尾部 '/'。 */
internal fun encodeHref(path: List<String>, isDirectory: Boolean): String {
    val builder = StringBuilder()
    for (segment in path) {
        builder.append('/')
        for (byte in segment.toByteArray(StandardCharsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            if (c == '%'.code || c < 0x21 || c > 0x7e || c == '?'.code || c == '#'.code) {
                builder.append('%')
                    .append((c ushr 4).toString(16).uppercase(Locale.ROOT))
                    .append((c and 0xf).toString(16).uppercase(Locale.ROOT))
            } else {
                builder.append(c.toChar())
            }
        }
    }
    if (isDirectory) {
        builder.append('/')
    }
    return builder.toString()
}

private fun xmlEscape(value: String): String {
    val builder = StringBuilder(value.length)
    for (c in value) {
        when {
            c == '&' -> builder.append("&amp;")
            c == '<' -> builder.append("&lt;")
            c == '>' -> builder.append("&gt;")
            c == '"' -> builder.append("&quot;")
            c == '\'' -> builder.append("&#39;")
            c < ' ' -> builder.append("&#").append(c.code).append(';')
            else -> builder.append(c)
        }
    }
    return builder.toString()
}

/** 逐行读取头部，超限即抛错；返回的行不含 CRLF。*/
private fun readLine(input: InputStream): String? {
    val builder = StringBuilder(64)
    var count = 0
    while (count <= WebDavKernel.MAX_HEADER_BYTES) {
        val c = input.read()
        if (c < 0) {
            return if (builder.isEmpty()) null else builder.toString()
        }
        ++count
        if (c == '\n'.code) {
            break
        }
        if (c != '\r'.code) {
            builder.append(c.toChar())
        }
    }
    return builder.toString()
}
