package me.zhanghai.android.files.filelist

/** Column sizing for the measured file area, rather than the entire device screen. */
internal object FileListLayout {
    fun showPreview(viewType: FileViewType, widthDp: Float, fontScale: Float, picking: Boolean): Boolean =
        viewType == FileViewType.PREVIEW && !picking && widthDp >= 640 * fontScale.coerceAtLeast(1f)

    fun spanCount(viewType: FileViewType, widthDp: Float, fontScale: Float): Int {
        if (viewType == FileViewType.PREVIEW) return 1
        // List cells retain their thumbnail, metadata and 48dp overflow target. Increase the
        // minimum width for large text instead of squeezing file names into narrow columns.
        val minimumWidth = when (viewType) {
            FileViewType.LIST, FileViewType.PREVIEW -> 320
            FileViewType.COMPACT -> 280
            FileViewType.GRID -> 180
        } * fontScale.coerceAtLeast(1f)
        return (widthDp / minimumWidth).toInt().coerceAtLeast(1)
    }
}
