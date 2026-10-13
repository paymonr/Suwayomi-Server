package suwayomi.tachidesk.manga.impl

import eu.kanade.tachiyomi.source.model.SChapter
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChapterSyncChangesTest {
    private val base =
        ChapterDataClass.fromSChapter(
            SChapter.create().apply {
                url = "/c/1"
                name = "Chapter 1"
                scanlator = "Group"
                date_upload = 1_000
                chapter_number = 1f
            },
            id = 1,
            index = 1,
            fetchedAt = 10,
            mangaId = 1,
            realUrl = "https://x/c/1",
        )

    @Test fun `identical is unchanged`() = assertFalse(base.differsInSyncedFields(base.copy()))

    @Test fun `fields the sync doesn't write don't count`() =
        assertFalse(base.differsInSyncedFields(base.copy(read = true, downloaded = true, pageCount = 30, fetchedAt = 99, lastReadAt = 5)))

    @Test fun `name`() = assertTrue(base.differsInSyncedFields(base.copy(name = "Chapter 1 (fixed)")))

    @Test fun `upload date`() = assertTrue(base.differsInSyncedFields(base.copy(uploadDate = 2_000)))

    @Test fun `chapter number`() = assertTrue(base.differsInSyncedFields(base.copy(chapterNumber = 1.5f)))

    @Test fun `scanlator`() = assertTrue(base.differsInSyncedFields(base.copy(scanlator = null)))

    @Test fun `source order`() = assertTrue(base.differsInSyncedFields(base.copy(index = 2)))

    @Test fun `real url`() = assertTrue(base.differsInSyncedFields(base.copy(realUrl = "https://y/c/1")))
}
