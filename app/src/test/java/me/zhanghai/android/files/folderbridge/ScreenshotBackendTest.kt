package me.zhanghai.android.files.folderbridge

import java.io.File
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScreenshotBackendTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun seed(root: File, path: String): File = File(root, path).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4)) }
    @Test fun onlyKnownScreenshotDirectoriesAndImagesAreExposed() {
        val root = temporary.newFolder()
        seed(root, "Pictures/Screenshots/new.PNG"); seed(root, "DCIM/Screenshots/second.jpg")
        seed(root, "Pictures/Camera/private.png"); seed(root, "Pictures/ScreenshotsBackup/private.png")
        seed(root, "Pictures/Screenshots/video.mp4"); seed(root, "Pictures/Screenshots/.private/hidden.png")
        val backend = ScreenshotBackend(root)
        assertEquals(setOf("Pictures/Screenshots/new.PNG", "DCIM/Screenshots/second.jpg"), backend.listFiles().map { it.path }.toSet())
        for (path in listOf("Pictures/Camera/private.png", "Pictures/Screenshots/../../secret.png", "Pictures/Screenshots/video.mp4")) {
            try { backend.openFile(path); fail(path) } catch (_: Exception) { }
        }
    }
    @Test fun trashAndRestorePreserveBytesAndRefuseOverwrite() {
        val root = temporary.newFolder(); val path = "Pictures/Screenshots/中文 shot.png"
        val original = seed(root, path); val data = original.readBytes(); val changed = mutableListOf<File>()
        val backend = ScreenshotBackend(root) { changed.add(it) }
        val trash = backend.trash(path, path)
        assertFalse(original.exists()); assertArrayEquals(data, backend.openFile(trash).input.use { it.readBytes() })
        val item = backend.listFiles().single(); assertEquals(path, item.path); assertEquals(trash, item.trashPath)
        original.writeText("collision")
        try { backend.restore(trash, path, path); fail() } catch (_: Exception) { }
        assertEquals("collision", original.readText()); assertTrue(File(root, trash).exists())
        original.delete(); backend.restore(trash, path, path)
        assertArrayEquals(data, original.readBytes()); assertFalse(File(root, trash).exists()); assertEquals(2, changed.size)
    }
    @Test fun screenshotEndpointUsesPairingRangesAndSeparateSharingGate() {
        val root = temporary.newFolder(); seed(root, "Pictures/Screenshots/a.png")
        val backend = ScreenshotBackend(root); val kernel = FolderBridgeKernel(backend, "token", "Test")
        kernel.screenshotsBackend = { backend }; kernel.recordingsEnabled = { false }
        fun call(method: String, path: String, body: String = "", token: String = "token", range: String = ""): String {
            val request = "$method $path HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Length: ${body.toByteArray().size}\r\n$range\r\n$body"
            val output = ByteArrayOutputStream(); kernel.handle(request.byteInputStream(), output); return output.toString("UTF-8")
        }
        assertTrue(call("GET", "/v1/screenshots").contains("Pictures/Screenshots/a.png"))
        assertTrue(call("GET", "/v1/screenshots", token = "wrong").startsWith("HTTP/1.1 401"))
        assertTrue(call("GET", "/v1/screenshots/file?path=Pictures%2FScreenshots%2Fa.png", range = "Range: bytes=1-2\r\n").startsWith("HTTP/1.1 206"))
        assertTrue(call("POST", "/v1/screenshots/trash", "{\"id\":\"Pictures/Screenshots/a.png\",\"path\":\"Pictures/Screenshots/a.png\"}").contains("\"ok\":true"))
        kernel.screenshotsBackend = { null }; assertTrue(call("GET", "/v1/screenshots").startsWith("HTTP/1.1 403"))
        assertTrue(call("GET", "/v1/tree").contains("\"enabled\":false"))
    }
}
