package me.zhanghai.android.files.diagnostics

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import me.zhanghai.android.files.bridge.BridgeActivity
import java.io.File

/**
 * Debug-only: completes the first-trust pairing confirmation on a real phone.
 *
 * The bridge page is FLAG_SECURE, so screenshot/uiautomator-driven clicking is
 * impossible from adb; Honor's secure install dialog also blocks a separate
 * test APK. Running inside the app's own package keeps everything in-process:
 * hold the screen on (onStop cancels an in-flight pairing), open the page,
 * then locate the confirm button through the accessibility tree and click it.
 */
class FolderBridgePairingInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val finishText = "已在 Mac 核对，完成配对"
        @Suppress("DEPRECATION")
        val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "fileweave:qa-pairing")
        wake.acquire(10 * 60 * 1000L)
        try {
            uiAutomation.executeShellCommand("input keyevent WAKEUP").close()
            Thread.sleep(1500)
            uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
            Thread.sleep(1000)

            val intent = Intent(targetContext, BridgeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            targetContext.startActivity(intent)
            Thread.sleep(4000)

            val deadline = System.currentTimeMillis() + 150_000
            var clicked = false
            while (System.currentTimeMillis() < deadline) {
                val node = findNode(finishText)
                if (node != null) {
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    break
                }
                Thread.sleep(1000)
            }
            Thread.sleep(3000)
            val saved = File(targetContext.noBackupFilesDir, "bridge.json").exists()
            println("FW_QA_PAIRING clicked=$clicked saved=$saved")
        } finally {
            wake.release()
        }
        finish(0, null)
    }

    /** Search all windows' accessibility trees for a clickable node showing [text]. */
    private fun findNode(text: String): AccessibilityNodeInfo? {
        for (window in uiAutomation.windows) {
            val root = window.root ?: continue
            val found = findNodeRecursive(root, text)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text && node.isClickable) {
            return node
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findNodeRecursive(child, text)
            if (found != null) {
                return found
            }
        }
        return null
    }
}
