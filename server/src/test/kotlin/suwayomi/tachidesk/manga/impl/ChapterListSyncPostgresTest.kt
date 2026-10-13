package suwayomi.tachidesk.manga.impl

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.util.source.GetSource.registerSource
import suwayomi.tachidesk.manga.impl.util.source.GetSource.unregisterSource
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.PostgresTest
import suwayomi.tachidesk.test.createLibraryManga
import kotlin.test.assertEquals

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
}
