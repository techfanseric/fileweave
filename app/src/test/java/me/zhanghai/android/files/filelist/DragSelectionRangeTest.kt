package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Test

class DragSelectionRangeTest {
    @Test
    fun expandingAndRetractingPreservesEarlierSelection() {
        val range = DragSelectionRange(setOf(0, 7), 2)
        assertEquals(setOf(0, 2, 3, 4, 5, 7), range.selectionAt(5))
        assertEquals(setOf(0, 2, 3, 7), range.selectionAt(3))
        assertEquals(setOf(0, 1, 2, 7), range.selectionAt(0))
    }

    @Test
    fun startingOnSelectedItemDeselectsAndRestoresOnRetraction() {
        val range = DragSelectionRange(setOf(0, 1, 2, 3, 7), 2)
        assertEquals(setOf(0, 1, 7), range.selectionAt(4))
        assertEquals(setOf(0, 1, 3, 7), range.selectionAt(2))
        assertEquals(setOf(3, 7), range.selectionAt(0))
    }
}
