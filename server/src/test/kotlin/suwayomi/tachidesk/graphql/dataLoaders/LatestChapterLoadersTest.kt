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
}
