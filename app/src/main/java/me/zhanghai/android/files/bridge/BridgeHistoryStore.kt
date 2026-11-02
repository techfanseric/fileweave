package me.zhanghai.android.files.bridge

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.asFileSize

/**
 * 手机与 Mac 互通历史的本地记录，供连接页面展示“最近做过什么”。
 *
 * 隐私取舍：只保存文件名和剪贴板前 80 个字符的预览，不保存完整内容——
 * 历史的价值在于回看“发生过什么”，完整内容仍以源文件和系统剪贴板为准；
 * 若把已删除或敏感的正文再留一份明文，就与用户“删掉即忘掉”的预期相悖。
 * 文件位于应用私有目录 noBackupFilesDir（其他应用不可读，也不进入云备份），
 * 与配对凭据 BridgeStore 同级；即使换机恢复备份，历史也不会跟着走。
 *
 * 只保留最近 100 条：历史是辅助回看的窗口而非档案，有界上限既满足浏览需求，
 * 也避免文件随使用无限膨胀。超出上限时裁掉最旧的记录。
 */
object BridgeHistoryStore {
    val changes = androidx.lifecycle.MutableLiveData(0L)

    private const val FILE_NAME = "history.json"
    private const val PREVIEW_LENGTH = 80

    /** kind：文件传输 / 剪贴板；outgoing：true=发出，false=收到（文件下载也归为收到）。 */
    enum class Kind { FILE, CLIPBOARD }

    data class Entry(
        val kind: Kind,
        val outgoing: Boolean,
        val name: String? = null, // 仅文件事件：文件名
        val size: Long? = null, // 仅文件事件：字节数
        val preview: String? = null, // 仅剪贴板事件：前 80 字符预览
        val time: Long, // System.currentTimeMillis()
        val id: String = java.util.UUID.randomUUID().toString()
    ) {
        companion object {
            fun file(outgoing: Boolean, name: String, size: Long): Entry =
                Entry(Kind.FILE, outgoing, name = name, size = size, time = System.currentTimeMillis())

            /** 预览截断只在这里发生，保证任何调用方都存不进完整正文。 */
            fun clipboard(outgoing: Boolean, text: String): Entry =
                Entry(Kind.CLIPBOARD, outgoing, preview = text.take(PREVIEW_LENGTH), time = System.currentTimeMillis())
        }
    }

    private val repositories = java.util.concurrent.ConcurrentHashMap<String, BridgeHistoryRepository>()
    private fun repository(context: Context): BridgeHistoryRepository {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        return repositories.getOrPut(file.absolutePath) { BridgeHistoryRepository(file) }
    }

    /** Logging is best effort; a history failure never turns a successful transfer into a failure. */
    fun add(context: Context, entry: Entry) {
        try { repository(context).add(entry) }
        catch (_: Exception) { return }
        changes.postValue(System.nanoTime())
    }

    fun list(context: Context): List<Entry> = repository(context).list()

    /** Deletion failures stay visible: never hide a row that could return on restart. */
    fun remove(context: Context, id: String): Boolean {
        val removed = repository(context).remove(id)
        if (removed) changes.postValue(System.nanoTime())
        return removed
    }

    fun clear(context: Context) {
        repository(context).clear()
        changes.postValue(System.nanoTime())
    }

}

// 历史条目的展示文案，供两个连接页面共用；时间格式与文件大小沿用仓库现有工具。
// SimpleDateFormat 非线程安全，此格式化仅在主线程渲染时调用。
private val bridgeHistoryTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val bridgeHistoryWhitespace = Regex("\\s+")

internal fun BridgeHistoryStore.Entry.formatForDisplay(context: Context): String {
    val time = bridgeHistoryTimeFormat.format(Date(this.time))
    return if (kind == BridgeHistoryStore.Kind.FILE) {
        context.getString(
            if (outgoing) R.string.fw_bridge_history_file_sent else R.string.fw_bridge_history_file_downloaded,
            name.orEmpty(), (size ?: 0L).asFileSize().formatHumanReadable(context), time
        )
    } else {
        // 预览里的换行折叠为空格，保证每条历史占一行。
        val preview = preview.orEmpty().replace(bridgeHistoryWhitespace, " ")
        context.getString(
            if (outgoing) R.string.fw_bridge_history_clipboard_sent else R.string.fw_bridge_history_clipboard_received,
            preview, time
        )
    }
}
