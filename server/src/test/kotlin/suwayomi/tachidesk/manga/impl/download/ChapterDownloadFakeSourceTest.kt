package suwayomi.tachidesk.manga.impl.download

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.Page
import suwayomi.tachidesk.manga.impl.download.fileProvider.ForkDownloadSettings
import suwayomi.tachidesk.manga.impl.util.getChapterCachePath
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.impl.util.source.GetSource.registerSource
import suwayomi.tachidesk.manga.impl.util.source.GetSource.unregisterSource
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** F13 end to end: a whole chapter downloaded through ChapterDownloadHelper from a source that serves numbered PNGs. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterDownloadFakeSourceTest : ApplicationTest() {
    private val sourceId = 7_000_013L
    private val pageCount = 5
    private val source = FakePageSource(sourceId, pageCount)
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

    private fun downloadsInOrderWithPageNames(
        asCbz: Boolean,
        storedNames: (FakeChapter) -> List<String>,
    ) {
        serverConfig.downloadAsCbz.value = asCbz
        val c = createChapterWithPages(sourceId, pageCount, "F13 fake source")
        source.requestedPages.clear()

        val progress = downloadChapter(c, sourceId, pageCount)

        assertEquals((0 until pageCount).toList(), source.requestedPages.sorted())
        assertEquals((1..pageCount).map { it.toFloat() / pageCount }, progress)
        assertEquals((1..pageCount).map { "%03d.png".format(it) }, storedNames(c))
        (0 until pageCount).map { pageAsRead(c, it) }.forEachIndexed {
            i,
            bytes,
            ->
            assertContentEquals(FakePageSource.pageBytes(i), bytes, "page ${i + 1}")
        }
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
        val c = createChapterWithPages(sourceId, pageCount, "F13 fake source")
        val cacheDir = File(runBlocking { getChapterCachePath(c.mangaId, c.chapterId) }).apply { mkdirs() }
        val keptPage = FakePageSource.pageBytes(1) + byteArrayOf(0x4B) // page 2 from an earlier, interrupted run
        File(cacheDir, Page.getPageName(1, pageCount) + ".png").writeBytes(keptPage)
        // page 4 was cut off mid-write; ImageResponse.saveImage names it "<page>.tmp"
        File(cacheDir, Page.getPageName(3, pageCount) + ".tmp").writeBytes(byteArrayOf(1))
        source.requestedPages.clear()

        val progress = downloadChapter(c, sourceId, pageCount)

        assertEquals(listOf(0, 2, 3, 4), source.requestedPages.sorted())
        assertEquals((2..pageCount).map { it.toFloat() / pageCount }, progress)
        val pages = (0 until pageCount).map { pageAsRead(c, it) }
        assertContentEquals(keptPage, pages[1])
        listOf(0, 2, 3, 4).forEach { i -> assertContentEquals(FakePageSource.pageBytes(i), pages[i], "page ${i + 1}") }
    }
}
