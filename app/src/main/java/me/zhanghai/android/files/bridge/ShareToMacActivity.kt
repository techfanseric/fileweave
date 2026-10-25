package me.zhanghai.android.files.bridge

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.features.FeatureActivity
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.provider.common.newInputStream

/** Selecting the system share target is the send action; no second confirmation. */
open class ShareToMacActivity : FeatureActivity() {
    private lateinit var transfer: MacShareTransfer
    protected open val fromClipboard = false
    protected open val fromInternalFile = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        transfer = ViewModelProvider(this)[MacShareTransfer::class.java]
        if (fromClipboard) {
            val density = resources.displayMetrics.density
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((20 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
                setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
                addView(android.widget.ProgressBar(this@ShareToMacActivity), android.widget.LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()))
                addView(android.widget.TextView(this@ShareToMacActivity).apply {
                    setText(R.string.fw_share_sending); textSize = 15f
                    setPadding((16 * density).toInt(), 0, 0, 0)
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                addView(android.widget.Button(this@ShareToMacActivity).apply {
                    setText(android.R.string.cancel); setOnClickListener { finish() }
                })
            }
            setContentView(row)
            window.setGravity(android.view.Gravity.BOTTOM)
            window.setDimAmount(0f)
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
            screen(getString(R.string.fw_share_to_mac))
            text(getString(R.string.fw_share_sending))
            button(getString(android.R.string.cancel)) { finish() }
        }
        transfer.result.observe(this) { result ->
            Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            finish()
        }
        if (fromInternalFile) {
            transfer.send(emptyList(), null, intent.extraPathList)
        } else if (!fromClipboard) {
            try {
                require(intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE
                    || intent.action == Intent.ACTION_PROCESS_TEXT)
                @Suppress("DEPRECATION")
                val uris = when (intent.action) {
                    Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                    Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                    else -> emptyList()
                }.ifEmpty {
                    if (intent.action == Intent.ACTION_PROCESS_TEXT) emptyList() else
                        intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
                }.distinct()
                // Never accept arbitrary local paths or turn this exported activity into a
                // proxy for FileWeave's private providers.
                require(uris.all { it.scheme == "content" && it.authority?.startsWith(packageName) == false
                    && it.authority?.startsWith("me.zhanghai.android.files") == false })
                val key = if (intent.action == Intent.ACTION_PROCESS_TEXT) Intent.EXTRA_PROCESS_TEXT else Intent.EXTRA_TEXT
                transfer.send(uris, if (uris.isEmpty()) intent.getCharSequenceExtra(key)?.toString() else null)
            } catch (_: Exception) {
                Toast.makeText(this, R.string.fw_bridge_failure, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fromClipboard && !transfer.started) {
            // Read only after gaining input focus, never from a background service.
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            transfer.send(emptyList(), clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString())
        }
    }
}

/** Internal-only entry point: external apps cannot ask us to read the clipboard. */
class SendClipboardActivity : ShareToMacActivity() {
    override val fromClipboard = true
}

class SendFileActivity : ShareToMacActivity() {
    override val fromInternalFile = true
}

class MacShareTransfer(application: Application) : AndroidViewModel(application) {
    val result = MutableLiveData<Int>()
    var started = false
        private set
    private var client: BridgeClient? = null

    fun send(uris: List<Uri>, text: String?, paths: List<java8.nio.file.Path> = emptyList()) {
        if (started) return
        started = true
        val context = getApplication<Application>()
        viewModelScope.launch {
            try {
                val config = BridgeStore.read(context)
                if (config == null) { result.value = R.string.fw_clipboard_not_connected; return@launch }
                if (uris.isEmpty() && text.isNullOrEmpty() && paths.isEmpty()) {
                    result.value = R.string.fw_bridge_empty_clipboard; return@launch
                }
                val bridge = BridgeClient(config).also { client = it }
                withContext(Dispatchers.IO) {
                    if (paths.isNotEmpty()) {
                        me.zhanghai.android.files.folderbridge.PairedShares.select(paths)
                        withContext(Dispatchers.Main) { me.zhanghai.android.files.folderbridge.PairedShares.ensureStarted(context) }
                        bridge.highlightSelection()
                    }
                    for (uri in uris) {
                        ensureActive()
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "received-file"
                        context.contentResolver.openInputStream(uri)!!.use { upload(bridge, name, it) }
                    }
                    if (!text.isNullOrEmpty()) {
                        bridge.sendClipboard(text)
                        BridgeHistoryStore.add(context, BridgeHistoryStore.Entry.clipboard(outgoing = true, text = text))
                    }
                }
                result.value = if (paths.isNotEmpty()) R.string.fw_selection_shared
                    else if (uris.isEmpty()) R.string.fw_bridge_clipboard_sent else R.string.fw_bridge_sent
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                result.value = R.string.fw_bridge_failure
            } finally {
                client?.close(); client = null
            }
        }
    }

    private fun upload(bridge: BridgeClient, name: String, input: java.io.InputStream) {
        var size = 0L
        val counting = object : java.io.FilterInputStream(input) {
            override fun read(): Int = input.read().also { if (it >= 0) size++ }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                input.read(bytes, offset, length).also { if (it > 0) size += it }
        }
        bridge.upload(name, counting)
        BridgeHistoryStore.add(getApplication(), BridgeHistoryStore.Entry.file(outgoing = true, name = name, size = size))
    }

    override fun onCleared() { client?.close() }
}
