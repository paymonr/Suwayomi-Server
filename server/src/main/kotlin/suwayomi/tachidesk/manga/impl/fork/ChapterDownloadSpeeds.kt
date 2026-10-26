package suwayomi.tachidesk.manga.impl.fork

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Fork-only (download speeds): per-source and per-day numbers from [ChapterDownloadStats] attempts. */
object ChapterDownloadSpeeds {
    const val TOP_FAILURES = 5

    data class FailureCount(
        val outcome: String,
        val count: Int,
    )

    data class SourceSpeed(
        val sourceId: Long,
        /** attempts that downloaded the chapter */
        val chapters: Int,
        val failedAttempts: Int,
        val pages: Int,
        val bytes: Long,
        val secondsPerPageMedian: Double?,
        val secondsPerPageP90: Double?,
        val megabytesPerSecond: Double?,
        val listSecondsMedian: Double?,
        val pageRetries: Int,
        val topFailures: List<FailureCount>,
    )

    data class DaySpeed(
        val day: LocalDate,
        val chapters: Int,
        val failedAttempts: Int,
        val secondsPerPageMedian: Double?,
        val megabytesPerSecond: Double?,
    )

    fun bySource(attempts: List<ChapterDownloadStats.Attempt>): List<SourceSpeed> =
        attempts
            .groupBy { it.sourceId }
            .toSortedMap()
            .map { (sourceId, rows) ->
                val (ok, failed) = rows.partition { it.outcome == ChapterDownloadStats.OK }
                val perPage = secondsPerPage(ok)
                SourceSpeed(
                    sourceId = sourceId,
                    chapters = ok.size,
                    failedAttempts = failed.size,
                    pages = ok.sumOf { it.pages },
                    bytes = ok.sumOf { it.bytes },
                    secondsPerPageMedian = median(perPage),
                    secondsPerPageP90 = p90(perPage),
                    megabytesPerSecond = megabytesPerSecond(ok),
                    listSecondsMedian = median(rows.filter { it.listMs >= 0 }.map { it.listMs / 1000.0 }),
                    pageRetries = rows.sumOf { it.pageRetries },
                    topFailures = topFailures(failed),
                )
            }

    /** Per local day in [zone], oldest first; a day without attempts is left out. */
    fun byDay(
        attempts: List<ChapterDownloadStats.Attempt>,
        zone: ZoneId,
    ): List<DaySpeed> =
        attempts
            .groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            .toSortedMap()
            .map { (day, rows) ->
                val (ok, failed) = rows.partition { it.outcome == ChapterDownloadStats.OK }
                DaySpeed(day, ok.size, failed.size, median(secondsPerPage(ok)), megabytesPerSecond(ok))
            }

    /** Start (epoch ms) of the last [days] local days in [zone], today included. */
    fun daysWindowStart(
        days: Int,
        now: Instant,
        zone: ZoneId,
    ): Long =
        now
            .atZone(zone)
            .toLocalDate()
            .minusDays(days - 1L)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    /** The middle value, or the mean of the middle two; null without values. */
    internal fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Nearest-rank P90: the smallest value with at least 90 % of the values at or below it; null without values. */
    internal fun p90(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val rank = (9 * values.size + 9) / 10 // ceil(0.9 n) in integers
        return values.sorted()[rank - 1]
    }

    /**
     * Each chapter's seconds per page this attempt downloaded; an attempt that downloaded none (its pages were already on
     * disk) is left out.
     */
    private fun secondsPerPage(ok: List<ChapterDownloadStats.Attempt>): List<Double> =
        ok.filter { it.pagesFetched > 0 }.map { it.totalMs / 1000.0 / it.pagesFetched }

    /**
     * Megabytes (10^6 bytes) per second over the [ok] attempts that downloaded every page themselves (the file's size is
     * the whole chapter's); null without any time.
     */
    private fun megabytesPerSecond(ok: List<ChapterDownloadStats.Attempt>): Double? {
        val whole = ok.filter { it.pages > 0 && it.pagesFetched == it.pages }
        val ms = whole.sumOf { it.totalMs.toLong() }
        return if (ms <= 0) null else whole.sumOf { it.bytes } / 1_000_000.0 / (ms / 1000.0)
    }

    private fun topFailures(failed: List<ChapterDownloadStats.Attempt>): List<FailureCount> =
        failed
            .groupingBy { it.outcome }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_FAILURES)
            .map { FailureCount(it.key, it.value) }
}
