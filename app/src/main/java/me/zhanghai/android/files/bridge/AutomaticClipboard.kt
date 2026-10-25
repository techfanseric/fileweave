package me.zhanghai.android.files.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import me.zhanghai.android.files.app.application

/** Text only, no history. Incoming clips are tagged to prevent an echo loop. */
object AutomaticClipboard {
    const val REMOTE_LABEL = "FileWeave · Mac"
    private const val LIMIT = 256 * 1024 - 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<String>(Channel.CONFLATED)
    @Volatile private var lastSent: String? = null
    @Volatile var lastMacText: String? = null
        private set
    init {
        scope.launch {
            for (text in pending) {
                if (text == lastSent || text == lastMacText) continue
                val config = BridgeStore.read(application) ?: continue
                val bridge = BridgeClient(config)
                try { bridge.sendClipboard(text); lastSent = text }
                catch (_: Exception) { /* A later explicit copy retries; never replay stale clips. */ }
                finally { bridge.close() }
            }
        }
    }
    fun copied(text: String) {
        if (text.isEmpty() || text.toByteArray().size > LIMIT ||
            BridgeConnectionService.stateLiveData.value?.connected != true) return
        pending.trySend(text)
    }
    fun receive(context: Context, text: String) {
        require(text.toByteArray().size <= LIMIT)
        if (text == lastMacText || text == lastSent) { lastMacText = text; return }
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(REMOTE_LABEL, text))
        lastMacText = text
    }
}
