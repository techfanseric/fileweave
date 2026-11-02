package me.zhanghai.android.files.filelist

/** Stable descending MediaStore order, including files with identical added timestamps. */
internal data class RecentFileKey(val addedSeconds: Long?, val id: Long) {
    fun isOlderThan(other: RecentFileKey): Boolean = when {
        addedSeconds == other.addedSeconds -> id < other.id
        addedSeconds == null -> true
        other.addedSeconds == null -> false
        else -> addedSeconds < other.addedSeconds
    }
}

internal data class RecentIndexedFile(val path: String, val key: RecentFileKey)
internal data class RecentFilePage<T>(val files: List<T>, val lastKey: RecentFileKey?, val hasMore: Boolean)

/** Look ahead to one more *eligible* file; excluded, stale, and duplicate rows do not fill a page. */
internal fun <T> collectRecentFilePage(
    rows: Iterator<RecentIndexedFile>,
    limit: Int,
    alreadyLoadedPaths: Set<String> = emptySet(),
    checkCancelled: () -> Unit = {},
    load: (String) -> T?
): RecentFilePage<T> {
    require(limit > 0)
    val files = ArrayList<T>()
    val seen = alreadyLoadedPaths.toMutableSet()
    var lastKey: RecentFileKey? = null
    while (rows.hasNext()) {
        checkCancelled()
        val row = rows.next()
        if (!seen.add(row.path)) continue
        val file = load(row.path) ?: continue
        if (files.size == limit) return RecentFilePage(files, lastKey, true)
        files += file
        lastKey = row.key
    }
    return RecentFilePage(files, lastKey, false)
}
