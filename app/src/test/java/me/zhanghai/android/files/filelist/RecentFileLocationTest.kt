package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentFileLocationTest {
    @Test fun omitsOnlyTheKnownStorageRoot() {
        assertEquals("Download/我的工作", RecentFileLocation.display("/storage/emulated/0/Download/我的工作", "/storage/emulated/0", "Internal storage"))
        assertEquals("Internal storage", RecentFileLocation.display("/storage/emulated/0", "/storage/emulated/0", "Internal storage"))
        assertEquals("内部存储", RecentFileLocation.display("/storage/emulated/0", "/storage/emulated/0", "内部存储"))
    }
    @Test fun preservesOtherVolumesAndSimilarPrefixes() {
        for (path in listOf("/storage/emulated/01/Download", "/storage/ABCD/DCIM", "smb://host/shared/文件")) {
            assertEquals(path, RecentFileLocation.display(path, "/storage/emulated/0", "Internal storage"))
        }
    }
}
