package me.zhanghai.android.files.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private val retry = BridgeRetry()
    private var automation: Job? = null
    private var connecting = false
    private var connectionFailed = false
    private var confirmingIdentity = false
    private var lastRefresh = 0L
    private var lastDiscoveryRestart = 0L
    private var pairingJob: Job? = null
    private var pairingBridge: BridgeClient? = null
    private var pairingCode: String? = null
    private var pairingName: String? = null
    private var autoPair = true
    private var discoveryFailed = false
    private var connected = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val settleDiscovery = Runnable { autoConnect() }
    private var config: BridgeConfig? = null
    private var client: BridgeClient? = null
    private var sharedFiles = emptyList<Pair<String, Long>>()
    private var pendingPath: java8.nio.file.Path? = null
    private var downloading: String? = null
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
        if (config == null || !connected) {
            text(status ?: getString(if (discoveryFailed) R.string.fw_bridge_discovery_failed else R.string.fw_bridge_discovering))
            if (nearby.size > 1 && (config == null || (connectionFailed && nearby.values.none { it.pin == config?.pin }))) {
                text(getString(R.string.fw_bridge_choose_mac))
                nearby.values.forEach { endpoint -> button(endpoint.name) { autoPair = true; beginPairing(endpoint) } }
            }
            return
        }
        val current = config!!
        text(status ?: getString(R.string.fw_bridge_ready))
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
    }

    override fun onStart() {
        super.onStart()
        retry.reset()
        autoPair = true
        restartDiscovery()
        autoConnect()
        automation = lifecycleScope.launch {
            while (true) {
                delay(1000)
                if (discoveryFailed && now() - lastDiscoveryRestart >= 10_000) restartDiscovery()
                if (connected && !busy && !connecting && now() - lastRefresh >= 5000) refreshFiles()
                else if (!connected) autoConnect()
            }
        }
    }

    private fun now() = android.os.SystemClock.elapsedRealtime()

    private fun restartDiscovery() {
        discovery?.stop()
        nearby.clear()
        discoveryFailed = false
        lastDiscoveryRestart = now()
        discovery = BridgeDiscovery(this, { endpoint ->
            nearby[endpoint.name] = endpoint
            handler.removeCallbacks(settleDiscovery)
            handler.postDelayed(settleDiscovery, 1500)
            if (!busy && pairingCode == null && !connected) render()
        }, { name ->
            nearby.remove(name)?.let { endpoint ->
                if (config?.pin == endpoint.pin) {
                    connected = false; retry.reset(); status = getString(R.string.fw_bridge_auto_wait)
                }
            }
            if (!busy && pairingCode == null && !connected) render()
        }, { discoveryFailed = true; if (!busy && pairingCode == null && !connected) render() }).also { it.start() }
        if (!busy && pairingCode == null && !connected) render()
    }

    private fun autoConnect() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || connected || busy || connecting ||
            confirmingIdentity || pairingCode != null || !retry.ready(now())) return
        val saved = config
        if (saved != null) {
            val matched = nearby.values.lastOrNull { it.pin == saved.pin }
            // A changed identity always returns to the human comparison flow, never silent trust.
            if (matched == null && connectionFailed && autoPair && nearby.size == 1) {
                beginPairing(nearby.values.single())
            } else connect(matched?.config(saved.token) ?: saved)
        } else if (autoPair && nearby.size == 1) beginPairing(nearby.values.single())
    }

    private fun readFiles(bridge: BridgeClient): List<Pair<String, Long>> {
        val array = bridge.json("files").getJSONArray("files")
        return (0 until array.length()).map { array.getJSONObject(it) }
            .map { it.getString("name") to it.getLong("size") }
    }

    private suspend fun refreshFiles() {
        val bridge = client ?: return
        lastRefresh = now()
        try {
            val files = withContext(Dispatchers.IO) { readFiles(bridge) }
            if (files != sharedFiles) { sharedFiles = files; render() }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (!busy) { recover(error); render() }
        }
    }

    private fun recover(error: Exception) {
        client?.close(); client = null
        connected = false; connectionFailed = true
        retry.failed(now())
        status = getString(R.string.fw_bridge_auto_wait)
        // A verified server rejecting the old credential needs fresh approval, not a reset button.
        if (error is BridgeHttpException && error.status == 401) {
            BridgeStore.forget(this); config = null; sharedFiles = emptyList(); autoPair = true
            status = getString(R.string.fw_bridge_reapprove)
        }
    }

    private fun beginPairing(endpoint: BridgeDiscovery.MacEndpoint) {
        if (busy || connecting || confirmingIdentity || pairingJob?.isActive == true) return
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
                autoPair = (error as? BridgeHttpException)?.status != 403
                retry.failed(now())
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
                    confirmingIdentity = true
                    MaterialAlertDialogBuilder(this@BridgeActivity)
                        .setTitle(getString(R.string.fw_bridge_trust, endpoint.name))
                        .setMessage(getString(R.string.fw_bridge_finish_message, code))
                        .setPositiveButton(R.string.fw_bridge_finish) { _, _ ->
                            try {
                                // Human trust is complete: keep credentials even if Wi-Fi drops now.
                                BridgeStore.save(this@BridgeActivity, proposed)
                                config = proposed; connected = false
                                connect(proposed)
                            } catch (_: Exception) { showFailure(); render() }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .setOnDismissListener { confirmingIdentity = false; autoPair = false }.show()
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
        automation?.cancel(); automation = null
        handler.removeCallbacks(settleDiscovery)
        discovery?.stop(); discovery = null
        if (pairingCode != null) cancelPairing()
        super.onStop()
    }

    private fun connect(proposed: BridgeConfig) {
        if (busy || connecting) return
        connecting = true
        lifecycleScope.launch {
            val bridge = BridgeClient(proposed)
            try {
                val result = withContext(Dispatchers.IO) {
                    val info = bridge.json("status")
                    require(info.getInt("protocol") == 1)
                    val files = readFiles(bridge)
                    BridgeStore.save(this@BridgeActivity, proposed)
                    info.getString("name") to files
                }
                client?.close(); client = bridge; config = proposed; connected = true
                connectionFailed = false; retry.reset(); lastRefresh = now()
                status = getString(R.string.fw_bridge_connected, result.first)
                sharedFiles = result.second
            } catch (error: Exception) {
                bridge.close()
                if (error is kotlinx.coroutines.CancellationException) throw error
                recover(error)
            } finally { connecting = false; if (!isDestroyed) render() }
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
                recover(e)
                showFailure()
            } finally { busy = false; lastRefresh = 0; if (!isDestroyed) render() }
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
