package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import me.zhanghai.android.files.R
import me.zhanghai.android.files.bridge.BridgeHistoryRepository
import me.zhanghai.android.files.bridge.BridgeHistoryStore
import me.zhanghai.android.files.design.TransfersDestination
import me.zhanghai.android.files.features.FeatureActivity
import java.io.File

/** Two process runs prove row deletion and disk reload, using a separate history
 * directory. Never seeds, clears, reads, or deletes the user's actual history. */
class HistoryDeletionInstrumentation : Instrumentation() {
    private var phase = "delete"
    private var screen: HistoryDeletionProbeActivity? = null
    override fun onCreate(arguments: Bundle?) { phase = arguments?.getString("phase") ?: phase; super.onCreate(arguments); start() }
    override fun callActivityOnResume(activity: Activity) { super.callActivityOnResume(activity); if (activity is HistoryDeletionProbeActivity) screen = activity }
    override fun onStart() {
        var code = 0
        var stage = "launch"
        var message: String
        try {
            check(phase in listOf("delete", "verify"))
            val service = uiAutomation.serviceInfo
            service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            uiAutomation.serviceInfo = service
            val activity = startActivitySync(Intent(targetContext, HistoryDeletionProbeActivity::class.java)
                .putExtra("seed", phase == "delete").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HistoryDeletionProbeActivity
            check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val label = activity.getString(R.string.fw_bridge_history_entry_delete)
            stage = "find-delete-action"
            await { deleteButtons(label).isNotEmpty() }
            if (phase == "delete") {
                stage = "click-delete-action"
                check(deleteButtons(label).first().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                stage = "await-single-record"
                await { BridgeHistoryStore.list(activity).size == 1 && deleteButtons(label).isNotEmpty() }
            }
            stage = "verify-disk"
            val file = File(activity.noBackupFilesDir, "history.json")
            val fresh = BridgeHistoryRepository(file).list()
            check(fresh.size == 1 && fresh.single().id == "qa-keep")
            check(!file.readText().contains("qa-delete") && !file.readText().contains("Private synthetic delete me"))
            if (phase == "verify") {
                // This run started a new process; preserve the generated fixture
                // until the reload assertion, then remove only this private test log.
                BridgeHistoryStore.clear(activity)
                await { deleteButtons(label).isEmpty() }
            }
            message = "PASS: phase=$phase, exact-row deletion persisted, remaining=1, secure=true, real_history_untouched=true"
        } catch (error: Throwable) {
            code = 1; message = "FAIL: phase=$phase, stage=$stage, ${error.javaClass.simpleName}"
        } finally { runOnMainSync { screen?.finish() } }
        finish(code, Bundle().apply { putString("result", message) })
    }
    private fun deleteButtons(label: String): List<AccessibilityNodeInfo> {
        val roots = linkedMapOf<Int, AccessibilityNodeInfo>()
        uiAutomation.rootInActiveWindow?.let { if (it.packageName == targetContext.packageName) roots[it.windowId] = it }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val displays = uiAutomation.windowsOnAllDisplays
            for (index in 0 until displays.size()) for (window in displays.valueAt(index)) {
                if (window.isActive) window.root?.let { if (it.packageName == targetContext.packageName) roots[it.windowId] = it }
            }
        }
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.contentDescription?.toString() == label) {
                var action: AccessibilityNodeInfo? = node
                while (action != null && !action.isClickable) action = action.parent
                if (action != null) result += action
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        roots.values.forEach(::visit)
        return result.sortedBy { val bounds = android.graphics.Rect(); it.getBoundsInScreen(bounds); bounds.top }
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timed out" }; SystemClock.sleep(100) }
    }
}

class HistoryDeletionProbeActivity : FeatureActivity() {
    override fun getNoBackupFilesDir(): File = File(cacheDir, "history-deletion-qa").apply { mkdirs() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(false) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (intent.getBooleanExtra("seed", true)) {
            BridgeHistoryStore.clear(this)
            BridgeHistoryStore.add(this, BridgeHistoryStore.Entry(BridgeHistoryStore.Kind.FILE, true,
                name = "Generated keep.pdf", size = 123, time = 1000, id = "qa-keep"))
            BridgeHistoryStore.add(this, BridgeHistoryStore.Entry(BridgeHistoryStore.Kind.CLIPBOARD, false,
                preview = "Private synthetic delete me", time = 1001, id = "qa-delete"))
        }
        composeScreen {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides this@HistoryDeletionProbeActivity) {
                TransfersDestination { finish() }
            }
        }
    }
}
