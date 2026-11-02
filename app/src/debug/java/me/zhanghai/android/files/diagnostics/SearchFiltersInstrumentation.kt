package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Paths
import java.io.File
import java.time.LocalDate
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filelist.*
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.util.valueCompat

/** End-to-end searches against generated cache files, without touching user storage. */
class SearchFiltersInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val directory = File(targetContext.cacheDir, "search-filter-${SystemClock.uptimeMillis()}")
        val originalView = Settings.FILE_LIST_VIEW_TYPE.valueCompat
        val originalDrawer = Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.valueCompat
        val originalRoot = Settings.ROOT_STRATEGY.valueCompat
        val originalSort = Settings.FILE_LIST_SORT_OPTIONS.valueCompat
        var activity: FileListActivity? = null
        var failure: Throwable? = null
        try {
            check(targetContext.packageName.endsWith(".recentqa")) { "Requires isolated .recentqa package" }
            check(directory.mkdir())
            File(directory, "one.md").writeText("Markdown")
            File(directory, "two.txt").writeText("Text")
            File(directory, "child").mkdir()
            File(directory, "child/nested.md").writeText("Nested")
            onMain {
                Settings.ROOT_STRATEGY.putValue(RootStrategy.NEVER)
                Settings.FILE_LIST_VIEW_TYPE.putValue(FileViewType.LIST)
                Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(false)
            }
            activity = startActivitySync(FileListActivity.createViewIntent(Paths.get(directory.toURI()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as FileListActivity
            val screen = activity
            val content = screen.findViewById<ViewGroup>(R.id.contentLayout)
            val rv = content.findViewById<RecyclerView>(R.id.recyclerView)
            val fragment = screen.supportFragmentManager.fragments.filterIsInstance<FileListFragment>().single()
            await { rv.adapter?.itemCount == 3 && rv.childCount > 0 }
            fun filter(filters: SearchFilters, count: Int) {
                onMain { fragment.applySearchFilters(filters) }
                await { rv.adapter?.itemCount == count }
            }
            filter(SearchFilters(type = SearchFileType.MARKDOWN, recursive = false), 1)
            filter(SearchFilters(type = SearchFileType.MARKDOWN), 2)
            filter(SearchFilters(type = SearchFileType.EXTENSION, extension = "TXT"), 1)
            val today = LocalDate.now()
            filter(SearchFilters(type = SearchFileType.MARKDOWN, dates = SearchDateRange(today, today)), 2)
            // Local provider has no birth-time support; it must not substitute modification time.
            filter(SearchFilters(dateField = SearchDateField.CREATED, dates = SearchDateRange(today, today)), 0)
            await { content.findViewById<View>(R.id.emptyView).visibility == View.VISIBLE }
            onMain {
                val empty = content.findViewById<View>(R.id.emptyView)
                check(kotlin.math.abs((empty.top + empty.bottom) / 2f - content.height / 2f) < 8)
                SearchFiltersDialogFragment.newInstance(SearchFilters(type = SearchFileType.MARKDOWN))
                    .showNow(fragment.childFragmentManager, "search_filters")
            }
            val dialog = (fragment.childFragmentManager.findFragmentByTag("search_filters") as SearchFiltersDialogFragment).dialog!!
            onMain {
                check(dialog.findViewById<android.widget.Spinner>(R.id.typeSpinner).selectedItemPosition == SearchFileType.MARKDOWN.ordinal)
                check(dialog.findViewById<View>(R.id.recursiveCheck) != null)
                check(dialog.findViewById<View>(R.id.dateFieldSpinner) != null)
                check(dialog.findViewById<View>(R.id.dateRangeSpinner) != null)
                (fragment.childFragmentManager.findFragmentByTag("search_filters") as SearchFiltersDialogFragment).dismissNow()
            }
            onMain { Settings.FILE_LIST_VIEW_TYPE.putValue(FileViewType.PREVIEW) }
            if (screen.resources.configuration.screenWidthDp >= 640) {
                val pane = content.findViewById<ViewGroup>(R.id.previewPane)
                await { pane.visibility == View.VISIBLE && pane.width > 0 }
                onMain {
                    val empty = pane.findViewById<View>(R.id.previewEmptyState)
                    check(empty.visibility == View.VISIBLE)
                    check(kotlin.math.abs((empty.top + empty.bottom) / 2f - pane.height / 2f) < 8)
                    check(pane.findViewById<View>(R.id.previewScroll).visibility == View.GONE)
                }
            }
            File(directory, "photo.jpg").writeText("Generated JPEG metadata fixture")
            File(directory, "diagram.png").writeText("Generated PNG metadata fixture")
            File(directory, "clip.mp4").writeText("Generated video metadata fixture")
            File(directory, "voice.mp3").writeText("Generated audio metadata fixture")
            File(directory, "guide.pdf").writeText("Generated PDF metadata fixture")
            File(directory, "child/nested.png").writeText("Generated child PNG metadata fixture")
            File(directory, "child/nested.mp4").writeText("Generated child video metadata fixture")
            onMain {
                Settings.FILE_LIST_VIEW_TYPE.putValue(FileViewType.LIST)
                fragment.navigateToRoot(Paths.get(directory.absolutePath))
            }
            fun names(): Set<String> = (rv.adapter as ConcatAdapter).adapters
                .filterIsInstance<FileListAdapter>().single().list.map { it.path.fileName.toString() }.toSet()
            val rootNames = setOf("one.md", "two.txt", "child", "photo.jpg", "diagram.png", "clip.mp4", "voice.mp3", "guide.pdf")
            await { names() == rootNames }
            fun filterRow() = screen.findViewById<View>(R.id.quickFiltersScroll)
            fun toggleFilterRow() {
                onMain {
                    val action = screen.findViewById<Toolbar>(R.id.toolbar).findViewById<View>(R.id.action_search_filters)
                    check(action != null && action.isShown && action.performClick())
                }
            }
            fun revealFilters() {
                if (onMain { !filterRow().isShown }) toggleFilterRow()
                await { filterRow().isShown && !filterRow().isLayoutRequested }
            }
            onMain { check(!filterRow().isShown) { "Filters must be hidden for ordinary browsing" } }
            onMain {
                val item = screen.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_search)
                check(item.expandActionView())
            }
            await { filterRow().isShown }
            onMain { screen.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_search).collapseActionView() }
            await { !filterRow().isShown }
            revealFilters()
            onMain { screen.onBackPressedDispatcher.onBackPressed() }
            await { !filterRow().isShown }
            revealFilters()
            val categoryIds = listOf(R.id.quickFilterAll, R.id.quickFilterImage, R.id.quickFilterVideo, R.id.quickFilterAudio)
            val geometry = onMain { categoryIds.map { id -> screen.findViewById<View>(id).let { it.width to it.left } } }
            fun checkCategoryGeometry() {
                await { categoryIds.map { id -> screen.findViewById<View>(id).let { it.width to it.left } } == geometry }
                onMain { categoryIds.forEach { id ->
                    check(!screen.findViewById<com.google.android.material.chip.Chip>(id).isCheckedIconVisible)
                } }
            }
            fun chip(id: Int, expected: Set<String>) {
                revealFilters()
                onMain {
                    val view = screen.findViewById<View>(id)
                    check(view.isShown && view.isEnabled && view.performClick())
                }
                await { names() == expected }
                if (id != R.id.quickFilterAll) await { filterRow().isShown }
                else await { !filterRow().isShown }
            }
            chip(R.id.quickFilterImage, setOf("photo.jpg", "diagram.png"))
            checkCategoryGeometry()
            onMain {
                val image = screen.findViewById<com.google.android.material.chip.Chip>(R.id.quickFilterImage)
                check(image.performClick() && image.isChecked) { "Clicking the selected image chip cleared its checked state" }
            }
            await { names() == setOf("photo.jpg", "diagram.png") }
            fun orderedNames(): List<String> = (rv.adapter as ConcatAdapter).adapters
                .filterIsInstance<FileListAdapter>().single().list.map { it.path.fileName.toString() }
            onMain {
                val menu = screen.findViewById<Toolbar>(R.id.toolbar).menu
                check(fragment.onOptionsItemSelected(menu.findItem(R.id.action_sort_by_name)))
                if (!menu.findItem(R.id.action_sort_order_ascending).isChecked) {
                    check(fragment.onOptionsItemSelected(menu.findItem(R.id.action_sort_order_ascending)))
                }
            }
            await { orderedNames() == listOf("diagram.png", "photo.jpg") }
            onMain {
                check(fragment.onOptionsItemSelected(screen.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_sort_order_ascending)))
            }
            await { orderedNames() == listOf("photo.jpg", "diagram.png") }
            onMain {
                check(fragment.onOptionsItemSelected(screen.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_sort_order_ascending)))
            }
            await { orderedNames() == listOf("diagram.png", "photo.jpg") }
            chip(R.id.quickFilterVideo, setOf("clip.mp4"))
            checkCategoryGeometry()
            chip(R.id.quickFilterAudio, setOf("voice.mp3"))
            checkCategoryGeometry()
            chip(R.id.quickFilterAll, rootNames)
            revealFilters()
            onMain { check(screen.findViewById<View>(R.id.quickFilterFormat).performClick()) }
            clickPopupText("PNG")
            await { names() == setOf("diagram.png") }
            await { rv.findViewHolderForAdapterPosition(0)?.itemView
                ?.findViewById<android.widget.TextView>(R.id.nameText)?.text?.toString() == "diagram.png" }
            waitForIdleSync()
            onMain {
                check(screen.findViewById<android.widget.TextView>(R.id.quickFilterSelected).text.toString() == "PNG")
                saveScreenshot(screen, "quick-filters-ordinary-png.png")
                check(screen.findViewById<View>(R.id.quickFilterFormat).performClick())
            }
            clickPopupText("PDF")
            await { names() == setOf("guide.pdf") }
            onMain { check(screen.findViewById<View>(R.id.quickFilterFormat).performClick()) }
            clickPopupText(targetContext.getString(R.string.fw_filter_more))
            await { fragment.childFragmentManager.findFragmentByTag("search_filters") is SearchFiltersDialogFragment }
            onMain {
                val advanced = fragment.childFragmentManager.findFragmentByTag("search_filters") as SearchFiltersDialogFragment
                check(advanced.dialog!!.findViewById<android.widget.Spinner>(R.id.typeSpinner).selectedItemPosition == SearchFileType.EXTENSION.ordinal)
                advanced.dismissNow()
            }
            chip(R.id.quickFilterImage, setOf("photo.jpg", "diagram.png"))
            lateinit var search: SearchView
            onMain {
                val item = screen.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_search)
                check(item.isVisible && item.expandActionView())
                search = item.actionView as SearchView
                search.setQuery("diagram", true)
            }
            await { names() == setOf("diagram.png") }
            onMain { search.setQuery("", false) }
            await { names() == setOf("photo.jpg", "diagram.png") }
            onMain { search.setQuery("missing-generated-file", true) }
            await { names().isEmpty() }
            onMain {
                screen.onBackPressedDispatcher.onBackPressed()
                check(!screen.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_search).isActionViewExpanded)
            }
            await { names() == setOf("photo.jpg", "diagram.png") }
            onMain { fragment.navigateToRoot(Paths.get(File(directory, "child").absolutePath)) }
            await { names() == setOf("nested.md", "nested.png", "nested.mp4") }
            onMain { check(screen.findViewById<com.google.android.material.chip.Chip>(R.id.quickFilterAll).isChecked) }
            chip(R.id.quickFilterImage, setOf("nested.png"))
            onMain { fragment.navigateToRoot(Paths.get(directory.absolutePath)) }
            await { names() == rootNames }
            onMain { fragment.navigateTo(Paths.get(File(directory, "child").absolutePath)) }
            await { names() == setOf("nested.md", "nested.png", "nested.mp4") }
            chip(R.id.quickFilterImage, setOf("nested.png"))
            onMain { screen.onBackPressedDispatcher.onBackPressed() }
            await { names() == rootNames }
            onMain { check(screen.findViewById<com.google.android.material.chip.Chip>(R.id.quickFilterAll).isChecked) }
            // The virtual recent entry uses the same disclosure rules, independently of its file results.
            onMain { fragment.navigateToRecentFiles() }
            await { screen.findViewById<View>(R.id.recentFilesTitle).isShown && !filterRow().isShown }
            revealFilters()
            onMain { check(screen.findViewById<View>(R.id.quickFilterImage).performClick()) }
            await { screen.findViewById<com.google.android.material.chip.Chip>(R.id.quickFilterImage).isChecked && filterRow().isShown }
            onMain {
                val item = screen.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_search)
                check(item.expandActionView())
                check(item.collapseActionView())
            }
            await { filterRow().isShown }
            onMain { check(screen.findViewById<View>(R.id.quickFilterAll).performClick()) }
            await { !filterRow().isShown }
            onMain {
                val item = screen.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_search)
                check(item.expandActionView())
            }
            await { filterRow().isShown }
            onMain { screen.onBackPressedDispatcher.onBackPressed() }
            await { !filterRow().isShown }
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                activity?.let { onMain { it.finish() } }
                onMain {
                    Settings.FILE_LIST_VIEW_TYPE.putValue(originalView)
                    Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(originalDrawer)
                    Settings.ROOT_STRATEGY.putValue(originalRoot)
                    Settings.FILE_LIST_SORT_OPTIONS.putValue(originalSort)
                }
                directory.deleteRecursively()
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure?.addSuppressed(cleanup)
            }
        }
        val error = failure
        finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", if (error == null)
                "PASS: generated current-folder/recursive/type/extension/date searches, unknown creation time, advanced dialog and centered empty states; actual Image/Video/Audio/All chip clicks stay in this folder; name-sort menu and ascending/descending changes reorder type-only results; actual PNG/PDF/More popup selections; selected-format chip and generated View.draw screenshot; query clear and close preserve image filter; directory navigation and system Back clear conditions; ordinary/recent filter row is hidden by default, opens through search/filter, pins non-All filters, hides after All and closing search; category widths and positions remain identical without checked icons.\n"
            else "FAILED: ${error.stackTraceToString()}\n")
        })
    }

    private fun clickPopupText(label: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            val match = root?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text?.toString() == label && it.isVisibleToUser }
            if (match != null) {
                var clickable: AccessibilityNodeInfo? = match
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            Thread.sleep(50)
        }
        error("Popup option was not clickable: $label")
    }

    private fun saveScreenshot(activity: Activity, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            File(targetContext.cacheDir, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            onMain { ready = condition() }
            Thread.sleep(50)
        }
        check(ready) { "Search/UI state did not become ready" }
    }
}
