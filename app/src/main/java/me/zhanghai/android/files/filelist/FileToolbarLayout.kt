package me.zhanghai.android.files.filelist

/** Reserve readable location/title space before promoting optional controls out of More. */
internal object FileToolbarLayout {
    fun inlineSecondaryActions(widthDp: Float, fontScale: Float, picker: Boolean,
                               searching: Boolean): Int {
        if (picker) return 0
        val titleSpace = if (searching) 120f else 160f * fontScale.coerceAtLeast(1f)
        return ((widthDp - 56f - 106f - titleSpace) / 48f).toInt().coerceIn(0, if (searching) 1 else 2)
    }
}
