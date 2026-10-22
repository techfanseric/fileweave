package me.zhanghai.android.files.folderbridge

import org.junit.Assert.*
import org.junit.Test

/**
 * Path relativization and .Trash inference, kept as pure logic so it can be locked down on the
 * JVM instead of being discovered wrong on a real device.
 */
class FolderBridgePathsTest {
    @Test
    fun audioIsDetectedByMimeOrExtension() {
        assertTrue(FolderBridgePaths.isAudio("a.m4a", null))
        assertTrue(FolderBridgePaths.isAudio("a.M4A", null))
        assertTrue(FolderBridgePaths.isAudio("b.txt", "audio/mp4"))
        assertTrue(FolderBridgePaths.isAudio("c.amr", null))
        assertFalse(FolderBridgePaths.isAudio("b.txt", null))
        assertFalse(FolderBridgePaths.isAudio(null, "text/plain"))
        assertFalse(FolderBridgePaths.isAudio("x.m4a.bak", null))
    }

    @Test
    fun safeRelativePathsAreValidated() {
        assertTrue(FolderBridgePaths.isSafeRelativePath("a.m4a"))
        assertTrue(FolderBridgePaths.isSafeRelativePath("CallRecord/通话-1.m4a"))
        assertTrue(FolderBridgePaths.isSafeRelativePath("a/b/c.wav"))
        assertFalse(FolderBridgePaths.isSafeRelativePath(""))
        assertFalse(FolderBridgePaths.isSafeRelativePath("/sdcard/Sounds/a.m4a"))
        assertFalse(FolderBridgePaths.isSafeRelativePath("a/../b.m4a"))
        assertFalse(FolderBridgePaths.isSafeRelativePath("./a.m4a"))
        assertFalse(FolderBridgePaths.isSafeRelativePath("a//b.m4a"))
        assertFalse(FolderBridgePaths.isSafeRelativePath("a/"))
        assertFalse(FolderBridgePaths.isSafeRelativePath("a\\b.m4a"))
        assertFalse(FolderBridgePaths.isSafeRelativePath(".."))
    }

    @Test
    fun trashPathInfersBackOriginalPath() {
        assertEquals(
            "CallRecord/通话-1.m4a",
            FolderBridgePaths.originalPathFromTrash(".Trash/1729598400123/CallRecord/通话-1.m4a")
        )
        assertEquals("a.m4a", FolderBridgePaths.originalPathFromTrash(".Trash/7/a.m4a"))
        assertNull(FolderBridgePaths.originalPathFromTrash(".Trash/7"))
        assertNull(FolderBridgePaths.originalPathFromTrash(".Trash/7/"))
        assertNull(FolderBridgePaths.originalPathFromTrash(".Trash//a.m4a"))
        assertNull(FolderBridgePaths.originalPathFromTrash("Trash/7/a.m4a"))
        assertNull(FolderBridgePaths.originalPathFromTrash(".Trash/7/../a.m4a"))
    }

    @Test
    fun batchStampsParseWithZeroFallback() {
        assertEquals(1729598400123L, FolderBridgePaths.parseStamp("1729598400123"))
        assertEquals(0L, FolderBridgePaths.parseStamp("not-a-stamp"))
        assertEquals(0L, FolderBridgePaths.parseStamp(""))
    }

    @Test
    fun rootNameTakesLastSegment() {
        assertEquals("Sounds", FolderBridgePaths.rootNameOf("primary:Sounds"))
        assertEquals("Recordings", FolderBridgePaths.rootNameOf("primary:Sounds/Recordings"))
        assertEquals("Sounds", FolderBridgePaths.rootNameOf("/storage/emulated/0/Sounds"))
        assertEquals("x", FolderBridgePaths.rootNameOf("x"))
    }
}
