package suwayomi.tachidesk.graphql.mutations

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.dataAt
import suwayomi.tachidesk.graphql.executeGraphQL
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForkChapterMutationTest : ApplicationTest() {
    private fun chapters(count: Int): List<Int> {
        val mangaId = createLibraryManga("E3 ${System.nanoTime()}")
        createChapters(mangaId, count, read = false)
        return transaction {
            ChapterTable
                .select(ChapterTable.id)
                .where { ChapterTable.manga eq mangaId }
                .map { it[ChapterTable.id].value }
                .sorted()
        }
    }

    private fun flags(id: Int): Pair<Boolean, Int> =
        transaction {
            ChapterTable.selectAll().where { ChapterTable.id eq id }.first().let {
                it[ChapterTable.isDownloaded] to
                    it[ChapterTable.pageCount]
            }
        }

    private fun set(
        ids: List<Int>,
        isDownloaded: Boolean,
        pageCount: Int? = null,
    ) = executeGraphQL(
        "mutation { setChaptersDownloadState(input: { ids: [${ids.joinToString()}], isDownloaded: $isDownloaded" +
            (pageCount?.let { ", pageCount: $it" } ?: "") + " }) { chapters { id isDownloaded pageCount } } }",
    )

    @Test
    fun `marks chapters downloaded with their page count`() {
        val ids = chapters(2)
        val result = set(ids, true, 18)
        assertEquals(emptyList(), result.errors)
        assertEquals(
            ids.map { mapOf("id" to it, "isDownloaded" to true, "pageCount" to 18) },
            result.dataAt("setChaptersDownloadState", "chapters"),
        )
        assertEquals(ids.map { true to 18 }, ids.map(::flags))
    }

    @Test
    fun `clearing the flag leaves the page count alone`() {
        val (id) = chapters(1)
        set(listOf(id), true, 18)
        assertEquals(emptyList(), set(listOf(id), false).errors)
        assertEquals(false to 18, flags(id))
    }

    @Test
    fun `an unknown id changes nothing`() {
        val (id) = chapters(1)
        val result = set(listOf(id, 999_999_999), true, 5)
        assertTrue(result.errors.any { "999999999" in it.message }, result.errors.toString())
        assertEquals(false to -1, flags(id))
    }

    @Test
    fun `marking downloaded without a page count is refused`() {
        val (id) = chapters(1)
        val result = set(listOf(id), true)
        assertTrue(result.errors.any { "pageCount" in it.message }, result.errors.toString())
        assertEquals(false to -1, flags(id))
    }
}
