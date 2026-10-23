package me.zhanghai.android.files.folderbridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 自动识别的探测逻辑在 JVM 上验证：往注入的假存储根目录里摆放各厂商风格的录音目录
 * 布局，断言优先级与“目录存在且含音频才算命中”的规则。detect() 依赖的权限与
 * Environment 是设备行为，不在单测范围内（probe 已覆盖全部决策逻辑）。
 */
class FolderBridgeDefaultsTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    /** 在根目录下造一个 8 字节的文件（含父目录）。 */
    private fun seed(root: File, relativePath: String) {
        val file = File(root, relativePath)
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(8))
    }

    /** 探测结果换算回相对路径（统一分隔符），便于断言。 */
    private fun probedRelativePath(root: File, directory: File?): String =
        directory!!.relativeToOrSelf(root).path.replace(File.separatorChar, '/')

    // ---------------------------------------------------------------- 通话录音优先

    @Test
    fun callSubdirectoryWinsWhenPresentAndNonEmpty() {
        val root = temporaryFolder.newFolder()
        seed(root, "Recordings/note.m4a")
        seed(root, "Recordings/Call/call-1.m4a")
        assertEquals("Recordings/Call", probedRelativePath(root, FolderBridgeDefaults.probe(root)))
    }

    @Test
    fun emptyCallSubdirectoryIsIgnored() {
        val root = temporaryFolder.newFolder()
        seed(root, "Recordings/note.m4a")
        File(root, "Recordings/Call").mkdirs()
        assertEquals("Recordings", probedRelativePath(root, FolderBridgeDefaults.probe(root)))
    }

    @Test
    fun callSubdirectoryAloneIsEnough() {
        // Recordings 顶层没有别的录音，只有通话录音子目录，也应命中。
        val root = temporaryFolder.newFolder()
        seed(root, "Recordings/Call/call-1.m4a")
        assertEquals("Recordings/Call", probedRelativePath(root, FolderBridgeDefaults.probe(root)))
    }

    // ---------------------------------------------------------------- 候选顺序

    @Test
    fun candidatesAreProbedInPriorityOrder() {
        val root = temporaryFolder.newFolder()
        seed(root, "Sounds/s1.m4a")
        seed(root, "Music/Recordings/m1.mp3")
        seed(root, "Audio/Recordings/a1.amr")
        assertEquals("Sounds", probedRelativePath(root, FolderBridgeDefaults.probe(root)))
    }

    @Test
    fun missingCandidatesFallThroughToLaterOnes() {
        val root = temporaryFolder.newFolder()
        seed(root, "Audio/Recordings/a1.amr")
        assertEquals(
            "Audio/Recordings", probedRelativePath(root, FolderBridgeDefaults.probe(root))
        )
    }

    @Test
    fun candidateWithoutAudioDoesNotCount() {
        // 目录存在但只有非音频文件，不算命中，顺延到下一个候选。
        val root = temporaryFolder.newFolder()
        seed(root, "Recordings/note.txt")
        seed(root, "Sounds/s1.m4a")
        assertEquals("Sounds", probedRelativePath(root, FolderBridgeDefaults.probe(root)))
    }

    @Test
    fun audioInNestedSubdirectoryCounts() {
        // 录音实际放在嵌套子目录（如三星风格的 Sounds/CallRecord）。
        val root = temporaryFolder.newFolder()
        seed(root, "Sounds/CallRecord/s1.m4a")
        assertEquals("Sounds", probedRelativePath(root, FolderBridgeDefaults.probe(root)))
    }

    @Test
    fun emptyRootYieldsNull() {
        val root = temporaryFolder.newFolder()
        assertNull(FolderBridgeDefaults.probe(root))
    }

    @Test
    fun directoriesWithoutAudioYieldNull() {
        val root = temporaryFolder.newFolder()
        seed(root, "Recordings/note.txt")
        seed(root, "Music/other.bin")
        assertNull(FolderBridgeDefaults.probe(root))
    }

    @Test
    fun trashOnlyDirectoryDoesNotCount() {
        // 只剩 .Trash（回收站）内容的候选不算“装着录音”。
        val root = temporaryFolder.newFolder()
        seed(root, "Recordings/.Trash/1690000000000/old.m4a")
        assertNull(FolderBridgeDefaults.probe(root))
    }

    // ---------------------------------------------------------------- 音频扩展名

    @Test
    fun everyRecordingExtensionCountsAsAudio() {
        for ((index, extension) in FolderBridgeDefaults.AUDIO_EXTENSIONS.withIndex()) {
            val root = temporaryFolder.newFolder("root$index")
            seed(root, "Recordings/rec.$extension")
            assertEquals(
                extension, "Recordings",
                probedRelativePath(root, FolderBridgeDefaults.probe(root))
            )
        }
    }

    @Test
    fun extensionMatchIsCaseInsensitive() {
        val root = temporaryFolder.newFolder()
        seed(root, "Recordings/REC.M4A")
        assertEquals("Recordings", probedRelativePath(root, FolderBridgeDefaults.probe(root)))
    }
}
