package me.zhanghai.android.files.bridge

import android.content.Context
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.asFileSize
import org.json.JSONArray
import org.json.JSONObject

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
    private const val FILE_NAME = "history.json"
    private const val MAX_ENTRIES = 100
    private const val PREVIEW_LENGTH = 80

    /** kind：文件传输 / 剪贴板；outgoing：true=发出，false=收到（文件下载也归为收到）。 */
    enum class Kind { FILE, CLIPBOARD }

    data class Entry(
        val kind: Kind,
        val outgoing: Boolean,
        val name: String? = null, // 仅文件事件：文件名
        val size: Long? = null, // 仅文件事件：字节数
        val preview: String? = null, // 仅剪贴板事件：前 80 字符预览
        val time: Long // System.currentTimeMillis()
    ) {
        companion object {
            fun file(outgoing: Boolean, name: String, size: Long): Entry =
                Entry(Kind.FILE, outgoing, name = name, size = size, time = System.currentTimeMillis())

            /** 预览截断只在这里发生，保证任何调用方都存不进完整正文。 */
            fun clipboard(outgoing: Boolean, text: String): Entry =
                Entry(Kind.CLIPBOARD, outgoing, preview = text.take(PREVIEW_LENGTH), time = System.currentTimeMillis())
        }
    }

    // 进程内缓存：连接页每次状态刷新都会重新渲染并读取历史，缓存后不必反复读盘；
    // 写入方（两个页面）都在本进程内，缓存不会与文件失真。首次读取是一次小文件 I/O，
    // 与页面内已有的 BridgeStore.read 同一量级。
    private var cache: MutableList<Entry>? = null
    private val lock = Any()

    /** 追加一条记录（最新在前）。历史写盘尽力而为：失败不影响传输结果本身。 */
    fun add(context: Context, entry: Entry) {
        synchronized(lock) {
            val all = ArrayList<Entry>()
            all.add(entry)
            all.addAll(load(context))
            while (all.size > MAX_ENTRIES) all.removeAt(all.size - 1)
            try {
                save(context, all)
            } catch (_: Exception) {
                // 历史属附属信息，写失败不向用户报错；缓存仍更新以保持本会话内一致。
            }
            cache = all
        }
    }

    /** 最新在前。返回副本，调用方改动不影响存储。 */
    fun list(context: Context): List<Entry> = synchronized(lock) { ArrayList(load(context)) }

    fun clear(context: Context) {
        synchronized(lock) {
            file(context).delete()
            cache = ArrayList()
        }
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE_NAME)

    /** 读取并填充缓存；文件缺失或损坏时按空历史处理，下一次写入即自愈。 */
    private fun load(context: Context): MutableList<Entry> {
        cache?.let { return ArrayList(it) }
        val entries = try {
            val array = JSONArray(file(context).readText())
            (0 until array.length()).mapNotNull { index -> parse(array.getJSONObject(index)) }
                .toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
        cache = entries
        return ArrayList(entries)
    }

    /** 原子写：先写临时文件再改名，参考 BridgeStore 的做法，避免中断留下半个文件。 */
    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(json(it)) }
        val target = file(context)
        val temp = File(context.noBackupFilesDir, "$FILE_NAME.tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Unable to save history")
        }
    }

    private fun parse(json: JSONObject): Entry? = try {
        Entry(
            kind = if (json.getString("kind") == "file") Kind.FILE else Kind.CLIPBOARD,
            outgoing = json.getBoolean("out"),
            name = json.optString("name").takeIf { it.isNotEmpty() },
            size = if (json.has("size")) json.getLong("size") else null,
            preview = json.optString("preview").takeIf { it.isNotEmpty() },
            time = json.getLong("time")
        )
    } catch (_: Exception) {
        null
    }

    private fun json(entry: Entry): JSONObject = JSONObject()
        .put("kind", if (entry.kind == Kind.FILE) "file" else "clipboard")
        .put("out", entry.outgoing)
        .apply {
            entry.name?.let { put("name", it) }
            entry.size?.let { put("size", it) }
            entry.preview?.let { put("preview", it) }
        }
        .put("time", entry.time)
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
