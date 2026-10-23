package me.zhanghai.android.files.folderbridge

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * File 后端直接对临时目录在 JVM 上验证：遍历、读取与 .Trash 回收站语义与 SAF 版
 * 对齐，重点是路径安全校验——词法校验与规范化后的“仍在共享根内”双保险必须挡住
 * ../ 逃逸和指向根外的符号链接。
 */
class FolderBridgeFileBackendTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var backend: FolderBridgeFileBackend

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("Recordings")
        backend = FolderBridgeFileBackend(root)
    }

    private fun seed(relativePath: String, bytes: ByteArray = ByteArray(8) { it.toByte() }): File {
        val file = File(root, relativePath)
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    /** 在共享根之外造一个音频文件，用于逃逸测试。 */
    private fun seedOutside(): File {
        val file = File(root.parentFile, "outside.m4a")
        file.writeBytes(ByteArray(4))
        return file
    }

    // ---------------------------------------------------------------- 遍历

    @Test
    fun rootNameIsTheSharedDirectoriesName() {
        assertEquals("Recordings", backend.rootName)
    }

    @Test
    fun listFilesWalksDirectoriesAndKeepsOnlyAudio() {
        seed("a.m4a")
        seed("Call/b.amr")
        seed("note.txt")
        seed("UPPER.WAV")
        val items = backend.listFiles()
        assertEquals(setOf("a.m4a", "Call/b.amr", "UPPER.WAV"), items.map { it.path }.toSet())
        val item = items.first { it.path == "Call/b.amr" }
        // 活动文件的 id 就是相对路径。
        assertEquals("Call/b.amr", item.id)
        assertEquals(8L, item.size)
        assertTrue(item.mtime > 0)
        assertNull(item.trashPath)
        assertNull(item.movedAt)
    }

    @Test
    fun listFilesExposesTrashEntriesWithOriginalPaths() {
        seed("a.m4a")
        val trashPath = backend.trash("a.m4a", "a.m4a")
        assertTrue(trashPath.matches(Regex("\\.Trash/[0-9]+/a\\.m4a")))
        assertFalse(File(root, "a.m4a").exists())
        val items = backend.listFiles()
        assertEquals(1, items.size)
        val item = items.first()
        // 身份是原相对路径；id 与 trashPath 都从 .Trash 起算。
        assertEquals("a.m4a", item.path)
        assertEquals(trashPath, item.trashPath)
        assertEquals(trashPath, item.id)
        assertTrue("movedAt 应为毫秒时间戳", (item.movedAt ?: 0L) > 0L)
    }

    // ---------------------------------------------------------------- 读取与路径安全

    @Test
    fun openFileStreamsExactBytes() {
        val bytes = ByteArray(100) { it.toByte() }
        seed("Call/通话-1.m4a", bytes)
        val opened = backend.openFile("Call/通话-1.m4a")
        assertEquals(100L, opened.size)
        opened.input.use { input ->
            assertArrayEquals(bytes, input.readBytes())
        }
    }

    @Test
    fun openFileRejectsTraversalAbsoluteAndMissingPaths() {
        seed("a.m4a")
        seedOutside()
        for (path in listOf(
            "../outside.m4a",       // 直接逃逸到根外
            "../Recordings/a.m4a",  // 绕一圈回到根内也不行
            "a/../a.m4a",           // 中间含 .. 段
            "/a.m4a",               // 绝对路径
            "a.m4a/",               // 以分隔符结尾
            "",                     // 空路径
            "missing.m4a"           // 不存在
        )) {
            try {
                backend.openFile(path)
                fail("应当拒绝路径: $path")
            } catch (_: FolderBridgeNotFound) {
                // 预期：统一按“未找到”处理
            }
        }
    }

    @Test
    fun openFileRejectsSymlinkPointingOutsideRoot() {
        val outside = seedOutside()
        val link = File(root, "leak.m4a")
        val created = try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        } catch (_: Exception) {
            false
        }
        assumeTrue("环境不支持创建符号链接，跳过", created)
        // 词法上无害，但规范化后落在根外：必须拒绝。
        try {
            backend.openFile("leak.m4a")
            fail("指向根外的符号链接必须被拒绝")
        } catch (_: FolderBridgeNotFound) { }
    }

    @Test
    fun symlinkedDirectoryOutsideRootIsNotWalked() {
        val outsideDir = temporaryFolder.newFolder("outside")
        File(outsideDir, "x.m4a").writeBytes(ByteArray(8))
        val link = File(root, "link")
        val created = try {
            Files.createSymbolicLink(link.toPath(), outsideDir.toPath())
            true
        } catch (_: Exception) {
            false
        }
        assumeTrue("环境不支持创建符号链接，跳过", created)
        assertTrue(backend.listFiles().isEmpty())
    }

    // ---------------------------------------------------------------- 回收站

    @Test
    fun trashMovesFileIntoTimestampedBatchKeepingHierarchy() {
        val original = seed("Call/通话-1.m4a", ByteArray(100) { it.toByte() })
        val trashPath = backend.trash("Call/通话-1.m4a", "Call/通话-1.m4a")
        assertTrue(trashPath.matches(Regex("\\.Trash/[0-9]+/Call/通话-1\\.m4a")))
        assertFalse(original.exists())
        // 批内保留原子目录层级，Mac 靠它识别条目。
        val trashed = File(root, trashPath)
        assertTrue(trashed.isFile)
        assertArrayEquals(ByteArray(100) { it.toByte() }, trashed.readBytes())
    }

    @Test
    fun restorePutsFileBackWithIdenticalBytes() {
        val bytes = ByteArray(100) { it.toByte() }
        seed("Call/b.m4a", bytes)
        val trashPath = backend.trash("Call/b.m4a", "Call/b.m4a")
        backend.restore(trashPath, "Call/b.m4a", "Call/b.m4a")
        assertArrayEquals(bytes, File(root, "Call/b.m4a").readBytes())
        assertFalse(File(root, trashPath).exists())
    }

    @Test
    fun restoreRefusesToOverwriteExistingFileAndKeepsTheOriginal() {
        seed("a.m4a", ByteArray(16) { 1 })
        val trashPath = backend.trash("a.m4a", "a.m4a")
        // 原位置出现了新文件：拒绝覆盖，回收站里的原件必须保留。
        seed("a.m4a", ByteArray(16) { 2 })
        try {
            backend.restore(trashPath, "a.m4a", "a.m4a")
            fail("原位置已有文件时应拒绝还原")
        } catch (_: IOException) { }
        assertTrue(File(root, trashPath).isFile)
    }

    @Test
    fun trashAndRestoreRejectTraversalTargets() {
        seed("a.m4a")
        val outside = seedOutside()
        try {
            backend.trash("../outside.m4a", "../outside.m4a")
            fail("越界路径必须被拒绝")
        } catch (_: Exception) { }
        try {
            backend.restore("a.m4a", "a.m4a", "../outside.m4a")
            fail("越界还原目标必须被拒绝")
        } catch (_: Exception) { }
        // 两个文件都原封不动。
        assertTrue(outside.isFile)
        assertTrue(File(root, "a.m4a").isFile)
        assertFalse(File(root, ".Trash").exists())
    }
}
