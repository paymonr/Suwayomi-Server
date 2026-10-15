package suwayomi.tachidesk.manga.impl

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.impl.util.source.GetSource.registerSource
import suwayomi.tachidesk.manga.impl.util.source.GetSource.unregisterSource
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.PostgresTest
import suwayomi.tachidesk.test.createLibraryManga
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChapterListSyncPostgresTest : PostgresTest() {
    private val source = StubSource(1L)

    @BeforeAll fun register() = registerSource(1L to source)

    @AfterAll fun unregister() = unregisterSource(1L)

    private fun chapters(vararg names: String) =
        names.mapIndexed { i, n ->
            SChapter.create().apply {
                url = "/c/${n.substringBefore(' ')}"
                name = n
                date_upload = 1_000L + i
                chapter_number = (i + 1).toFloat()
            }
        }

    private fun sync(
        mangaId: Int,
        list: List<SChapter>,
    ) = runBlocking {
        val row = transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.first() }
        Chapter.updateChapterListDatabase(row, list, source)
    }

    private fun xmins(mangaId: Int): Map<String, String> =
        transaction {
            exec("SELECT url, xmin::text FROM chapter WHERE manga = $mangaId") { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
            }.orEmpty()
        }

    @Test
    fun `syncing an unchanged list rewrites no rows`() {
        val mangaId = createLibraryManga("F1 same ${System.nanoTime()}")
        sync(mangaId, chapters("3", "2", "1"))
        val before = xmins(mangaId)
        sync(mangaId, chapters("3", "2", "1"))
        assertEquals(before, xmins(mangaId))
    }

    @Test
    fun `a renamed chapter is the only row rewritten`() {
        val mangaId = createLibraryManga("F1 rename ${System.nanoTime()}")
        sync(mangaId, chapters("3", "2", "1"))
        val before = xmins(mangaId)
        sync(mangaId, chapters("3", "2 (fixed)", "1"))
        val after = xmins(mangaId)
        assertEquals(before.filterKeys { it != "/c/2" }, after.filterKeys { it != "/c/2" })
        assert(before["/c/2"] != after["/c/2"]) { "renamed chapter must be rewritten" }
    }

    @Test
    fun `duplicate urls still sync without rewrites`() {
        val mangaId = createLibraryManga("F1 dup ${System.nanoTime()}")
        val list = chapters("3", "2", "1") + chapters("2")
        sync(mangaId, list)
        val before = xmins(mangaId)
        sync(mangaId, list)
        assertEquals(before, xmins(mangaId))
    }

    private data class Row(
        val name: String,
        val downloaded: Boolean,
        val pageCount: Int,
    )

    private fun rowsByUrl(mangaId: Int): Map<String, List<Row>> =
        transaction {
            ChapterTable
                .selectAll()
                .where { ChapterTable.manga eq mangaId }
                .orderBy(ChapterTable.id)
                .groupBy(
                    { it[ChapterTable.url] },
                    { Row(it[ChapterTable.name], it[ChapterTable.isDownloaded], it[ChapterTable.pageCount]) },
                )
        }

    @Test
    fun `a renamed downloaded chapter keeps its download and its folder follows the new name`() {
        val mangaId = createLibraryManga("F1 renamed download ${System.nanoTime()}")
        sync(mangaId, chapters("3", "2", "1"))
        transaction {
            ChapterTable.update({ (ChapterTable.manga eq mangaId) and (ChapterTable.url eq "/c/2") }) {
                it[isDownloaded] = true
                it[pageCount] = 5
            }
        }
        val oldFolder = File(runBlocking { getChapterDownloadPath(mangaId, "2", null) }).apply { mkdirs() }
        File(oldFolder, "001.png").writeBytes(byteArrayOf(1, 2, 3))
        val newFolder = File(runBlocking { getChapterDownloadPath(mangaId, "2 (fixed)", null) })

        sync(mangaId, chapters("3", "2 (fixed)", "1"))

        assertEquals(listOf(Row("2 (fixed)", downloaded = true, pageCount = 5)), rowsByUrl(mangaId).getValue("/c/2"))
        assertFalse(oldFolder.exists(), "old folder must be gone")
        assertTrue(File(newFolder, "001.png").isFile, "page must be in the renamed folder")
    }

    @Test
    fun `a renamed downloaded chapter keeps its download and its cbz follows the new name`() {
        val mangaId = createLibraryManga("F1 renamed cbz ${System.nanoTime()}")
        sync(mangaId, chapters("3", "2", "1"))
        transaction {
            ChapterTable.update({ (ChapterTable.manga eq mangaId) and (ChapterTable.url eq "/c/2") }) {
                it[isDownloaded] = true
                it[pageCount] = 5
            }
        }
        val oldCbz = File(runBlocking { getChapterCbzPath(mangaId, "2", null) })
        oldCbz.parentFile.mkdirs()
        oldCbz.writeBytes(byteArrayOf(1, 2, 3))
        val newCbz = File(runBlocking { getChapterCbzPath(mangaId, "2 (fixed)", null) })

        sync(mangaId, chapters("3", "2 (fixed)", "1"))

        assertEquals(listOf(Row("2 (fixed)", downloaded = true, pageCount = 5)), rowsByUrl(mangaId).getValue("/c/2"))
        assertFalse(oldCbz.exists(), "old cbz must be gone")
        assertTrue(newCbz.isFile, "cbz must have the new name")
    }
}
