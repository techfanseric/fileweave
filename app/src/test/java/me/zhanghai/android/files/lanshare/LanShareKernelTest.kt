package me.zhanghai.android.files.lanshare

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class LanShareKernelTest {
    private class Backend : LanShareKernel.Backend {
        var offset = -1L
        var closed = false
        var onRead: (() -> Unit)? = null
        override fun stat(path: List<String>): LanShareKernel.Entry? = when (path) {
            emptyList<String>() -> LanShareKernel.Entry("Selected", true, 0)
            listOf("a+b.txt") -> LanShareKernel.Entry("a+b.txt", false, 10)
            else -> null
        }
        override fun list(path: List<String>) = listOf(LanShareKernel.Entry("a+b.txt", false, 10), LanShareKernel.Entry("<script>.txt", false, 1))
        override fun open(path: List<String>, offset: Long): java.io.InputStream {
            this.offset = offset
            return object : ByteArrayInputStream("0123456789".toByteArray()) {
                init { skip(offset) }
                override fun read(b: ByteArray, off: Int, len: Int): Int { onRead?.invoke(); return super.read(b, off, len) }
                override fun close() { closed = true; super.close() }
            }
        }
    }
    private fun request(kernel: LanShareKernel, target: String = "/secret/a%2Bb.txt", method: String = "GET", headers: String = ""): String {
        val out = ByteArrayOutputStream()
        kernel.handle("$method $target HTTP/1.1\r\n$headers\r\n".byteInputStream(), out)
        return out.toString("UTF-8")
    }
    @Test fun rangeSeeksAndCloses() {
        val backend = Backend()
        val response = request(LanShareKernel("secret", backend), headers = "Range: bytes=4-6\r\n")
        assertTrue(response.startsWith("HTTP/1.1 206")); assertTrue(response.endsWith("456"))
        assertEquals(4L, backend.offset); assertTrue(backend.closed)
    }
    @Test fun suffixRange() {
        assertTrue(request(LanShareKernel("secret", Backend()), headers = "Range: bytes=-3\r\n").endsWith("789"))
    }
    @Test fun rejectsInvalidRanges() {
        for (range in listOf("bytes=99-", "bytes=5-2", "bytes=0-1,4-5", "bytes=-0", "bytes=9999999999999999999999-")) {
            assertTrue(range, request(LanShareKernel("secret", Backend()), headers = "Range: $range\r\n").startsWith("HTTP/1.1 416"))
        }
    }
    @Test fun headNeverOpensFile() {
        val backend = Backend(); val response = request(LanShareKernel("secret", backend), method = "HEAD")
        assertTrue(response.endsWith("\r\n\r\n")); assertEquals(-1L, backend.offset)
    }
    @Test fun wrongLinkCannotListSelection() {
        assertTrue(request(LanShareKernel("secret", Backend()), target = "/other/").startsWith("HTTP/1.1 404"))
    }
    @Test fun rejectsTraversalAndEncodedSeparators() {
        for (path in listOf("../a", "%2e%2e/a", "a%2Fb", "a%5Cb", "%00a")) {
            assertTrue(path, request(LanShareKernel("secret", Backend()), target = "/secret/$path").startsWith("HTTP/1.1 400"))
        }
    }
    @Test fun readOnly() {
        for (method in listOf("POST", "DELETE", "PUT", "MOVE")) assertTrue(request(LanShareKernel("secret", Backend()), method = method).startsWith("HTTP/1.1 405"))
    }
    @Test fun escapedIndexAndEncodedLinks() {
        val response = request(LanShareKernel("secret", Backend()), target = "/secret/")
        assertTrue(response.contains("&lt;script&gt;.txt")); assertFalse(response.contains("<script>"))
        assertTrue(response.contains("a%2Bb.txt")); assertTrue(response.contains("Referrer-Policy: no-referrer"))
    }
    @Test fun stoppedShareNoLongerServes() {
        val kernel = LanShareKernel("secret", Backend()); kernel.active = false
        assertTrue(request(kernel).startsWith("HTTP/1.1 410"))
    }
    @Test fun disconnectClosesStream() {
        val backend = Backend()
        val output = object : java.io.OutputStream() { var count = 0; override fun write(value: Int) { if (++count > 700) throw java.io.IOException("Disconnected") } }
        try { LanShareKernel("secret", backend).handle("GET /secret/a%2Bb.txt HTTP/1.1\r\n\r\n".byteInputStream(), output) } catch (_: java.io.IOException) { }
        // Also cover failure exactly when the body is written.
        val broken = object : java.io.OutputStream() { override fun write(value: Int) {} ; override fun write(b: ByteArray, off: Int, len: Int) { throw java.io.IOException("Disconnected") } }
        try { LanShareKernel("secret", backend).handle("GET /secret/a%2Bb.txt HTTP/1.1\r\n\r\n".byteInputStream(), broken) } catch (_: java.io.IOException) { }
        assertTrue(backend.closed)
    }
}
