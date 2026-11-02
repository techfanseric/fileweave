package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import me.zhanghai.android.files.bridge.BridgeClient
import me.zhanghai.android.files.bridge.BridgeConnectionService
import me.zhanghai.android.files.bridge.BridgeStore
import me.zhanghai.android.files.filelist.FileListActivity

/** A focused non-AppActivity simulates copying elsewhere, then returning to FileWeave.
 * Clipboard snapshots stay in memory and the original is restored in finally.
 */
class ClipboardFocusInstrumentation : Instrumentation() {
    private var screen: Activity? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun callActivityOnResume(activity: Activity) { super.callActivityOnResume(activity); if (activity is FileListActivity) screen = activity }
    override fun onStart() {
        var original: ClipData? = null
        var captured = false
        var resultCode = 0
        var resultText = ""
        try {
            startActivitySync(Intent(targetContext, FileListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await { screen?.window?.decorView?.hasWindowFocus() == true && BridgeConnectionService.stateLiveData.value?.connected == true }
            val clipboard = targetContext.getSystemService(ClipboardManager::class.java)
            runOnMainSync { original = clipboard.primaryClip; captured = true }
            val marker = "FileWeave clipboard focus QA " + java.util.UUID.randomUUID()
            runOnMainSync { screen!!.startActivity(Intent(targetContext, ClipboardProbeActivity::class.java).putExtra("text", marker)) }
            val config = BridgeStore.read(targetContext) ?: error("No paired Mac")
            val client = BridgeClient(config)
            try { await { try { client.json("received-clipboard").optJSONObject("clipboard")?.optString("text") == marker } catch (_: Exception) { false } } }
            finally { client.close() }
            resultText = "PASS: copied with FileWeave unfocused, return-to-app forwarded current text"
        } catch (error: Throwable) {
            resultCode = 1
            resultText = "FAIL: " + error.javaClass.simpleName + ": " + error.message
        } finally {
            if (captured) runOnMainSync {
                val clipboard = targetContext.getSystemService(ClipboardManager::class.java)
                original?.let { clipboard.setPrimaryClip(it) } ?: run { if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() }
            }
        }
        finish(resultCode, Bundle().apply { putString("result", resultText) })
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 25000
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timed out waiting for pairing/focus/clipboard" }; SystemClock.sleep(150) }
    }
}
class ClipboardProbeActivity : me.zhanghai.android.files.app.AppActivity() {
    private var didCopy = false
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(android.widget.TextView(this).apply { text = "FileWeave clipboard verification" }) }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        // Keep the normal AppActivity/theme contract but opt this QA screen out
        // of forwarding, just as an external app has no FileWeave listener.
        super.onWindowFocusChanged(false)
        if (hasFocus && !didCopy) {
            didCopy = true
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FileWeave QA", intent.getStringExtra("text")))
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 500)
        }
    }
}
