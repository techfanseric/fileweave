package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Paths
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.FileListAdapter
import me.zhanghai.android.files.filelist.FileListFragment
import me.zhanghai.android.files.filelist.FileViewType
import me.zhanghai.android.files.filelist.RecentFileRules
import me.zhanghai.android.files.filelist.RecentFilesLiveData
import me.zhanghai.android.files.filelist.RecentFilesSettings
import me.zhanghai.android.files.filelist.SearchFileType
import me.zhanghai.android.files.filelist.SearchFilters
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Isolated QA-package check against the parent's generated shared-storage index fixtures. */
class RecentFilesInstrumentation : Instrumentation() {
    private var fixtureRoot = "/sdcard/Download/FileWeaveRecentQA"

    override fun onCreate(arguments: Bundle?) {
        fixtureRoot = arguments?.getString("fixture_root") ?: fixtureRoot
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var original: RecentFileRules? = null
        var source: RecentFilesLiveData? = null
        var observer: Observer<Stateful<List<FileItem>>>? = null
        val state = AtomicReference<Stateful<List<FileItem>>>()
        val sequence = mutableListOf<String>()
        var failure: Throwable? = null
        var report = ""
        val generatedUris = mutableListOf<Uri>()
        var generatedRoot: File? = null
        var activity: FileListActivity? = null
        var originalView: FileViewType? = null
        var originalDrawer: Boolean? = null
        var originalRootStrategy: RootStrategy? = null
        try {
            check(targetContext.packageName.endsWith(".recentqa")) { "Requires isolated .recentqa package" }
            val root = File(fixtureRoot).canonicalPath
            val expected = indexedFixtures(root)
            check(expected.size >= 3) { "Expected three generated indexed fixtures; found ${expected.size}" }
            onMain {
                original = RecentFilesSettings.read()
                RecentFilesSettings.write(RecentFileRules())
                source = RecentFilesLiveData()
                observer = Observer {
                    state.set(it)
                    sequence += "${it.javaClass.simpleName}(${it.value?.size})"
                }
                source!!.observeForever(observer!!)
            }
            fun terminal(): List<FileItem> {
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (SystemClock.uptimeMillis() < deadline) {
                    when (val result = state.get()) {
                        is Success -> return result.value
                        is Failure -> throw result.throwable
                        else -> Thread.sleep(25)
                    }
                }
                val pending = RecentFilesLiveData::class.java.getDeclaredField("future").run {
                    isAccessible = true
                    get(source) as? Future<*>
                }
                // FutureTask captures Errors; surface the original cause rather than just timing out.
                if (pending?.isDone == true && !pending.isCancelled) pending.get(100, TimeUnit.MILLISECONDS)
                error("Recent source timed out; states=$sequence; futureDone=${pending?.isDone}; futureCancelled=${pending?.isCancelled}")
            }
            val all = terminal()
            check(all.filter { it.path.toString().startsWith("$root/") }.map { it.path.toString() } == expected) {
                "All-files fixture order differed from MediaStore DATE_ADDED order"
            }
            onMain {
                RecentFilesSettings.write(RecentFileRules(RecentFileRules.Mode.ONLY, listOf(fixtureRoot)))
                state.set(null)
                source!!.loadValue()
            }
            val included = terminal()
            check(included.map { it.path.toString() } == expected) { "Only-included root failed recursive/alias matching" }
            onMain {
                RecentFilesSettings.write(RecentFileRules(RecentFileRules.Mode.EXCLUDE, listOf(root)))
                state.set(null)
                source!!.loadValue()
            }
            val excluded = terminal()
            check(excluded.none { it.path.toString().startsWith("$root/") }) { "Excluded fixture still present" }
            check(expected.size == 3) { "Paging smoke requires exactly three generated fixture files" }
            onMain {
                source!!.removeObserver(observer!!)
                source!!.close()
                RecentFilesSettings.write(RecentFileRules(RecentFileRules.Mode.ONLY, listOf(root)))
                state.set(null)
                source = RecentFilesLiveData(pageSize = 2)
                source!!.observeForever(observer!!)
            }
            val firstPage = terminal()
            check(firstPage.map { it.path.toString() } == expected.take(2))
            check(source!!.hasMore) { "The third eligible file should enable Load more" }
            onMain { state.set(null); source!!.loadMore() }
            val appended = terminal()
            check(appended.map { it.path.toString() } == expected)
            check(appended.take(2) == firstPage && appended.map { it.path }.distinct().size == 3)
            check(!source!!.hasMore) { "Final partial page must remove Load more" }
            onMain { state.set(null); source!!.loadValue() }
            check(terminal().map { it.path.toString() } == expected) { "Refresh collapsed the already requested window" }
            val missing = RecentFileRules(RecentFileRules.Mode.ONLY, listOf("$root/missing-generated-folder"))
            onMain {
                RecentFilesSettings.write(missing)
                state.set(null); source!!.loadValue()
            }
            check(terminal().isEmpty() && !source!!.hasMore)
            onMain {
                RecentFilesSettings.write(RecentFileRules(RecentFileRules.Mode.ONLY, listOf(root)))
                state.set(null); source!!.loadValue()
            }
            check(terminal().size == 2 && source!!.hasMore) { "Rule changes must reset paging" }
            onMain {
                source!!.loadMore()
                RecentFilesSettings.write(missing)
                state.set(null); source!!.loadValue()
            }
            check(terminal().isEmpty() && !source!!.hasMore) { "Cancelled append leaked old-scope files" }
            onMain { source!!.removeObserver(observer!!); source!!.close() }
            // Insert the image first: 501 newer text rows must not hide an older match.
            val folderName = "FileWeaveRecentSearchQA-${java.util.UUID.randomUUID()}"
            val searchRoot = File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), folderName).canonicalFile
            generatedRoot = searchRoot
            if (android.os.Build.VERSION.SDK_INT < 29) check(searchRoot.mkdirs())
            val collection = if (android.os.Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI
                else MediaStore.Files.getContentUri("external")
            fun insert(name: String, mime: String, bytes: ByteArray) {
                val values = ContentValues().apply {
                    put(OpenableColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/$folderName/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    } else {
                        @Suppress("DEPRECATION")
                        put(MediaStore.MediaColumns.DATA, File(searchRoot, name).absolutePath)
                    }
                }
                val uri = checkNotNull(targetContext.contentResolver.insert(collection, values))
                generatedUris += uri
                checkNotNull(targetContext.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    targetContext.contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                }
            }
            val targetName = "needle-older-image.png"
            val png = java.io.ByteArrayOutputStream().use { out ->
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.BLUE)
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                    out.toByteArray()
                } finally { bitmap.recycle() }
            }
            insert(targetName, "image/png", png)
            repeat(501) { insert("newer-text-${it.toString().padStart(3, '0')}.txt", "text/plain", byteArrayOf(65)) }
            val generatedOrder = indexedFixtures(searchRoot.absolutePath)
            check(generatedOrder.size == 502 && generatedOrder.indexOf(File(searchRoot, targetName).absolutePath) >= 500)
            fun replaceSource(query: String = "", filters: SearchFilters = SearchFilters()) {
                onMain {
                    observer?.let { source?.removeObserver(it) }
                    source?.close()
                    RecentFilesSettings.write(RecentFileRules(RecentFileRules.Mode.ONLY, listOf(searchRoot.absolutePath)))
                    state.set(null)
                    source = RecentFilesLiveData(query = query, filters = filters)
                    source!!.observeForever(observer!!)
                }
            }
            replaceSource()
            val unfilteredWindow = terminal()
            check(unfilteredWindow.size == 500 && unfilteredWindow.none { it.path.fileName.toString() == targetName } && source!!.hasMore)
            replaceSource(filters = SearchFilters(type = SearchFileType.IMAGE, recursive = false))
            check(terminal().map { it.path.fileName.toString() } == listOf(targetName) && !source!!.hasMore) {
                "Type matching was limited to the first 500 candidates"
            }
            replaceSource(query = "NEEDLE", filters = SearchFilters(type = SearchFileType.EXTENSION, extension = "PNG"))
            check(terminal().map { it.path.fileName.toString() } == listOf(targetName))
            replaceSource(query = "missing-generated-name", filters = SearchFilters(type = SearchFileType.IMAGE))
            check(terminal().isEmpty() && !source!!.hasMore)
            onMain {
                source!!.removeObserver(observer!!); source!!.close()
                originalView = Settings.FILE_LIST_VIEW_TYPE.valueCompat
                originalDrawer = Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.valueCompat
                originalRootStrategy = Settings.ROOT_STRATEGY.valueCompat
                Settings.FILE_LIST_VIEW_TYPE.putValue(FileViewType.LIST)
                Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(false)
                Settings.ROOT_STRATEGY.putValue(RootStrategy.NEVER)
            }
            val screen = startActivitySync(Intent(targetContext, FileListActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as FileListActivity
            activity = screen
            val recycler = screen.findViewById<ViewGroup>(R.id.contentLayout).findViewById<RecyclerView>(R.id.recyclerView)
            fun names(): List<String> = (recycler.adapter as ConcatAdapter).adapters
                .filterIsInstance<FileListAdapter>().single().list.map { it.path.fileName.toString() }
            awaitUi { screen.hasWindowFocus() && names().size == 500 }
            onMain {
                val filter = screen.findViewById<Toolbar>(R.id.toolbar).findViewById<View>(R.id.action_search_filters)
                check(filter != null && filter.performClick())
            }
            awaitUi { screen.findViewById<View>(R.id.quickFiltersScroll).isShown }
            onMain {
                val image = screen.findViewById<View>(R.id.quickFilterImage)
                check(image.isShown && image.isEnabled && image.performClick())
            }
            awaitUi { names() == listOf(targetName) }
            awaitUi { recycler.findViewHolderForAdapterPosition(0)?.itemView
                ?.findViewById<android.widget.TextView>(R.id.nameText)?.text?.toString() == targetName }
            waitForIdleSync()
            onMain { saveScreenshot(screen, "quick-filters-recent-image.png") }
            lateinit var searchView: SearchView
            onMain {
                val item = screen.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_search)
                check(item.isVisible && item.expandActionView()) { "Recent search is unavailable" }
                searchView = item.actionView as SearchView
                searchView.setQuery("missing-generated-name", true)
            }
            awaitUi { names().isEmpty() }
            onMain { searchView.setQuery("", false) }
            awaitUi { names() == listOf(targetName) }
            onMain { searchView.setQuery("NEEDLE", true) }
            awaitUi { names() == listOf(targetName) }
            onMain { check(screen.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_search).collapseActionView()) }
            awaitUi { names() == listOf(targetName) }
            onMain { check(screen.findViewById<com.google.android.material.chip.Chip>(R.id.quickFilterImage).isChecked) }
            val fragment = screen.supportFragmentManager.fragments.filterIsInstance<FileListFragment>().single()
            onMain { fragment.navigateToRoot(Paths.get(searchRoot.absolutePath)) }
            awaitUi { names().size == 502 }
            onMain { check(screen.findViewById<com.google.android.material.chip.Chip>(R.id.quickFilterAll).isChecked) }
            report = "PASS: RecentFilesLiveData terminal loading; date-added ordering (${expected.size}); whitelist alias/exclusion; 2+1 keyset append without duplicates; final-footer state; refresh keeps window; rules reset and cancel stale append; generated 502-row MediaStore scope finds an image/uppercase filename/PNG extension beyond the first 500 candidates; actual recent Image chip, search clear, collapse-preserved type, navigation reset, generated View.draw screenshot; states=$sequence\n"
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                onMain {
                    observer?.let { source?.removeObserver(it) }
                    source?.close()
                    activity?.finish()
                    originalView?.let { Settings.FILE_LIST_VIEW_TYPE.putValue(it) }
                    originalDrawer?.let { Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(it) }
                    originalRootStrategy?.let { Settings.ROOT_STRATEGY.putValue(it) }
                    original?.let { RecentFilesSettings.write(it) }
                }
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure?.addSuppressed(cleanup)
            }
            generatedUris.asReversed().forEach { uri ->
                try {
                    check(targetContext.contentResolver.delete(uri, null, null) == 1) { "Generated search row was not removed: $uri" }
                } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure?.addSuppressed(cleanup)
                }
            }
            try { generatedRoot?.deleteRecursively() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure?.addSuppressed(cleanup)
            }
        }
        val error = failure
        finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", if (error == null) report else "FAIL: ${error.stackTraceToString()}\nstates=$sequence\n")
        })
    }

    @Suppress("DEPRECATION")
    private fun indexedFixtures(root: String): List<String> {
        val paths = ArrayList<String>()
        targetContext.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.MediaColumns.DATA),
            null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
        )!!.use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0) ?: continue
                if (path.startsWith("$root/") && File(path).isFile) paths += path
            }
        }
        return paths
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            Thread.sleep(50)
        }
        error("Recent search UI did not become ready")
    }

    private fun saveScreenshot(activity: Activity, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val replacements = mutableListOf<Triple<ImageView, Drawable, Bitmap>>()
        fun useSoftwareImages(node: View) {
            if (node is ImageView) {
                val drawable = node.drawable
                if (drawable is BitmapDrawable && android.os.Build.VERSION.SDK_INT >= 26 &&
                    drawable.bitmap.config == Bitmap.Config.HARDWARE) {
                    val software = checkNotNull(drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false))
                    replacements += Triple(node, drawable, software)
                    node.setImageDrawable(BitmapDrawable(activity.resources, software))
                }
            }
            if (node is ViewGroup) for (index in 0 until node.childCount) useSoftwareImages(node.getChildAt(index))
        }
        try {
            useSoftwareImages(view)
            view.draw(Canvas(bitmap))
            File(targetContext.cacheDir, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            replacements.forEach { (image, drawable, software) -> image.setImageDrawable(drawable); software.recycle() }
            bitmap.recycle()
        }
    }
}
