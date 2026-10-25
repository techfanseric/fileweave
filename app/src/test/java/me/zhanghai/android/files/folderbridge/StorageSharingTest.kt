package me.zhanghai.android.files.folderbridge

import me.zhanghai.android.files.lanshare.LanShareKernel
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class StorageSharingTest {
    private val recordings = object : FolderBridgeBackend {
        override val rootName = "Recordings"
        override fun listFiles() = emptyList<FolderBridgeItem>()
        override fun openFile(path: String): FolderBridgeOpenFile = error("Unexpected read")
        override fun trash(id: String, path: String): String = error("Unexpected write")
        override fun restore(id: String, path: String, to: String) { error("Unexpected write") }
    }
    private val files = object : LanShareKernel.Backend {
        override fun stat(path: List<String>) = LanShareKernel.Entry("a.txt", false, 3)
        override fun list(path: List<String>) = listOf(LanShareKernel.Entry("a.txt", false, 3))
        override fun open(path: List<String>, offset: Long) = "abc".byteInputStream().apply { skip(offset) }
    }
    private fun request(kernel: FolderBridgeKernel, path: String, token: String = "paired"): String {
        val out = ByteArrayOutputStream()
        kernel.handle("GET $path HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n".byteInputStream(), out)
        return out.toString("UTF-8")
    }
    @Test fun storageRequiresPairing() {
        val kernel = FolderBridgeKernel(recordings, "paired", "Phone").apply { storageBackend = { files } }
        assertTrue(request(kernel, "/v1/storage", "other").startsWith("HTTP/1.1 401"))
    }
    @Test fun explicitSelectionDoesNotEnableGlobalStorage() {
        val kernel = FolderBridgeKernel(recordings, "paired", "Phone").apply { storageBackend = { if (it) files else null } }
        assertTrue(request(kernel, "/v1/storage").contains("\"enabled\":false"))
        assertTrue(request(kernel, "/v1/storage?selected=1").contains("a.txt"))
        assertTrue(request(kernel, "/v1/storage/file?selected=1&path=a.txt").endsWith("abc"))
        assertFalse(request(kernel, "/v1/storage/file?path=a.txt").endsWith("abc"))
    }
    @Test fun recordingsSwitchDoesNotDisableStorage() {
        val kernel = FolderBridgeKernel(recordings, "paired", "Phone").apply { storageBackend = { files }; recordingsEnabled = { false } }
        assertTrue(request(kernel, "/v1/file?path=a.m4a").startsWith("HTTP/1.1 403"))
        assertTrue(request(kernel, "/v1/storage").contains("a.txt"))
    }
    @Test fun storageTraversalRejectedBeforeBackend() {
        val kernel = FolderBridgeKernel(recordings, "paired", "Phone").apply { storageBackend = { files } }
        assertTrue(request(kernel, "/v1/storage?path=..%2Fsecret").startsWith("HTTP/1.1 403"))
    }
    @Test fun clipboardPushIsAuthenticatedAndIndependentOfFileSwitches() {
        var received: String? = null
        val kernel = FolderBridgeKernel(recordings, "paired", "Phone").apply {
            recordingsEnabled = { false }; storageBackend = { null }; receiveClipboard = { received = it }
        }
        val text = "generated clipboard fixture"
        fun post(token: String, body: String): String {
            val out = ByteArrayOutputStream()
            kernel.handle(("POST /v1/clipboard HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n" + body).byteInputStream(), out)
            return out.toString("UTF-8")
        }
        assertTrue(post("other", text).startsWith("HTTP/1.1 401")); assertNull(received)
        assertTrue(post("paired", text).startsWith("HTTP/1.1 200")); assertEquals(text, received)
        assertTrue(post("paired", "x".repeat(256 * 1024)).startsWith("HTTP/1.1 400"))
        assertEquals(text, received)
    }

}
