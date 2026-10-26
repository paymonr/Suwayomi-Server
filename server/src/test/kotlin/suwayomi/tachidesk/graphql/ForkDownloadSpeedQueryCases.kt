package suwayomi.tachidesk.graphql

import suwayomi.tachidesk.manga.impl.fork.ChapterDownloadStats
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shared by the H2 and Postgres classes. */
@Suppress("UNCHECKED_CAST")
internal object ForkDownloadSpeedQueryCases {
    private fun row(
        sourceId: Long,
        startedAt: Long,
        pages: Int,
        totalMs: Int,
        bytes: Long,
        listMs: Int,
        outcome: String = ChapterDownloadStats.OK,
    ) = ChapterDownloadStats.Attempt(sourceId, 1, 1, 1, startedAt, listMs, totalMs, pages, bytes, 0, outcome)

    fun answersPerSourceForTheWindow(sourceId: Long) {
        val t = 1_700_000_000_000L
        ChapterDownloadStats.record(row(sourceId, t, pages = 10, totalMs = 10_000, bytes = 5_000_000, listMs = 1_000))
        ChapterDownloadStats.record(row(sourceId, t + 1, pages = 20, totalMs = 40_000, bytes = 15_000_000, listMs = 3_000))
        ChapterDownloadStats.record(
            row(sourceId, t + 2, 0, 20_000, 0, ChapterDownloadStats.NO_PAGE_LIST, "HttpException: HTTP error 403"),
        )
        ChapterDownloadStats.record(row(sourceId, t + 10, pages = 99, totalMs = 1, bytes = 1, listMs = 1)) // after the window

        val result =
            executeGraphQL(
                """{ downloadSpeedBySource(since: "$t", until: "${t + 10}") {
                    sourceId chapters failedAttempts pages bytes secondsPerPageMedian secondsPerPageP90
                    megabytesPerSecond listSecondsMedian pageRetries topFailures { outcome count } } }""",
            )

        assertEquals(emptyList(), result.errors)
        val entry =
            (result.dataAt("downloadSpeedBySource") as List<Map<String, Any?>>)
                .single { it["sourceId"] == sourceId.toString() }
        assertEquals(2, entry["chapters"])
        assertEquals(1, entry["failedAttempts"])
        assertEquals(30, entry["pages"])
        assertEquals("20000000", entry["bytes"])
        assertEquals(1.5, entry["secondsPerPageMedian"])
        assertEquals(2.0, entry["secondsPerPageP90"])
        assertEquals(0.4, entry["megabytesPerSecond"] as Double, 1e-9)
        assertEquals(2.0, entry["listSecondsMedian"])
        assertEquals(0, entry["pageRetries"])
        assertEquals(listOf(mapOf("outcome" to "HttpException: HTTP error 403", "count" to 1)), entry["topFailures"])
    }

    fun answersPerDayForOneSource(
        sourceId: Long,
        otherSourceId: Long,
    ) {
        val now = System.currentTimeMillis()
        val threeDaysAgo = now - 3 * 86_400_000L
        ChapterDownloadStats.record(row(sourceId, now - 1_000, pages = 10, totalMs = 10_000, bytes = 1_000_000, listMs = 500))
        ChapterDownloadStats.record(row(sourceId, threeDaysAgo, pages = 10, totalMs = 30_000, bytes = 3_000_000, listMs = 500))
        ChapterDownloadStats.record(row(otherSourceId, now - 1_000, pages = 10, totalMs = 50_000, bytes = 1, listMs = 500))

        fun days(n: Int): List<Map<String, Any?>> {
            val result =
                executeGraphQL(
                    """{ downloadSpeedByDay(sourceId: "$sourceId", days: $n) {
                        day chapters failedAttempts secondsPerPageMedian megabytesPerSecond } }""",
                )
            assertEquals(emptyList(), result.errors)
            return result.dataAt("downloadSpeedByDay") as List<Map<String, Any?>>
        }

        val zone = ZoneId.systemDefault()

        fun dayOf(ms: Long) =
            Instant
                .ofEpochMilli(ms)
                .atZone(zone)
                .toLocalDate()
                .toString()
        assertEquals(listOf(dayOf(now - 1_000)), days(2).map { it["day"] })
        assertEquals(listOf(dayOf(threeDaysAgo), dayOf(now - 1_000)), days(7).map { it["day"] })
        assertEquals(listOf(3.0, 1.0), days(7).map { it["secondsPerPageMedian"] })
    }

    /** suwayomi-dm asks with since alone. */
    fun answersWithoutUntil(sourceId: Long) {
        val now = System.currentTimeMillis()
        ChapterDownloadStats.record(row(sourceId, now - 1_000, pages = 10, totalMs = 10_000, bytes = 1_000_000, listMs = 500))

        val result = executeGraphQL("""{ downloadSpeedBySource(since: "${now - 60_000}") { sourceId chapters } }""")

        assertEquals(emptyList(), result.errors)
        val entry =
            (result.dataAt("downloadSpeedBySource") as List<Map<String, Any?>>)
                .single { it["sourceId"] == sourceId.toString() }
        assertEquals(1, entry["chapters"])
    }

    fun refusesDaysOutOfRange() {
        listOf(0, 366).forEach { n ->
            val result = executeGraphQL("""{ downloadSpeedByDay(sourceId: "1", days: $n) { day } }""")
            assertTrue(result.errors.any { "days must be between 1 and 365" in it.message }, "days: $n, errors: ${result.errors}")
        }
    }
}
