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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var discovery: BridgeDiscovery? = null
    private val nearby = linkedMapOf<String, BridgeDiscovery.MacEndpoint>()
    private val attempted = mutableSetOf<String>()
    private var pairingJob: Job? = null
    private var pairingBridge: BridgeClient? = null
    private var pairingCode: String? = null
    private var pairingName: String? = null
    private var autoPair = true
    private var discoveryFailed = false
    private var connected = false
    private var manualPairing = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val settleDiscovery = Runnable { autoConnect() }
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
        manualPairing = pairingText != null
        downloading = savedInstanceState?.getString("downloading")
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("downloading", downloading); super.onSaveInstanceState(outState) }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun render() {
        screen(getString(R.string.fw_bridge_title))
        if (pairingCode != null) {
            text(getString(R.string.fw_bridge_wait_approval, pairingName.orEmpty(), pairingCode!!))
            button(getString(android.R.string.cancel)) { cancelPairing(); autoPair = false; render() }
            return
        }
        if (busy) { text(getString(R.string.fw_working)); return }
        if (config == null || manualPairing) {
            status?.let { text(it) }
            text(getString(if (discoveryFailed) R.string.fw_bridge_discovery_failed else R.string.fw_bridge_discovering))
            nearby.values.forEach { endpoint ->
                button(endpoint.name) { autoPair = true; beginPairing(endpoint) }
            }
            button(getString(R.string.fw_bridge_search)) { autoPair = true; restartDiscovery() }
            if (!manualPairing) {
                button(getString(R.string.fw_bridge_manual)) { manualPairing = true; render() }
                return
            }
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
                        field.text?.clear(); pairingText = null; manualPairing = false
                        connect(proposed)
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
            button(getString(android.R.string.cancel)) { pairingText = null; manualPairing = false; render() }
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
            BridgeStore.forget(this); connected = false; autoPair = false; render()
        }
    }

    override fun onStart() {
        super.onStart()
        attempted.clear()
        restartDiscovery()
        config?.let { if (!manualPairing) connect(it, quiet = true) }
    }

    private fun restartDiscovery() {
        discovery?.stop()
        nearby.clear()
        discoveryFailed = false
        discovery = BridgeDiscovery(this, { endpoint ->
            nearby[endpoint.name] = endpoint
            handler.removeCallbacks(settleDiscovery)
            handler.postDelayed(settleDiscovery, 1500)
            if (!busy && pairingCode == null && !manualPairing) render()
        }, { name ->
            nearby.remove(name)?.let { endpoint ->
                attempted.remove("${endpoint.host}:${endpoint.port}:${endpoint.pin}")
                if (config?.pin == endpoint.pin) { connected = false; status = getString(R.string.fw_bridge_auto_wait) }
            }
            if (!busy && pairingCode == null && !manualPairing) render()
        }, { discoveryFailed = true; if (!busy && pairingCode == null && !manualPairing) render() }).also { it.start() }
        if (!busy && pairingCode == null && !manualPairing) render()
    }

    private fun autoConnect() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || manualPairing || pairingCode != null) return
        if (busy) { handler.postDelayed(settleDiscovery, 1500); return }
        val saved = config
        if (saved != null) {
            // Never replace the remembered certificate with a different broadcast identity.
            nearby.values.filter { it.pin == saved.pin }.forEach { endpoint ->
                val key = "${endpoint.host}:${endpoint.port}:${endpoint.pin}"
                if (attempted.add(key) && (!connected ||
                            endpoint.host != saved.host || endpoint.port != saved.port)) {
                    connect(endpoint.config(saved.token), quiet = true)
                    return
                }
            }
        } else if (autoPair && nearby.size == 1) beginPairing(nearby.values.single())
    }

    private fun beginPairing(endpoint: BridgeDiscovery.MacEndpoint) {
        if (busy || pairingJob?.isActive == true) return
        val nonce = android.util.Base64.encodeToString(ByteArray(32).also(java.security.SecureRandom()::nextBytes),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val code = BridgePairingCode.create(endpoint.pin, nonce)
        pairingCode = code
        pairingName = endpoint.name
        busy = true
        render()
        val bridge = BridgeClient(endpoint.config("0".repeat(43)))
        pairingBridge = bridge
        pairingJob = lifecycleScope.launch {
            var approved: BridgeConfig? = null
            var requestId: String? = null
            try {
                val id = withContext(Dispatchers.IO) {
                    bridge.pairingRequest("pair/start", org.json.JSONObject().put("nonce", nonce)
                        .put("name", android.os.Build.MODEL.take(80))).getString("id")
                }
                requestId = id
                val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    delay(1000)
                    val result = withContext(Dispatchers.IO) {
                        bridge.pairingRequest("pair/poll", org.json.JSONObject().put("id", id).put("nonce", nonce))
                    }
                    if (result.getString("state") == "approved") {
                        approved = endpoint.config(result.getString("token"))
                        BridgeConfig.fromJson(approved.json())
                        break
                    }
                }
                if (approved == null) status = getString(R.string.fw_bridge_pair_expired)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                status = getString(when ((error as? BridgeHttpException)?.status) {
                    403 -> R.string.fw_bridge_pair_declined
                    404 -> R.string.fw_bridge_pair_expired
                    429 -> R.string.fw_bridge_pair_busy
                    else -> R.string.fw_bridge_failure
                })
                autoPair = false
            } finally {
                if (approved == null && requestId != null) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    try { bridge.pairingRequest("pair/cancel", org.json.JSONObject().put("id", requestId).put("nonce", nonce)) } catch (_: Exception) { }
                }
                bridge.close()
                if (pairingBridge === bridge) {
                    pairingBridge = null; pairingCode = null; pairingName = null; busy = false
                    if (!isDestroyed) render()
                }
            }
            approved?.let { proposed ->
                autoPair = false
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    MaterialAlertDialogBuilder(this@BridgeActivity)
                        .setTitle(getString(R.string.fw_bridge_trust, endpoint.name))
                        .setMessage(getString(R.string.fw_bridge_finish_message, code))
                        .setPositiveButton(R.string.fw_bridge_finish) { _, _ -> connect(proposed) }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
        }
    }

    private fun cancelPairing() {
        pairingBridge?.close(); pairingBridge = null
        pairingJob?.cancel(); pairingJob = null
        pairingCode = null; pairingName = null; busy = false
    }

    override fun onStop() {
        handler.removeCallbacks(settleDiscovery)
        discovery?.stop(); discovery = null
        if (pairingCode != null) cancelPairing()
        super.onStop()
    }

    private fun connect(proposed: BridgeConfig, quiet: Boolean = false) {
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
                client?.close(); client = bridge; config = proposed; connected = true
                status = getString(R.string.fw_bridge_connected, result.first)
                sharedFiles = result.second
            } catch (e: Exception) {
                bridge.close()
                if (e is kotlinx.coroutines.CancellationException) throw e
                connected = false
                if (quiet) status = getString(R.string.fw_bridge_auto_wait) else showFailure()
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
