package suwayomi.tachidesk.server.database.fork

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.PostgresTest
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForkChangeTrackingPostgresTest : PostgresTest() {
    private fun stamp(mangaId: Int): Long =
        transaction {
            exec("SELECT fork_changed_at FROM suwayomi.manga WHERE id = $mangaId") { rs ->
                rs.next()
                rs.getLong(1)
            }
        }!!

    /** Sets the stamp to 1, so any later stamp shows as larger. */
    private fun backdate(mangaId: Int) {
        transaction { exec("UPDATE suwayomi.manga SET fork_changed_at = 1 WHERE id = $mangaId") }
    }

    private fun chapterOf(mangaId: Int): Int =
        transaction {
            ChapterTable
                .select(ChapterTable.id)
                .where { ChapterTable.manga eq mangaId }
                .first()[ChapterTable.id]
                .value
        }

    /** A library series with one chapter, stamp backdated. */
    private fun librarySeries(): Pair<Int, Int> {
        val mangaId = createLibraryManga("E1 ${System.nanoTime()}")
        createChapters(mangaId, 1, read = false)
        backdate(mangaId)
        return mangaId to chapterOf(mangaId)
    }

    private fun triggerNames(): List<String> =
        transaction {
            exec("SELECT tgname FROM pg_trigger WHERE NOT tgisinternal AND tgname LIKE 'fork\\_%'") { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }.orEmpty().sorted()

    @Test
    fun `databaseUp turns change tracking on`() {
        assertTrue(ForkChangeTracking.active)
        assertEquals(ForkChangeTracking.triggerNames.sorted(), triggerNames())
    }

    @Test
    fun `a new series gets a stamp`() {
        assertTrue(stamp(createLibraryManga("E1 new ${System.nanoTime()}")) > 1)
    }

    @Test
    fun `downloading a chapter stamps its library series`() {
        val (mangaId, chapterId) = librarySeries()
        transaction {
            ChapterTable.update({ ChapterTable.id eq chapterId }) {
                it[ChapterTable.isDownloaded] = true
                it[ChapterTable.pageCount] = 12
            }
        }
        assertTrue(stamp(mangaId) > 1)
    }

    @Test
    fun `a new chapter stamps its library series`() {
        val (mangaId, _) = librarySeries()
        createChapters(mangaId, 1, read = false, start = 2)
        assertTrue(stamp(mangaId) > 1)
    }

    @Test
    fun `a removed chapter stamps its library series`() {
        val (mangaId, chapterId) = librarySeries()
        transaction { ChapterTable.deleteWhere { ChapterTable.id eq chapterId } }
        assertTrue(stamp(mangaId) > 1)
    }

    @Test
    fun `a category change stamps the series`() {
        val (mangaId, _) = librarySeries()
        CategoryManga.addMangaToCategory(mangaId, Category.createCategory("E1 ${System.nanoTime()}"))
        assertTrue(stamp(mangaId) > 1)
    }

    @Test
    fun `fetch times and reading progress leave the stamp alone`() {
        val (mangaId, chapterId) = librarySeries()
        transaction {
            MangaTable.update({ MangaTable.id eq mangaId }) {
                it[MangaTable.lastFetchedAt] = 123
                it[MangaTable.chaptersLastFetchedAt] = 456
            }
            ChapterTable.update({ ChapterTable.id eq chapterId }) {
                it[ChapterTable.lastPageRead] = 3
                it[ChapterTable.lastReadAt] = 789
                it[ChapterTable.fetchedAt] = 999
            }
        }
        assertEquals(1, stamp(mangaId))
    }

    @Test
    fun `chapter changes of a series outside the library leave its stamp alone`() {
        val mangaId =
            transaction {
                MangaTable
                    .insertAndGetId {
                        it[MangaTable.title] = "E1 browse ${System.nanoTime()}"
                        it[MangaTable.url] = "/e1/${System.nanoTime()}"
                        it[MangaTable.sourceReference] = 1
                    }.value
            }
        backdate(mangaId)
        createChapters(mangaId, 2, read = false)
        assertEquals(1, stamp(mangaId))
    }

    @Test
    fun `taking a series out of the library stamps it`() {
        val (mangaId, _) = librarySeries()
        transaction { MangaTable.update({ MangaTable.id eq mangaId }) { it[MangaTable.inLibrary] = false } }
        assertTrue(stamp(mangaId) > 1)
    }

    /** suwayomi-dm's graft role may update only chapter.is_downloaded and page_count (containers/suwayomi-dm/backend/src/db/graftDb.ts). */
    @Test
    fun `the graft's narrow role can still mark a chapter downloaded`() {
        val (mangaId, chapterId) = librarySeries()
        newConnection().use { c ->
            c.createStatement().use { s ->
                s.execute("DO \$\$ BEGIN CREATE ROLE fork_graft_test NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$")
                s.execute("GRANT USAGE ON SCHEMA suwayomi TO fork_graft_test")
                s.execute("GRANT SELECT, UPDATE (is_downloaded, page_count) ON suwayomi.chapter TO fork_graft_test")
                s.execute("SET ROLE fork_graft_test")
                try {
                    assertEquals(
                        1,
                        s.executeUpdate("UPDATE suwayomi.chapter SET is_downloaded = true, page_count = 9 WHERE id = $chapterId"),
                    )
                } finally {
                    s.execute("RESET ROLE")
                }
            }
        }
        assertTrue(stamp(mangaId) > 1)
    }

    @Test
    fun `deleting a library series with its chapters works`() {
        val (mangaId, _) = librarySeries()
        transaction { MangaTable.deleteWhere { MangaTable.id eq mangaId } }
        val left = transaction { MangaTable.select(MangaTable.id).where { MangaTable.id eq mangaId }.count() }
        assertEquals(0, left)
    }

    @Test
    fun `applying it again keeps it on and adds nothing`() {
        ForkChangeTracking.apply()
        assertTrue(ForkChangeTracking.active)
        assertEquals(ForkChangeTracking.triggerNames.sorted(), triggerNames())
    }
}
