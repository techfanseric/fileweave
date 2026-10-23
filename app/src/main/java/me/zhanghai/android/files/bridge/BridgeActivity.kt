package me.zhanghai.android.files.bridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.features.FeatureActivity
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.viewer.video.BridgeVideoActivity

/**
 * 与配对 Mac 的文件互通页。发现、零触配对、列表刷新与剪贴板同步都在
 * BridgeConnectionService 后台完成，本页只负责用户触发的传输：发送文件、
 * 播放/下载 Mac 共享的文件，并以互通历史为主体，回看最近做过什么。
 * 剪贴板操作集中在 ClipboardActivity，这里不再重复堆叠。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BridgeActivity : FeatureActivity() {
    private var busy = false
    private var client: BridgeClient? = null
    private var pendingPath: java8.nio.file.Path? = null
    private var downloading: String? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work { bridge ->
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "received-file"
            val counting = CountingInputStream(contentResolver.openInputStream(uri) ?: throw IOException("Source unavailable"))
            counting.use { bridge.upload(name, it) }
            // 上传成功后记录历史：只存文件名与实际传输字节数，不涉及内容。
            BridgeHistoryStore.add(this@BridgeActivity, BridgeHistoryStore.Entry.file(outgoing = true, name = name, size = counting.count))
            getString(R.string.fw_bridge_sent)
        }
    }
    private val destination = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val name = downloading
        if (uri != null && name != null) work { bridge ->
            val counting = try {
                val output = CountingOutputStream(contentResolver.openOutputStream(uri) ?: throw IOException("Destination unavailable"))
                output.use { bridge.download(name, it) }
                output
            } catch (e: Exception) {
                // 不留下看似完整的半个下载文件。
                try { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) } catch (_: Exception) { }
                throw e
            }
            BridgeHistoryStore.add(this@BridgeActivity, BridgeHistoryStore.Entry.file(outgoing = false, name = name, size = counting.count))
            getString(R.string.fw_bridge_downloaded)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 剪贴板内容与配对凭据不得出现在截图中。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        pendingPath = intent.extraPath
        downloading = savedInstanceState?.getString("downloading")
        render()
        BridgeConnectionService.stateLiveData.observe(this) { render() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("downloading", downloading); super.onSaveInstanceState(outState)
    }

    private fun render() {
        val state = BridgeConnectionService.stateLiveData.valueCompat
        screen(getString(R.string.fw_bridge_title))
        // 顶部只保留一行紧凑状态：连接由后台维护，页面聚焦历史与文件操作。
        text(when {
            state.connected -> getString(R.string.fw_bridge_connection_connected, state.macName.orEmpty())
            state.phase == BridgeConnectionService.Phase.PAIRING -> getString(R.string.fw_bridge_connection_pairing)
            else -> getString(R.string.fw_bridge_history_status_disconnected)
        })
        button(getString(R.string.fw_bridge_send_file)) { picker.launch(arrayOf("*/*")) }
        pendingPath?.let { path ->
            button(getString(R.string.fw_bridge_send_selected, path.fileName)) {
                work { bridge ->
                    val counting = CountingInputStream(path.newInputStream())
                    counting.use { bridge.upload(path.fileName.toString(), it) }
                    pendingPath = null
                    BridgeHistoryStore.add(this@BridgeActivity, BridgeHistoryStore.Entry.file(outgoing = true, name = path.fileName.toString(), size = counting.count))
                    getString(R.string.fw_bridge_sent)
                }
            }
        }
        // 互通历史为主体：倒序逐行展示，空态给出引导。
        val history = BridgeHistoryStore.list(this)
        text(getString(R.string.fw_bridge_history_title))
        if (history.isEmpty()) {
            text(getString(R.string.fw_bridge_history_empty))
        } else {
            history.forEach { entry -> text(entry.formatForDisplay(this)) }
        }
        // Mac 共享的文件列表放在历史下方，用独立标题与历史区分。
        text(getString(R.string.fw_bridge_history_shared_title))
        if (state.files.isEmpty()) text(getString(R.string.fw_bridge_shared_empty))
        state.files.forEach { (name, _) ->
            button(name) {
                val video = name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "3gp", "ts")
                val actions = if (video) arrayOf(getString(R.string.fw_bridge_play), getString(R.string.fw_bridge_download)) else arrayOf(getString(R.string.fw_bridge_download))
                MaterialAlertDialogBuilder(this).setTitle(name).setItems(actions) { _, index ->
                    if (video && index == 0) {
                        val config = BridgeStore.read(this) ?: return@setItems
                        startActivity(Intent(this, BridgeVideoActivity::class.java)
                            .setDataAndType(Uri.parse(BridgeClient(config).url("file", name).toString()),
                                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.').lowercase()) ?: "video/*"))
                    } else { downloading = name; destination.launch(name) }
                }.show()
            }
        }
        // 清空入口固定在页面底部，仅在确有历史时展示。
        if (history.isNotEmpty()) button(getString(R.string.fw_bridge_history_clear)) { confirmClear() }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fw_bridge_history_clear)
            .setMessage(R.string.fw_bridge_history_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { BridgeHistoryStore.clear(this@BridgeActivity) }
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun work(action: suspend (BridgeClient) -> String) {
        val config = BridgeStore.read(this) ?: run {
            Toast.makeText(this, R.string.fw_bridge_failure, Toast.LENGTH_SHORT).show()
            return
        }
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val bridge = client ?: BridgeClient(config).also { client = it }
            try {
                val message = withContext(Dispatchers.IO) { action(bridge) }
                if (message.isNotBlank()) Toast.makeText(this@BridgeActivity, message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                client?.close(); client = null
                MaterialAlertDialogBuilder(this@BridgeActivity)
                    .setTitle(R.string.fw_operation_failed).setMessage(R.string.fw_bridge_failure)
                    .setPositiveButton(android.R.string.ok, null).show()
            } finally { busy = false; render() }
        }
    }

    override fun onDestroy() { client?.close(); super.onDestroy() }

    /**
     * 统计实际传输的字节数用于写入历史（比事先查询文件大小更准，SAF 流也适用）。
     * 只透传读写与关闭，不改变数据本身；在 work 的 IO 线程中使用。
     */
    private class CountingInputStream(private val source: InputStream) : InputStream() {
        var count = 0L
        override fun read(): Int = source.read().also { if (it >= 0) count++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            source.read(buffer, offset, length).also { if (it > 0) count += it }
        override fun close() { source.close() }
    }

    private class CountingOutputStream(private val target: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(oneByte: Int) { target.write(oneByte); count++ }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { target.write(buffer, offset, length); count += length }
        override fun flush() { target.flush() }
        override fun close() { target.close() }
    }
}
