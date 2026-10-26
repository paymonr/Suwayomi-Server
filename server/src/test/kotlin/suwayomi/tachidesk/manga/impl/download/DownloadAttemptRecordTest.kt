package suwayomi.tachidesk.manga.impl.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.manga.impl.download.model.DownloadState
import suwayomi.tachidesk.manga.impl.fork.ChapterDownloadStats
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.source.GetSource.registerSource
import suwayomi.tachidesk.manga.impl.util.source.GetSource.unregisterSource
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.database.fork.ForkSchema
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Download speeds: the real Downloader writes one fork_chapter_download row per attempt that finishes or fails. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DownloadAttemptRecordTest : ApplicationTest() {
    private var previousCbz = false

    @BeforeAll
    fun setUp() {
        previousCbz = serverConfig.downloadAsCbz.value
        serverConfig.downloadAsCbz.value = true
    }

    @AfterAll
    fun tearDown() {
        serverConfig.downloadAsCbz.value = previousCbz
    }

    private fun rows(sourceId: Long) = ChapterDownloadStats.attempts(0, Long.MAX_VALUE, sourceId)

    /** A fake source with a fresh id (the H2 test database outlives a run) and one chapter on it. */
    private fun withSource(
        pageCount: Int,
        statusFor: (page: Int, attempt: Int) -> Int = { _, _ -> 200 },
        block: (FakePageSource, FakeChapter) -> Unit,
    ) {
        val source = FakePageSource(System.nanoTime(), pageCount, statusFor)
        registerSource(source.id to source)
        try {
            block(source, createChapterWithPages(source.id, pageCount, "speeds ${source.id}"))
        } finally {
            unregisterSource(source.id)
        }
    }

    /** Runs a Downloader over [c] until its queue is done; returns the queue item. */
    private fun download(
        source: FakePageSource,
        c: FakeChapter,
        pageCount: Int,
    ): DownloadQueueItem {
        val item = DownloadQueueItem(c.chapterId, 1, c.mangaId, source.id, pageCount)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val done = CompletableDeferred<Unit>()
            Downloader(scope, source.id, CopyOnWriteArrayList(listOf(item)), { _, _ -> }, { done.complete(Unit) }, {}).start()
            runBlocking { withTimeout(60_000) { done.await() } }
        } finally {
            scope.cancel()
        }
        return item
    }

    @Test
    fun `a downloaded chapter leaves one ok row with its pages, size and times`() =
        withSource(pageCount = 3) { source, c ->
            val before = System.currentTimeMillis()
            download(source, c, 3)
            val after = System.currentTimeMillis()

            val row = rows(source.id).single()
            assertEquals(ChapterDownloadStats.OK, row.outcome)
            assertEquals(1, row.attempt)
            assertEquals(c.mangaId, row.mangaId)
            assertEquals(c.chapterId, row.chapterId)
            assertEquals(3, row.pages)
            assertEquals(File(runBlocking { getChapterCbzPath(c.mangaId, c.chapterId) }).length(), row.bytes)
            assertTrue(row.bytes > 0)
            assertTrue(row.startedAt in before..after, "started at ${row.startedAt}, test ran $before..$after")
            assertTrue(row.listMs in 0..row.totalMs, "page list ${row.listMs} ms of ${row.totalMs} ms")
            assertEquals(0, row.pageRetries)
        }

    @Test
    fun `a chapter that keeps failing leaves one row per failed attempt`() =
        withSource(pageCount = 3, statusFor = { _, _ -> 404 }) { source, c ->
            download(source, c, 3)

            val rows = rows(source.id)
            assertEquals(listOf(1, 2, 3), rows.map { it.attempt })
            rows.forEach { row ->
                assertEquals("HttpException: HTTP error 404", row.outcome)
                assertEquals(0L, row.bytes)
                assertEquals(3, row.pages)
                assertTrue(row.listMs >= 0)
            }
        }

    @Test
    fun `page retries are counted on the attempt`() =
        withSource(pageCount = 3, statusFor = { page, attempt -> if (page == 1 && attempt == 1) 503 else 200 }) { source, c ->
            download(source, c, 3)

            val row = rows(source.id).single()
            assertEquals(ChapterDownloadStats.OK, row.outcome)
            assertEquals(1, row.pageRetries)
        }

    @Test
    fun `an empty chapter leaves one failed row with no pages`() =
        withSource(pageCount = 0) { source, c ->
            download(source, c, 0)

            val row = rows(source.id).single()
            assertEquals("EmptyChapterException: Chapter does not have any pages to download", row.outcome)
            assertEquals(0, row.pages)
            assertTrue(row.listMs >= 0)
        }

    @Test
    fun `a stopped download leaves no row, and its retries don't carry into the next attempt`() =
        withSource(pageCount = 3, statusFor = { page, attempt -> if (page == 0 && attempt <= 2) 503 else 200 }) { source, c ->
            val item = DownloadQueueItem(c.chapterId, 1, c.mangaId, source.id, 3)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val downloader = Downloader(scope, source.id, CopyOnWriteArrayList(listOf(item)), { _, _ -> }, {}, {})
            try {
                downloader.start()
                runBlocking {
                    withTimeout(10_000) { while (source.attemptsFor(0) < 1) delay(20) }
                    downloader.stop() // page 0 waits 2 s to retry; stopping cancels the wait
                }
                assertEquals(emptyList(), rows(source.id))
            } finally {
                scope.cancel()
            }

            download(source, c, 3) // page 0: 503 once more, then 200

            val row = rows(source.id).single()
            assertEquals(ChapterDownloadStats.OK, row.outcome)
            assertEquals(1, row.pageRetries)
        }

    @Test
    fun `a download whose row can't be written still finishes`() =
        withSource(pageCount = 2) { source, c ->
            transaction { exec("DROP TABLE ${ChapterDownloadStats.TABLE}") }
            try {
                val item = download(source, c, 2)

                assertEquals(DownloadState.Finished, item.state)
                val downloaded =
                    transaction {
                        ChapterTable.selectAll().where { ChapterTable.id eq c.chapterId }.single()[ChapterTable.isDownloaded]
                    }
                assertTrue(downloaded)
            } finally {
                ForkSchema.apply()
            }
            assertEquals(emptyList(), rows(source.id))
        }
}
