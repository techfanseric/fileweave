package me.zhanghai.android.files.webdavserver

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.NoSuchFileException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * WebDAV 服务器的全栈 JVM 集成测试：真实 ServerSocket + 真实 TCP 连接。
 *
 * 按 AGENTS/任务要求验证 PROPFIND/GET/PUT 往返：标准 HTTP 方法（GET/PUT/HEAD/DELETE）用
 * HttpURLConnection 打本地端口；PROPFIND/MKCOL/MOVE/COPY 等 WebDAV 扩展方法受 JDK 白名单
 * 限制无法通过 HttpURLConnection.setRequestMethod 发送（会抛 ProtocolException），改用
 * 原始 Socket 发完整请求字节，同样是完整网络栈往返。未引入 OkHttp 测试依赖，避免为
 * 测试新增第三方库。
 */
class WebDavServerTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var backend: FileBackend
    private var server: WebDavServer? = null

    private val port: Int
        get() = server!!.localPort

    @Before
    fun setUp() {
        backend = FileBackend(temporaryFolder.newFolder("share"))
        server = WebDavServer(
            0, WebDavKernel(backend, USERNAME, PASSWORD, writable = true)
        ).also { it.start() }
    }

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private companion object {
        const val USERNAME = "user"
        const val PASSWORD = "pass"
        val AUTHORIZATION = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("$USERNAME:$PASSWORD".toByteArray(StandardCharsets.UTF_8))
    }

    // ------------------------------------------------------------- HTTP 客户端

    private class HttpResponse(
        val status: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray
    ) {
        fun header(name: String): String? = headers[name]?.firstOrNull()
        val bodyText: String get() = String(body, StandardCharsets.UTF_8)
    }

    private fun http(
        method: String,
        path: String,
        body: ByteArray? = null,
        authorize: Boolean = true
    ): HttpResponse {
        val connection = URL("http://127.0.0.1:$port$path").openConnection()
            as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            if (authorize) {
                connection.setRequestProperty("Authorization", AUTHORIZATION)
            }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val bodyBytes = (if (status in 200..299) connection.inputStream
                else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResponse(status, connection.headerFields, bodyBytes)
        } finally {
            connection.disconnect()
        }
    }

    /** 用原始 Socket 发一条请求并读到连接关闭（请求带 Connection: close）。 */
    private fun raw(
        method: String,
        target: String,
        headers: List<String> = emptyList(),
        body: ByteArray? = null,
        authorize: Boolean = true
    ): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val builder = StringBuilder("$method $target HTTP/1.1\r\n")
            if (authorize) {
                builder.append("Authorization: ").append(AUTHORIZATION).append("\r\n")
            }
            for (header in headers) {
                builder.append(header).append("\r\n")
            }
            if (body != null) {
                builder.append("Content-Length: ").append(body.size).append("\r\n")
            }
            builder.append("Connection: close\r\n\r\n")
            socket.getOutputStream().apply {
                write(builder.toString().toByteArray(StandardCharsets.ISO_8859_1))
                if (body != null) {
                    write(body)
                }
                flush()
            }
            val output = ByteArrayOutputStream()
            val input: InputStream = socket.getInputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = try {
                    input.read(buffer)
                } catch (error: IOException) {
                    break
                }
                if (count < 0) {
                    break
                }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.ISO_8859_1.name())
        }
    }

    // ------------------------------------------------------------- 用例

    @Test
    fun optionsOverSocket() {
        val response = raw("OPTIONS", "/", authorize = false)
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("DAV: 1"))
        assertTrue(response.contains("Allow: OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, MKCOL, MOVE, COPY"))
    }

    @Test
    fun unauthorizedOverSocket() {
        val response = raw("PROPFIND", "/", listOf("Depth: 1"), authorize = false)
        assertTrue(response.startsWith("HTTP/1.1 401"))
        assertTrue(response.contains("WWW-Authenticate: Basic"))
    }

    @Test
    fun putGetHeadDeleteRoundtrip() {
        val content = "hello webdav".toByteArray()
        assertEquals(201, http("PUT", "/hello%20world.txt", content).status)
        val stored = File(backend.root, "hello world.txt")
        assertTrue(stored.isFile)
        val get = http("GET", "/hello%20world.txt")
        assertEquals(200, get.status)
        assertTrue(get.body.contentEquals(content))
        assertEquals("text/plain", get.header("Content-Type"))
        val head = http("HEAD", "/hello%20world.txt")
        assertEquals(200, head.status)
        assertEquals(content.size.toString(), head.header("Content-Length"))
        assertEquals(0, head.body.size)
        assertEquals(204, http("DELETE", "/hello%20world.txt").status)
        assertFalse(stored.exists())
        assertEquals(404, http("GET", "/hello%20world.txt").status)
    }

    @Test
    fun rangeOverHttp() {
        http("PUT", "/ten.bin", "0123456789".toByteArray())
        val connection = URL("http://127.0.0.1:$port/ten.bin").openConnection()
            as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", AUTHORIZATION)
            connection.setRequestProperty("Range", "bytes=2-5")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            assertEquals(206, connection.responseCode)
            assertEquals("bytes 2-5/10", connection.getHeaderField("Content-Range"))
            assertEquals(
                "2345", connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            )
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun utf8FilenameRoundtrip() {
        val name = "中文 文档.txt"
        val encoded = "/%E4%B8%AD%E6%96%87%20%E6%96%87%E6%A1%A3.txt"
        assertEquals(201, http("PUT", encoded, "内容".toByteArray()).status)
        assertTrue(File(backend.root, name).isFile)
        val get = http("GET", encoded)
        assertEquals(200, get.status)
        assertEquals("内容", get.bodyText)
        val propfind = raw("PROPFIND", "/", listOf("Depth: 1"))
        assertTrue(propfind.startsWith("HTTP/1.1 207"))
        // href 必须是 UTF-8 百分号编码，而不是裸字符。
        assertTrue(
            propfind.contains("<D:href>/%E4%B8%AD%E6%96%87%20%E6%96%87%E6%A1%A3.txt</D:href>")
        )
    }

    @Test
    fun propfindDepth1OverSocket() {
        http("PUT", "/b.txt", "b".toByteArray())
        http("PUT", "/a.txt", "a".toByteArray())
        assertTrue(raw("MKCOL", "/dir").startsWith("HTTP/1.1 201"))
        val response = raw("PROPFIND", "/", listOf("Depth: 1"))
        assertTrue(response.startsWith("HTTP/1.1 207"))
        assertTrue(response.contains("application/xml"))
        // 排序后的 a.txt、b.txt、dir/；目录 href 以斜杠结尾。
        val aIndex = response.indexOf("<D:href>/a.txt</D:href>")
        val bIndex = response.indexOf("<D:href>/b.txt</D:href>")
        val dirIndex = response.indexOf("<D:href>/dir/</D:href>")
        assertTrue(aIndex > 0 && bIndex > aIndex && dirIndex > bIndex)
    }

    @Test
    fun mkcolPutMoveCopyOverSocket() {
        assertTrue(raw("MKCOL", "/dir").startsWith("HTTP/1.1 201"))
        assertEquals(201, http("PUT", "/dir/a.txt", "x".toByteArray()).status)
        val move = raw(
            "MOVE", "/dir/a.txt",
            listOf("Destination: http://127.0.0.1:$port/dir/b.txt")
        )
        assertTrue(move.startsWith("HTTP/1.1 201"))
        assertFalse(File(backend.root, "dir/a.txt").exists())
        assertEquals("x", File(backend.root, "dir/b.txt").readText())
        val copy = raw("COPY", "/dir/b.txt", listOf("Destination: /dir/c.txt"))
        assertTrue(copy.startsWith("HTTP/1.1 201"))
        assertEquals("x", File(backend.root, "dir/c.txt").readText())
        assertEquals("x", File(backend.root, "dir/b.txt").readText())
    }

    @Test
    fun deleteDirectoryRecursively() {
        assertTrue(raw("MKCOL", "/dir").startsWith("HTTP/1.1 201"))
        http("PUT", "/dir/a.txt", "x".toByteArray())
        assertEquals(204, http("DELETE", "/dir").status)
        assertFalse(File(backend.root, "dir").exists())
    }

    @Test
    fun readonlyKernelRejectsWritesOverSocket() {
        server?.stop()
        backend = FileBackend(temporaryFolder.newFolder("readonly"))
        server = WebDavServer(
            0, WebDavKernel(backend, USERNAME, PASSWORD, writable = false)
        ).also { it.start() }
        assertEquals(403, http("PUT", "/a.txt", "x".toByteArray()).status)
        assertTrue(raw("MKCOL", "/d").startsWith("HTTP/1.1 403"))
        // 读路径不受影响。
        assertTrue(raw("PROPFIND", "/", listOf("Depth: 1")).startsWith("HTTP/1.1 207"))
    }

    @Test
    fun traversalAttemptIsDropped() {
        // 词法穿越段（..）直接断连：读不到任何 HTTP 响应字节。
        val response = try {
            raw("GET", "/../etc/passwd", authorize = true)
        } catch (error: IOException) {
            return
        }
        assertFalse(response.startsWith("HTTP/1.1 2"))
    }
}

/** JVM 测试用的真实文件后端；Android 端运行时用的是 WebDavProviderBackend。 */
private class FileBackend(val root: File) : WebDavBackend {
    private val canonicalRoot = root.canonicalFile

    /** canonical 双保险：符号链接指向根外也拒绝（AGENTS 要求的 canonical 校验）。 */
    private fun resolve(path: List<String>): File {
        var file = canonicalRoot
        for (segment in path) {
            file = File(file, segment)
        }
        val canonical = file.canonicalFile
        if (canonical != canonicalRoot &&
            !canonical.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            throw IOException("Path escapes the shared root")
        }
        return canonical
    }

    override fun stat(path: List<String>): WebDavResource? {
        val file = resolve(path)
        if (!file.exists()) {
            return null
        }
        return WebDavResource(
            path.lastOrNull() ?: canonicalRoot.name, file.isDirectory,
            if (file.isDirectory) -1L else file.length(), file.lastModified()
        )
    }

    override fun list(path: List<String>): List<WebDavResource>? {
        val file = resolve(path)
        if (!file.isDirectory) {
            return null
        }
        return file.listFiles().orEmpty().map { child ->
            WebDavResource(child.name, child.isDirectory,
                if (child.isDirectory) -1L else child.length(), child.lastModified())
        }
    }

    override fun openRead(path: List<String>, startOffset: Long): WebDavOpenFile {
        val file = resolve(path)
        if (!file.isFile) {
            throw NoSuchFileException(path.joinToString("/"))
        }
        val input = FileInputStream(file)
        // 本地文件的 skip 即 lseek，真正的随机访问。
        var remaining = startOffset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                input.close()
                throw IOException("EOF while seeking")
            }
            remaining -= skipped
        }
        return WebDavOpenFile(file.length(), input)
    }

    override fun openWrite(path: List<String>): OutputStream {
        val file = resolve(path)
        if (file.isDirectory) {
            throw IOException("Is a directory")
        }
        val parent = file.parentFile ?: throw NoSuchFileException("/")
        if (!parent.isDirectory) {
            throw NoSuchFileException(parent.path)
        }
        return FileOutputStream(file)
    }

    override fun createDirectory(path: List<String>) {
        val file = resolve(path)
        if (file.exists()) {
            throw WebDavConflictException(path.joinToString("/"))
        }
        val parent = file.parentFile ?: throw NoSuchFileException("/")
        if (!parent.isDirectory) {
            throw NoSuchFileException(parent.path)
        }
        if (!file.mkdir()) {
            throw IOException("mkdir failed")
        }
    }

    override fun delete(path: List<String>) {
        deleteRecursively(resolve(path))
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles().orEmpty().forEach { deleteRecursively(it) }
        }
        if (!file.delete()) {
            throw IOException("delete failed: $file")
        }
    }

    override fun move(from: List<String>, to: List<String>) {
        val source = resolve(from)
        val target = resolve(to)
        if (!source.exists() || target.exists() && !target.delete()) {
            throw IOException("move failed")
        }
        if (!source.renameTo(target)) {
            throw IOException("rename failed")
        }
    }

    override fun copyFile(from: List<String>, to: List<String>) {
        val source = resolve(from)
        val target = resolve(to)
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
    }
}
