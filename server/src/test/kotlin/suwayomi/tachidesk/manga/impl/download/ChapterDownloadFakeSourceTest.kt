package suwayomi.tachidesk.manga.impl.download

import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.ChapterDownloadHelper
import suwayomi.tachidesk.manga.impl.Page
import suwayomi.tachidesk.manga.impl.download.fileProvider.ForkDownloadSettings
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.manga.impl.util.getChapterCachePath
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.impl.util.source.GetSource.registerSource
import suwayomi.tachidesk.manga.impl.util.source.GetSource.unregisterSource
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** F13 end to end: a whole chapter downloaded through ChapterDownloadHelper from a source that serves numbered PNGs. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterDownloadFakeSourceTest : ApplicationTest() {
    private class NumberedPagesSource(
        override val id: Long,
    ) : HttpSource() {
        override val name = "numbered pages"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "https://pages.invalid"

        val requestedPages: MutableList<Int> = Collections.synchronizedList(mutableListOf())

        override val client: OkHttpClient =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val page =
                        chain
                            .request()
                            .url.pathSegments
                            .last()
                            .toInt()
                    requestedPages += page
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "image/png")
                        .body(pageBytes(page).toResponseBody("image/png".toMediaType()))
                        .build()
                }.build()
    }

    private val sourceId = 7_000_013L
    private val source = NumberedPagesSource(sourceId)
    private val pageCount = 5
    private var previousCbz = false

    @BeforeAll
    fun setUp() {
        registerSource(sourceId to source)
        previousCbz = serverConfig.downloadAsCbz.value
        System.setProperty(ForkDownloadSettings.PARALLEL_PAGES_PROPERTY, "3")
    }

    @AfterAll
    fun tearDown() {
        unregisterSource(sourceId)
        serverConfig.downloadAsCbz.value = previousCbz
        System.clearProperty(ForkDownloadSettings.PARALLEL_PAGES_PROPERTY)
    }

    private data class Chapter(
        val mangaId: Int,
        val chapterId: Int,
    )

    private fun chapterWithPages(): Chapter {
        val mangaId = createLibraryManga("F13 fake source ${System.nanoTime()}")
        createChapters(mangaId, 1, read = false)
        val chapterId =
            transaction {
                MangaTable.update({ MangaTable.id eq mangaId }) { it[sourceReference] = sourceId }
                ChapterTable.update({ ChapterTable.manga eq mangaId }) { it[ChapterTable.pageCount] = pageCount }
                val chapterId =
                    ChapterTable
                        .select(ChapterTable.id)
                        .where { ChapterTable.manga eq mangaId }
                        .first()[ChapterTable.id]
                        .value
                PageTable.batchInsert(0 until pageCount) { i ->
                    this[PageTable.index] = i
                    this[PageTable.url] = "/p/$i"
                    this[PageTable.imageUrl] = "https://pages.invalid/p/$i"
                    this[PageTable.chapter] = chapterId
                }
                chapterId
            }
        return Chapter(mangaId, chapterId)
    }

    private fun download(c: Chapter): List<Float> {
        val progress = Collections.synchronizedList(mutableListOf<Float>())
        val item = DownloadQueueItem(c.chapterId, 1, c.mangaId, sourceId, pageCount)
        runBlocking {
            ChapterDownloadHelper.download(c.mangaId, c.chapterId, item, this) { d, _ -> progress += d!!.progress }
        }
        return progress.toList()
    }

    private fun pagesAsRead(c: Chapter): List<ByteArray> =
        runBlocking {
            (0 until pageCount).map { i -> ChapterDownloadHelper.getImage(c.mangaId, c.chapterId, i).first.use { it.readBytes() } }
        }

    private fun downloadsInOrderWithPageNames(
        asCbz: Boolean,
        storedNames: (Chapter) -> List<String>,
    ) {
        serverConfig.downloadAsCbz.value = asCbz
        val c = chapterWithPages()
        source.requestedPages.clear()

        val progress = download(c)

        assertEquals((0 until pageCount).toList(), source.requestedPages.sorted())
        assertEquals((1..pageCount).map { it.toFloat() / pageCount }, progress)
        assertEquals((1..pageCount).map { "%03d.png".format(it) }, storedNames(c))
        pagesAsRead(c).forEachIndexed { i, bytes -> assertContentEquals(pageBytes(i), bytes, "page ${i + 1}") }
    }

    @Test
    fun `a chapter downloads to a folder in page order with page names`() =
        downloadsInOrderWithPageNames(asCbz = false) { c ->
            val folder = runBlocking { getChapterDownloadPath(c.mangaId, c.chapterId) }
            File(folder)
                .list()
                .orEmpty()
                .filter { it.endsWith(".png") }
                .sorted()
        }

    @Test
    fun `a chapter downloads to a cbz in page order with page names`() =
        downloadsInOrderWithPageNames(asCbz = true) { c ->
            val cbz = runBlocking { getChapterCbzPath(c.mangaId, c.chapterId) }
            ZipFile(cbz).use { zip ->
                zip
                    .entries()
                    .toList()
                    .map { it.name }
                    .filter { it.endsWith(".png") }
                    .sorted()
            }
        }

    @Test
    fun `a resumed download keeps finished pages and fetches only the missing ones`() {
        serverConfig.downloadAsCbz.value = true
        val c = chapterWithPages()
        val cacheDir = File(runBlocking { getChapterCachePath(c.mangaId, c.chapterId) }).apply { mkdirs() }
        val keptPage = pageBytes(1) + byteArrayOf(0x4B) // page 2 from an earlier, interrupted run
        File(cacheDir, Page.getPageName(1, pageCount) + ".png").writeBytes(keptPage)
        // page 4 was cut off mid-write; ImageResponse.saveImage names it "<page>.tmp"
        File(cacheDir, Page.getPageName(3, pageCount) + ".tmp").writeBytes(byteArrayOf(1))
        source.requestedPages.clear()

        val progress = download(c)

        assertEquals(listOf(0, 2, 3, 4), source.requestedPages.sorted())
        assertEquals((2..pageCount).map { it.toFloat() / pageCount }, progress)
        val pages = pagesAsRead(c)
        assertContentEquals(keptPage, pages[1])
        listOf(0, 2, 3, 4).forEach { i -> assertContentEquals(pageBytes(i), pages[i], "page ${i + 1}") }
    }

    private companion object {
        private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        fun pageBytes(page: Int): ByteArray = pngSignature + "page $page".toByteArray()
    }
}
