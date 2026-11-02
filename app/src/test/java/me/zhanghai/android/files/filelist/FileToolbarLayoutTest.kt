package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Test

class FileToolbarLayoutTest {
    @Test fun compactToolbarReservesTitleBeforeSecondaryActions() {
        assertEquals(0, FileToolbarLayout.inlineSecondaryActions(360f, 1f, false, false))
        assertEquals(1, FileToolbarLayout.inlineSecondaryActions(400f, 1f, false, false))
        assertEquals(2, FileToolbarLayout.inlineSecondaryActions(600f, 1f, false, false))
    }
    @Test fun largeTextAndPickerKeepSecondaryActionsInMore() {
        assertEquals(0, FileToolbarLayout.inlineSecondaryActions(400f, 1.5f, false, false))
        assertEquals(0, FileToolbarLayout.inlineSecondaryActions(800f, 1f, true, false))
        assertEquals(1, FileToolbarLayout.inlineSecondaryActions(360f, 1f, false, true))
    }
}
