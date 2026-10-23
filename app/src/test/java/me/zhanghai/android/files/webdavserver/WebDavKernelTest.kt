package me.zhanghai.android.files.webdavserver

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.NoSuchFileException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * WebDAV 协议核在 JVM 上的直接测试：认证、PROPFIND XML、Range、写方法、路径安全与
 * keep-alive。文件系统语义走假后端；真实 socket 往返见 WebDavServerTest。
 */
class WebDavKernelTest {
    private class FakeBackend : WebDavBackend {
        val files = LinkedHashMap<List<String>, ByteArray>()
        val directories = LinkedHashSet<List<String>>(listOf(emptyList()))

        private fun parentMissing(path: List<String>): Boolean =
            path.size > 1 && path.dropLast(1) !in directories

        override fun stat(path: List<String>): WebDavResource? = when {
            path in directories -> WebDavResource(path.lastOrNull() ?: "/", true, -1L, 1000L)
            path in files -> WebDavResource(
                path.last(), false, files.getValue(path).size.toLong(), 2000L
            )
            else -> null
        }

        override fun list(path: List<String>): List<WebDavResource>? {
            if (path !in directories) {
                return null
            }
            val children = ArrayList<WebDavResource>()
            for (directory in directories) {
                if (directory.size == path.size + 1 && directory.subList(0, path.size) == path) {
                    children += WebDavResource(directory.last(), true, -1L, 1000L)
                }
            }
            for (entry in files) {
                val key = entry.key
                if (key.size == path.size + 1 && key.subList(0, path.size) == path) {
                    children += WebDavResource(
                        key.last(), false, entry.value.size.toLong(), 2000L
                    )
                }
            }
            return children
        }

        override fun openRead(path: List<String>, startOffset: Long): WebDavOpenFile {
            val bytes = files[path] ?: throw NoSuchFileException(path.joinToString("/"))
            val input = ByteArrayInputStream(bytes)
            input.skip(startOffset)
            return WebDavOpenFile(bytes.size.toLong(), input)
        }

        override fun openWrite(path: List<String>): java.io.OutputStream {
            if (path in directories) {
                throw IOException("Is a directory")
            }
            if (parentMissing(path)) {
                throw NoSuchFileException(path.dropLast(1).joinToString("/"))
            }
            return object : ByteArrayOutputStream() {
                override fun close() {
                    files[path] = toByteArray()
                }
            }
        }

        override fun createDirectory(path: List<String>) {
            if (path in directories || path in files) {
                throw WebDavConflictException(path.joinToString("/"))
            }
            if (parentMissing(path)) {
                throw NoSuchFileException(path.dropLast(1).joinToString("/"))
            }
            directories += path
        }

        override fun delete(path: List<String>) {
            val fileRemoved = files.remove(path) != null
            val directoryRemoved = directories.remove(path)
            if (!fileRemoved && !directoryRemoved) {
                throw NoSuchFileException(path.joinToString("/"))
            }
        }

        override fun move(from: List<String>, to: List<String>) {
            if (from in files) {
                files[to] = files.remove(from)!!
            } else if (from in directories) {
                directories.remove(from)
                directories += to
                val moved = files.keys.filter { it.size > from.size && it.subList(0, from.size) == from }
                for (key in moved) {
                    files[to + key.subList(from.size, key.size)] = files.remove(key)!!
                }
            } else {
                throw NoSuchFileException(from.joinToString("/"))
            }
        }

        override fun copyFile(from: List<String>, to: List<String>) {
            files[to] = files[from] ?: throw NoSuchFileException(from.joinToString("/"))
        }
    }

    private class Response(raw: String, val body: ByteArray) {
        val lines = raw.split("\r\n")
        val status: Int = lines.first().split(" ")[1].toInt()
        val headers: Map<String, String> = lines.drop(1).filter { it.contains(':') }.associate {
            val index = it.indexOf(':')
            it.substring(0, index).lowercase() to it.substring(index + 1).trim()
        }

        fun header(name: String): String? = headers[name]
        val bodyText: String get() = String(body, Charsets.UTF_8)
    }

    private fun kernel(backend: WebDavBackend, anonymous: Boolean = false, writable: Boolean = true) =
        WebDavKernel(
            backend, if (anonymous) null else USERNAME, if (anonymous) null else PASSWORD, writable
        )

    private fun request(
        kernel: WebDavKernel,
        method: String,
        target: String,
        body: ByteArray? = null,
        authorization: String? = AUTHORIZATION,
        headers: List<String> = emptyList()
    ): Response {
        val builder = StringBuilder("$method $target HTTP/1.1\r\n")
        if (authorization != null) {
            builder.append("Authorization: ").append(authorization).append("\r\n")
        }
        for (header in headers) {
            builder.append(header).append("\r\n")
        }
        if (body != null) {
            builder.append("Content-Length: ").append(body.size).append("\r\n")
        }
        builder.append("\r\n")
        val output = ByteArrayOutputStream()
        kernel.handleConnection(
            ByteArrayInputStream(
                builder.toString().toByteArray(Charsets.ISO_8859_1) + (body ?: ByteArray(0))
            ),
            output
        )
        return parseResponse(output.toByteArray())
    }

    /** 解析单条响应；有多条（keep-alive）时只取第一条。 */
    private fun parseResponse(bytes: ByteArray): Response {
        val split = indexOfHeaderEnd(bytes)
        val headerText = String(bytes, 0, split, Charsets.ISO_8859_1)
        val headerLines = headerText.split("\r\n")
        val contentLength = headerLines.drop(1)
            .firstOrNull { it.lowercase().startsWith("content-length:") }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val bodyStart = split + 4
        // HEAD 等无 body 响应会带 Content-Length 但不实际输出内容，这里做钳制。
        val bodyEnd = (bodyStart + contentLength).coerceAtMost(bytes.size)
        val body = bytes.copyOfRange(bodyStart, bodyEnd)
        return Response(headerText, body)
    }

    private fun indexOfHeaderEnd(bytes: ByteArray): Int {
        var index = 0
        while (index + 3 < bytes.size) {
            if (bytes[index] == '\r'.code.toByte() && bytes[index + 1] == '\n'.code.toByte() &&
                bytes[index + 2] == '\r'.code.toByte() && bytes[index + 3] == '\n'.code.toByte()
            ) {
                return index
            }
            ++index
        }
        fail("No header end found")
        throw AssertionError()
    }

    private companion object {
        const val USERNAME = "user"
        const val PASSWORD = "pass"
        val AUTHORIZATION = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("$USERNAME:$PASSWORD".toByteArray(Charsets.UTF_8))
    }

    // ------------------------------------------------------------ 认证

    @Test
    fun optionsDoesNotRequireAuth() {
        val response = request(kernel(FakeBackend()), "OPTIONS", "/", authorization = null)
        assertEquals(200, response.status)
        assertEquals("1", response.header("dav"))
        assertTrue(response.header("allow")!!.contains("PROPFIND"))
        assertEquals("DAV", response.header("ms-author-via"))
    }

    @Test
    fun unauthorizedWithoutCredentials() {
        val response = request(kernel(FakeBackend()), "PROPFIND", "/", authorization = null)
        assertEquals(401, response.status)
        assertTrue(response.header("www-authenticate")!!.startsWith("Basic "))
    }

    @Test
    fun unauthorizedWithWrongPassword() {
        val wrong = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("$USERNAME:wrong".toByteArray(Charsets.UTF_8))
        val response = request(kernel(FakeBackend()), "PROPFIND", "/", authorization = wrong)
        assertEquals(401, response.status)
    }

    @Test
    fun anonymousModeSkipsAuth() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = "hello".toByteArray()
        val response = request(kernel(backend, anonymous = true), "PROPFIND", "/a.txt",
            authorization = null, headers = listOf("Depth: 0"))
        assertEquals(207, response.status)
    }

    // ------------------------------------------------------------ PROPFIND

    @Test
    fun propfindDepth1ListsChildrenWithEncodedHrefs() {
        val backend = FakeBackend()
        backend.directories += listOf("docs")
        backend.files[listOf("docs", "中文 文档.txt")] = "你好".toByteArray()
        backend.files[listOf("docs", "a.bin")] = ByteArray(3)
        val response = request(kernel(backend), "PROPFIND", "/docs/", headers = listOf("Depth: 1"))
        assertEquals(207, response.status)
        assertTrue(response.header("content-type")!!.startsWith("application/xml"))
        val xml = response.bodyText
        // 集合本身与两个子项。
        assertTrue(xml.contains("<D:href>/docs/</D:href>"))
        assertTrue(xml.contains("<D:href>/docs/%E4%B8%AD%E6%96%87%20%E6%96%87%E6%A1%A3.txt</D:href>"))
        assertTrue(xml.contains("<D:href>/docs/a.bin</D:href>"))
        assertTrue(xml.contains("<D:resourcetype><D:collection/></D:resourcetype>"))
        assertTrue(xml.contains("<D:getcontentlength>6</D:getcontentlength>"))
        assertTrue(xml.contains("application/octet-stream"))
        // 子项排序稳定：a.bin 在中文文档之前。
        assertTrue(xml.indexOf("a.bin") < xml.indexOf("%E4%B8%AD"))
    }

    @Test
    fun propfindDepth0ReturnsOnlySelf() {
        val backend = FakeBackend()
        backend.directories += listOf("docs")
        backend.files[listOf("docs", "a.txt")] = ByteArray(1)
        val response = request(kernel(backend), "PROPFIND", "/docs", headers = listOf("Depth: 0"))
        assertEquals(207, response.status)
        assertFalse(response.bodyText.contains("a.txt"))
    }

    @Test
    fun propfindDepthInfinityRejected() {
        val response = request(kernel(FakeBackend()), "PROPFIND", "/",
            headers = listOf("Depth: infinity"))
        assertEquals(403, response.status)
    }

    @Test
    fun propfindMissingPath() {
        val response = request(kernel(FakeBackend()), "PROPFIND", "/nope",
            headers = listOf("Depth: 0"))
        assertEquals(404, response.status)
    }

    // ------------------------------------------------------------ GET/HEAD/Range

    @Test
    fun getFile() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = "0123456789".toByteArray()
        val response = request(kernel(backend), "GET", "/a.txt")
        assertEquals(200, response.status)
        assertEquals("0123456789", response.bodyText)
        assertEquals("10", response.header("content-length"))
        assertEquals("text/plain", response.header("content-type"))
        assertEquals("bytes", response.header("accept-ranges"))
        assertTrue(response.header("etag")!!.startsWith("\""))
        assertTrue(response.header("last-modified")!!.endsWith("GMT"))
    }

    @Test
    fun getOpenRange() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = "0123456789".toByteArray()
        val response = request(kernel(backend), "GET", "/a.txt",
            headers = listOf("Range: bytes=4-"))
        assertEquals(206, response.status)
        assertEquals("456789", response.bodyText)
        assertEquals("bytes 4-9/10", response.header("content-range"))
    }

    @Test
    fun getClosedRange() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = "0123456789".toByteArray()
        val response = request(kernel(backend), "GET", "/a.txt",
            headers = listOf("Range: bytes=2-5"))
        assertEquals(206, response.status)
        assertEquals("2345", response.bodyText)
    }

    @Test
    fun getSuffixRange() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = "0123456789".toByteArray()
        val response = request(kernel(backend), "GET", "/a.txt",
            headers = listOf("Range: bytes=-3"))
        assertEquals(206, response.status)
        assertEquals("789", response.bodyText)
        assertEquals("bytes 7-9/10", response.header("content-range"))
    }

    @Test
    fun getUnsatisfiableRange() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = "0123456789".toByteArray()
        val response = request(kernel(backend), "GET", "/a.txt",
            headers = listOf("Range: bytes=99-"))
        assertEquals(416, response.status)
        assertEquals("bytes */10", response.header("content-range"))
    }

    @Test
    fun headFileHasNoBodyButLength() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = "0123456789".toByteArray()
        val response = request(kernel(backend), "HEAD", "/a.txt")
        assertEquals(200, response.status)
        assertEquals("10", response.header("content-length"))
        assertEquals(0, response.body.size)
    }

    // ------------------------------------------------------------ PUT

    @Test
    fun putCreatesAndOverwrites() {
        val backend = FakeBackend()
        val kernel = kernel(backend)
        val created = request(kernel, "PUT", "/a.txt", body = "one".toByteArray())
        assertEquals(201, created.status)
        assertEquals("one", String(backend.files.getValue(listOf("a.txt"))))
        val overwritten = request(kernel, "PUT", "/a.txt", body = "two".toByteArray())
        assertEquals(204, overwritten.status)
        assertEquals("two", String(backend.files.getValue(listOf("a.txt"))))
        // 204 响应不得带 Content-Length（RFC 9110）。
        assertNull(overwritten.header("content-length"))
    }

    @Test
    fun putParentMissingIsConflict() {
        val response = request(kernel(FakeBackend()), "PUT", "/no/such/a.txt",
            body = "x".toByteArray())
        assertEquals(409, response.status)
    }

    @Test
    fun putOnDirectoryIs405() {
        val backend = FakeBackend()
        backend.directories += listOf("dir")
        val response = request(kernel(backend), "PUT", "/dir", body = "x".toByteArray())
        assertEquals(405, response.status)
    }

    @Test
    fun putRejectedWhenReadOnly() {
        val response = request(kernel(FakeBackend(), writable = false), "PUT", "/a.txt",
            body = "x".toByteArray())
        assertEquals(403, response.status)
    }

    // ------------------------------------------------------------ MKCOL/DELETE

    @Test
    fun mkcolCreatesDirectory() {
        val backend = FakeBackend()
        val response = request(kernel(backend), "MKCOL", "/dir")
        assertEquals(201, response.status)
        assertTrue(listOf("dir") in backend.directories)
    }

    @Test
    fun mkcolExistingIs405() {
        val backend = FakeBackend()
        backend.directories += listOf("dir")
        assertEquals(405, request(kernel(backend), "MKCOL", "/dir").status)
    }

    @Test
    fun mkcolParentMissingIs409() {
        assertEquals(409, request(kernel(FakeBackend()), "MKCOL", "/no/such").status)
    }

    @Test
    fun deleteRemovesFile() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = ByteArray(1)
        val kernel = kernel(backend)
        assertEquals(204, request(kernel, "DELETE", "/a.txt").status)
        assertNull(backend.stat(listOf("a.txt")))
        assertEquals(404, request(kernel, "DELETE", "/a.txt").status)
    }

    @Test
    fun deleteRootIs403() {
        assertEquals(403, request(kernel(FakeBackend()), "DELETE", "/").status)
    }

    @Test
    fun deleteRejectedWhenReadOnly() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = ByteArray(1)
        assertEquals(403, request(kernel(backend, writable = false), "DELETE", "/a.txt").status)
    }

    // ------------------------------------------------------------ MOVE/COPY

    @Test
    fun moveWithAbsoluteDestinationUri() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = "x".toByteArray()
        val response = request(kernel(backend), "MOVE", "/a.txt", headers = listOf(
            "Destination: http://192.168.1.2:8080/b.txt"
        ))
        assertEquals(201, response.status)
        assertNull(backend.stat(listOf("a.txt")))
        assertEquals("x", String(backend.files.getValue(listOf("b.txt"))))
    }

    @Test
    fun moveOverwriteFPreconditionFails() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = "x".toByteArray()
        backend.files[listOf("b.txt")] = "y".toByteArray()
        val response = request(kernel(backend), "MOVE", "/a.txt", headers = listOf(
            "Destination: /b.txt", "Overwrite: F"
        ))
        assertEquals(412, response.status)
        assertEquals("y", String(backend.files.getValue(listOf("b.txt"))))
    }

    @Test
    fun moveOverwriteTReplaces() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = "x".toByteArray()
        backend.files[listOf("b.txt")] = "y".toByteArray()
        val response = request(kernel(backend), "MOVE", "/a.txt",
            headers = listOf("Destination: /b.txt"))
        assertEquals(204, response.status)
        assertEquals("x", String(backend.files.getValue(listOf("b.txt"))))
    }

    @Test
    fun moveIntoOwnSubtreeIs403() {
        val backend = FakeBackend()
        backend.directories += listOf("dir")
        val response = request(kernel(backend), "MOVE", "/dir", headers = listOf(
            "Destination: /dir/inside"
        ))
        assertEquals(403, response.status)
    }

    @Test
    fun copyDirectoryRecursively() {
        val backend = FakeBackend()
        backend.directories += listOf("src")
        backend.directories += listOf("src", "inner")
        backend.files[listOf("src", "a.txt")] = "a".toByteArray()
        backend.files[listOf("src", "inner", "b.txt")] = "b".toByteArray()
        val response = request(kernel(backend), "COPY", "/src", headers = listOf(
            "Destination: /dst"
        ))
        assertEquals(201, response.status)
        assertEquals("a", String(backend.files.getValue(listOf("dst", "a.txt"))))
        assertEquals("b", String(backend.files.getValue(listOf("dst", "inner", "b.txt"))))
        // 源保持不变。
        assertEquals("a", String(backend.files.getValue(listOf("src", "a.txt"))))
    }

    @Test
    fun destinationEscapingRootIs400() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = "x".toByteArray()
        assertEquals(400, request(kernel(backend), "MOVE", "/a.txt",
            headers = listOf("Destination: /../evil")).status)
        assertEquals(400, request(kernel(backend), "MOVE", "/a.txt",
            headers = listOf("Destination: /a/../../evil")).status)
    }

    // ------------------------------------------------------------ 路径安全与 keep-alive

    @Test
    fun traversalTargetDropsConnection() {
        val kernel = kernel(FakeBackend())
        try {
            request(kernel, "GET", "/../secret")
            fail("Expected IOException")
        } catch (expected: IOException) {
            // 词法穿越段直接断连，不给攻击者任何探测信号。
        }
    }

    @Test
    fun twoRequestsOnOneConnection() {
        val backend = FakeBackend()
        backend.files[listOf("a.txt")] = "hi".toByteArray()
        val builder = StringBuilder()
        builder.append("OPTIONS / HTTP/1.1\r\n\r\n")
        builder.append("GET /a.txt HTTP/1.1\r\n")
            .append("Authorization: ").append(AUTHORIZATION).append("\r\n\r\n")
        val output = ByteArrayOutputStream()
        kernel(backend).handleConnection(
            ByteArrayInputStream(builder.toString().toByteArray(Charsets.ISO_8859_1)), output
        )
        val raw = output.toString(Charsets.ISO_8859_1.name())
        assertTrue(raw.contains("HTTP/1.1 200 OK"))
        assertTrue(raw.contains("hi"))
    }

    @Test
    fun connectionCloseIsHonored() {
        val response = request(kernel(FakeBackend()), "PROPFIND", "/",
            headers = listOf("Depth: 0", "Connection: close"))
        assertEquals(207, response.status)
        assertEquals("close", response.header("connection"))
    }

    @Test
    fun unknownMethodIs501AndLockIs405() {
        assertEquals(501, request(kernel(FakeBackend()), "REPORT", "/").status)
        val lock = request(kernel(FakeBackend()), "LOCK", "/a.txt")
        assertEquals(405, lock.status)
        assertTrue(lock.header("allow")!!.contains("PUT"))
    }
}
