package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import java.io.File
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.ui.ActionPanel
import me.zhanghai.android.files.util.extraPath

/** Cache-only, isolated-package regression: real menu routing and dialog back dispatcher. */
@androidx.media3.common.util.UnstableApi
class MenuPanelInstrumentation : Instrumentation() {
    private var screen: Activity? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun callActivityOnResume(activity: Activity) {
        super.callActivityOnResume(activity)
        if (activity is FileListActivity || activity is me.zhanghai.android.files.viewer.text.TextEditorActivity) screen = activity
    }
    override fun onStart() {
        val directory = File(targetContext.cacheDir, "menu-qa")
        try {
            check(targetContext.packageName.endsWith(".menuqa")) { "Requires isolated .menuqa package" }
            directory.deleteRecursively()
            directory.mkdirs()
            File(directory, "menu-sample.txt").writeText("Menu regression sample")
            onMain { me.zhanghai.android.files.settings.Settings.ROOT_STRATEGY.putValue(me.zhanghai.android.files.provider.root.RootStrategy.NEVER) }
            startActivitySync(Intent(targetContext, FileListActivity::class.java)
                .setAction(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply { extraPath = Paths.get(directory.absolutePath) })
            await { screen?.findViewById<Toolbar>(R.id.toolbar)?.menu?.findItem(R.id.action_page_more) != null }
            onMain { screen!!.findViewById<Toolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_page_more, 0) }
            await { ActionPanel.current(screen!!) != null }
            onMain {
                val panel = ActionPanel.current(screen!!)!!
                val items = panel.contentView.findViewById<android.widget.LinearLayout>(R.id.panelItems)
                check(items.childCount == 6) { "Directory root must have six actions, got ${items.childCount}" }
                click(R.id.action_folder_tools)
                check(shown(R.id.action_copy_path))
                click(R.id.panelBack)
                check(shown(R.id.action_folder_share))
                click(R.id.action_folder_share)
                check(shown(R.id.action_lan_share))
            }
            back()
            await { shown(R.id.action_folder_tools) }
            onMain {
                check(ActionPanel.current(screen!!) != null) { "Back closed the root" }
                click(R.id.action_filter_files)
            }
            await { uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("${targetContext.packageName}:id/typeSpinner")?.isNotEmpty() == true }
            back()
            await { topHas("panelClose") && shown(R.id.action_folder_tools) }
            back()
            await { ActionPanel.current(screen!!) == null }
            await { screen!!.findViewById<View>(R.id.menuButton) != null }
            onMain { screen!!.findViewById<View>(R.id.menuButton).performClick() }
            await { shown(R.id.action_file_tools) }
            onMain {
                click(R.id.action_share_send)
                check(shown(R.id.action_fw_send_mac))
                click(R.id.panelBack)
                check(shown(R.id.action_open_with))
                click(R.id.action_file_tools)
                click(R.id.action_archive)
            }
            await { uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(targetContext.getString(R.string.file_create_archive_title))?.isNotEmpty() == true }
            back()
            // Back first hides the keyboard on devices that auto-focus the archive filename.
            SystemClock.sleep(300)
            if (uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(targetContext.getString(R.string.file_create_archive_title))?.isNotEmpty() == true) back()
            await { topHas("panelClose") && shown(R.id.action_copy_path) }
            back()
            await { shown(R.id.action_share_send) }
            back()
            await { ActionPanel.current(screen!!) == null }
            onMain { screen!!.findViewById<Toolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_view_sort, 0) }
            await { uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("${targetContext.packageName}:id/viewDetails")?.isNotEmpty() == true }
            val node = uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("${targetContext.packageName}:id/viewHidden").single()
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(200)
            check(uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("${targetContext.packageName}:id/viewHidden").isNotEmpty()) { "Settings panel dismissed after toggle" }
            uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("android:id/button1").single()
                .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            await { topHas("action_page_more") }
            onMain { screen!!.findViewById<Toolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_search_filters, 0) }
            await { screen!!.findViewById<View>(R.id.quickFiltersScroll).isShown }
            onMain { screen!!.findViewById<Toolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_search_filters, 0) }
            await { !screen!!.findViewById<View>(R.id.quickFiltersScroll).isShown }
            // Queue navigation uses generated cache WAVs, never personal recordings.
            val first = File(directory, "first.wav"); val second = File(directory, "second.wav")
            wav(first); wav(second)
            val paths = listOf(first, second).map { Paths.get(it.absolutePath) }
            onMain { me.zhanghai.android.files.viewer.audio.AudioPlayback.play(targetContext, paths, paths.first()) }
            await { screen!!.findViewById<View>(R.id.audio_queue)?.isShown == true }
            onMain { screen!!.findViewById<View>(R.id.audio_queue).performClick() }
            await { topHas("panelClose") }
            onMain {
                val rows = ActionPanel.current(screen!!)!!.contentView.findViewById<android.widget.LinearLayout>(R.id.panelItems)
                rows.getChildAt(0).performClick()
                click(R.id.panelBack)
                rows.getChildAt(0).performClick()
            }
            back()
            await { topHas("panelClose") }
            onMain {
                val rows = ActionPanel.current(screen!!)!!.contentView.findViewById<android.widget.LinearLayout>(R.id.panelItems)
                rows.getChildAt(0).performClick()
                clickText(R.string.fw_queue_down)
                check(me.zhanghai.android.files.viewer.audio.AudioPlayback.paths == paths.reversed())
                clickText(R.string.fw_queue_clear)
            }
            await { !topHas("panelClose") }
            back()
            await { topHas("panelClose") }
            onMain {
                check(me.zhanghai.android.files.viewer.audio.AudioPlayback.paths == paths.reversed())
                click(R.id.panelClose)
                check(me.zhanghai.android.files.viewer.audio.AudioPlayback.player != null)
                me.zhanghai.android.files.viewer.audio.AudioPlayback.close()
            }
            startActivitySync(Intent(targetContext, me.zhanghai.android.files.viewer.text.TextEditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { extraPath = Paths.get(File(directory, "menu-sample.txt").absolutePath) })
            await { screen is me.zhanghai.android.files.viewer.text.TextEditorActivity && topHas("action_text_more") }
            onMain { screen!!.findViewById<Toolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_text_more, 0) }
            await { shown(R.id.action_encoding) }
            onMain {
                click(R.id.action_encoding)
                ActionPanel.current(screen!!)!!.contentView.findViewById<android.widget.EditText>(R.id.panelSearch).setText("GB18030")
                val rows = ActionPanel.current(screen!!)!!.contentView.findViewById<android.widget.LinearLayout>(R.id.panelItems)
                check(rows.childCount == 1 && (rows.getChildAt(0) as TextView).text.toString().equals("GB18030", true))
                click(R.id.panelBack)
                check(shown(R.id.action_reload))
                click(R.id.action_reload)
            }
            await { ActionPanel.current(screen!!) == null }
            onMain {
                screen!!.finish()
            }
            directory.deleteRecursively()
            finish(Activity.RESULT_OK, Bundle().apply { putString("result", "PASS: directory six actions; title/system back; filter/archive cancellation; file submenu; persistent view settings; quick-filter disclosure; queue back/reorder/clear cancel; searchable encoding and parent return") })
        } catch (error: Throwable) {
            val screenshot = uiAutomation.takeScreenshot()
            if (screenshot != null) java.io.FileOutputStream(File(targetContext.getExternalFilesDir(null), "menu-failure.png")).use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("error", error.stackTraceToString()) })
        }
    }
    private fun back() {
        waitForIdleSync()
        uiAutomation.waitForIdle(250, 5000)
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }
    private fun clickText(title: Int) {
        val rows = ActionPanel.current(screen!!)!!.contentView.findViewById<android.widget.LinearLayout>(R.id.panelItems)
        val text = targetContext.getString(title)
        val row = (0 until rows.childCount).map { rows.getChildAt(it) }.filterIsInstance<TextView>().single { it.text.toString() == text }
        check(row.performClick())
    }
    private fun wav(file: File) {
        val samples = 8000 * 3
        val data = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        file.writeBytes(data.array())
    }
    private fun topHas(id: String): Boolean {
        if (id == "panelClose" && ActionPanel.current(screen ?: return false)?.contentView?.hasWindowFocus() != true) return false
        return uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("${targetContext.packageName}:id/$id")?.isNotEmpty() == true
    }
    private fun click(id: Int) { check(ActionPanel.current(screen!!)!!.contentView.findViewById<View>(id).performClick()) }
    private fun shown(id: Int): Boolean {
        val activity = screen ?: return false
        return ActionPanel.current(activity)?.contentView?.findViewById<View>(id)?.isShown == true
    }
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            onMain { ready = predicate() }
            if (ready) return
            SystemClock.sleep(100)
        }
        error("Timed out waiting for menu state")
    }
}
