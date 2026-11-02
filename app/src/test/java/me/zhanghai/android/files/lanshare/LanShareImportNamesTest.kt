package me.zhanghai.android.files.lanshare

import org.junit.Assert.*
import org.junit.Test

class LanShareImportNamesTest {
    @Test fun extensionlessImagesRemainPreviewable() {
        assertEquals("shared-file-1.png", LanShareImportNames.choose(null, "image/png", 1, hashSetOf()))
        assertEquals("photo.bin.jpg", LanShareImportNames.choose("photo.bin", "image/jpeg", 1, hashSetOf()))
        assertEquals("photo.JPG", LanShareImportNames.choose("photo.JPG", "image/jpeg", 1, hashSetOf()))
    }
    @Test fun labelsCannotTraverseOrCollide() {
        val used = hashSetOf<String>()
        assertEquals(".._escape_.txt", LanShareImportNames.choose("../escape\\.txt", null, 1, used))
        assertEquals("photo.png", LanShareImportNames.choose("photo.png", null, 2, used))
        assertEquals("photo (2).png", LanShareImportNames.choose("photo.png", null, 3, used))
    }
    @Test fun unicodeByteLimitPreservesRasterExtension() {
        val name = LanShareImportNames.choose("测试😀".repeat(100), "image/webp", 1, hashSetOf())
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 180)
        assertTrue(name.endsWith(".webp"))
        assertFalse(name.dropLast(5).last().isHighSurrogate())
    }
}
