package me.zhanghai.android.files.folderbridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

/**
 * The HTTP core (parsing, auth, routing, Range) runs against a fake backend on the JVM; SAF,
 * NSD and TLS handshake behavior is device-only and not covered here.
 */
class FolderBridgeKernelTest {
    private companion object {
        const val TOKEN = "tQx2G8LpVzY4wRjN7cSbHf0KdM5aXeU1iB3oP6gT2vY"
    }

    private class FakeBackend : FolderBridgeBackend {
        override val rootName = "Sounds"

        // path -> (documentId, bytes)
        val files = LinkedHashMap<String, Pair<String, ByteArray>>()
        // trashPath -> (documentId, bytes)
        val trash = LinkedHashMap<String, Pair<String, ByteArray>>()
        var listFailure: Exception? = null
        var trashMovedAt: Long? = 42L

        override fun listFiles(): List<FolderBridgeItem> {
            listFailure?.let { throw it }
            val live = files.map { (path, value) ->
                FolderBridgeItem(value.first, path, value.second.size.toLong(), 123456L)
            }
            val trashed = trash.entries.map { (trashPath, value) ->
                val original = FolderBridgePaths.originalPathFromTrash(trashPath)!!
                FolderBridgeItem(
                    value.first, original, value.second.size.toLong(), 123456L,
                    trashPath = trashPath, movedAt = trashMovedAt
                )
            }
            return live + trashed
        }

        override fun openFile(path: String): FolderBridgeOpenFile {
            val value = files[path] ?: throw FolderBridgeNotFound(path)
            return FolderBridgeOpenFile(value.second.size.toLong(),
                ByteArrayInputStream(value.second))
        }

        override fun trash(id: String, path: String): String {
            val entry = files.entries.firstOrNull { it.value.first == id && it.key == path }
                ?: throw FolderBridgeNotFound(path)
            val trashPath = "${FolderBridgePaths.TRASH_DIR}/77/${entry.key}"
            trash[trashPath] = entry.value
            files.remove(entry.key)
            return trashPath
        }

        override fun restore(id: String, path: String, to: String) {
            val entry = trash.entries.firstOrNull { it.value.first == id } ?: return
            files[to] = entry.value
            trash.remove(entry.key)
        }
    }

    private class Response(val statusLine: String, val headers: Map<String, String>,
                           val body: ByteArray) {
        val status: Int = statusLine.split(" ")[1].toInt()
        val bodyText: String get() = String(body, Charsets.UTF_8)
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private val backend = FakeBackend()
    private val kernel = FolderBridgeKernel(backend, TOKEN, "Test Model")

    private fun request(method: String, target: String, body: String? = null,
                        token: String? = TOKEN, extraHeaders: List<String> = emptyList()): Response {
        val builder = StringBuilder("$method $target HTTP/1.1\r\n")
        if (token != null) {
            builder.append("Authorization: Bearer ").append(token).append("\r\n")
        }
        for (header in extraHeaders) {
            builder.append(header).append("\r\n")
        }
        if (body != null) {
            builder.append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size)
                .append("\r\n")
        }
        builder.append("\r\n")
        if (body != null) {
            builder.append(body)
        }
        val output = ByteArrayOutputStream()
        kernel.handle(ByteArrayInputStream(builder.toString().toByteArray(Charsets.UTF_8)), output)
        return parseResponse(output.toByteArray())
    }

    private fun parseResponse(bytes: ByteArray): Response {
        val text = String(bytes, Charsets.UTF_8)
        val split = text.indexOf("\r\n\r\n")
        val headerText = text.substring(0, split)
        val statusLine = headerText.substringBefore("\r\n")
        val headers = headerText.substringAfter("\r\n", "").split("\r\n")
            .filter { it.contains(':') }
            .associate {
                it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
            }
        val headerByteLength = headerText.toByteArray(Charsets.UTF_8).size + 4
        val body = bytes.copyOfRange(headerByteLength, bytes.size)
        return Response(statusLine, headers, body)
    }

    private fun seedFiles() {
        backend.files["CallRecord/通话-1.m4a"] = "doc-1" to ByteArray(100) { it.toByte() }
        backend.files["note.mp3"] = "doc-2" to ByteArray(10) { (it * 3).toByte() }
    }

    // ---------------------------------------------------------------- Auth

    @Test
    fun everyEndpointRequiresThePairedToken() {
        seedFiles()
        assertEquals(401, request("GET", "/v1/status", token = null).status)
        assertEquals(401, request("GET", "/v1/status", token = "wrong-token").status)
        assertEquals(401, request("GET", "/v1/status", token = TOKEN.substring(1)).status)
        assertEquals(401, request("GET", "/v1/status", token = "$TOKEN${TOKEN}").status)
        assertEquals(401, request("GET", "/v1/tree", token = null).status)
        assertEquals(401, request("GET", "/v1/file?path=a.m4a", token = null).status)
        assertEquals(401, request("POST", "/v1/trash", body = "{}", token = null).status)
        assertEquals(200, request("GET", "/v1/status").status)
    }

    // ---------------------------------------------------------------- Status & tree

    @Test
    fun statusReportsProtocolModelFilesAndRoot() {
        seedFiles()
        val response = request("GET", "/v1/status")
        assertEquals(200, response.status)
        assertTrue(response.bodyText.contains("\"proto\":1"))
        assertTrue(response.bodyText.contains("\"model\":\"Test Model\""))
        assertTrue(response.bodyText.contains("\"files\":2"))
        assertTrue(response.bodyText.contains("\"root\":\"Sounds\""))
        assertEquals("close", response.header("Connection"))
    }

    @Test
    fun statusReportsMinusOneWhenScanFails() {
        backend.listFailure = RuntimeException("provider down")
        val response = request("GET", "/v1/status")
        assertEquals(200, response.status)
        assertTrue(response.bodyText.contains("\"files\":-1"))
        assertEquals(-1, kernel.lastScanCount)
    }

    @Test
    fun treeListsRelativePathsAndTrashEntries() {
        seedFiles()
        backend.trash[".Trash/77/CallRecord/old.m4a"] = "doc-9" to ByteArray(5)
        val response = request("GET", "/v1/tree")
        assertEquals(200, response.status)
        assertTrue(response.bodyText.contains("\"ok\":true"))
        // Paths are relative; no /sdcard prefix ever appears.
        assertTrue(response.bodyText.contains("\"path\":\"CallRecord/通话-1.m4a\""))
        assertFalse(response.bodyText.contains("/sdcard"))
        assertTrue(response.bodyText.contains("\"path\":\"CallRecord/old.m4a\""))
        assertTrue(response.bodyText.contains(
            "\"trashPath\":\".Trash/77/CallRecord/old.m4a\""))
        assertTrue(response.bodyText.contains("\"movedAt\":"))
        assertTrue(response.bodyText.contains("\"id\":\"doc-1\""))
        assertTrue(response.bodyText.contains("\"size\":100"))
        assertTrue(response.bodyText.contains("\"mtime\":123456"))
    }

    @Test
    fun treeOmitsMovedAtWhenTheBatchStampIsUnreadable() {
        seedFiles()
        backend.trashMovedAt = null
        backend.trash[".Trash/not-a-stamp/CallRecord/old.m4a"] = "doc-9" to ByteArray(5)
        val response = request("GET", "/v1/tree")
        assertEquals(200, response.status)
        // trashPath stays; movedAt is absent entirely so the Mac never renders 1970-01-01.
        assertTrue(response.bodyText.contains(
            "\"trashPath\":\".Trash/not-a-stamp/CallRecord/old.m4a\""))
        assertFalse(response.bodyText.contains("\"movedAt\""))
    }

    @Test
    fun treeReportsScanFailureAsOkFalse() {
        backend.listFailure = RuntimeException("provider down")
        val response = request("GET", "/v1/tree")
        assertEquals(200, response.status)
        assertTrue(response.bodyText.contains("\"ok\":false"))
    }

    // ---------------------------------------------------------------- File + Range

    @Test
    fun fileServesWholeFile() {
        seedFiles()
        val response = request("GET", "/v1/file?path=note.mp3")
        assertEquals(200, response.status)
        assertEquals("audio/mp4", response.header("Content-Type"))
        assertEquals("bytes", response.header("Accept-Ranges"))
        assertEquals("10", response.header("Content-Length"))
        assertEquals("close", response.header("Connection"))
        assertArrayEquals(backend.files["note.mp3"]!!.second, response.body)
    }

    @Test
    fun fileServesBoundedRange() {
        seedFiles()
        val response = request("GET", "/v1/file?path=note.mp3",
            extraHeaders = listOf("Range: bytes=2-5"))
        assertEquals(206, response.status)
        assertEquals("4", response.header("Content-Length"))
        assertEquals("bytes 2-5/10", response.header("Content-Range"))
        assertArrayEquals(backend.files["note.mp3"]!!.second.copyOfRange(2, 6), response.body)
    }

    @Test
    fun fileServesOpenEndedRangeAndClampsToEnd() {
        seedFiles()
        val open = request("GET", "/v1/file?path=note.mp3",
            extraHeaders = listOf("Range: bytes=8-"))
        assertEquals(206, open.status)
        assertArrayEquals(backend.files["note.mp3"]!!.second.copyOfRange(8, 10), open.body)

        val clamped = request("GET", "/v1/file?path=note.mp3",
            extraHeaders = listOf("Range: bytes=3-99"))
        assertEquals(206, clamped.status)
        assertEquals("bytes 3-9/10", clamped.header("Content-Range"))
        assertArrayEquals(backend.files["note.mp3"]!!.second.copyOfRange(3, 10), clamped.body)
    }

    @Test
    fun fileRejectsInvalidAndMultiRanges() {
        seedFiles()
        assertEquals(416, request("GET", "/v1/file?path=note.mp3",
            extraHeaders = listOf("Range: bytes=0-1,3-4")).status)
        assertEquals(416, request("GET", "/v1/file?path=note.mp3",
            extraHeaders = listOf("Range: bytes=10-")).status)
        assertEquals(416, request("GET", "/v1/file?path=note.mp3",
            extraHeaders = listOf("Range: bytes=9-2")).status)
        assertEquals(416, request("GET", "/v1/file?path=note.mp3",
            extraHeaders = listOf("Range: chunks=0-1")).status)
        assertEquals(416, request("GET", "/v1/file?path=note.mp3",
            extraHeaders = listOf("Range: bytes=a-b")).status)
    }

    @Test
    fun fileRejectsOverflowingRangeNumbers() {
        seedFiles()
        assertEquals(416, request("GET", "/v1/file?path=note.mp3",
            extraHeaders = listOf("Range: bytes=99999999999999999999-")).status)
        assertEquals(416, request("GET", "/v1/file?path=note.mp3",
            extraHeaders = listOf("Range: bytes=0-99999999999999999999")).status)
    }

    @Test
    fun plusSignStaysLiteralInTheQueryPath() {
        backend.files["a+b.m4a"] = "doc-3" to ByteArray(4) { 7 }
        // Query semantics: '+' is a literal character, never a space.
        assertEquals(200, request("GET", "/v1/file?path=a+b.m4a").status)
        // Explicit percent-encoding of the same name still decodes correctly.
        assertEquals(200, request("GET", "/v1/file?path=a%2Bb.m4a").status)
    }

    @Test
    fun fileDecodesUtf8PathAndPreservesQueryCharacters() {
        val path = "通话录音/测试😀 +%&=.m4a"
        val content = ByteArray(512) { it.toByte() }
        backend.files[path] = "doc-unicode" to content
        val encoded = java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        val whole = request("GET", "/v1/file?path=$encoded")
        assertEquals(200, whole.status)
        assertArrayEquals(content, whole.body)
        val range = request("GET", "/v1/file?path=$encoded",
            extraHeaders = listOf("Range: bytes=128-255"))
        assertEquals(206, range.status)
        assertArrayEquals(content.copyOfRange(128, 256), range.body)
    }

    @Test
    fun fileRejectsMissingAndUnsafePaths() {
        seedFiles()
        assertEquals(400, request("GET", "/v1/file").status)
        assertEquals(400, request("GET", "/v1/file?path=").status)
        assertEquals(400, request("GET", "/v1/file?path=..%2Fescape.m4a").status)
        assertEquals(400, request("GET", "/v1/file?path=%2Fsdcard%2FSounds%2Fa.m4a").status)
        assertEquals(404, request("GET", "/v1/file?path=missing.m4a").status)
    }

    // ---------------------------------------------------------------- Trash & restore

    @Test
    fun trashMovesByDocumentIdAndReturnsTrashPath() {
        seedFiles()
        val response = request("POST", "/v1/trash",
            body = "{\"id\":\"doc-1\",\"path\":\"CallRecord/通话-1.m4a\"}")
        assertEquals(200, response.status)
        assertTrue(response.bodyText.contains("\"ok\":true"))
        assertTrue(response.bodyText.contains(
            "\"trashPath\":\".Trash/77/CallRecord/通话-1.m4a\""))
        assertFalse(backend.files.containsKey("CallRecord/通话-1.m4a"))
        assertTrue(backend.trash.containsKey(".Trash/77/CallRecord/通话-1.m4a"))
    }

    @Test
    fun trashRejectsMissingFieldsAndUnsafePaths() {
        seedFiles()
        val missing = request("POST", "/v1/trash", body = "{\"id\":\"doc-1\"}")
        assertEquals(200, missing.status)
        assertTrue(missing.bodyText.contains("\"ok\":false"))
        assertTrue(backend.files.containsKey("CallRecord/通话-1.m4a"))
        val unsafe = request("POST", "/v1/trash",
            body = "{\"id\":\"doc-1\",\"path\":\"../elsewhere.m4a\"}")
        assertTrue(unsafe.bodyText.contains("\"ok\":false"))
    }

    @Test
    fun restorePutsFileBackAtRelativeTarget() {
        seedFiles()
        backend.trash[".Trash/77/CallRecord/old.m4a"] = "doc-9" to ByteArray(5)
        val response = request("POST", "/v1/restore",
            body = "{\"id\":\"doc-9\",\"path\":\"CallRecord/old.m4a\"," +
                "\"to\":\"CallRecord/old-restored.m4a\"}")
        assertEquals(200, response.status)
        assertTrue(response.bodyText.contains("\"ok\":true"))
        assertTrue(backend.files.containsKey("CallRecord/old-restored.m4a"))
        assertTrue(backend.trash.isEmpty())
        val missing = request("POST", "/v1/restore", body = "{\"id\":\"x\",\"path\":\"y\"}")
        assertTrue(missing.bodyText.contains("\"ok\":false"))
    }

    // ---------------------------------------------------------------- Routing & limits

    @Test
    fun unknownEndpointsGet404AndWrongMethodsGet405() {
        seedFiles()
        assertEquals(404, request("GET", "/v1/nope").status)
        assertEquals(404, request("GET", "/").status)
        assertEquals(405, request("POST", "/v1/status", body = "{}").status)
        assertEquals(405, request("GET", "/v1/trash").status)
        assertEquals(405, request("PUT", "/v1/file?path=a.m4a").status)
        assertEquals(405, request("POST", "/v1/tree", body = "{}").status)
    }

    @Test
    fun oversizedHeadersAreRejected() {
        seedFiles()
        val request = "GET /v1/status HTTP/1.1\r\n" +
            "Authorization: Bearer $TOKEN\r\n" +
            "X-Pad: " + "a".repeat(FolderBridgeKernel.MAX_HEADER_BYTES) + "\r\n" +
            "\r\n"
        val output = ByteArrayOutputStream()
        kernel.handle(ByteArrayInputStream(request.toByteArray(Charsets.UTF_8)), output)
        val response = parseResponse(output.toByteArray())
        assertEquals(400, response.status)
    }

    @Test
    fun oversizedBodyIsRejected() {
        seedFiles()
        val request = "POST /v1/trash HTTP/1.1\r\n" +
            "Authorization: Bearer $TOKEN\r\n" +
            "Content-Length: ${FolderBridgeKernel.MAX_BODY_BYTES + 1}\r\n" +
            "\r\n"
        val output = ByteArrayOutputStream()
        kernel.handle(ByteArrayInputStream(request.toByteArray(Charsets.UTF_8)), output)
        val response = parseResponse(output.toByteArray())
        assertEquals(413, response.status)
    }

    @Test
    fun emptyConnectionIsIgnoredQuietly() {
        val output = ByteArrayOutputStream()
        kernel.handle(ByteArrayInputStream(ByteArray(0)), output)
        assertEquals(0, output.size())
    }
}
