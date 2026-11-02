package me.zhanghai.android.files.filelist

/** Directory recursion and an unused extension field alone are scope options, not active filters. */
internal fun createFileSearchState(query: String, filters: SearchFilters): SearchState =
    SearchState(query.isNotEmpty() || filters.type != SearchFileType.ANY || filters.dates != null,
        query, filters)
