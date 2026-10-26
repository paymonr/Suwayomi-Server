package suwayomi.tachidesk.manga.impl.fork

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChapterDownloadSpeedsTest {
    private val chicago = ZoneId.of("America/Chicago")

    private fun ok(
        sourceId: Long = 1,
        startedAt: Long = 0,
        pages: Int,
        totalMs: Int,
        bytes: Long,
        listMs: Int = 1_000,
        pageRetries: Int = 0,
    ) = ChapterDownloadStats.Attempt(sourceId, 1, 1, 1, startedAt, listMs, totalMs, pages, bytes, pageRetries, ChapterDownloadStats.OK)

    private fun failed(
        outcome: String,
        sourceId: Long = 1,
        startedAt: Long = 0,
        pageRetries: Int = 0,
    ) = ChapterDownloadStats.Attempt(
        sourceId,
        1,
        1,
        1,
        startedAt,
        ChapterDownloadStats.NO_PAGE_LIST,
        20_000,
        0,
        0,
        pageRetries,
        outcome,
    )

    @Test
    fun `a source's speeds come from its ok attempts, failures counted apart`() {
        val speed =
            ChapterDownloadSpeeds
                .bySource(
                    listOf(
                        ok(pages = 10, totalMs = 10_000, bytes = 5_000_000, listMs = 1_000, pageRetries = 2),
                        ok(pages = 20, totalMs = 40_000, bytes = 15_000_000, listMs = 3_000),
                        failed("HttpException: HTTP error 403", pageRetries = 1),
                    ),
                ).single()

        assertEquals(1L, speed.sourceId)
        assertEquals(2, speed.chapters)
        assertEquals(1, speed.failedAttempts)
        assertEquals(30, speed.pages)
        assertEquals(20_000_000L, speed.bytes)
        assertEquals(1.5, speed.secondsPerPageMedian) // 1.0 and 2.0 s/page
        assertEquals(2.0, speed.secondsPerPageP90)
        assertEquals(0.4, speed.megabytesPerSecond!!, 1e-9) // 20 MB in 50 s
        assertEquals(2.0, speed.listSecondsMedian) // the failed attempt never got a page list
        assertEquals(3, speed.pageRetries)
        assertEquals(listOf(ChapterDownloadSpeeds.FailureCount("HttpException: HTTP error 403", 1)), speed.topFailures)
    }

    @Test
    fun `median is the middle value or the mean of the middle two, P90 is nearest-rank`() {
        val oneToTen = (1..10).map { it.toDouble() }
        assertEquals(5.5, ChapterDownloadSpeeds.median(oneToTen))
        assertEquals(9.0, ChapterDownloadSpeeds.p90(oneToTen))
        assertEquals(2.0, ChapterDownloadSpeeds.median(listOf(3.0, 1.0, 2.0)))
        assertEquals(3.0, ChapterDownloadSpeeds.p90(listOf(3.0, 1.0, 2.0)))
        assertNull(ChapterDownloadSpeeds.median(emptyList()))
        assertNull(ChapterDownloadSpeeds.p90(emptyList()))
    }

    @Test
    fun `a source that only fails has no speeds, only failures`() {
        val speed =
            ChapterDownloadSpeeds
                .bySource(List(3) { failed("D: Timed out waiting for WebView after 20s") })
                .single()

        assertEquals(0, speed.chapters)
        assertEquals(3, speed.failedAttempts)
        assertNull(speed.secondsPerPageMedian)
        assertNull(speed.secondsPerPageP90)
        assertNull(speed.megabytesPerSecond)
        assertNull(speed.listSecondsMedian)
        assertEquals(listOf(ChapterDownloadSpeeds.FailureCount("D: Timed out waiting for WebView after 20s", 3)), speed.topFailures)
    }

    @Test
    fun `a chapter with no pages or no time can't make a speed infinite`() {
        val speed = ChapterDownloadSpeeds.bySource(listOf(ok(pages = 0, totalMs = 0, bytes = 0))).single()

        assertNull(speed.secondsPerPageMedian)
        assertNull(speed.megabytesPerSecond)
    }

    @Test
    fun `the top failures are the five most frequent, ties by name`() {
        val counts = listOf("a" to 1, "b" to 4, "c" to 2, "d" to 2, "e" to 3, "f" to 5)
        val speed = ChapterDownloadSpeeds.bySource(counts.flatMap { (outcome, n) -> List(n) { failed(outcome) } }).single()

        assertEquals(listOf("f" to 5, "b" to 4, "e" to 3, "c" to 2, "d" to 2), speed.topFailures.map { it.outcome to it.count })
    }

    @Test
    fun `sources come out in id order`() {
        val speeds =
            ChapterDownloadSpeeds.bySource(
                listOf(ok(sourceId = 9, pages = 1, totalMs = 1_000, bytes = 1), ok(sourceId = 2, pages = 1, totalMs = 1_000, bytes = 1)),
            )

        assertEquals(listOf(2L, 9L), speeds.map { it.sourceId })
    }

    @Test
    fun `days are the server's local days, oldest first, empty days left out`() {
        val lateOn25th = ZonedDateTime.of(2026, 10, 25, 23, 30, 0, 0, chicago).toInstant().toEpochMilli()
        val earlyOn26th = lateOn25th + 3_600_000
        val on28th = lateOn25th + 3 * 86_400_000L

        val days =
            ChapterDownloadSpeeds.byDay(
                listOf(
                    ok(startedAt = on28th, pages = 10, totalMs = 30_000, bytes = 3_000_000),
                    ok(startedAt = lateOn25th, pages = 10, totalMs = 10_000, bytes = 1_000_000),
                    failed("x", startedAt = earlyOn26th),
                    ok(startedAt = earlyOn26th, pages = 10, totalMs = 20_000, bytes = 1_000_000),
                ),
                chicago,
            )

        assertEquals(listOf(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28)), days.map { it.day })
        assertEquals(listOf(1, 1, 1), days.map { it.chapters })
        assertEquals(listOf(0, 1, 0), days.map { it.failedAttempts })
        assertEquals(listOf(1.0, 2.0, 3.0), days.map { it.secondsPerPageMedian })
        assertEquals(0.1, days[0].megabytesPerSecond!!, 1e-9)
    }

    @Test
    fun `the last n days start at local midnight n-1 days ago`() {
        val now = ZonedDateTime.of(2026, 10, 25, 14, 0, 0, 0, chicago).toInstant()

        assertEquals(
            ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, chicago).toInstant().toEpochMilli(),
            ChapterDownloadSpeeds.daysWindowStart(1, now, chicago),
        )
        assertEquals(
            ZonedDateTime.of(2026, 9, 26, 0, 0, 0, 0, chicago).toInstant().toEpochMilli(),
            ChapterDownloadSpeeds.daysWindowStart(30, now, chicago),
        )
    }
}
