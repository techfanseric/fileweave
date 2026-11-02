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
            listOf("photo + <x>.png") -> LanShareKernel.Entry("photo + <x>.png", false, 10)
            listOf("active.svg") -> LanShareKernel.Entry("active.svg", false, 10)
            else -> null
        }
        override fun list(path: List<String>) = listOf(LanShareKernel.Entry("a+b.txt", false, 10), LanShareKernel.Entry("<script>.txt", false, 1), LanShareKernel.Entry("photo + <x>.png", false, 10), LanShareKernel.Entry("active.svg", false, 10))
        override fun open(path: List<String>, offset: Long): java.io.InputStream {
            this.offset = offset
            return object : ByteArrayInputStream("0123456789".toByteArray()) {
                init { skip(offset) }
                override fun read(b: ByteArray, off: Int, len: Int): Int { onRead?.invoke(); return super.read(b, off, len) }
                override fun close() { closed = true; super.close() }
            }
        }
    }
    private fun request(kernel: LanShareKernel, target: String = "/secret/a%2Bb.txt", method: String = "GET", headers: String = "", body: String = ""): String {
        val out = ByteArrayOutputStream()
        kernel.handle("$method $target HTTP/1.1\r\n$headers\r\n$body".byteInputStream(), out)
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
        val response = request(kernel)
        assertTrue(response.startsWith("HTTP/1.1 410"))
        assertTrue(response.endsWith("Share ended"))
        assertTrue(response.contains("Content-Length: 11"))
        assertTrue(request(kernel, method = "HEAD").endsWith("\r\n\r\n"))
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
    @Test fun clipboardRequiresExplicitSnapshotAndCorrectToken() {
        val kernel = LanShareKernel("secret", Backend())
        val target = "/secret/?clipboard=1"
        val notShared = request(kernel, target)
        assertTrue(notShared.startsWith("HTTP/1.1 200"))
        assertFalse(notShared.contains("<textarea"))
        kernel.clipboardText = "private </textarea><script>alert(1)</script> & text"
        val response = request(kernel, target)
        assertTrue(response.contains("private &lt;/textarea&gt;&lt;script&gt;alert(1)&lt;/script&gt; &amp; text"))
        assertFalse(response.contains("<script>"))
        assertTrue(request(kernel, "/other/?clipboard=1").startsWith("HTTP/1.1 404"))
        assertFalse(request(kernel, "/other/?clipboard=1").contains("private"))
        assertTrue(request(kernel, target, "HEAD").endsWith("\r\n\r\n"))
        kernel.clipboardText = null
        assertFalse(request(kernel, target).contains("private"))
        kernel.active = false
        assertTrue(request(kernel, target).startsWith("HTTP/1.1 410"))
    }
    @Test fun imagePreviewUsesEscapedInlineImageAndDownloadLink() {
        val kernel = LanShareKernel("secret", Backend())
        val path = "/secret/photo%20%2B%20%3Cx%3E.png"
        val index = request(kernel, "/secret/")
        assertTrue(index.contains("$path?preview=1"))
        val preview = request(kernel, "$path?preview=1")
        assertTrue(preview.contains("src=\"$path?inline=1\""))
        assertTrue(preview.contains("alt=\"photo + &lt;x&gt;.png\""))
        assertTrue(preview.contains("href=\"$path\" download"))
        assertTrue(preview.contains("img-src 'self'"))
        val image = request(kernel, "$path?inline=1")
        assertTrue(image.contains("Content-Type: image/png"))
        assertTrue(image.contains("Content-Disposition: inline;"))
        assertTrue(image.endsWith("0123456789"))
        val download = request(kernel, path)
        assertTrue(download.contains("Content-Disposition: attachment;"))
    }
    @Test fun inlineImageSupportsHeadAndRandomReads() {
        val backend = Backend()
        val kernel = LanShareKernel("secret", backend)
        val path = "/secret/photo%20%2B%20%3Cx%3E.png?inline=1"
        assertTrue(request(kernel, path, "HEAD").endsWith("\r\n\r\n"))
        assertEquals(-1L, backend.offset)
        val range = request(kernel, path, headers = "Range: bytes=2-4\r\n")
        assertTrue(range.startsWith("HTTP/1.1 206"))
        assertTrue(range.contains("Content-Type: image/png"))
        assertTrue(range.endsWith("234"))
        assertEquals(2L, backend.offset)
        assertTrue(backend.closed)
    }
    @Test fun activeDocumentsRemainDownloads() {
        val kernel = LanShareKernel("secret", Backend())
        assertTrue(request(kernel, "/secret/active.svg?inline=1").startsWith("HTTP/1.1 415"))
        assertTrue(request(kernel, "/secret/active.svg?preview=1").startsWith("HTTP/1.1 415"))
        val download = request(kernel, "/secret/active.svg")
        assertTrue(download.contains("Content-Type: application/octet-stream"))
        assertTrue(download.contains("Content-Disposition: attachment;"))
    }
    @Test fun revocationDuringReadClosesWithoutWritingFurtherBytes() {
        val backend = Backend()
        val kernel = LanShareKernel("secret", backend)
        backend.onRead = { kernel.active = false }
        val response = request(kernel)
        assertTrue(response.endsWith("\r\n\r\n"))
        assertTrue(backend.closed)
    }
    @Test fun cancelledTransferClosesStream() {
        val backend = Backend()
        val kernel = LanShareKernel("secret", backend)
        backend.onRead = { Thread.currentThread().interrupt() }
        try {
            assertTrue(request(kernel).endsWith("\r\n\r\n"))
            assertTrue(backend.closed)
        } finally { Thread.interrupted() }
    }
    private fun login(kernel: LanShareKernel, body: String) = request(kernel, "/", "POST",
        "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.toByteArray().size}\r\n", body)
    @Test fun shortAddressDoesNotExposeSelectionBeforeValidPost() {
        val kernel = LanShareKernel("secret", Backend(), LanShareManualAccess("123456"))
        val form = request(kernel, "/")
        assertTrue(form.startsWith("HTTP/1.1 200"))
        assertTrue(form.contains("method=post"))
        assertFalse(form.contains("a+b.txt"))
        assertFalse(form.contains("/secret/"))
        assertTrue(login(kernel, "code=000000").startsWith("HTTP/1.1 403"))
        val allowed = login(kernel, "code=123456")
        assertTrue(allowed.startsWith("HTTP/1.1 303"))
        assertTrue(allowed.contains("Location: /secret/"))
        assertTrue(allowed.endsWith("\r\n\r\n"))
    }
    @Test fun malformedRootPostsConsumeGlobalRateBudget() {
        val kernel = LanShareKernel("secret", Backend(), LanShareManualAccess("123456"))
        repeat(5) { assertTrue(login(kernel, "missing=123456").startsWith("HTTP/1.1 403")) }
        val throttled = login(kernel, "code=123456")
        assertTrue(throttled.startsWith("HTTP/1.1 429"))
        assertFalse(throttled.contains("Location:"))
        assertTrue(request(kernel, "/secret/").startsWith("HTTP/1.1 200"))
    }
    @Test fun rootPostBodyIsBoundedAndCodeHasNoTenMinuteLimit() {
        var now = 0L
        val kernel = LanShareKernel("secret", Backend(), LanShareManualAccess("123456") { now })
        assertTrue(login(kernel, "code=" + "1".repeat(65)).startsWith("HTTP/1.1 403"))
        now = java.util.concurrent.TimeUnit.MINUTES.toNanos(10)
        val stillValid = login(kernel, "code=123456")
        assertTrue(stillValid.startsWith("HTTP/1.1 303"))
        assertTrue(stillValid.contains("Location: /secret/"))
        assertTrue(request(kernel, "/secret/").startsWith("HTTP/1.1 200"))
        assertTrue(request(LanShareKernel("secret", Backend()), "/").startsWith("HTTP/1.1 404"))
    }
    @Test fun clipboardOnlyRootShowsSharedTextWithoutAnEmptyFolder() {
        val empty = object : LanShareKernel.Backend {
            override fun stat(path: List<String>) = if (path.isEmpty()) LanShareKernel.Entry("Shared files", true, 0) else null
            override fun list(path: List<String>) = emptyList<LanShareKernel.Entry>()
            override fun open(path: List<String>, offset: Long): java.io.InputStream = error("No files")
        }
        val kernel = LanShareKernel("secret", empty).apply { clipboardText = "only <text>" }
        val response = request(kernel, "/secret/")
        assertTrue(response.contains("only &lt;text&gt;"))
        assertFalse(response.contains("Empty folder"))
        assertTrue(request(kernel, "/secret/unselected").startsWith("HTTP/1.1 404"))
    }
    @Test fun directCodeGetRedirectsToStrongUrlWithoutServingContentFirst() {
        val kernel = LanShareKernel("secret", Backend(), LanShareManualAccess("123456"))
        kernel.clipboardText = "private clipboard fixture"
        val response = request(kernel, "/?code=123456")
        assertTrue(response.startsWith("HTTP/1.1 303"))
        assertTrue(response.contains("Location: /secret/"))
        assertTrue(response.contains("Cache-Control: no-store"))
        assertTrue(response.contains("Referrer-Policy: no-referrer"))
        assertTrue(response.endsWith("\r\n\r\n"))
        assertFalse(response.contains("private clipboard fixture"))
    }
    @Test fun wrongMalformedAndDuplicateCodeQueriesNeverRevealStrongUrl() {
        val queries = listOf("code=000000", "code=123456&code=123456", "code=123456&unused=x",
            "code=%31%32%33%34%35%36", "code=12345", "code=1234567", "CODE=123456", "missing=123456")
        for (query in queries) {
            val kernel = LanShareKernel("secret", Backend(), LanShareManualAccess("123456"))
            val response = request(kernel, "/?$query")
            assertTrue(query, response.startsWith("HTTP/1.1 403"))
            assertFalse(query, response.contains("Location:"))
            assertFalse(query, response.contains("/secret/"))
        }
    }
    @Test fun codeGetsAndPostsConsumeTheSameGlobalBudget() {
        val kernel = LanShareKernel("secret", Backend(), LanShareManualAccess("123456"))
        repeat(3) { assertTrue(request(kernel, "/?code=wrong").startsWith("HTTP/1.1 403")) }
        repeat(2) { assertTrue(login(kernel, "missing=123456").startsWith("HTTP/1.1 403")) }
        val response = request(kernel, "/?code=123456")
        assertTrue(response.startsWith("HTTP/1.1 429"))
        assertFalse(response.contains("Location:"))
        assertTrue(login(kernel, "code=123456").startsWith("HTTP/1.1 429"))
    }
    @Test fun directCodeGetRemainsValidAfterOneDay() {
        var now = 0L
        val kernel = LanShareKernel("secret", Backend(), LanShareManualAccess("123456") { now })
        now = java.util.concurrent.TimeUnit.HOURS.toNanos(24)
        val response = request(kernel, "/?code=123456")
        assertTrue(response.startsWith("HTTP/1.1 303"))
        assertTrue(response.contains("Location: /secret/"))
        assertTrue(request(kernel, "/secret/").startsWith("HTTP/1.1 200"))
    }
    @Test fun headCodeQueriesNeverRedeemOrConsumeAttempts() {
        val kernel = LanShareKernel("secret", Backend(), LanShareManualAccess("123456"))
        for (query in listOf("code=123456", "code=wrong")) {
            val response = request(kernel, "/?$query", method = "HEAD")
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertFalse(response.contains("Location:"))
            assertFalse(response.contains("/secret/"))
            assertTrue(response.endsWith("\r\n\r\n"))
        }
        repeat(5) { assertTrue(request(kernel, "/?code=000000").startsWith("HTTP/1.1 403")) }
        assertTrue(request(kernel, "/?code=123456").startsWith("HTTP/1.1 429"))
    }
    @Test fun renewingCodeKeepsStrongLinkFilesAndClipboard() {
        val kernel = LanShareKernel("secret", Backend(), LanShareManualAccess("123456"))
        kernel.clipboardText = "retained snapshot"
        assertTrue(kernel.renewManualAccess("654321"))
        assertTrue(request(kernel, "/?code=123456").startsWith("HTTP/1.1 403"))
        val current = request(kernel, "/?code=654321")
        assertTrue(current.startsWith("HTTP/1.1 303"))
        assertTrue(current.contains("Location: /secret/"))
        assertTrue(request(kernel).endsWith("0123456789"))
        assertTrue(request(kernel, "/secret/?clipboard=1").contains("retained snapshot"))
    }
    @Test fun postWaitingForBodyCannotRedeemCodeThatWasRenewed() {
        val kernel = LanShareKernel("secret", Backend(), LanShareManualAccess("123456"))
        val body = "code=123456"
        val bytes = ("POST / HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n$body").byteInputStream()
        val input = object : java.io.FilterInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                // Headers use scalar reads. Renewal occurs once the pending request reads its body.
                kernel.renewManualAccess("654321")
                return super.read(buffer, offset, length)
            }
        }
        val output = ByteArrayOutputStream()
        kernel.handle(input, output)
        assertTrue(output.toString("UTF-8").startsWith("HTTP/1.1 403"))
        assertFalse(output.toString("UTF-8").contains("Location:"))
        assertTrue(request(kernel, "/?code=654321").startsWith("HTTP/1.1 303"))
    }
}
