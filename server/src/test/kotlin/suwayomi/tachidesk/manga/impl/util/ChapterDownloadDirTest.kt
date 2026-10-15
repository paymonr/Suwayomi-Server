package suwayomi.tachidesk.manga.impl.util

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.createLibraryManga
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChapterDownloadDirTest : ApplicationTest() {
    private fun chapter(index: Int) =
        ChapterDataClass.fromSChapter(
            SChapter.create().apply {
                url = "/c/1"
                name = "Chapter 1"
                scanlator = "Group"
            },
            id = 1,
            index = index,
            fetchedAt = 0,
            mangaId = 999_999, // no such series: any path lookup would throw
            realUrl = null,
        )

    @Test
    fun `same name and scanlator returns true without any path lookups`() {
        assertTrue(runBlocking { ChapterDownloadDirs(999_999).update(chapter(index = 1), chapter(index = 5)) })
    }

    @Test
    fun `renames for one series look its folder up only once`() {
        val mangaId = createLibraryManga("F1 dirs ${System.nanoTime()}")

        fun chapter(name: String) =
            ChapterDataClass.fromSChapter(
                SChapter.create().apply {
                    url = "/c/$name"
                    this.name = name
                },
                id = 1,
                index = 1,
                fetchedAt = 0,
                mangaId = mangaId,
                realUrl = null,
            )
        val path = { name: String -> File(runBlocking { getChapterDownloadPath(mangaId, name, null) }) }
        val (oldA, oldB, newA, newB) = listOf("A", "B", "A2", "B2").map(path)
        oldA.mkdirs()
        oldB.mkdirs()
        val dirs = ChapterDownloadDirs(mangaId)

        runBlocking {
            assertTrue(dirs.update(chapter("A"), chapter("A2")))
            // looking the series up again would now throw
            transaction { MangaTable.deleteWhere { MangaTable.id eq mangaId } }
            assertTrue(dirs.update(chapter("B"), chapter("B2")))
        }

        assertTrue(newA.isDirectory && newB.isDirectory)
        assertFalse(oldA.exists() || oldB.exists())
    }
}
