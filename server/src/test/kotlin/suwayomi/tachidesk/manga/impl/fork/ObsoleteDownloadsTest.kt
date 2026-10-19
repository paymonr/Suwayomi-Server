package suwayomi.tachidesk.manga.impl.fork

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.download.Downloader
import suwayomi.tachidesk.manga.impl.download.FakePageSource
import suwayomi.tachidesk.manga.impl.download.createChapterWithPages
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.manga.impl.fork.ObsoleteDownloadsScenarios.source
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.source.GetSource.registerSource
import suwayomi.tachidesk.manga.impl.util.source.GetSource.unregisterSource
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.test.ApplicationTest
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObsoleteDownloadsTest : ApplicationTest() {
    @BeforeAll fun register() = registerSource(source.id to source)

    @AfterAll fun unregister() = unregisterSource(source.id)

    @Test fun `a new scanlator's cbz replaces the old one once it downloads`() =
        ObsoleteDownloadsScenarios.newScanlatorReplacesTheOldCbzOnceItDownloads()

    @Test fun `a new scanlator's folder replaces the old one once it downloads`() =
        ObsoleteDownloadsScenarios.newScanlatorReplacesTheOldFolderOnceItDownloads()

    @Test fun `an already downloaded replacement removes the old file at once`() =
        ObsoleteDownloadsScenarios.anAlreadyDownloadedReplacementRemovesTheOldFileAtOnce()

    @Test fun `a removed chapter with nothing replacing it keeps its file`() =
        ObsoleteDownloadsScenarios.aRemovedChapterWithNothingReplacingItKeepsItsFile()

    @Test fun `a refused rename keeps the old file until the chapter downloads again`() =
        ObsoleteDownloadsScenarios.aRefusedRenameKeepsTheOldFileUntilTheChapterDownloadsAgain()

    @Test fun `a download shared with another chapter replaces nothing`() =
        ObsoleteDownloadsScenarios.aDownloadSharedWithAnotherChapterReplacesNothing()

    @Test fun `a file a downloaded chapter points to is never deleted`() =
        ObsoleteDownloadsScenarios.aFileADownloadedChapterPointsToIsNeverDeleted()

    @Test fun `a chapter without a recognised number is left alone`() =
        ObsoleteDownloadsScenarios
            .aChapterWithoutARecognisedNumberIsLeftAlone()

    @Test fun `chapters from different volumes don't replace each other`() =
        ObsoleteDownloadsScenarios.chaptersFromDifferentVolumesDontReplaceEachOther()

    @Test fun `episodes from different seasons don't replace each other`() =
        ObsoleteDownloadsScenarios.episodesFromDifferentSeasonsDontReplaceEachOther()

    @Test fun `the same volume's chapter from another group still replaces it`() =
        ObsoleteDownloadsScenarios.theSameVolumesChapterFromAnotherGroupStillReplacesIt()

    @Test fun `without its table, syncs and downloads still work`() = ObsoleteDownloadsScenarios.withoutItsTableSyncsAndDownloadsStillWork()

    @Test
    fun `the downloader removes a chapter's old download once the chapter has downloaded`() {
        val fake = FakePageSource(7_000_016L, 2)
        registerSource(fake.id to fake)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val c = createChapterWithPages(fake.id, 2, "F16 downloader")
            transaction { ChapterTable.update({ ChapterTable.id eq c.chapterId }) { it[chapter_number] = 1f } }
            val old =
                File(runBlocking { getChapterCbzPath(c.mangaId, "Chapter 1 (old)", null) }).apply {
                    parentFile.mkdirs()
                    writeBytes(byteArrayOf(1, 2, 3))
                }
            val oldChapter =
                ChapterDataClass.fromSChapter(
                    SChapter.create().apply {
                        url = "/old"
                        name = "Chapter 1 (old)"
                        chapter_number = 1f
                    },
                    id = 0,
                    index = 1,
                    fetchedAt = 0,
                    mangaId = c.mangaId,
                    realUrl = null,
                )
            runBlocking { ObsoleteDownloads.dropped(c.mangaId, listOf(oldChapter)) }
            assertTrue(old.exists(), "chapter 1 isn't downloaded yet")

            val done = CompletableDeferred<Unit>()
            val item = DownloadQueueItem(c.chapterId, 1, c.mangaId, fake.id, 2)
            Downloader(scope, fake.id, CopyOnWriteArrayList(listOf(item)), { _, _ -> }, { done.complete(Unit) }, {}).start()
            runBlocking { withTimeout(30_000) { done.await() } }

            assertTrue(
                transaction { ChapterTable.selectAll().where { ChapterTable.id eq c.chapterId }.single()[ChapterTable.isDownloaded] },
            )
            assertFalse(old.exists(), "replaced by the download")
        } finally {
            scope.cancel()
            unregisterSource(fake.id)
        }
    }
}
