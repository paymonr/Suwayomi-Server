package suwayomi.tachidesk.manga.impl.fork

import kotlin.test.assertEquals

/** Shared by the H2 and Postgres classes, so both databases run the same checks. */
internal object ChapterDownloadStatsCases {
    fun attempt(
        sourceId: Long,
        startedAt: Long,
        outcome: String = ChapterDownloadStats.OK,
    ) = ChapterDownloadStats.Attempt(
        sourceId = sourceId,
        mangaId = 1,
        chapterId = 2,
        attempt = 1,
        startedAt = startedAt,
        listMs = 300,
        totalMs = 4_000,
        pages = 20,
        bytes = 2_000_000,
        pageRetries = 1,
        outcome = outcome,
        pagesFetched = 15,
    )

    /** The window is [since, until): an attempt started at until is left out. */
    fun readsBackTheWindowOldestFirst(sourceId: Long) {
        val first = attempt(sourceId, 1_000)
        val second = attempt(sourceId, 2_000, "HttpException: HTTP error 403")
        val after = attempt(sourceId, 3_000)
        listOf(second, after, first).forEach(ChapterDownloadStats::record)

        assertEquals(listOf(first, second), ChapterDownloadStats.attempts(1_000, 3_000, sourceId))
    }

    fun filtersBySource(
        sourceId: Long,
        otherSourceId: Long,
    ) {
        ChapterDownloadStats.record(attempt(sourceId, 10_000))
        ChapterDownloadStats.record(attempt(otherSourceId, 10_000))

        assertEquals(listOf(sourceId), ChapterDownloadStats.attempts(10_000, 10_001, sourceId).map { it.sourceId })
        assertEquals(
            setOf(sourceId, otherSourceId),
            ChapterDownloadStats
                .attempts(10_000, 10_001)
                .map { it.sourceId }
                .filter { it == sourceId || it == otherSourceId }
                .toSet(),
        )
    }

    fun cutsALongOutcomeToFit(sourceId: Long) {
        ChapterDownloadStats.record(attempt(sourceId, 20_000, "x".repeat(400)))

        assertEquals(
            ChapterDownloadStats.MAX_OUTCOME,
            ChapterDownloadStats
                .attempts(20_000, 20_001, sourceId)
                .single()
                .outcome.length,
        )
    }
}
