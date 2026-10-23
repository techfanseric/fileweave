package me.zhanghai.android.files.filelist

/** Recompute from the initial selection so dragging backwards restores earlier selections. */
internal class DragSelectionRange(private val baseline: Set<Int>, val anchor: Int) {
    fun selectionAt(end: Int): Set<Int> {
        val range = minOf(anchor, end)..maxOf(anchor, end)
        return if (anchor in baseline) baseline - range.toSet() else baseline + range
    }
}
