package suwayomi.tachidesk.manga.impl.download

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.ChapterDownloadHelper
import suwayomi.tachidesk.manga.impl.Page
import suwayomi.tachidesk.manga.impl.chapter.getChapterDownloadReadyById
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.source.GetSource.registerSource
import suwayomi.tachidesk.manga.impl.util.source.GetSource.unregisterSource
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageRowsAfterDownloadTest : ApplicationTest() {
    private val sourceId = 7_000_017L
    private val pageCount = 4
    private val source = FakePageSource(sourceId, pageCount)
    private var previousCbz = false

    @BeforeAll
    fun setUp() {
        registerSource(sourceId to source)
        previousCbz = serverConfig.downloadAsCbz.value
        serverConfig.downloadAsCbz.value = true
    }

    @AfterAll
    fun tearDown() {
        unregisterSource(sourceId)
        serverConfig.downloadAsCbz.value = previousCbz
    }

    private fun pageRows(chapterId: Int): Long =
        transaction { PageTable.select(PageTable.id).where { PageTable.chapter eq chapterId }.count() }

    private fun markDownloaded(chapterId: Int) {
        transaction { ChapterTable.update({ ChapterTable.id eq chapterId }) { it[isDownloaded] = true } }
    }

    private fun readPage(
        c: FakeChapter,
        index: Int,
    ): ByteArray =
        runBlocking { Page.getPageImage(mangaId = c.mangaId, chapterId = c.chapterId, index = index).first.use { it.readBytes() } }

    @Test
    fun `a finished download leaves no page rows`() {
        val c = createChapterWithPages(sourceId, pageCount, "F7 rows")
        downloadChapter(c, sourceId, pageCount)
        assertEquals(0, pageRows(c.chapterId))
    }

    @Test
    fun `a chapter already on disk drops its page rows too`() {
        val c = createChapterWithPages(sourceId, pageCount, "F7 on disk")
        downloadChapter(c, sourceId, pageCount)
        insertPageRows(c.chapterId, pageCount)
        downloadChapter(c, sourceId, pageCount) // finds the complete CBZ and returns early
        assertEquals(0, pageRows(c.chapterId))
    }

    @Test
    fun `a downloaded chapter whose file is gone fetches its page list again`() {
        val c = createChapterWithPages(sourceId, pageCount, "F7 file gone")
        downloadChapter(c, sourceId, pageCount)
        markDownloaded(c.chapterId)
        transaction { PageTable.deleteWhere { PageTable.chapter eq c.chapterId } }
        File(runBlocking { getChapterCbzPath(c.mangaId, c.chapterId) }).delete()
        val listsBefore = source.pageListRequests.get()

        assertContentEquals(FakePageSource.pageBytes(1), readPage(c, 1))
        assertEquals(listsBefore + 1, source.pageListRequests.get())
    }

    /** Characterization: the online path F7 relies on. Passes before and after the fix. */
    @Test
    fun `reading online after deleting a download fetches the page list again`() {
        val c = createChapterWithPages(sourceId, pageCount, "F7 deleted")
        downloadChapter(c, sourceId, pageCount)
        markDownloaded(c.chapterId)
        runBlocking { ChapterDownloadHelper.delete(c.mangaId, c.chapterId) }
        val listsBefore = source.pageListRequests.get()

        assertEquals(pageCount, runBlocking { getChapterDownloadReadyById(c.chapterId) }.pageCount)
        assertEquals(listsBefore + 1, source.pageListRequests.get())
        assertContentEquals(FakePageSource.pageBytes(2), readPage(c, 2))
    }
}
