package me.zhanghai.android.files.folderbridge

import android.os.Environment
import java.io.File
import java.util.Locale
import me.zhanghai.android.files.util.supportsExternalStorageManager

/**
 * 录音目录自动识别：按固定优先级探测各厂商系统录音应用在 primary 外置存储根目录下的
 * 默认存放目录，让 MagicOS/荣耀等系统的用户打开开关即可共享，无需手动选目录。
 *
 * 规则：
 * * 不区分厂商、对所有设备按同一顺序探测（候选名同时覆盖荣耀/华为的 Recordings、
 *   三星风格的 Sounds 与原生风格的 Music/Recordings、Audio/Recordings）。探测门槛是
 * “目录存在且其中确实含音频文件”，空目录或无关同名目录不算命中，误判代价可控。
 * * 通话录音是核心场景：Recordings/Call 子目录存在且非空时优先共享它。
 * * [detect] 仅在已授予所有文件访问权限（MANAGE_EXTERNAL_STORAGE）时探测，否则
 *   File API 读不到这些目录，直接返回 null。
 * * [probe] 只依赖注入的根目录与真实文件系统，便于在 JVM 上用假环境做单元测试。
 */
object FolderBridgeDefaults {

    /** 录音类音频的扩展名；用于判定候选目录是否真的装着录音，也供 File 后端列举文件。 */
    val AUDIO_EXTENSIONS = listOf(
        "m4a", "mp3", "amr", "aac", "ogg", "opus", "wav", "3gp"
    )

    /** 相对 primary 外置存储根目录的候选目录，按优先级排列。 */
    private val CANDIDATE_PATHS = listOf(
        "Recordings",
        "Sounds",
        "Music/Recordings",
        "Audio/Recordings"
    )

    /** 荣耀/华为的通话录音固定存放在 Recordings/Call；存在且非空时优先共享该子目录。 */
    private const val CALL_PATH = "Recordings/Call"

    /** 单个候选目录内递归寻找音频时最多走访的条目数，防止异常巨大的目录拖慢探测。 */
    private const val MAX_PROBE_ENTRIES = 500

    /**
     * 自动识别入口：仅在支持且已授予所有文件访问权限时探测系统外置存储根目录。
     * 命中返回录音目录本身，未命中（或无权限）返回 null。
     */
    fun detect(): File? {
        if (!Environment::class.supportsExternalStorageManager() ||
            !Environment.isExternalStorageManager()
        ) {
            return null
        }
        return probe(Environment.getExternalStorageDirectory())
    }

    /**
     * 在给定根目录下按优先级探测录音目录。
     * 优先级：Recordings/Call（存在且非空）> 依次含音频的候选目录 > 未命中（null）。
     */
    fun probe(root: File): File? {
        val call = File(root, CALL_PATH)
        if (call.isDirectory && !call.list().isNullOrEmpty()) {
            return call
        }
        return CANDIDATE_PATHS.asSequence()
            .map { File(root, it) }
            .firstOrNull { it.isDirectory && containsAudio(it) }
    }

    /** 递归查找音频文件；走访条目数超过上限即放弃（视作未命中）。 */
    private fun containsAudio(directory: File): Boolean =
        containsAudio(directory, intArrayOf(MAX_PROBE_ENTRIES))

    private fun containsAudio(directory: File, budget: IntArray): Boolean {
        val children = directory.listFiles() ?: return false
        for (child in children) {
            if (--budget[0] <= 0) {
                return false
            }
            // 跳过点开头的隐藏内容（比如我们自己维护的 .Trash）：只剩回收站的目录
            // 不算“装着录音”。
            if (child.name.startsWith(".")) {
                continue
            }
            if (child.isFile && isAudioName(child.name)) {
                return true
            }
            if (child.isDirectory && containsAudio(child, budget)) {
                return true
            }
        }
        return false
    }

    /** 按扩展名判断是否录音类音频；File API 查不到 MIME，只能看扩展名（大小写不敏感）。 */
    fun isAudioName(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in AUDIO_EXTENSIONS
    }
}
