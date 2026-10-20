package me.zhanghai.android.files.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.features.FeatureActivity
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.viewer.video.BridgeVideoActivity

class BridgeActivity : FeatureActivity() {
    private var busy = false
    private var config: BridgeConfig? = null
    private var client: BridgeClient? = null
    private var sharedFiles = emptyList<Pair<String, Long>>()
    private var pendingPath: java8.nio.file.Path? = null
    private var downloading: String? = null
    private var pairingText: String? = null
    private var status: String? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work { bridge ->
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "received-file"
            contentResolver.openInputStream(uri)?.use { bridge.upload(name, it) } ?: throw IOException("Source unavailable")
            getString(R.string.fw_bridge_sent)
        }
    }
    private val destination = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val name = downloading
        if (uri != null && name != null) work { bridge ->
            try {
                contentResolver.openOutputStream(uri)?.use { bridge.download(name, it) } ?: throw IOException("Destination unavailable")
            } catch (e: Exception) {
                // Do not leave an apparently complete partial download.
                try { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) } catch (_: Exception) { }
                throw e
            }
            getString(R.string.fw_bridge_downloaded)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pairing credentials must not appear in screenshots or recents previews.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        config = BridgeStore.read(this)
        pendingPath = intent.extraPath
        pairingText = intent.data?.takeIf { it.scheme == "fileweave" && it.host == "pair" }?.toString()
        downloading = savedInstanceState?.getString("downloading")
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("downloading", downloading); super.onSaveInstanceState(outState) }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun render() {
        screen(getString(R.string.fw_bridge_title))
        if (busy) { text(getString(R.string.fw_working)); return }
        if (config == null || pairingText != null) {
            text(getString(R.string.fw_bridge_intro))
            val field = TextInputEditText(this).apply {
                hint = "fileweave://pair?…"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isSaveEnabled = false
                if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                setText(pairingText.orEmpty())
                maxLines = 5
            }
            content.addView(TextInputLayout(this).apply { addView(field) })
            button(getString(R.string.fw_bridge_pair)) {
                val proposed = try { BridgeConfig.parse(field.text.toString()) } catch (_: Exception) {
                    field.error = getString(R.string.fw_bridge_invalid_pairing); return@button
                }
                MaterialAlertDialogBuilder(this).setTitle(getString(R.string.fw_bridge_trust, proposed.host))
                    .setMessage(getString(R.string.fw_bridge_pin, proposed.pin.chunked(8).joinToString(" ")))
                    .setPositiveButton(R.string.fw_bridge_pair) { _, _ ->
                        field.text?.clear(); pairingText = null
                        connect(proposed)
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
            if (config != null) button(getString(android.R.string.cancel)) { pairingText = null; render() }
            return
        }
        val current = config!!
        text("${current.host}:${current.port}\n${status ?: getString(R.string.fw_bridge_ready)}")
        button(getString(R.string.fw_bridge_check)) { connect(current) }
        button(getString(R.string.fw_bridge_send_clipboard)) {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrEmpty()) Toast.makeText(this, R.string.fw_bridge_empty_clipboard, Toast.LENGTH_SHORT).show()
            else work { it.sendClipboard(text); getString(R.string.fw_bridge_clipboard_sent) }
        }
        button(getString(R.string.fw_bridge_get_clipboard)) {
            work { bridge ->
                val text = bridge.json("clipboard").getString("text")
                withContext(Dispatchers.Main) {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) MaterialAlertDialogBuilder(this@BridgeActivity)
                        .setTitle(R.string.fw_bridge_mac_clipboard).setMessage(text.take(4000))
                        .setPositiveButton(R.string.fw_bridge_copy) { _, _ ->
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FileWeave", text))
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
                ""
            }
        }
        button(getString(R.string.fw_bridge_send_file)) { picker.launch(arrayOf("*/*")) }
        pendingPath?.let { path ->
            button(getString(R.string.fw_bridge_send_selected, path.fileName)) {
                work { bridge -> path.newInputStream().use { bridge.upload(path.fileName.toString(), it) }; pendingPath = null; getString(R.string.fw_bridge_sent) }
            }
        }
        text(getString(R.string.fw_bridge_shared))
        if (sharedFiles.isEmpty()) text(getString(R.string.fw_bridge_shared_empty))
        sharedFiles.forEach { (name, _) ->
            button(name) {
                val video = name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "3gp", "ts")
                val actions = if (video) arrayOf(getString(R.string.fw_bridge_play), getString(R.string.fw_bridge_download)) else arrayOf(getString(R.string.fw_bridge_download))
                MaterialAlertDialogBuilder(this).setTitle(name).setItems(actions) { _, index ->
                    if (video && index == 0) startActivity(Intent(this, BridgeVideoActivity::class.java)
                        .setDataAndType(Uri.parse(BridgeClient(current).url("file", name).toString()),
                            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.').lowercase()) ?: "video/*"))
                    else { downloading = name; destination.launch(name) }
                }.show()
            }
        }
        button(getString(R.string.fw_bridge_forget)) {
            client?.close(); config = null; sharedFiles = emptyList(); status = null
            BridgeStore.forget(this); render()
        }
    }

    private fun connect(proposed: BridgeConfig) {
        if (busy) return
        busy = true; render()
        lifecycleScope.launch {
            val bridge = BridgeClient(proposed)
            try {
                val result = withContext(Dispatchers.IO) {
                    val info = bridge.json("status")
                    require(info.getInt("protocol") == 1)
                    val array = bridge.json("files").getJSONArray("files")
                    val files = (0 until array.length()).map { array.getJSONObject(it) }.map { it.getString("name") to it.getLong("size") }
                    BridgeStore.save(this@BridgeActivity, proposed)
                    info.getString("name") to files
                }
                client?.close(); client = bridge; config = proposed
                status = getString(R.string.fw_bridge_connected, result.first)
                sharedFiles = result.second
            } catch (e: Exception) {
                bridge.close()
                if (e is kotlinx.coroutines.CancellationException) throw e
                showFailure()
            } finally { busy = false; if (!isDestroyed) render() }
        }
    }
    private fun work(action: suspend (BridgeClient) -> String) {
        val cfg = config ?: return
        if (busy) return
        busy = true; render()
        lifecycleScope.launch {
            val bridge = client ?: BridgeClient(cfg).also { client = it }
            try {
                val message = withContext(Dispatchers.IO) { action(bridge) }
                if (message.isNotBlank()) Toast.makeText(this@BridgeActivity, message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                showFailure()
            } finally { busy = false; if (!isDestroyed) render() }
        }
    }
    private fun showFailure() {
        status = getString(R.string.fw_bridge_failure)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fw_operation_failed).setMessage(R.string.fw_bridge_failure)
            .setPositiveButton(android.R.string.ok, null).show()
    }
    override fun onDestroy() { client?.close(); super.onDestroy() }
}
