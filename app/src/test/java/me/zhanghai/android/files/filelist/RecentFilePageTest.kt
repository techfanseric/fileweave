package me.zhanghai.android.files.filelist

import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException

class RecentFilePageTest {
    private fun rows(count: Int) = (count downTo 1).map {
        RecentIndexedFile("/files/$it", RecentFileKey(100L, it.toLong()))
    }

    @Test fun exact500EndsWithoutAnUnnecessaryButtonBut501OffersAnotherPage() {
        val exact = collectRecentFilePage(rows(500).iterator(), 500) { it }
        assertEquals(500, exact.files.size)
        assertFalse(exact.hasMore)
        val extra = collectRecentFilePage(rows(501).iterator(), 500) { it }
        assertEquals(500, extra.files.size)
        assertTrue(extra.hasMore)
        assertEquals(RecentFileKey(100L, 2L), extra.lastKey)
    }

    @Test fun filteredStaleAndDuplicateRowsDoNotFillPagesOrTriggerLoadMoreAtTheEnd() {
        val source = listOf("a", "blocked", "b", "a", "stale", "c", "hidden").mapIndexed { index, name ->
            RecentIndexedFile("/$name", RecentFileKey(10L, (10 - index).toLong()))
        }
        val attempts = mutableListOf<String>()
        val load: (String) -> String? = {
            attempts += it
            it.takeUnless { path -> path in setOf("/blocked", "/stale", "/hidden") }
        }
        val first = collectRecentFilePage(source.iterator(), 2, load = load)
        assertEquals(listOf("/a", "/b"), first.files)
        assertTrue(first.hasMore)
        assertEquals(1, attempts.count { it == "/a" })
        attempts.clear()
        val second = collectRecentFilePage(
            source.filter { it.key.isOlderThan(first.lastKey!!) }.iterator(), 2,
            first.files.toSet(), load = load
        )
        assertEquals(listOf("/c"), second.files)
        assertFalse(second.hasMore)
        assertFalse("Existing metadata must not be loaded again", attempts.any { it in first.files })
        val exactWithExcludedTail = collectRecentFilePage(source.iterator(), 3, load = load)
        assertFalse(exactWithExcludedTail.hasMore)
    }

    @Test fun sameTimestampKeysetAppends500WithoutDuplicatesWhenANewerFileAppears() {
        val original = rows(1000)
        val first = collectRecentFilePage(original.iterator(), 500) { it }
        val changed = listOf(RecentIndexedFile("/files/new", RecentFileKey(101L, 1001L))) + original
        val second = collectRecentFilePage(
            changed.filter { it.key.isOlderThan(first.lastKey!!) }.iterator(), 500,
            first.files.toSet()
        ) { it }
        assertEquals(500, second.files.size)
        assertFalse(second.hasMore)
        val appended = first.files + second.files
        assertEquals(1000, appended.distinct().size)
        assertFalse("New files appear on refresh, not midway through an older page", "/files/new" in appended)
        val refreshed = collectRecentFilePage(changed.iterator(), 1000) { it }
        assertEquals("/files/new", refreshed.files.first())
        assertEquals(1000, refreshed.files.size)
        assertTrue(refreshed.hasMore)
    }

    @Test fun nullAddedTimesRemainReachableAndOrderByIdAfterKnownTimes() {
        val source = listOf(
            RecentIndexedFile("/known", RecentFileKey(0L, 1L)),
            RecentIndexedFile("/unknown-new", RecentFileKey(null, 30L)),
            RecentIndexedFile("/unknown-old", RecentFileKey(null, 20L))
        )
        val first = collectRecentFilePage(source.iterator(), 1) { it }
        val second = collectRecentFilePage(source.filter { it.key.isOlderThan(first.lastKey!!) }.iterator(), 1) { it }
        assertEquals(listOf("/unknown-new"), second.files)
        val third = collectRecentFilePage(source.filter { it.key.isOlderThan(second.lastKey!!) }.iterator(), 1) { it }
        assertEquals(listOf("/unknown-old"), third.files)
        assertFalse(third.hasMore)
    }

    @Test(expected = InterruptedIOException::class)
    fun cancellationStopsScanningBeforeLoadingMoreMetadata() {
        var checked = 0
        collectRecentFilePage(rows(1000).iterator(), 500,
            checkCancelled = { if (++checked == 4) throw InterruptedIOException() }) { it }
    }
}
