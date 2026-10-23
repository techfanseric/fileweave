package me.zhanghai.android.files.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.features.FeatureActivity
import me.zhanghai.android.files.util.valueCompat

/**
 * 剪贴板互通入口。连接本身由 BridgeConnectionService 在后台常驻维护，页面不再堆叠
 * 连接状态与操作，而是聚焦两件事：常用的剪贴板动作（紧凑排在上方），以及与 Mac 的
 * 互通历史（主体）。系统只允许有焦点的应用读取剪贴板，所以发送方向仍需在此页触发。
 */
class ClipboardActivity : FeatureActivity() {
    private var busy = false
    private var client: BridgeClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 剪贴板内容不得出现在截图与最近任务预览中。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        render()
        BridgeConnectionService.stateLiveData.observe(this) { render() }
    }

    private fun render() {
        val state = BridgeConnectionService.stateLiveData.valueCompat
        screen(getString(R.string.fw_clipboard_title))
        // 顶部只保留一行紧凑状态：连接是后台默认能力，无需占据大段篇幅。
        text(when {
            state.connected -> getString(R.string.fw_bridge_connection_connected, state.macName.orEmpty())
            state.phase == BridgeConnectionService.Phase.PAIRING -> getString(R.string.fw_bridge_connection_pairing)
            else -> getString(R.string.fw_bridge_history_status_disconnected)
        })
        // 常用动作紧凑排列在状态行与历史列表之间。
        button(getString(R.string.fw_bridge_send_clipboard)) { sendClipboard() }
        button(getString(R.string.fw_clipboard_refresh)) { fetchMacClipboard() }
        state.macClipboard?.takeIf { it.isNotEmpty() }?.let { mac ->
            button(getString(R.string.fw_clipboard_copy_mac)) { copyToPhone(mac) }
        }
        renderHistory()
    }

    /** 历史为主体：倒序逐行展示，空态给出引导；非空时在底部提供清空入口。 */
    private fun renderHistory() {
        val history = BridgeHistoryStore.list(this)
        text(getString(R.string.fw_bridge_history_title))
        if (history.isEmpty()) {
            text(getString(R.string.fw_bridge_history_empty))
            return
        }
        history.forEach { entry -> text(entry.formatForDisplay(this)) }
        button(getString(R.string.fw_bridge_history_clear)) { confirmClear() }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fw_bridge_history_clear)
            .setMessage(R.string.fw_bridge_history_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { BridgeHistoryStore.clear(this@ClipboardActivity) }
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, R.string.fw_bridge_empty_clipboard, Toast.LENGTH_SHORT).show()
            return
        }
        work { bridge ->
            bridge.sendClipboard(text)
            // 传输成功才落历史；work 的 IO 上下文顺带承担了写盘，避免阻塞主线程。
            BridgeHistoryStore.add(this@ClipboardActivity, BridgeHistoryStore.Entry.clipboard(outgoing = true, text = text))
            getString(R.string.fw_bridge_clipboard_sent)
        }
    }

    private fun fetchMacClipboard() {
        work { bridge ->
            val text = bridge.json("clipboard").getString("text")
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@ClipboardActivity)
                    .setTitle(R.string.fw_bridge_mac_clipboard)
                    .setMessage(text.take(4000))
                    .setPositiveButton(R.string.fw_bridge_copy) { _, _ -> copyToPhone(text) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            ""
        }
    }

    private fun copyToPhone(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("FileWeave", text))
        // 复制发生在主线程，历史写盘放后台执行；预览截断由 Entry.clipboard 统一处理。
        lifecycleScope.launch(Dispatchers.IO) {
            BridgeHistoryStore.add(this@ClipboardActivity, BridgeHistoryStore.Entry.clipboard(outgoing = false, text = text))
        }
        Toast.makeText(this, R.string.fw_clipboard_copied, Toast.LENGTH_SHORT).show()
    }

    private fun work(action: suspend (BridgeClient) -> String) {
        val config = BridgeStore.read(this) ?: run {
            Toast.makeText(this, R.string.fw_clipboard_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val bridge = client ?: BridgeClient(config).also { client = it }
            try {
                val message = withContext(Dispatchers.IO) { action(bridge) }
                if (message.isNotBlank()) {
                    Toast.makeText(this@ClipboardActivity, message, Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                client?.close(); client = null
                Toast.makeText(this@ClipboardActivity, R.string.fw_bridge_failure, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                // 无论成败都重绘，让刚写入的历史立刻可见。
                render()
            }
        }
    }

    override fun onDestroy() {
        client?.close()
        super.onDestroy()
    }
}
