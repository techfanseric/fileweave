package me.zhanghai.android.files.filelist

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Dates are inclusive local calendar dates; the upper instant is next day's start, exclusive. */
data class SearchDateRange(val start: LocalDate?, val end: LocalDate?) {
    init { require(start == null || end == null || !end.isBefore(start)) }
    fun contains(millis: Long?, zone: ZoneId): Boolean {
        if (millis == null) return false
        val instant = Instant.ofEpochMilli(millis)
        return (start == null || !instant.isBefore(start.atStartOfDay(zone).toInstant())) &&
            (end == null || instant.isBefore(end.plusDays(1).atStartOfDay(zone).toInstant()))
    }
    companion object {
        fun recent(days: Long, clock: Clock): SearchDateRange {
            require(days > 0)
            val today = LocalDate.now(clock)
            return SearchDateRange(today.minusDays(days - 1), today)
        }
    }
}

enum class SearchFileType { ANY, IMAGE, VIDEO, AUDIO, MARKDOWN, EXTENSION }
enum class SearchDateField { MODIFIED, CREATED }

data class SearchFilters(
    val type: SearchFileType = SearchFileType.ANY,
    val extension: String = "",
    val recursive: Boolean = true,
    val dateField: SearchDateField = SearchDateField.MODIFIED,
    val dates: SearchDateRange? = null
) {
    fun matches(
        name: String, mimeType: String, directory: Boolean, modified: Long?, created: Long?,
        zone: ZoneId
    ): Boolean {
        val typeMatches = when (type) {
            SearchFileType.ANY -> true
            SearchFileType.IMAGE -> !directory && mimeType.startsWith("image/")
            SearchFileType.VIDEO -> !directory && mimeType.startsWith("video/")
            SearchFileType.AUDIO -> !directory && mimeType.startsWith("audio/")
            SearchFileType.MARKDOWN -> !directory &&
                name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")
            SearchFileType.EXTENSION -> !directory && extension.trim().trimStart('.').let {
                it.isNotEmpty() && name.substringAfterLast('.', "").equals(it, true)
            }
        }
        return typeMatches && (dates?.contains(
            if (dateField == SearchDateField.MODIFIED) modified else created, zone
        ) ?: true)
    }
}
