package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import java.io.ByteArrayInputStream
import java.io.File
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.bridge.*
import me.zhanghai.android.files.design.TransfersActivity
import me.zhanghai.android.files.filelist.*
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.theme.custom.ThemeColor
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.vault.*

/** Disposable-package integration checks. Uses generated files only and never reads the clipboard. */
class RedesignInstrumentation : Instrumentation() {
    private var locale = "en"
    private var night = false
    private var accent = false
    private var checkDefault = false
    private var navigationOnly = false
    private val screens = mutableListOf<Activity>()
    private val outputs = mutableListOf<String>()
    override fun onCreate(arguments: Bundle?) {
        locale = arguments?.getString("locale") ?: locale
        night = arguments?.getString("night")?.toBoolean() ?: false
        accent = arguments?.getString("accent")?.toBoolean() ?: false
        checkDefault = arguments?.getString("checkDefault")?.toBoolean() ?: false
        navigationOnly = arguments?.getString("navigationOnly")?.toBoolean() ?: false
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        var failure: Throwable? = null
        val directory = File(targetContext.cacheDir, "redesign-fixtures")
        try {
            val accessibility = uiAutomation.serviceInfo
            accessibility.flags = accessibility.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            uiAutomation.serviceInfo = accessibility
            check(targetContext.packageName.endsWith(".redesignqa")) { "Requires disposable .redesignqa package" }
            if (checkDefault && android.os.Build.VERSION.SDK_INT >= 33) {
                check(targetContext.getSystemService(android.app.LocaleManager::class.java).applicationLocales.toLanguageTags() == "en") {
                    "First startup did not explicitly select English"
                }
            }
            directory.mkdirs()
            repeat(45) { File(directory, "File-${it.toString().padStart(2, '0')}.txt").writeText("Generated UI fixture $it") }
            onMain {
                Settings.ROOT_STRATEGY.putValue(me.zhanghai.android.files.provider.root.RootStrategy.NEVER)
                Settings.NIGHT_MODE.putValue(if (night) NightMode.ON else NightMode.OFF)
                Settings.MATERIAL_DESIGN_3.putValue(!accent)
                Settings.THEME_COLOR.putValue(if (accent) ThemeColor.MATERIAL_GREEN else ThemeColor.COLOR_PRIMARY)
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale))
                if (android.os.Build.VERSION.SDK_INT >= 33) targetContext.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(locale)
                BridgeConnectionService.stop(targetContext)
            }
            val main = open(Intent(targetContext, FileListActivity::class.java).setAction(Intent.ACTION_MAIN).apply { extraPath = Paths.get(directory.absolutePath) }) as FileListActivity
            await { (main.findViewById<RecyclerView>(R.id.recyclerView)?.adapter?.itemCount ?: 0) > 0 }
            if (accent) onMain {
                check(com.google.android.material.color.MaterialColors.getColor(main, androidx.appcompat.R.attr.colorPrimary, 0) ==
                    androidx.core.content.ContextCompat.getColor(main, ThemeColor.MATERIAL_GREEN.resourceId)) { "Custom green accent did not apply" }
            }
            val fragment = main.supportFragmentManager.findFragmentById(R.id.fw_file_host)!!
            val model = onMainValue { ViewModelProvider(fragment)[FileListViewModel::class.java] }
            if (navigationOnly) {
                check(main.resources.configuration.screenWidthDp >= 600) { "Wide navigation check needs at least 600dp" }
                val navigation = main.findViewById<View>(R.id.fw_destination_navigation)
                check(navigation.width < navigation.height) { "Navigation did not adapt to a rail" }
            }
            val originalPath = onMainValue { model.currentPath }
            val recycler = main.findViewById<RecyclerView>(R.id.recyclerView)
            onMain { recycler.scrollToPosition(22) }
            waitForIdleSync(); SystemClock.sleep(300)
            val scroll = onMainValue { (recycler.layoutManager as androidx.recyclerview.widget.GridLayoutManager).findFirstVisibleItemPosition() }
            fun railFileTop(): Int {
                val rect = android.graphics.Rect()
                nodes().first { it.text?.toString() == main.getString(R.string.fw_redesign_files) }.getBoundsInScreen(rect)
                return rect.top
            }
            fun railMetrics(): String = onMainValue {
                val nav = main.findViewById<android.view.ViewGroup>(R.id.fw_destination_navigation)
                val root = nav.parent as View
                val rootPoint = IntArray(2).also { root.getLocationOnScreen(it) }
                val navPoint = IntArray(2).also { nav.getLocationOnScreen(it) }
                val child = nav.getChildAt(0)
                "root=${rootPoint.toList()}/${root.measuredWidth}x${root.measuredHeight}, nav=${navPoint.toList()}/${nav.measuredWidth}x${nav.measuredHeight}, paddingTop=${nav.paddingTop}, childTop=${child?.top}, childHeight=${child?.height}, decorFlags=${main.window.decorView.systemUiVisibility}"
            }
            val initialRailMetrics = if (navigationOnly) railMetrics() else ""
            val initialRailTop = if (navigationOnly) railFileTop() else 0
            val initialRailPadding = onMainValue { main.findViewById<View>(R.id.fw_destination_navigation).paddingTop }
            capture(main, "files")
            click(main.getString(R.string.fw_redesign_devices))
            await { main.findViewById<View>(R.id.fw_file_host)?.visibility == View.GONE }
            check(onMainValue { fragment.lifecycle.currentState } == androidx.lifecycle.Lifecycle.State.STARTED)
            capture(main, "devices")
            if (navigationOnly) {
                sendStatus(0, Bundle().apply {
                    putString("rail_metrics_files", initialRailMetrics)
                    putString("rail_metrics_devices", railMetrics())
                    putString("rail_label_top", "$initialRailTop -> ${railFileTop()}")
                })
                check(railFileTop() == initialRailTop) { "Rail moved: $initialRailTop -> ${railFileTop()}; $initialRailMetrics -> ${railMetrics()}" }
            }
            onMain { main.onBackPressedDispatcher.onBackPressed() }
            await { main.findViewById<View>(R.id.fw_file_host).isShown }
            check(onMainValue { model.currentPath } == originalPath) { "Path changed across destinations" }
            check(onMainValue { (recycler.layoutManager as androidx.recyclerview.widget.GridLayoutManager).findFirstVisibleItemPosition() } == scroll) { "Scroll position changed across destinations" }
            // Selection remains the same when navigating away and back.
            onMain { recycler.findViewHolderForAdapterPosition(scroll)?.itemView?.performLongClick() }
            val selected = onMainValue { model.selectedFiles.toSet() }
            check(selected.isNotEmpty()) { "Fixture file selection did not activate" }
            click(main.getString(R.string.fw_redesign_devices))
            onMain { main.onBackPressedDispatcher.onBackPressed() }
            check(onMainValue { model.selectedFiles.toSet() } == selected) { "Selection changed across destinations" }
            onMain { main.onBackPressedDispatcher.onBackPressed() }
            BridgeHistoryStore.clear(targetContext)
            BridgeHistoryStore.add(targetContext, BridgeHistoryStore.Entry.file(true, "Quarterly report.pdf", 294912))
            BridgeHistoryStore.add(targetContext, BridgeHistoryStore.Entry.clipboard(false, "Generated clipboard preview — no system clipboard access"))
            click(main.getString(R.string.fw_redesign_transfers))
            await { has(main.getString(R.string.fw_redesign_history_scope)) }
            capture(main, "transfers")
            if (navigationOnly) check(railFileTop() == initialRailTop) { "Rail moved on Transfers" }
            onMain { main.onBackPressedDispatcher.onBackPressed() }
            if (navigationOnly) {
                capture(main, "files-return")
                fun rotate(activity: Activity, orientation: Int): FileListActivity {
                    val monitor = addMonitor(FileListActivity::class.java.name, null, false)
                    val rotated = try {
                        onMain { activity.requestedOrientation = orientation }
                        checkNotNull(monitor.waitForActivityWithTimeout(15000)) { "Orientation did not recreate Activity" } as FileListActivity
                    } finally { removeMonitor(monitor) }
                    screens += rotated
                    await { rotated.hasWindowFocus() && (rotated.findViewById<View>(R.id.fw_file_host)?.width ?: 0) > 0 }
                    val nextFragment = rotated.supportFragmentManager.findFragmentById(R.id.fw_file_host)!!
                    check(onMainValue { ViewModelProvider(nextFragment)[FileListViewModel::class.java].currentPath } == originalPath) { "Rotation lost file path" }
                    return rotated
                }
                val portrait = rotate(main, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                check(portrait.resources.configuration.screenWidthDp < 600)
                capture(portrait, "rotated-portrait")
                val landscape = rotate(portrait, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                check(landscape.resources.configuration.screenWidthDp >= 600)
                capture(landscape, "rotated-landscape")
                click(landscape.getString(R.string.fw_redesign_devices))
                capture(landscape, "rotated-devices")
                onMain { landscape.onBackPressedDispatcher.onBackPressed() }
                await { landscape.findViewById<View>(R.id.fw_file_host).isShown }
                return
            }
            for ((action, suffix) in listOf(Intent.ACTION_OPEN_DOCUMENT to "open", Intent.ACTION_GET_CONTENT to "content",
                Intent.ACTION_CREATE_DOCUMENT to "create", Intent.ACTION_OPEN_DOCUMENT_TREE to "directory")) {
                val picker = open(Intent(targetContext, FileListActivity::class.java).setAction(action).setType("*/*")
                    .putExtra(Intent.EXTRA_TITLE, "Generated picker.txt").apply { extraPath = Paths.get(directory.absolutePath) })
                check(picker.findViewById<View>(R.id.fw_file_host) == null) { "Document picker includes app navigation" }
                val toolbarBounds = android.graphics.Rect()
                val pathBounds = android.graphics.Rect()
                onMain {
                    picker.findViewById<View>(R.id.toolbar).getGlobalVisibleRect(toolbarBounds)
                    picker.findViewById<View>(R.id.breadcrumbLayout).getGlobalVisibleRect(pathBounds)
                    val toolbar = picker.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
                    val title = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }
                        .filterIsInstance<android.widget.TextView>().first { it.text == toolbar.title }
                    check(title.layout?.getEllipsisCount(0) == 0) { "Picker task title is truncated: $suffix" }
                    val tools = toolbar.menu.findItem(R.id.action_page_model).subMenu!!
                    check(tools.findItem(R.id.fw_picker_filter).isVisible && tools.findItem(R.id.fw_picker_view_options).isVisible) { "Picker tools were lost" }
                }
                check(pathBounds.top >= toolbarBounds.bottom) { "Picker title overlaps its breadcrumb: $suffix" }
                capture(picker, "picker-$suffix")
                onMain { picker.finish() }
            }
            val stateField = BridgeConnectionService::class.java.getDeclaredField("_state").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val state = stateField.get(null) as MutableLiveData<BridgeConnectionService.State>
            onMain { state.value = BridgeConnectionService.State(phase = BridgeConnectionService.Phase.CONNECTED, connected = true,
                macName = "Studio Mac", files = listOf("Project brief.pdf" to 241664L, "Morning recording.m4a" to 4718592L, "Video preview.mp4" to 13631488L)) }
            val bridge = open(Intent(targetContext, BridgeActivity::class.java))
            await { has("Project brief.pdf") }
            capture(bridge, "mac-files")
            check(!has("Quarterly report.pdf")) { "Transfer history obscures shared files" }
            onMain { bridge.finish() }
            val clipboard = open(Intent(targetContext, ClipboardActivity::class.java))
            capture(clipboard, "clipboard-empty")
            onMain { clipboard.finish() }
            val root = File(targetContext.noBackupFilesDir, "vault")
            VaultSession.lock(); root.deleteRecursively()
            VaultSession.unlock(root, "Generated-QA-passphrase-2026".toByteArray(), true)
            val data = ByteArray(4096) { (it % 251).toByte() }
            VaultSession.import(ByteArrayInputStream(data), "Generated encrypted sample.txt")
            val generation = VaultSession.generation()
            val handle = VaultSession.open("/Generated encrypted sample.txt", generation)
            val slice = ByteArray(37)
            check(VaultSession.read(handle, 191, slice, 0, slice.size, generation) == slice.size && slice.contentEquals(data.copyOfRange(191, 228))) { "Vault random read mismatch" }
            val vault = open(Intent(targetContext, VaultActivity::class.java))
            await { has("Generated encrypted sample.txt") }
            capture(vault, "vault-open")
            click(vault.getString(R.string.fw_lock))
            await { !VaultSession.isOpen() }
            check(runCatching { VaultSession.read(handle, 0, slice, 0, slice.size, generation) }.isFailure) { "Locked vault retained a readable handle" }
            capture(vault, "vault-locked")
            fun passwordDraft(): String {
                val field = VaultActivity::class.java.getDeclaredField("passwordDraft\$delegate").apply { isAccessible = true }
                return (field.get(vault) as androidx.compose.runtime.State<*>).value as String
            }
            fun typePassword(value: String) {
                await { nodes().any { it.isEditable } }
                check(nodes().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                })) { "Password input rejected text" }
                await { onMainValue { passwordDraft() } == value }
            }
            typePassword("Generated-QA-passphrase-2026")
            click(vault.getString(R.string.fw_unlock))
            await { VaultSession.isOpen() && has("Generated encrypted sample.txt") }
            check(onMainValue { passwordDraft() }.isEmpty()) { "Submitted password was retained" }
            click(vault.getString(R.string.fw_lock))
            typePassword("Generated draft cleared on background")
            val cover = open(Intent(targetContext, ClipboardActivity::class.java))
            await { onMainValue { passwordDraft() }.isEmpty() }
            check(!VaultSession.isOpen()) { "Background vault remained open" }
            onMain { cover.finish() }
            await { vault.hasWindowFocus() }
            check(onMainValue { passwordDraft() }.isEmpty()) { "Password draft returned after resume" }
            capture(vault, "vault-resumed-cleared")
            onMain { vault.finish() }
            val largeDevices = open(Intent(targetContext, RedesignPreviewActivity::class.java))
            capture(largeDevices, "devices-font150")
            onMain { largeDevices.finish() }
            val largeTransfers = open(Intent(targetContext, RedesignPreviewActivity::class.java).putExtra("transfers", true))
            capture(largeTransfers, "transfers-font150")
            onMain { largeTransfers.finish() }
        } catch (error: Throwable) { failure = error }
        finally {
            onMain { screens.reversed().forEach { if (!it.isDestroyed) it.finish() } }
            VaultSession.lock()
            directory.deleteRecursively()
            finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putString("result", failure?.stackTraceToString() ?: if (navigationOnly) "PASS: >=600dp rail startup, landscape/portrait rotation with path retained, navigation path/scroll/selection, system Back and retained file view" else "PASS: navigation path/scroll/selection, picker isolation, shared-file hierarchy, vault random read/lock invalidation, password unlock and synchronous background draft clearing")
                putString("screenshots", outputs.joinToString("\n"))
                putBoolean("default_english_checked", checkDefault)
            })
        }
    }
    private fun open(intent: Intent): Activity = startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)).also { activity ->
        screens += activity
        await { activity.hasWindowFocus() && activity.window.decorView.width > 0 }
        onMain { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        waitForIdleSync(); SystemClock.sleep(350)
    }
    private fun capture(activity: Activity, name: String) {
        // Generated fixtures in this disposable package only; production secure flags stay intact.
        onMain { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        waitForIdleSync(); SystemClock.sleep(400)
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        val output = File(targetContext.cacheDir, "redesign-${locale}-${if (night) "dark" else "light"}${if (accent) "-green" else ""}-${if (navigationOnly) "wide-" else ""}$name.png")
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle(); outputs += output.absolutePath
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val output = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            output += node
            repeat(node.childCount) { collect(node.getChild(it)) }
        }
        collect(uiAutomation.rootInActiveWindow)
        if (output.isEmpty()) uiAutomation.windows.forEach { collect(it.root) }
        return output
    }
    private fun has(text: String) = nodes().any { it.text?.toString()?.contains(text) == true || it.contentDescription?.toString()?.contains(text) == true }
    private fun click(text: String) {
        await { has(text) }
        val candidates = nodes().filter { it.text?.toString()?.contains(text) == true || it.contentDescription?.toString()?.contains(text) == true }
        val node = candidates.lastOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text } ?: candidates.last()
        var action: AccessibilityNodeInfo? = node
        while (action != null && !action.isClickable) action = action.parent
        check(action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot click $text" }
        waitForIdleSync(); SystemClock.sleep(300)
    }
    private fun await(check: () -> Boolean) { val deadline = SystemClock.uptimeMillis() + 15000; while (SystemClock.uptimeMillis() < deadline) { if (check()) return; SystemClock.sleep(100) }; error("Timed out waiting for UI") }
    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun <T> onMainValue(action: () -> T): T { var value: T? = null; onMain { value = action() }; @Suppress("UNCHECKED_CAST") return value as T }
}
