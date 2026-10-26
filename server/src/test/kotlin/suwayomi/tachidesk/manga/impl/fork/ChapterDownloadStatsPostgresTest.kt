package suwayomi.tachidesk.manga.impl.fork

import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.PostgresTest

class ChapterDownloadStatsPostgresTest : PostgresTest() {
    @Test
    fun `reads back the window, oldest first`() = ChapterDownloadStatsCases.readsBackTheWindowOldestFirst(System.nanoTime())

    @Test
    fun `filters by source`() = ChapterDownloadStatsCases.filtersBySource(System.nanoTime(), System.nanoTime())

    @Test
    fun `cuts a long outcome to fit`() = ChapterDownloadStatsCases.cutsALongOutcomeToFit(System.nanoTime())

    @Test
    fun `keeps an outcome with control characters or a split surrogate pair`() =
        ChapterDownloadStatsCases.keepsAnOutcomeWithControlCharactersOrASplitPair(System.nanoTime())
}
