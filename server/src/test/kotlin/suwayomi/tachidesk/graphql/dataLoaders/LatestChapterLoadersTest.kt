package suwayomi.tachidesk.graphql.dataLoaders

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.graphql.dataLoaders.ChapterLoaderFixture.MISSING
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LatestChapterLoadersTest : ApplicationTest() {
    private lateinit var ids: ChapterLoaderFixture.Ids
    private val keys get() = listOf(ids.a, ids.b, ids.c, MISSING)

    @BeforeAll
    fun fixture() {
        ids = ChapterLoaderFixture.create()
    }

    private fun sourceOrders(loader: com.expediagroup.graphql.dataloader.KotlinDataLoader<Int, ChapterType>) =
        ChapterLoaderFixture.load(loader, keys).map { it?.sourceOrder }

    @Test fun `latest uploaded`() = assertEquals(listOf(3, 1, null, null), sourceOrders(LatestUploadedChapterForMangaDataLoader()))

    @Test fun `latest fetched`() = assertEquals(listOf(4, 1, null, null), sourceOrders(LatestFetchedChapterForMangaDataLoader()))

    @Test fun `latest read`() = assertEquals(listOf(2, null, null, null), sourceOrders(LatestReadChapterForMangaDataLoader()))

    @Test fun `last read`() = assertEquals(listOf(2, 1, null, null), sourceOrders(LastReadChapterForMangaDataLoader()))

    @Test fun `first unread`() = assertEquals(listOf(3, 1, null, null), sourceOrders(FirstUnreadChapterForMangaDataLoader()))

    @Test fun `highest numbered`() = assertEquals(listOf(4, 1, null, null), sourceOrders(HighestNumberedChapterForMangaDataLoader()))

    @Test fun `chapter count`() = assertEquals(listOf(4, 1, 0, 0), ChapterLoaderFixture.load(ChapterCountForMangaDataLoader(), keys))

    @Test
    fun `ties go to the higher source order, then to the lowest id`() {
        val (d1, d2, _) = ids.dChapters
        val loaders =
            mapOf(
                "last read" to LastReadChapterForMangaDataLoader(),
                "highest numbered" to HighestNumberedChapterForMangaDataLoader(),
                "latest uploaded" to LatestUploadedChapterForMangaDataLoader(),
                "latest fetched" to LatestFetchedChapterForMangaDataLoader(),
                "first unread" to FirstUnreadChapterForMangaDataLoader(),
                "latest read" to LatestReadChapterForMangaDataLoader(),
            )
        assertEquals(
            mapOf(
                "last read" to d2,
                "highest numbered" to d2,
                "latest uploaded" to d2,
                "latest fetched" to d2,
                "first unread" to d1,
                "latest read" to null,
            ),
            loaders.mapValues { (_, loader) -> ChapterLoaderFixture.load(loader, listOf(ids.d)).single()?.id },
        )
    }
}
