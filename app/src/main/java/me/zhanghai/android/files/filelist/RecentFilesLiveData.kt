package me.zhanghai.android.files.filelist

import android.database.ContentObserver
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java8.nio.file.Paths
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.linux.LinuxFileSystemProvider
import me.zhanghai.android.files.provider.common.KnownCreationTime
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.File
import java.io.IOException
import java.text.Collator
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Recently indexed shared-storage files, ordered by DATE_ADDED, never by modified time. */
class RecentFilesLiveData internal constructor(
    private val pageSize: Int = PAGE_SIZE,
    query: String = "",
    filters: SearchFilters = SearchFilters()
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private val search = RecentFileSearch(query, filters)
    private val resolver = application.contentResolver
    private val uri = MediaStore.Files.getContentUri("external")
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var future: Future<*>? = null
    private var cancellation: CancellationSignal? = null
    private var generation = 0
    private var closed = false
    private var observing = false
    private var lastRules: RecentFileRules? = null
    private var lastShowHidden: Boolean? = null
    private var files: List<FileItem> = emptyList()
    private var lastKey: RecentFileKey? = null
    private var requestedCount = pageSize
    var hasMore = false
        private set
    var isLoadingMore = false
        private set
    var isLoading = false
        private set
    var loadMoreFailed = false
        private set
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = loadValue()
    }

    init { require(pageSize > 0) }

    override fun onActive() {
        if (closed) return
        try {
            resolver.registerContentObserver(uri, true, observer)
            observing = true
        } catch (_: SecurityException) {
            // The query reports missing access through the normal error state.
        }
        loadValue()
    }

    override fun onInactive() {
        if (observing) resolver.unregisterContentObserver(observer)
        observing = false
        cancelLoad()
    }

    private fun cancelLoad() {
        ++generation
        cancellation?.cancel()
        cancellation = null
        future?.cancel(true)
        future = null
    }

    /** Refresh the requested window, keeping older pages when returning from an opened file. */
    fun loadValue() = load(append = false)

    fun loadMore() {
        if (!isLoading && hasMore) load(append = true)
    }

    @Suppress("DEPRECATION")
    private fun load(append: Boolean) {
        if (closed || !hasActiveObservers()) return
        val rules = RecentFilesSettings.read()
        val showHidden = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        val changedRules = lastRules != rules || lastShowHidden != showHidden
        val appendPage = append && !changedRules
        if (changedRules) {
            files = emptyList()
            lastKey = null
            requestedCount = pageSize
            hasMore = false
        }
        lastRules = rules
        lastShowHidden = showHidden
        cancelLoad()
        val requestGeneration = generation
        val signal = CancellationSignal().also { cancellation = it }
        val previous = files.takeIf { it.isNotEmpty() }
        val base = if (appendPage) files else emptyList()
        val after = if (appendPage) lastKey else null
        if (appendPage) requestedCount = maxOf(requestedCount, base.size + pageSize)
        val limit = if (appendPage) pageSize else requestedCount
        isLoading = true
        isLoadingMore = appendPage
        loadMoreFailed = false
        value = Loading(previous)
        future = executor.submit {
            try {
                val conditions = mutableListOf<String>()
                val arguments = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    conditions += "${MediaStore.MediaColumns.IS_PENDING} = 0"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    conditions += "${MediaStore.MediaColumns.IS_TRASHED} = 0"
                }
                if (after != null) {
                    if (after.addedSeconds == null) {
                        conditions += "(${MediaStore.MediaColumns.DATE_ADDED} IS NULL AND ${MediaStore.MediaColumns._ID} < ?)"
                        arguments += after.id.toString()
                    } else {
                        conditions += "(${MediaStore.MediaColumns.DATE_ADDED} IS NULL OR ${MediaStore.MediaColumns.DATE_ADDED} < ? OR " +
                            "(${MediaStore.MediaColumns.DATE_ADDED} = ? AND ${MediaStore.MediaColumns._ID} < ?))"
                        arguments += listOf(after.addedSeconds.toString(), after.addedSeconds.toString(), after.id.toString())
                    }
                }
                val collator = Collator.getInstance()
                val zone = ZoneId.systemDefault()
                val page = resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns._ID),
                    conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
                    arguments.takeIf { it.isNotEmpty() }?.toTypedArray(),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC",
                    signal
                )?.use { cursor ->
                    val rows = sequence {
                        while (cursor.moveToNext()) {
                            signal.throwIfCanceled()
                            val path = cursor.getString(0)?.takeIf { it.startsWith("/") } ?: continue
                            yield(RecentIndexedFile(path, RecentFileKey(if (cursor.isNull(1)) null else cursor.getLong(1), cursor.getLong(2))))
                        }
                    }.iterator()
                    collectRecentFilePage<FileItem>(rows, limit, base.mapTo(HashSet()) { it.path.toString() }, signal::throwIfCanceled) { path ->
                        if (!rules.includes(path) || !search.matchesName(File(path).name)) return@collectRecentFilePage null
                        try {
                            // Automatic index scans use local-only metadata and never start Root.
                            val file = File(path)
                            if (!file.isFile || !file.canRead()) null else {
                                LinuxFileSystemProvider.loadLocalRegularFileItem(Paths.get(path), collator)
                                    ?.takeIf { item ->
                                        val attributes = item.attributes
                                        val created = if ((attributes as? KnownCreationTime)?.hasCreationTime == true)
                                            attributes.creationTime().toMillis() else null
                                        (showHidden || !item.isHidden) && search.matches(
                                            item.name, item.mimeType.value,
                                            attributes.lastModifiedTime().toMillis(), created, zone
                                        )
                                    }
                            }
                        } catch (_: IOException) {
                            null // Stale or inaccessible indexed entry.
                        } catch (_: SecurityException) {
                            null
                        }
                    }
                } ?: throw IOException("MediaStore returned no file index")
                handler.post {
                    if (closed || generation != requestGeneration || signal.isCanceled) return@post
                    files = base + page.files
                    lastKey = page.lastKey ?: after
                    hasMore = page.hasMore
                    isLoading = false
                    isLoadingMore = false
                    value = Success(files)
                }
            } catch (error: Exception) {
                publishFailure(error, previous, appendPage, requestGeneration, signal)
            } catch (error: LinkageError) {
                // FutureTask otherwise hides native/provider initialization failures.
                publishFailure(error, previous, appendPage, requestGeneration, signal)
            }
        }
    }

    private fun publishFailure(
        error: Throwable, previous: List<FileItem>?, append: Boolean,
        requestGeneration: Int, signal: CancellationSignal
    ) {
        handler.post {
            if (closed || generation != requestGeneration || signal.isCanceled) return@post
            isLoading = false
            isLoadingMore = false
            loadMoreFailed = append
            value = Failure(previous, error)
        }
    }

    override fun close() {
        closed = true
        if (observing) resolver.unregisterContentObserver(observer)
        observing = false
        cancelLoad()
        executor.shutdownNow()
    }

    companion object {
        const val PAGE_SIZE = 500
    }
}
