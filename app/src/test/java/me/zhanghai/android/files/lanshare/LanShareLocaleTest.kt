package me.zhanghai.android.files.lanshare

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class LanShareLocaleTest {
    private val backend = object : LanShareKernel.Backend {
        override fun stat(path: List<String>) = when (path) {
            emptyList<String>() -> LanShareKernel.Entry("Shared files", true, 0, rootTitle = LanShareKernel.RootTitle.SHARED_FILES)
            listOf("照片.png") -> LanShareKernel.Entry("照片.png", false, 3)
            else -> null
        }
        override fun list(path: List<String>) = listOf(LanShareKernel.Entry("照片.png", false, 3))
        override fun open(path: List<String>, offset: Long) = "abc".byteInputStream().apply { skip(offset) }
    }
    private fun request(kernel: LanShareKernel, target: String, headers: String = ""): String {
        val output = ByteArrayOutputStream()
        kernel.handle("GET $target HTTP/1.1\r\n$headers\r\n".byteInputStream(), output)
        return output.toString("UTF-8")
    }

    @Test fun defaultIsEnglishEvenWithChineseBrowserLanguage() {
        val html = request(LanShareKernel("fixture", backend), "/fixture/", "Accept-Language: zh-CN\r\n")
        assertTrue(html.contains("<html lang=\"en\">"))
        assertTrue(html.contains("<h1>Shared files</h1>"))
        assertTrue(html.contains("Local network · Read only"))
        assertFalse(html.contains("共享期间可持续访问。"))
        assertTrue(html.contains("照片.png")) // A filename is never translated.
    }

    @Test fun languageChoicePersistsAcrossDirectoryClipboardAndPreviewRequests() {
        val kernel = LanShareKernel("fixture", backend).apply { clipboardText = "user </textarea><script> & text" }
        val selected = request(kernel, "/fixture/?lang=zh-CN")
        assertTrue(selected.contains("Set-Cookie: fileweave_language=zh-CN;"))
        assertTrue(selected.contains("SameSite=Strict; HttpOnly"))
        assertTrue(selected.contains("<h1>共享文件</h1>"))
        val cookie = "Cookie: fileweave_language=zh-CN\r\n"
        val clipboard = request(kernel, "/fixture/?clipboard=1", cookie)
        assertTrue(clipboard.contains("<h1>剪贴板</h1>"))
        assertTrue(clipboard.contains("user &lt;/textarea&gt;&lt;script&gt; &amp; text"))
        assertFalse(clipboard.contains("<script>"))
        val preview = request(kernel, "/fixture/%E7%85%A7%E7%89%87.png?preview=1", cookie)
        assertTrue(preview.contains("下载原图"))
        assertTrue(preview.contains("<h1>照片.png</h1>"))
        val english = request(kernel, "/fixture/?lang=en", cookie)
        assertTrue(english.contains("<h1>Shared files</h1>"))
        assertTrue(english.contains("Set-Cookie: fileweave_language=en;"))
    }

    @Test fun syntheticPhoneStorageTitleDoesNotFollowAndroidHostLanguage() {
        val storage = object : LanShareKernel.Backend by backend {
            override fun stat(path: List<String>): LanShareKernel.Entry? =
                backend.stat(path)?.let {
                    if (path.isEmpty()) it.copy(name = "手机存储", rootTitle = LanShareKernel.RootTitle.PHONE_STORAGE)
                    else it
                }
        }
        val kernel = LanShareKernel("fixture", storage)
        assertTrue(request(kernel, "/fixture/").contains("<h1>Phone storage</h1>"))
        assertTrue(request(kernel, "/fixture/?lang=zh-CN").contains("<h1>手机存储</h1>"))
    }

    @Test fun switchKeepsScopeAndViewWithoutCopyingAccessCode() {
        val target = "/fixture/folder/photo.png?preview=1&lang=en"
        assertEquals("/fixture/folder/photo.png?preview=1&lang=zh-CN",
            LanSharePage.languageTarget(target, LanSharePage.Language.CHINESE))
        assertEquals("/fixture/?clipboard=1&lang=en", LanSharePage.languageTarget(
            "/fixture/?clipboard=1&lang=zh-CN", LanSharePage.Language.ENGLISH))
        assertEquals("/?lang=zh-CN", LanSharePage.languageTarget(
            "/?code=123456", LanSharePage.Language.CHINESE))
    }

    @Test fun changingLoginLanguageDoesNotRedeemOrConsumeAccessAttempts() {
        val kernel = LanShareKernel("fixture", backend, LanShareManualAccess("123456"))
        repeat(10) {
            val response = request(kernel, "/?lang=zh-CN")
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("输入访问码"))
            assertFalse(response.contains("/fixture/"))
        }
        assertTrue(request(kernel, "/?code=123456&lang=zh-CN").startsWith("HTTP/1.1 303"))
    }

    @Test fun errorsAndInvalidCodesUseTheChosenLanguageWithoutRevealingScope() {
        val kernel = LanShareKernel("fixture", backend, LanShareManualAccess("123456"))
        val invalid = request(kernel, "/?code=000000&lang=zh-CN")
        assertTrue(invalid.startsWith("HTTP/1.1 403"))
        assertTrue(invalid.contains("访问码不正确。"))
        assertFalse(invalid.contains("Invalid access code."))
        assertFalse(invalid.contains("/fixture/"))
        assertTrue(request(kernel, "/wrong/?lang=zh-CN").endsWith("未找到"))
        kernel.active = false
        assertTrue(request(kernel, "/fixture/?lang=zh-CN").endsWith("分享已结束"))
    }

    @Test fun unknownAndInjectedLanguageValuesCannotBecomeMarkupOrHeaders() {
        val kernel = LanShareKernel("fixture", backend)
        val response = request(kernel, "/fixture/?lang=%22%3E%3Cscript%3E")
        assertTrue(response.contains("<html lang=\"en\">"))
        assertFalse(response.contains("Set-Cookie:"))
        assertFalse(response.contains("<script>"))
        assertTrue(response.contains("default-src 'none'"))
        val escaped = LanSharePage.render("<script> & title", "", currentTarget = "/fixture/?x=\"<img>")
        assertTrue(escaped.contains("&lt;script&gt; &amp; title"))
        assertFalse(escaped.contains("<img>"))
    }
}
