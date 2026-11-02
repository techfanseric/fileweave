package me.zhanghai.android.files.filelist

import java.time.ZoneId

/** Recent search keeps the configured index scope; directory-only recursion does not narrow it. */
internal data class RecentFileSearch(
    val query: String = "",
    val filters: SearchFilters = SearchFilters()
) {
    fun matchesName(name: String): Boolean = name.contains(query, ignoreCase = true)

    fun matches(
        name: String, mimeType: String, modified: Long?, created: Long?, zone: ZoneId
    ): Boolean = matchesName(name) && filters.matches(
        name, mimeType, false, modified, created, zone
    )
}
