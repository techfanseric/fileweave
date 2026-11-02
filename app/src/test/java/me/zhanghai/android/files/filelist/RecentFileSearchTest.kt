package me.zhanghai.android.files.filelist

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecentFileSearchTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun searchFindsAndPagesMatchingFilesBeyondTheFirst500IndexRows() {
        val irrelevant = (2000 downTo 1401).map { id ->
            RecentIndexedFile("/storage/camera/IMG-$id.jpg", RecentFileKey(100L, id.toLong()))
        }
        val privateFile = RecentIndexedFile("/storage/docs/private/report-secret.PDF", RecentFileKey(100L, 1200L))
        val documents = (1000 downTo 500).map { id ->
            RecentIndexedFile("/storage/docs/report-$id.PDF", RecentFileKey(100L, id.toLong()))
        }
        val rows = irrelevant + privateFile + documents
        val rules = RecentFileRules(RecentFileRules.Mode.EXCLUDE, listOf("/storage/docs/private"))
        val search = RecentFileSearch("REPORT", SearchFilters(type = SearchFileType.EXTENSION, extension = ".pdf"))
        val load: (String) -> String? = { path ->
            val name = path.substringAfterLast('/')
            path.takeIf { rules.includes(path) && search.matches(name, "application/pdf", null, null, zone) }
        }
        val first = collectRecentFilePage(rows.iterator(), 500, load = load)
        assertEquals(documents.take(500).map { it.path }, first.files)
        assertTrue(first.hasMore)
        val second = collectRecentFilePage(
            rows.filter { it.key.isOlderThan(first.lastKey!!) }.iterator(), 500,
            first.files.toSet(), load = load
        )
        assertEquals(listOf(documents.last().path), second.files)
        assertFalse(second.hasMore)
        assertEquals(501, (first.files + second.files).distinct().size)
    }

    @Test fun nameTypeAndInclusiveDatesApplyTogetherBeforePaging() {
        val day = LocalDate.of(2026, 11, 1)
        val search = RecentFileSearch("trip", SearchFilters(
            type = SearchFileType.IMAGE, dates = SearchDateRange(day, day)
        ))
        val start = Instant.parse("2026-10-31T16:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-01T15:59:59.999Z").toEpochMilli()
        assertTrue(search.matches("TRIP.jpg", "image/jpeg", start, null, zone))
        assertTrue(search.matches("trip-final.png", "image/png", end, null, zone))
        assertFalse(search.matches("trip.pdf", "application/pdf", start, null, zone))
        assertFalse(search.matches("trip.jpg", "image/jpeg", start - 1, null, zone))
        assertFalse(search.matches("trip.jpg", "image/jpeg", end + 1, null, zone))
        assertFalse(search.matches("other.jpg", "image/jpeg", start, null, zone))
    }

    @Test fun blankQueryStillAllowsFiltersAndMissingBirthTimeNeverUsesModifiedTime() {
        val day = LocalDate.of(2026, 11, 1)
        val modified = Instant.parse("2026-11-01T03:00:00Z").toEpochMilli()
        val markdown = RecentFileSearch(filters = SearchFilters(type = SearchFileType.MARKDOWN))
        assertTrue(markdown.matches("NOTES.MD", "text/plain", modified, null, zone))
        assertFalse(markdown.matches("notes.pdf", "application/pdf", modified, null, zone))
        val created = RecentFileSearch(filters = SearchFilters(
            dateField = SearchDateField.CREATED, dates = SearchDateRange(day, day)
        ))
        assertFalse(created.matches("new.txt", "text/plain", modified, null, zone))
        assertTrue(created.matches("new.txt", "text/plain", null, modified, zone))
        assertFalse(RecentFileSearch("documents").matchesName("new.txt"))
    }

    @Test fun clearingKeywordKeepsEffectiveFiltersButScopeOnlyReturnsToNormalBrowsing() {
        val scope = SearchFilters(recursive = false)
        val noKeyword = createFileSearchState("", scope)
        assertFalse(noKeyword.isSearching)
        assertEquals(scope, noKeyword.filters)
        assertTrue(createFileSearchState("report", scope).isSearching)
        assertFalse(createFileSearchState("", scope.copy(extension = "pdf", dateField = SearchDateField.CREATED)).isSearching)
        val typed = scope.copy(type = SearchFileType.IMAGE)
        assertTrue(createFileSearchState("", typed).isSearching)
        assertEquals(typed, createFileSearchState("", typed).filters)
        val dated = scope.copy(dates = SearchDateRange(null, LocalDate.of(2026, 11, 1)))
        assertTrue(createFileSearchState("", dated).isSearching)
        assertFalse(createFileSearchState("", SearchFilters()).isSearching)
    }
}
