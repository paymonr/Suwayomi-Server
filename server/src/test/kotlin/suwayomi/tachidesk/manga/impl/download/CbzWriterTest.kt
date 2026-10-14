package suwayomi.tachidesk.manga.impl.download

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.ChapterDownloadHelper
import suwayomi.tachidesk.manga.impl.download.fileProvider.impl.writeCbz
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CbzWriterTest : ApplicationTest() {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 4, 5)

    private fun pagesFolder(): File =
        createTempDirectory("f11-pages").toFile().apply {
            File(this, "001.jpg").writeBytes(jpeg)
            File(this, "002.png").writeBytes(png)
            File(this, "ComicInfo.xml").writeText("<ComicInfo><Title>t</Title></ComicInfo>")
        }

    private data class Downloaded(
        val mangaId: Int,
        val chapterId: Int,
        val cbz: File,
    )

    private fun chapterCbz(title: String): Downloaded {
        val mangaId = createLibraryManga("$title ${System.nanoTime()}")
        createChapters(mangaId, 1, read = false)
        val chapterId =
            transaction {
                ChapterTable
                    .select(ChapterTable.id)
                    .where { ChapterTable.manga eq mangaId }
                    .first()[ChapterTable.id]
                    .value
            }
        val cbz = File(runBlocking { getChapterCbzPath(mangaId, chapterId) }).apply { parentFile.mkdirs() }
        return Downloaded(mangaId, chapterId, cbz)
    }

    private fun Downloaded.page(index: Int): ByteArray =
        runBlocking { ChapterDownloadHelper.getImage(mangaId, chapterId, index).first.use { it.readBytes() } }

    @Test
    fun `images are stored uncompressed and ComicInfo is deflated`() {
        val cbz = File(createTempDirectory("f11-cbz").toFile(), "chapter.cbz")
        writeCbz(pagesFolder(), cbz)
        val methods = ZipFile(cbz).use { zip -> zip.entries().toList().associate { it.name to it.method } }
        assertEquals(
            mapOf("001.jpg" to ZipEntry.STORED, "002.png" to ZipEntry.STORED, "ComicInfo.xml" to ZipEntry.DEFLATED),
            methods,
        )
    }

    /** Characterization for Review Focus 5: passes before and after the change. */
    @Test
    fun `pages read the same from an older deflated cbz and a new stored one`() {
        val folder = pagesFolder()
        val old = chapterCbz("F11 old")
        ZipOutputStream(old.cbz.outputStream()).use { zip ->
            folder.listFiles()!!.sortedBy { it.name }.forEach {
                zip.putNextEntry(ZipEntry(it.name))
                zip.write(it.readBytes())
                zip.closeEntry()
            }
        }
        val new = chapterCbz("F11 new")
        writeCbz(folder, new.cbz)

        assertEquals(2, runBlocking { ChapterDownloadHelper.getImageCount(old.mangaId, old.chapterId) })
        assertEquals(2, runBlocking { ChapterDownloadHelper.getImageCount(new.mangaId, new.chapterId) })
        (0 until 2).forEach { i -> assertContentEquals(old.page(i), new.page(i), "page ${i + 1}") }
    }
}
