package me.zhanghai.android.files.filelist

import org.junit.Assert.*
import org.junit.Test

class RecentFileRulesTest {
    @Test fun allIncludesEverythingRegardlessOfRememberedRoots() {
        assertTrue(RecentFileRules(roots = listOf("/storage/private")).includes("/storage/private/a.txt"))
    }
    @Test fun excludeAppliesRecursivelyWithComponentBoundaries() {
        val rules = RecentFileRules(RecentFileRules.Mode.EXCLUDE, listOf("/storage/DCIM/"))
        assertFalse(rules.includes("/storage/DCIM/a.jpg"))
        assertFalse(rules.includes("/storage/DCIM/nested/a.jpg"))
        assertFalse(rules.includes("/storage/DCIM"))
        assertTrue(rules.includes("/storage/DCIM-other/a.jpg"))
        assertTrue(rules.includes("/storage/Download/a.pdf"))
    }
    @Test fun includeAllowsOnlyRootsAndTheirDescendants() {
        val rules = RecentFileRules(RecentFileRules.Mode.ONLY, listOf("/storage/Download", "/sd/DCIM"))
        assertTrue(rules.includes("/storage/Download/a.pdf"))
        assertTrue(rules.includes("/sd/DCIM/sub/a.jpg"))
        assertFalse(rules.includes("/storage/Downloads/a.pdf"))
        assertFalse(rules.includes("/storage/DCIM/a.jpg"))
        assertFalse(RecentFileRules(RecentFileRules.Mode.ONLY).includes("/storage/a.txt"))
    }
    @Test fun trashIsAlwaysExcludedEvenWhenHiddenFilesOrOnlyRootsAreUsed() {
        for (path in listOf("/sdcard/Sounds/.Trash/123/call.m4a", "/sdcard/Pictures/.Trashes/1/a.png", "/sdcard/.Trash-1000/files/a.txt", "/sdcard/DCIM/.recycle/a.jpg", "/sdcard/DCIM/.trashed-12345-a.jpg")) {
            assertFalse(path, RecentFileRules().includes(path))
            assertFalse(path, RecentFileRules(RecentFileRules.Mode.ONLY, listOf("/sdcard")).includes(path))
        }
        for (path in listOf("/sdcard/Documents/trash-notes.txt", "/sdcard/My.Trash/a.txt", "/sdcard/Trash backups/a.jpg", "/sdcard/Sounds/restored.m4a")) {
            assertTrue(path, RecentFileRules().includes(path))
        }
    }

    @Test fun galleryRecycleAndBinContainersAreExcludedInEveryMode() {
        val paths = listOf(
            "/storage/emulated/0/Pictures/.Gallery2/recycle/bins/0/GF6DA.hndgp",
            "/storage/emulated/0/Pictures/.Gallery2/recycle/metadata/index.db",
            "/sdcard/Pictures/.GALLERY2/RECYCLE/BINS/0/a.hndgp",
            "/sdcard/Pictures/recycle/bin/a.jpg",
            "/sdcard/Pictures/recycle/bins/0/a.jpg",
            "/sdcard/recyclebin/files/a.jpg",
            "/sdcard/recycle_bin/a.txt"
        )
        for (mode in RecentFileRules.Mode.values()) {
            val rules = RecentFileRules(mode, if (mode == RecentFileRules.Mode.ONLY) listOf("/") else emptyList())
            for (path in paths) assertFalse("$mode: $path", rules.includes(path))
        }
        assertFalse(RecentFileRules(RecentFileRules.Mode.ONLY,
            listOf("/sdcard/Pictures/recycle/bin")).includes("/sdcard/Pictures/recycle/bin/a.jpg"))
        for (path in listOf(
            "/sdcard/Documents/recycle-notes.txt", "/sdcard/Documents/bin/app.txt",
            "/sdcard/recycle/art/a.jpg", "/sdcard/recycle/binary/a.jpg",
            "/sdcard/Pictures/.Gallery2/recycle-archive/a.jpg",
            "/sdcard/Pictures/other.Gallery2/recycle/a.jpg"
        )) assertTrue(path, RecentFileRules().includes(path))
    }

    @Test fun normalizationHandlesRootDotsAndRejectsRelativePaths() {
        assertEquals("/storage/Download", RecentFileRules.normalizeAbsolutePath("/storage//DCIM/.././Download/"))
        assertNull(RecentFileRules.normalizeAbsolutePath("storage/Download"))
        assertTrue(RecentFileRules(RecentFileRules.Mode.ONLY, listOf("/")).includes("/storage/a"))
        assertFalse(RecentFileRules(RecentFileRules.Mode.EXCLUDE, listOf("/storage/Download"))
            .includes("/storage/DCIM/../Download/a"))
    }
}
