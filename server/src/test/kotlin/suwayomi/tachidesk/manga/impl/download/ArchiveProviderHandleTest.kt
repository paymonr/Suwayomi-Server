package suwayomi.tachidesk.manga.impl.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.ChapterDownloadHelper
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ArchiveProviderHandleTest : ApplicationTest() {
    private data class DownloadedChapter(
        val mangaId: Int,
        val chapterId: Int,
        val cbz: File,
    )

    private fun openHandlesTo(file: File): Int {
        val target = file.canonicalPath
        return File("/proc/self/fd").listFiles().orEmpty().count { fd ->
            runCatching { Files.readSymbolicLink(fd.toPath()).toString() == target }.getOrDefault(false)
        }
    }

    private fun downloadedChapter(pages: Int): DownloadedChapter =
        runBlocking {
            val mangaId = createLibraryManga("F14 ${System.nanoTime()}")
            createChapters(mangaId, 1, read = false)
            val chapterId =
                transaction {
                    ChapterTable
                        .select(ChapterTable.id)
                        .where { ChapterTable.manga eq mangaId }
                        .first()[ChapterTable.id]
                        .value
                }
            val cbz = File(getChapterCbzPath(mangaId, chapterId))
            cbz.parentFile.mkdirs()
            ZipOutputStream(cbz.outputStream()).use { zip ->
                repeat(pages) { i ->
                    zip.putNextEntry(ZipEntry("%03d.jpg".format(i + 1)))
                    zip.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), i.toByte()))
                    zip.closeEntry()
                }
            }
            DownloadedChapter(mangaId, chapterId, cbz)
        }

    @Test
    fun `reading every page leaves no archive open`() {
        assumeTrue(File("/proc/self/fd").isDirectory)
        val c = downloadedChapter(pages = 5)
        runBlocking {
            repeat(5) { index -> ChapterDownloadHelper.getImage(c.mangaId, c.chapterId, index).first.use { it.readBytes() } }
            assertEquals(5, ChapterDownloadHelper.getImageCount(c.mangaId, c.chapterId))
        }
        assertEquals(0, openHandlesTo(c.cbz))
    }

    @Test
    fun `concurrent page reads leave no archive open`() {
        assumeTrue(File("/proc/self/fd").isDirectory)
        val c = downloadedChapter(pages = 8)
        runBlocking {
            (0 until 8)
                .map { index ->
                    async(Dispatchers.IO) { ChapterDownloadHelper.getImage(c.mangaId, c.chapterId, index).first.use { it.readBytes() } }
                }.awaitAll()
        }
        assertEquals(0, openHandlesTo(c.cbz))
    }

    @Test
    fun `a corrupt archive fails without leaking a handle`() {
        assumeTrue(File("/proc/self/fd").isDirectory)
        val c = downloadedChapter(pages = 1)
        c.cbz.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertFails { runBlocking { ChapterDownloadHelper.getImage(c.mangaId, c.chapterId, 0) } }
        assertEquals(0, openHandlesTo(c.cbz))
    }
}
