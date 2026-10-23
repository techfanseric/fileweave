package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import me.zhanghai.android.files.bridge.BridgeActivity
import me.zhanghai.android.files.bridge.ClipboardActivity

/**
 * Debug-only: exercises the FLAG_SECURE bridge pages in-process, where neither
 * screenshots nor uiautomator can see. Walks the view tree to report what is on
 * screen and clicks the send-clipboard button, verifying the phone -> Mac
 * clipboard path and the history list without any manual tapping.
 */
class BridgeUiTestInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        // ClipboardActivity: dump texts, click the send button.
        val clip = launch(ClipboardActivity::class.java)
        val clipTexts = mutableListOf<String>()
        collectTexts(clip, clipTexts)
        println("FW_UI_CLIPBOARD " + clipTexts.joinToString(" | ").take(2000))
        val clicked = clickButtonContaining(clip, "发送")
        Thread.sleep(4000)
        clip.finish()

        // BridgeActivity: dump texts (the history list should show the new entry).
        val bridge = launch(BridgeActivity::class.java)
        val bridgeTexts = mutableListOf<String>()
        collectTexts(bridge, bridgeTexts)
        println("FW_UI_SHARED " + bridgeTexts.joinToString(" | ").take(3000))
        println("FW_UI_DONE clicked=$clicked")
        bridge.finish()
        finish(0, null)
    }

    private fun launch(activityClass: Class<out Activity>): Activity {
        val monitor = addMonitor(activityClass.name, null, false)
        val intent = Intent(targetContext, activityClass)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
        return waitForMonitor(monitor)
    }

    private fun collectTexts(activity: Activity, out: MutableList<String>) {
        runOnMainSync {
            collectRecursive(activity.window.decorView, out)
        }
    }

    private fun collectRecursive(view: View?, out: MutableList<String>) {
        if (view == null) {
            return
        }
        if (view is TextView) {
            view.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectRecursive(view.getChildAt(index), out)
            }
        }
    }

    private fun clickButtonContaining(activity: Activity, textPart: String): Boolean {
        val clicked = arrayOf(false)
        runOnMainSync {
            clicked[0] = clickRecursive(activity.window.decorView, textPart)
        }
        return clicked[0]
    }

    private fun clickRecursive(view: View?, textPart: String): Boolean {
        if (view == null) {
            return false
        }
        if (view is Button && view.text?.contains(textPart) == true && view.isClickable) {
            view.performClick()
            return true
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (clickRecursive(view.getChildAt(index), textPart)) {
                    return true
                }
            }
        }
        return false
    }
}
