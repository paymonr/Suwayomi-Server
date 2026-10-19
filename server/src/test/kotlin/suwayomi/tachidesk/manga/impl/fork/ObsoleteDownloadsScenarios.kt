package suwayomi.tachidesk.manga.impl.fork

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.database.fork.ForkSchema
import suwayomi.tachidesk.test.createLibraryManga
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F16 cases, run on H2 ([ObsoleteDownloadsTest]) and Postgres ([ObsoleteDownloadsPostgresTest]).
 * Each case makes its own series; [createLibraryManga] puts it on source 1, which the test classes register as [source].
 */
internal object ObsoleteDownloadsScenarios {
    val source = StubSource(1L)

    private fun chapter(
        url: String,
        name: String,
        number: Float,
        scanlator: String? = null,
    ) = SChapter.create().apply {
        this.url = url
        this.name = name
        this.scanlator = scanlator
        chapter_number = number
        date_upload = 1_000L
    }

    private fun sync(
        mangaId: Int,
        vararg list: SChapter,
    ) = runBlocking {
        val row = transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.first() }
        Chapter.updateChapterListDatabase(row, list.toList(), source)
    }

    private fun idOf(
        mangaId: Int,
        url: String,
    ): Int =
        transaction {
            ChapterTable
                .select(ChapterTable.id)
                .where { (ChapterTable.manga eq mangaId) and (ChapterTable.url eq url) }
                .single()[ChapterTable.id]
                .value
        }

    private fun isDownloaded(
        mangaId: Int,
        url: String,
    ): Boolean =
        transaction { ChapterTable.selectAll().where { ChapterTable.id eq idOf(mangaId, url) }.single()[ChapterTable.isDownloaded] }

    /** A chapter that was downloaded before the sync under test. */
    private fun markDownloaded(
        mangaId: Int,
        url: String,
    ) {
        val id = idOf(mangaId, url)
        transaction {
            ChapterTable.update({ ChapterTable.id eq id }) {
                it[isDownloaded] = true
                it[pageCount] = 1
            }
        }
    }

    /** What the downloader does once a chapter's file is in place. */
    private fun downloaded(
        mangaId: Int,
        url: String,
    ) {
        markDownloaded(mangaId, url)
        runBlocking { ObsoleteDownloads.afterDownload(mangaId, idOf(mangaId, url)) }
    }

    private fun cbz(
        mangaId: Int,
        name: String,
        scanlator: String? = null,
    ) = File(runBlocking { getChapterCbzPath(mangaId, name, scanlator) })

    private fun folder(
        mangaId: Int,
        name: String,
        scanlator: String? = null,
    ) = File(runBlocking { getChapterDownloadPath(mangaId, name, scanlator) })

    private fun File.written() =
        apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

    private fun File.withPage() =
        apply {
            mkdirs()
            File(this, "001.png").writeBytes(byteArrayOf(1, 2, 3))
        }

    private fun series(case: String) = createLibraryManga("F16 $case ${System.nanoTime()}")

    fun newScanlatorReplacesTheOldCbzOnceItDownloads() {
        val m = series("scanlator cbz")
        sync(m, chapter("/c/1a", "Chapter 1", 1f, "Old Group"))
        markDownloaded(m, "/c/1a")
        val old = cbz(m, "Chapter 1", "Old Group").written()

        sync(m, chapter("/c/1b", "Chapter 1", 1f, "New Group"))
        assertTrue(old.exists(), "kept until the new group's chapter has downloaded")

        val new = cbz(m, "Chapter 1", "New Group").written()
        downloaded(m, "/c/1b")
        assertFalse(old.exists(), "replaced by the new group's download")
        assertTrue(new.exists())
    }

    fun newScanlatorReplacesTheOldFolderOnceItDownloads() {
        val m = series("scanlator folder")
        sync(m, chapter("/c/1a", "Chapter 1", 1f, "Old Group"))
        markDownloaded(m, "/c/1a")
        val old = folder(m, "Chapter 1", "Old Group").withPage()

        sync(m, chapter("/c/1b", "Chapter 1", 1f, "New Group"))
        assertTrue(old.exists(), "kept until the new group's chapter has downloaded")

        val new = folder(m, "Chapter 1", "New Group").withPage()
        downloaded(m, "/c/1b")
        assertFalse(old.exists(), "replaced by the new group's download")
        assertTrue(File(new, "001.png").exists())
    }

    fun anAlreadyDownloadedReplacementRemovesTheOldFileAtOnce() {
        val m = series("already replaced")
        sync(m, chapter("/c/1a", "Chapter 1", 1f, "Old Group"), chapter("/c/1b", "Chapter 1", 1f, "New Group"))
        markDownloaded(m, "/c/1a")
        markDownloaded(m, "/c/1b")
        val old = cbz(m, "Chapter 1", "Old Group").written()
        val new = cbz(m, "Chapter 1", "New Group").written()

        sync(m, chapter("/c/1b", "Chapter 1", 1f, "New Group"))

        assertFalse(old.exists(), "the source dropped it and chapter 1 is already downloaded from the new group")
        assertTrue(new.exists())
    }

    fun aRemovedChapterWithNothingReplacingItKeepsItsFile() {
        val m = series("removed")
        sync(m, chapter("/c/2", "Chapter 2", 2f), chapter("/c/1", "Chapter 1", 1f))
        markDownloaded(m, "/c/1")
        val one = cbz(m, "Chapter 1").written()

        sync(m, chapter("/c/2", "Chapter 2", 2f))
        cbz(m, "Chapter 2").written()
        downloaded(m, "/c/2")

        assertTrue(one.exists(), "no other chapter 1 was downloaded")
    }

    fun aRefusedRenameKeepsTheOldFileUntilTheChapterDownloadsAgain() {
        val m = series("refused rename")
        sync(m, chapter("/c/2", "Chapter 2", 2f))
        markDownloaded(m, "/c/2")
        val old = cbz(m, "Chapter 2").written()
        val taken = cbz(m, "Chapter 2 (fixed)").written() // the new name is already taken, so the rename is refused

        sync(m, chapter("/c/2", "Chapter 2 (fixed)", 2f))
        assertFalse(isDownloaded(m, "/c/2"), "upstream drops the download when the rename is refused")
        assertTrue(old.exists(), "kept until the chapter has downloaded again")

        downloaded(m, "/c/2") // the download finds the file at the new name
        assertFalse(old.exists(), "replaced by the new download")
        assertTrue(taken.exists())
    }

    fun aDownloadSharedWithAnotherChapterReplacesNothing() {
        val m = series("shared")
        sync(m, chapter("/c/3", "Chapter 3", 3f), chapter("/c/2", "Chapter 2", 2f))
        markDownloaded(m, "/c/3")
        markDownloaded(m, "/c/2")
        val three = cbz(m, "Chapter 3").written()
        val two = cbz(m, "Chapter 2").written()

        // the source labels chapter 2 with chapter 3's name; the rename is refused
        sync(m, chapter("/c/3", "Chapter 3", 3f), chapter("/c/2", "Chapter 3", 2f))
        downloaded(m, "/c/2") // the download finds chapter 3's file at its new name

        assertTrue(two.exists(), "the only copy of chapter 2's pages")
        assertTrue(three.exists())
    }

    fun aFileADownloadedChapterPointsToIsNeverDeleted() {
        val m = series("in use")
        sync(m, chapter("/c/1a", "Chapter 1", 1f, "Group"))
        markDownloaded(m, "/c/1a")
        val file = cbz(m, "Chapter 1", "Group").written()
        sync(m, chapter("/c/1b", "Chapter 1", 1f, "Other Group"))

        // the source goes back to the first group, under a new url; its download finds the old file
        sync(m, chapter("/c/1c", "Chapter 1", 1f, "Group"))
        downloaded(m, "/c/1c")

        assertTrue(file.exists(), "chapter 1 is downloaded to this file")
    }

    fun aChapterWithoutARecognisedNumberIsLeftAlone() {
        val m = series("no number")
        // different groups: upstream moves an unnumbered download to another unnumbered chapter from the same group
        sync(m, chapter("/c/x", "Extra", -1f, "Old Group"))
        markDownloaded(m, "/c/x")
        val extra = cbz(m, "Extra", "Old Group").written()

        sync(m, chapter("/c/y", "Bonus", -1f, "New Group"))
        cbz(m, "Bonus", "New Group").written()
        downloaded(m, "/c/y")

        assertTrue(extra.exists(), "an unnumbered chapter can't be matched to its replacement")
    }

    fun chaptersFromDifferentVolumesDontReplaceEachOther() {
        // numbering restarts each volume: both parse to chapter 1
        val m = series("volumes")
        sync(m, chapter("/c/v2c1", "Vol.2 Ch.1", 1f), chapter("/c/v1c1", "Vol.1 Ch.1", 1f))
        markDownloaded(m, "/c/v2c1")
        markDownloaded(m, "/c/v1c1")
        val volumeOne = cbz(m, "Vol.1 Ch.1").written()
        cbz(m, "Vol.2 Ch.1").written()

        sync(m, chapter("/c/v2c1", "Vol.2 Ch.1", 1f))

        assertTrue(volumeOne.exists(), "the only copy of volume 1's chapter 1")
    }

    fun episodesFromDifferentSeasonsDontReplaceEachOther() {
        val m = series("seasons")
        sync(m, chapter("/c/s2e1", "S2 Episode 1", 1f), chapter("/c/s1e1", "S1 Episode 1", 1f))
        markDownloaded(m, "/c/s2e1")
        markDownloaded(m, "/c/s1e1")
        val seasonOne = cbz(m, "S1 Episode 1").written()
        cbz(m, "S2 Episode 1").written()

        sync(m, chapter("/c/s2e1", "S2 Episode 1", 1f))

        assertTrue(seasonOne.exists(), "the only copy of season 1's episode 1")
    }

    fun theSameVolumesChapterFromAnotherGroupStillReplacesIt() {
        val m = series("same volume")
        sync(m, chapter("/c/a", "Vol.1 Ch.1", 1f, "Old Group"), chapter("/c/b", "Volume 1 Chapter 1", 1f, "New Group"))
        markDownloaded(m, "/c/a")
        markDownloaded(m, "/c/b")
        val old = cbz(m, "Vol.1 Ch.1", "Old Group").written()
        cbz(m, "Volume 1 Chapter 1", "New Group").written()

        sync(m, chapter("/c/b", "Volume 1 Chapter 1", 1f, "New Group"))

        assertFalse(old.exists(), "the same chapter, from another group")
    }

    fun volumesWrittenWithASpaceDontReplaceEachOther() {
        val m = series("volume spaces")
        sync(m, chapter("/c/v2c1", "Vol. 2 Ch. 1", 1f), chapter("/c/v1c1", "Vol. 1 Ch. 1", 1f))
        markDownloaded(m, "/c/v2c1")
        markDownloaded(m, "/c/v1c1")
        val volumeOne = cbz(m, "Vol. 1 Ch. 1").written()
        cbz(m, "Vol. 2 Ch. 1").written()

        sync(m, chapter("/c/v2c1", "Vol. 2 Ch. 1", 1f))

        assertTrue(volumeOne.exists(), "the only copy of volume 1's chapter 1")
    }

    fun namesGivingDifferentNumbersDontReplaceEachOther() {
        // an extension that numbers every chapter 0
        val m = series("numbered 0")
        sync(m, chapter("/c/2", "Chapter 2", 0f), chapter("/c/1", "Chapter 1", 0f))
        markDownloaded(m, "/c/2")
        markDownloaded(m, "/c/1")
        val one = cbz(m, "Chapter 1").written()
        cbz(m, "Chapter 2").written()

        sync(m, chapter("/c/2", "Chapter 2", 0f))

        assertTrue(one.exists(), "chapter 1, whatever number the source gave it")
    }

    fun aReplacementThatIsTheOldFileKeepsIt() {
        // on a case-insensitive filesystem "group_Chapter 1.cbz" is "Group_Chapter 1.cbz"; a link stands in for that here
        val m = series("same file")
        sync(m, chapter("/c/1a", "Chapter 1", 1f, "Group"))
        markDownloaded(m, "/c/1a")
        val file = cbz(m, "Chapter 1", "Group").written()
        sync(m, chapter("/c/1b", "Chapter 1", 1f, "group"))

        Files.createSymbolicLink(cbz(m, "Chapter 1", "group").toPath(), file.toPath())
        downloaded(m, "/c/1b")

        assertTrue(file.exists(), "chapter 1 is downloaded to this file")
    }

    fun aReplacementMissingOnDiskReplacesNothing() {
        val m = series("replacement missing")
        sync(m, chapter("/c/1a", "Chapter 1", 1f, "Old Group"))
        markDownloaded(m, "/c/1a")
        val old = cbz(m, "Chapter 1", "Old Group").written()
        sync(m, chapter("/c/1b", "Chapter 1", 1f, "New Group"))

        downloaded(m, "/c/1b") // flagged, but its file isn't there

        assertTrue(old.exists(), "chapter 1's only file")
    }

    fun anEmptyReplacementReplacesNothing() {
        val m = series("replacement empty")
        sync(m, chapter("/c/1a", "Chapter 1", 1f, "Old Group"))
        markDownloaded(m, "/c/1a")
        val old = cbz(m, "Chapter 1", "Old Group").written()
        sync(m, chapter("/c/1b", "Chapter 1", 1f, "New Group"))

        cbz(m, "Chapter 1", "New Group").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(0))
        }
        downloaded(m, "/c/1b")

        assertTrue(old.exists(), "chapter 1's only pages")
    }

    fun aFileAListedChapterIsNamedForIsKept() {
        val m = series("listed")
        sync(m, chapter("/c/1a", "Chapter 1", 1f, "Old Group"))
        markDownloaded(m, "/c/1a")
        val old = cbz(m, "Chapter 1", "Old Group").written()
        sync(m, chapter("/c/1b", "Chapter 1", 1f, "New Group"))
        // the old group's chapter is listed again, not downloaded yet
        sync(m, chapter("/c/1b", "Chapter 1", 1f, "New Group"), chapter("/c/1c", "Chapter 1", 1f, "Old Group"))

        cbz(m, "Chapter 1", "New Group").written()
        downloaded(m, "/c/1b")

        assertTrue(old.exists(), "/c/1c takes this file up once it's downloaded or opened")
    }

    fun aChapterRelabelledWithAnotherNumberIsntReplacedByItsOldNumber() {
        val m = series("renumbered")
        sync(m, chapter("/c/2", "Chapter 2", 2f))
        markDownloaded(m, "/c/2")
        val old = cbz(m, "Chapter 2").written()
        cbz(m, "Chapter 3").written() // the new name is taken, so the rename is refused

        // the source now calls /c/2 chapter 3, and a new url chapter 2
        sync(m, chapter("/c/9", "Ch. 2", 2f), chapter("/c/2", "Chapter 3", 3f))
        cbz(m, "Ch. 2").written()
        downloaded(m, "/c/9")

        assertTrue(old.exists(), "/c/2's pages, which the new chapter 2 doesn't replace")
    }

    fun aRelabelOntoAnotherVersionsNameKeepsItsPages() {
        val m = series("shared same number")
        sync(m, chapter("/c/2a", "Chapter 2", 2f), chapter("/c/2b", "Ch.2", 2f))
        markDownloaded(m, "/c/2a")
        markDownloaded(m, "/c/2b")
        val mine = cbz(m, "Chapter 2").written()
        cbz(m, "Ch.2").written()

        // the source labels /c/2a with /c/2b's name; the rename is refused
        sync(m, chapter("/c/2a", "Ch.2", 2f), chapter("/c/2b", "Ch.2", 2f))
        downloaded(m, "/c/2a") // the download finds /c/2b's file at its new name

        assertTrue(mine.exists(), "the only copy of /c/2a's pages")
    }

    fun aLinkedChapterFolderIsntFollowed() {
        val m = series("linked folder")
        sync(m, chapter("/c/1a", "Chapter 1", 1f, "Old Group"))
        markDownloaded(m, "/c/1a")
        val old = folder(m, "Chapter 1", "Old Group")
        val elsewhere = File(old.parentFile.parentFile, "F16 elsewhere ${System.nanoTime()}").withPage()
        old.parentFile.mkdirs()
        Files.createSymbolicLink(old.toPath(), elsewhere.toPath())
        sync(m, chapter("/c/1b", "Chapter 1", 1f, "New Group"))

        folder(m, "Chapter 1", "New Group").withPage()
        downloaded(m, "/c/1b")

        assertTrue(File(elsewhere, "001.png").exists(), "the linked folder's files aren't the chapter's to delete")
    }

    fun withoutItsTableSyncsAndDownloadsStillWork() {
        val m = series("no table")
        transaction { exec("DROP TABLE IF EXISTS fork_obsolete_download") }
        try {
            sync(m, chapter("/c/1a", "Chapter 1", 1f, "Old Group"))
            markDownloaded(m, "/c/1a")
            cbz(m, "Chapter 1", "Old Group").written()
            sync(m, chapter("/c/1b", "Chapter 1", 1f, "New Group"))
            cbz(m, "Chapter 1", "New Group").written()
            downloaded(m, "/c/1b")

            assertTrue(isDownloaded(m, "/c/1b"))
        } finally {
            ForkSchema.apply()
        }
    }
}
