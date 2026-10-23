package me.zhanghai.android.files.filelist

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SearchFiltersTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    @Test fun typeAndExtensionNeverMatchDirectories() {
        val md = SearchFilters(type = SearchFileType.MARKDOWN)
        assertTrue(md.matches("NOTES.MD", "text/plain", false, null, null, zone))
        assertFalse(md.matches("notes.md", "text/plain", true, null, null, zone))
        val ext = SearchFilters(type = SearchFileType.EXTENSION, extension = ".PDF")
        assertTrue(ext.matches("notes.pdf", "application/pdf", false, null, null, zone))
        assertFalse(ext.matches("notes.pdf.zip", "application/zip", false, null, null, zone))
        assertTrue(SearchFilters(type = SearchFileType.IMAGE).matches("a", "image/jpeg", false, null, null, zone))
    }
    @Test fun inclusiveCalendarDatesExcludeNextMidnight() {
        val range = SearchDateRange(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 23))
        assertTrue(range.contains(Instant.parse("2026-10-22T16:00:00Z").toEpochMilli(), zone))
        assertTrue(range.contains(Instant.parse("2026-10-23T15:59:59.999Z").toEpochMilli(), zone))
        assertFalse(range.contains(Instant.parse("2026-10-23T16:00:00Z").toEpochMilli(), zone))
        assertFalse(range.contains(null, zone))
    }
    @Test fun lastSevenDaysAndCreationTimeHaveExplicitSemantics() {
        val clock = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), zone)
        val range = SearchDateRange.recent(7, clock)
        assertEquals(LocalDate.of(2026, 10, 17), range.start)
        val filters = SearchFilters(dateField = SearchDateField.CREATED, dates = range)
        val now = clock.millis()
        assertFalse(filters.matches("a", "text/plain", false, now, null, zone))
        assertTrue(filters.matches("a", "text/plain", false, null, now, zone))
    }
    @Test fun calendarDaysRespectDaylightSavingBoundaries() {
        val dst = ZoneId.of("America/New_York")
        val range = SearchDateRange(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 8))
        assertTrue(range.contains(Instant.parse("2026-03-09T03:59:59Z").toEpochMilli(), dst))
        assertFalse(range.contains(Instant.parse("2026-03-09T04:00:00Z").toEpochMilli(), dst))
    }
    @Test(expected = IllegalArgumentException::class)
    fun invertedRangeRejected() { SearchDateRange(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 22)) }
}
