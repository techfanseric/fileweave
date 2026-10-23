package me.zhanghai.android.files.filelist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class FileListLayoutTest {
    @Test
    fun previewRequiresTwoReadablePanesAndDoesNotAffectPickers() {
        assertFalse(FileListLayout.showPreview(FileViewType.PREVIEW, 639f, 1f, false))
        assertTrue(FileListLayout.showPreview(FileViewType.PREVIEW, 640f, 1f, false))
        assertFalse(FileListLayout.showPreview(FileViewType.PREVIEW, 718f, 1.3f, false))
        assertFalse(FileListLayout.showPreview(FileViewType.PREVIEW, 840f, 1f, true))
        assertFalse(FileListLayout.showPreview(FileViewType.LIST, 840f, 1f, false))
        assertEquals(1, FileListLayout.spanCount(FileViewType.PREVIEW, 840f, 1f))
    }

    @Test
    fun compactListFitsMoreColumnsWithoutChangingStoredViewOrdinals() {
        assertEquals(3, FileListLayout.spanCount(FileViewType.COMPACT, 840f, 1f))
        assertEquals(1, FileListLayout.spanCount(FileViewType.COMPACT, 360f, 1f))
        assertEquals(0, FileViewType.LIST.ordinal)
        assertEquals(1, FileViewType.GRID.ordinal)
    }

    @Test
    fun listUsesAvailableContentWidth() {
        assertEquals(1, FileListLayout.spanCount(FileViewType.LIST, 360f, 1f))
        assertEquals(1, FileListLayout.spanCount(FileViewType.LIST, 639f, 1f))
        assertEquals(2, FileListLayout.spanCount(FileViewType.LIST, 640f, 1f))
        assertEquals(2, FileListLayout.spanCount(FileViewType.LIST, 840f, 1f))
        assertEquals(3, FileListLayout.spanCount(FileViewType.LIST, 1024f, 1f))
        // Opening a 304dp drawer on a 840dp window must return to a single column.
        assertEquals(1, FileListLayout.spanCount(FileViewType.LIST, 536f, 1f))
    }

    @Test
    fun largeTextReservesMoreSpaceForNames() {
        assertEquals(1, FileListLayout.spanCount(FileViewType.LIST, 700f, 1.3f))
        assertEquals(2, FileListLayout.spanCount(FileViewType.LIST, 840f, 1.3f))
        assertEquals(2, FileListLayout.spanCount(FileViewType.LIST, 640f, 0.85f))
    }

    @Test
    fun gridFitsNarrowAndExpandedWindows() {
        assertEquals(1, FileListLayout.spanCount(FileViewType.GRID, 280f, 1f))
        assertEquals(2, FileListLayout.spanCount(FileViewType.GRID, 360f, 1f))
        assertEquals(4, FileListLayout.spanCount(FileViewType.GRID, 840f, 1f))
        assertEquals(1, FileListLayout.spanCount(FileViewType.GRID, 0f, 1f))
    }
}
