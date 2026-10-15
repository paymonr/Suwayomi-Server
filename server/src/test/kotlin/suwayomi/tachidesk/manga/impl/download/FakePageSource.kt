package suwayomi.tachidesk.manga.impl.download

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
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
import suwayomi.tachidesk.manga.impl.ChapterDownloadHelper
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A source that serves numbered PNGs from memory, for download tests.
 * [statusFor] picks each image request's HTTP status from the page index and its attempt number (1-based).
 */
class FakePageSource(
    override val id: Long,
    private val pageCount: Int,
    private val statusFor: (page: Int, attempt: Int) -> Int = { _, _ -> 200 },
) : HttpSource() {
    override val name = "fake pages"
    override val lang = "en"
    override val supportsLatest = false
    override val baseUrl = "https://pages.invalid"

    val requestedPages: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val pageListRequests = AtomicInteger()
    private val attempts = ConcurrentHashMap<Int, AtomicInteger>()

    fun attemptsFor(page: Int): Int = attempts[page]?.get() ?: 0

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        pageListRequests.incrementAndGet()
        return (0 until pageCount).map { Page(it, "/p/$it", "$baseUrl/p/$it") }
    }

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
                val status = statusFor(page, attempts.getOrPut(page) { AtomicInteger() }.incrementAndGet())
                val body = if (status == 200) pageBytes(page) else ByteArray(0)
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message(if (status == 200) "OK" else "Error")
                    .header("Content-Type", "image/png")
                    .body(body.toResponseBody("image/png".toMediaType()))
                    .build()
            }.build()

    companion object {
        private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        fun pageBytes(page: Int): ByteArray = pngSignature + "page $page".toByteArray()
    }
}

data class FakeChapter(
    val mangaId: Int,
    val chapterId: Int,
)

fun insertPageRows(
    chapterId: Int,
    pageCount: Int,
) {
    transaction {
        PageTable.batchInsert(0 until pageCount) { i ->
            this[PageTable.index] = i
            this[PageTable.url] = "/p/$i"
            this[PageTable.imageUrl] = "https://pages.invalid/p/$i"
            this[PageTable.chapter] = chapterId
        }
    }
}

/** A library series on [sourceId] with one chapter whose [pageCount] page rows were already fetched. */
fun createChapterWithPages(
    sourceId: Long,
    pageCount: Int,
    title: String,
): FakeChapter {
    val mangaId = createLibraryManga("$title ${System.nanoTime()}")
    createChapters(mangaId, 1, read = false)
    val chapterId =
        transaction {
            MangaTable.update({ MangaTable.id eq mangaId }) { it[sourceReference] = sourceId }
            ChapterTable.update({ ChapterTable.manga eq mangaId }) { it[ChapterTable.pageCount] = pageCount }
            ChapterTable
                .select(ChapterTable.id)
                .where { ChapterTable.manga eq mangaId }
                .first()[ChapterTable.id]
                .value
        }
    insertPageRows(chapterId, pageCount)
    return FakeChapter(mangaId, chapterId)
}

/** Downloads [c] the way the downloader does and returns every progress value it reported. */
fun downloadChapter(
    c: FakeChapter,
    sourceId: Long,
    pageCount: Int,
): List<Float> {
    val progress = Collections.synchronizedList(mutableListOf<Float>())
    val item = DownloadQueueItem(c.chapterId, 1, c.mangaId, sourceId, pageCount)
    runBlocking {
        ChapterDownloadHelper.download(c.mangaId, c.chapterId, item, this) { d, _ -> progress += d!!.progress }
    }
    return progress.toList()
}

fun pageAsRead(
    c: FakeChapter,
    index: Int,
): ByteArray = runBlocking { ChapterDownloadHelper.getImage(c.mangaId, c.chapterId, index).first.use { it.readBytes() } }
