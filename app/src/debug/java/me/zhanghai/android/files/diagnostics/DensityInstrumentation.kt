package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.os.LocaleListCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filelist.*
import me.zhanghai.android.files.navigation.BookmarkDirectory
import me.zhanghai.android.files.navigation.navigationItems
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.storage.PrimaryStorageVolume
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.valueCompat

/** Generated fixtures in a disposable package only. Never touches the installed product data. */
class DensityInstrumentation : Instrumentation() {
    private var locale = "en"
    private var label = "compact"
    private val activities = mutableListOf<Activity>()
    private val outputs = mutableListOf<String>()
    override fun onCreate(arguments: Bundle?) {
        locale = arguments?.getString("locale") ?: locale
        label = arguments?.getString("label") ?: label
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        var failure: Throwable? = null
        val root = File(targetContext.cacheDir, "density-generated")
        val child = File(root, "Projects/客户文件与 very long directory name ending with useful-current-folder")
        val collection = MediaStore.Files.getContentUri("external")
        var uri: android.net.Uri? = null
        try {
            check(targetContext.packageName.endsWith(".redesignqa"))
            child.mkdirs()
            File(child, "Keep 用户文件名 original name.txt").writeText("Generated acceptance fixture")
            val metadata = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Quarterly report 用户原文 2026.pdf")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Generated FileWeave acceptance/Client project/Useful end folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = targetContext.contentResolver.insert(collection, metadata)!!
            targetContext.contentResolver.openOutputStream(uri)!!.use { it.write("Generated file".toByteArray()) }
            targetContext.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            onMain {
                // The disposable package's ambient bridge must not mirror host clipboard text
                // or run a second real connection during generated-data visual acceptance.
                me.zhanghai.android.files.bridge.BridgeConnectionService.stop(targetContext)
                Settings.ROOT_STRATEGY.putValue(RootStrategy.NEVER)
                Settings.NIGHT_MODE.putValue(NightMode.OFF)
                Settings.BOOKMARK_DIRECTORIES.putValue(listOf(BookmarkDirectory("屏幕截图", Paths.get(child.absolutePath))))
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale))
                if (android.os.Build.VERSION.SDK_INT >= 33) targetContext.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(locale)
            }
            // Allow a pre-existing SystemUI clipboard overlay from an earlier QA process to
            // expire without reading or mutating either device's clipboard.
            SystemClock.sleep(7000)
            val home = open(Intent(targetContext, FileListActivity::class.java).setAction(Intent.ACTION_MAIN)) as FileListActivity
            await { home.findViewById<RecyclerView>(R.id.recyclerView).adapter!!.itemCount > 0 }
            onMain {
                for ((language, expected) in listOf("en" to "Internal storage", "zh-CN" to "内部存储")) {
                    val config = Configuration(home.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
                    val context = home.createConfigurationContext(config)
                    check(PrimaryStorageVolume(null, true).getName(context) == expected)
                    check(PrimaryStorageVolume("内部存储 custom", true).getName(context) == "内部存储 custom")
                }
                check(navigationItems.filterNotNull().any { it.getTitle(home) == "屏幕截图" }) { "Custom bookmark was translated" }
                assertControls(home)
                val locations = descendants(home.findViewById(R.id.recyclerView)).filterIsInstance<TextView>().filter { it.id == R.id.locationText && it.isShown }
                check(locations.any { it.text.toString().endsWith("Useful end folder") && !it.text.startsWith("/storage/emulated/") }) { "Recent row lost useful path tail" }
                val title = home.findViewById<TextView>(R.id.recentFilesTitle)
                check(title.layout.getEllipsisCount(0) == 0) { "Recent files title was crowded out" }
                sendStatus(0, Bundle().apply {
                    putString("metrics", "width=${home.resources.configuration.screenWidthDp}dp font=${home.resources.configuration.fontScale}; recentRow=${home.findViewById<RecyclerView>(R.id.recyclerView).getChildAt(0).height / home.resources.displayMetrics.density}dp; path=${locations.firstOrNull()?.text}")
                })
            }
            capture(home, "recent")
            val homeFragment = home.supportFragmentManager.findFragmentById(R.id.fw_file_host) as FileListFragment
            onMain { homeFragment.applySearchFilters(SearchFilters(type = SearchFileType.IMAGE)) }
            waitForIdleSync(); SystemClock.sleep(350)
            onMain { check(home.findViewById<com.google.android.material.chip.Chip>(R.id.quickFilterImage).isShown) }
            capture(home, "active-filter")
            onMain { homeFragment.applySearchFilters(SearchFilters()) }
            waitForIdleSync(); SystemClock.sleep(350)
            val drawer = home.findViewById<DrawerLayout>(R.id.drawerLayout)
            if (drawer != null) {
                onMain { drawer.openDrawer(GravityCompat.START) }
                waitForIdleSync(); SystemClock.sleep(350)
                onMain {
                    val rows = descendants(drawer).filterIsInstance<TextView>().filter { it.isShown && it.id == R.id.titleText }
                    check(rows.any { it.text.toString() == home.getString(R.string.fw_internal_storage) })
                    for (text in rows) {
                        check(text.height >= (text.layout?.height ?: 0)) { "Sidebar clips ${text.text}" }
                        // Generated custom labels may be arbitrarily long, while all visible
                        // product labels must fit without disappearing behind switches/settings.
                        if (text.text.toString() != "屏幕截图") {
                            val layout = checkNotNull(text.layout)
                            check((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 }) { "Sidebar label ellipsized: ${text.text}" }
                        }
                    }
                }
                capture(home, "sidebar")
                val sidebar = descendants(drawer).filterIsInstance<RecyclerView>().first { it.adapter is me.zhanghai.android.files.navigation.NavigationListAdapter }
                onMain { (sidebar.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).scrollToPositionWithOffset(6, 0) }
                waitForIdleSync(); SystemClock.sleep(350)
                onMain {
                    val labels = descendants(sidebar).filterIsInstance<TextView>().filter { it.id == R.id.titleText }
                    for (text in labels) {
                        val layout = checkNotNull(text.layout)
                        check((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 }) { "Sidebar controls squeeze ${text.text}" }
                    }
                }
                capture(home, "sidebar-controls")
                onMain { drawer.closeDrawer(GravityCompat.START) }
            }
            val files = open(Intent(targetContext, FileListActivity::class.java).setAction(Intent.ACTION_MAIN).apply { extraPath = Paths.get(child.absolutePath) }) as FileListActivity
            await { files.findViewById<RecyclerView>(R.id.recyclerView).adapter!!.itemCount > 0 }
            val fragment = files.supportFragmentManager.findFragmentById(R.id.fw_file_host) as FileListFragment
            val model = onMainValue { ViewModelProvider(fragment)[FileListViewModel::class.java] }
            onMain { assertControls(files); assertBreadcrumb(files) }
            capture(files, "folder")
            // Navigate back while retaining forward trail segments, then resize the same breadcrumb.
            onMain { fragment.navigateTo(Paths.get(child.parentFile!!.absolutePath)) }
            waitForIdleSync(); SystemClock.sleep(350)
            val breadcrumb = files.findViewById<BreadcrumbLayout>(R.id.breadcrumbLayout)
            val originalWidth = onMainValue { breadcrumb.layoutParams.width }
            onMain {
                assertBreadcrumb(files)
                breadcrumb.layoutParams = breadcrumb.layoutParams.apply { width = (160 * files.resources.displayMetrics.density).toInt() }
                breadcrumb.requestLayout()
            }
            waitForIdleSync(); SystemClock.sleep(350)
            onMain { assertBreadcrumb(files) }
            onMain { breadcrumb.layoutParams = breadcrumb.layoutParams.apply { width = originalWidth } }
            waitForIdleSync(); SystemClock.sleep(350)
            capture(files, "back-leaf")
            onMain { files.findViewById<Toolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_page_more, 0) }
            waitForIdleSync(); SystemClock.sleep(350)
            capture(files, "more")
            onMain { me.zhanghai.android.files.ui.ActionPanel.dismiss(files) }
            onMain { fragment.showPath(model.currentPath) }
            waitForIdleSync(); SystemClock.sleep(350)
            capture(files, "full-path")
            onMain { fragment.childFragmentManager.fragments.filterIsInstance<androidx.fragment.app.DialogFragment>().forEach { it.dismiss() } }
            val picker = open(Intent(targetContext, FileListActivity::class.java).setAction(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").apply { extraPath = Paths.get(child.absolutePath) })
            onMain {
                val toolbar = picker.findViewById<Toolbar>(R.id.toolbar)
                check(!toolbar.menu.findItem(R.id.action_search_filters).isVisible)
                check(!toolbar.menu.findItem(R.id.action_view_sort).isVisible)
                val pathBounds = Rect(); val titleBounds = Rect()
                picker.findViewById<View>(R.id.breadcrumbLayout).getGlobalVisibleRect(pathBounds)
                toolbar.getGlobalVisibleRect(titleBounds)
                check(pathBounds.top >= titleBounds.bottom)
                check(model.currentPath == Paths.get(child.parentFile!!.absolutePath))
            }
            capture(picker, "picker")
        } catch (error: Throwable) { failure = error }
        finally {
            onMain { activities.reversed().forEach { if (!it.isDestroyed) it.finish() } }
            uri?.let { targetContext.contentResolver.delete(it, null, null) }
            root.deleteRecursively()
            finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putString("result", failure?.stackTraceToString() ?: "PASS: localized system storage/custom names, responsive controls and More reachability, readable sidebar, relative recent paths, current breadcrumb including retained forward history/resize, isolated picker")
                putString("screenshots", outputs.joinToString("\n"))
            })
        }
    }
    private fun assertControls(activity: Activity) {
        val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
        val width = toolbar.width / activity.resources.displayMetrics.density
        val expected = FileToolbarLayout.inlineSecondaryActions(width, activity.resources.configuration.fontScale, false, false)
        check(toolbar.menu.findItem(R.id.action_search_filters).isVisible == (expected >= 1))
        check(toolbar.menu.findItem(R.id.action_view_sort).isVisible == (expected >= 2))
        val more = toolbar.menu.findItem(R.id.action_page_model).subMenu!!
        if (expected < 1) check(more.findItem(R.id.fw_picker_filter)?.isVisible == true)
        if (expected < 2) check(more.findItem(R.id.fw_picker_view_options)?.isVisible == true)
    }
    private fun assertBreadcrumb(activity: Activity) {
        val breadcrumb = activity.findViewById<BreadcrumbLayout>(R.id.breadcrumbLayout)
        val group = breadcrumb.getChildAt(0) as ViewGroup
        val selected = (0 until group.childCount).map { group.getChildAt(it) }.first { it.isActivated }
        val rect = Rect()
        check(selected.getLocalVisibleRect(rect) && rect.width() == selected.width) { "Current breadcrumb clipped: ${rect.width()}/${selected.width}" }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun open(intent: Intent): Activity = startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)).also {
        activities += it
        await { it.hasWindowFocus() && it.window.decorView.width > 0 }
        onMain { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        waitForIdleSync(); SystemClock.sleep(400)
    }
    private fun capture(activity: Activity, name: String) {
        onMain { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        waitForIdleSync(); SystemClock.sleep(300)
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        val output = File(targetContext.cacheDir, "density-$locale-$label-$name.png")
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle(); outputs += output.absolutePath
    }
    private fun await(check: () -> Boolean) { val deadline = SystemClock.uptimeMillis() + 15000; while (SystemClock.uptimeMillis() < deadline) { if (check()) return; SystemClock.sleep(100) }; error("Timed out waiting for UI") }
    private fun onMain(action: () -> Unit) { var failure: Throwable? = null; runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }; failure?.let { throw it } }
    private fun <T> onMainValue(action: () -> T): T { var value: T? = null; onMain { value = action() }; @Suppress("UNCHECKED_CAST") return value as T }
}
