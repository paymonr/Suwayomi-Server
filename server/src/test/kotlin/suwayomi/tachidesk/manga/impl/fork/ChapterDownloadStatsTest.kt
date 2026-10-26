package suwayomi.tachidesk.manga.impl.fork

import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterDownloadStatsTest : ApplicationTest() {
    @Test
    fun `reads back the window, oldest first`() = ChapterDownloadStatsCases.readsBackTheWindowOldestFirst(System.nanoTime())

    @Test
    fun `filters by source`() = ChapterDownloadStatsCases.filtersBySource(System.nanoTime(), System.nanoTime())

    @Test
    fun `cuts a long outcome to fit`() = ChapterDownloadStatsCases.cutsALongOutcomeToFit(System.nanoTime())

    @Test
    fun `keeps an outcome with control characters or a split surrogate pair`() =
        ChapterDownloadStatsCases.keepsAnOutcomeWithControlCharactersOrASplitPair(System.nanoTime())

    @Test
    fun `elapsed time beyond what an Int holds is capped`() {
        assertEquals(1_500, ChapterDownloadStats.elapsedMs(0, 1_500_000_000))
        assertEquals(Int.MAX_VALUE, ChapterDownloadStats.elapsedMs(0, 30L * 86_400 * 1_000_000_000))
    }

    /** Recording must never break a download: the downloader records a failure before it settles the chapter's state. */
    @Test
    fun `recording a failure never throws, even when the failure can't be described`() {
        val sourceId = System.nanoTime()
        val unreadable =
            object : Exception() {
                override val message: String get() = throw IllegalStateException("no message")
            }

        ChapterDownloadStats.AttemptTimer(DownloadQueueItem(1, 1, 1, sourceId, 3)).failed(unreadable)

        assertTrue(
            ChapterDownloadStats
                .attempts(0, Long.MAX_VALUE, sourceId)
                .single()
                .outcome
                .isNotEmpty(),
        )
    }
}
