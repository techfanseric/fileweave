package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Paths
import java.io.File
import java.time.LocalDate
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filelist.*
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/** End-to-end searches against generated cache files, without touching user storage. */
class SearchFiltersInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val directory = File(targetContext.cacheDir, "search-filter-${SystemClock.uptimeMillis()}")
        val originalView = Settings.FILE_LIST_VIEW_TYPE.valueCompat
        val originalDrawer = Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.valueCompat
        var activity: FileListActivity? = null
        try {
            check(directory.mkdir())
            File(directory, "one.md").writeText("Markdown")
            File(directory, "two.txt").writeText("Text")
            File(directory, "child").mkdir()
            File(directory, "child/nested.md").writeText("Nested")
            runOnMainSync {
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
                runOnMainSync { fragment.applySearchFilters(filters) }
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
            runOnMainSync {
                val empty = content.findViewById<View>(R.id.emptyView)
                check(kotlin.math.abs((empty.top + empty.bottom) / 2f - content.height / 2f) < 8)
                SearchFiltersDialogFragment.newInstance(SearchFilters(type = SearchFileType.MARKDOWN))
                    .showNow(fragment.childFragmentManager, "search_filters")
            }
            val dialog = (fragment.childFragmentManager.findFragmentByTag("search_filters") as SearchFiltersDialogFragment).dialog!!
            runOnMainSync {
                check(dialog.findViewById<android.widget.Spinner>(R.id.typeSpinner).selectedItemPosition == SearchFileType.MARKDOWN.ordinal)
                check(dialog.findViewById<View>(R.id.recursiveCheck) != null)
                check(dialog.findViewById<View>(R.id.dateFieldSpinner) != null)
                check(dialog.findViewById<View>(R.id.dateRangeSpinner) != null)
                (fragment.childFragmentManager.findFragmentByTag("search_filters") as SearchFiltersDialogFragment).dismissNow()
            }
            runOnMainSync { Settings.FILE_LIST_VIEW_TYPE.putValue(FileViewType.PREVIEW) }
            if (screen.resources.configuration.screenWidthDp >= 640) {
                val pane = content.findViewById<ViewGroup>(R.id.previewPane)
                await { pane.visibility == View.VISIBLE && pane.width > 0 }
                runOnMainSync {
                    val empty = pane.findViewById<View>(R.id.previewEmptyState)
                    check(empty.visibility == View.VISIBLE)
                    check(kotlin.math.abs((empty.top + empty.bottom) / 2f - pane.height / 2f) < 8)
                    check(pane.findViewById<View>(R.id.previewScroll).visibility == View.GONE)
                }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream",
                "PASS: current-folder/recursive/type/extension/date searches, unknown creation time, filter dialog, centered empty states\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED: ${error.stackTraceToString()}\n") })
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
            runOnMainSync {
                Settings.FILE_LIST_VIEW_TYPE.putValue(originalView)
                Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(originalDrawer)
            }
            directory.deleteRecursively()
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            runOnMainSync { ready = condition() }
            Thread.sleep(50)
        }
        check(ready) { "Search/UI state did not become ready" }
    }
}
