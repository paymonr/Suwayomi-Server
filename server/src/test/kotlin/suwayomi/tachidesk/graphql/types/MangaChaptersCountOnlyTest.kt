package suwayomi.tachidesk.graphql.types

import graphql.schema.DataFetchingEnvironment
import graphql.schema.SelectedField
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.dataloader.DataLoader
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.createLibraryManga
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals

class MangaChaptersCountOnlyTest : ApplicationTest() {
    private fun manga(): MangaType {
        val id = createLibraryManga("count-only ${System.nanoTime()}")
        return transaction { MangaType(MangaTable.selectAll().where { MangaTable.id eq id }.first()) }
    }

    private fun env(vararg fields: String): DataFetchingEnvironment =
        mockk(relaxed = true) {
            every { selectionSet.immediateFields } returns fields.map { f -> mockk<SelectedField> { every { name } returns f } }
        }

    @Test
    fun `totalCount alone is answered by the count loader`() {
        val manga = manga()
        val env = env("totalCount")
        val countLoader = mockk<DataLoader<Int, Int>> { every { load(manga.id) } returns CompletableFuture.completedFuture(42) }
        every { env.getDataLoader<Int, Int>("ChapterCountForMangaDataLoader") } returns countLoader

        val result = manga.chapters(env).join()

        assertEquals(42, result.totalCount)
        assertEquals(emptyList(), result.nodes)
        verify(exactly = 0) { env.getDataLoader<Int, ChapterNodeList>("ChaptersForMangaDataLoader") }
    }

    @Test
    fun `nodes still come from the full loader`() {
        val manga = manga()
        val env = env("nodes", "totalCount")
        val full = ChapterNodeList.countOnly(0)
        val fullLoader =
            mockk<DataLoader<Int, ChapterNodeList>> { every { load(manga.id) } returns CompletableFuture.completedFuture(full) }
        every { env.getDataLoader<Int, ChapterNodeList>("ChaptersForMangaDataLoader") } returns fullLoader

        assertEquals(full, manga.chapters(env).join())
        verify(exactly = 0) { env.getDataLoader<Int, Int>("ChapterCountForMangaDataLoader") }
    }
}
