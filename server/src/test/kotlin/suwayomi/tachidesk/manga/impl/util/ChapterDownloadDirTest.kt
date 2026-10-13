package suwayomi.tachidesk.manga.impl.util

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.test.ApplicationTest
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
        assertTrue(runBlocking { updateChapterDownloadDir(chapter(index = 1), chapter(index = 5)) })
    }
}
