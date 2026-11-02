package me.zhanghai.android.files.bridge

import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import me.zhanghai.android.files.design.*
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
        composeScreen {
            FeaturePage(stringResource(R.string.fw_clipboard_title), { finish() }, busy = busy,
                actions = listOf(PageAction(stringResource(R.string.fw_redesign_all_history)) {
                    startActivity(Intent(this@ClipboardActivity, TransfersActivity::class.java))
                })) {
                item { StatusLine(when {
                    state.connected -> getString(R.string.fw_bridge_connection_connected, state.macName.orEmpty())
                    state.phase == BridgeConnectionService.Phase.PAIRING -> getString(R.string.fw_bridge_connection_pairing)
                    state.phase == BridgeConnectionService.Phase.RETRYING -> getString(R.string.fw_bridge_connection_retrying)
                    else -> getString(R.string.fw_bridge_connection_finding)
                }) }
                item { PrimaryAction(stringResource(R.string.fw_bridge_send_clipboard), state.connected && !busy) { sendClipboard() } }
                item { SectionTitle(stringResource(R.string.fw_redesign_clipboard_preview)) }
                val received = state.macClipboard?.takeIf { it.isNotEmpty() }
                if (received == null) item {
                    EmptyContent(stringResource(R.string.fw_redesign_clipboard_empty), stringResource(R.string.fw_redesign_clipboard_hint), R.drawable.clipboard_icon_white_24dp)
                } else {
                    item { SelectionContainer { Text(received, Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyLarge) } }
                    item { ContentRow(stringResource(R.string.fw_clipboard_copy_mac), icon = R.drawable.clipboard_icon_white_24dp, enabled = !busy, onClick = { copyToPhone(received) }) }
                }
                item { ContentRow(stringResource(R.string.fw_clipboard_refresh), icon = R.drawable.computer_icon_white_24dp, enabled = state.connected && !busy, onClick = { fetchMacClipboard() }) }
            }
        }
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
                copyToPhone(text)
            }
            ""
        }
    }

    private fun copyToPhone(text: String) {
        AutomaticClipboard.receive(this, text)
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
        busy = true; render()
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
