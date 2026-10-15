package suwayomi.tachidesk.graphql.dataLoaders

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import graphql.GraphQLContext
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.test.createLibraryManga

/**
 * Series A: 4 chapters with ties on upload date and fetch time; B: 1 unread chapter; C: no chapters;
 * MISSING: an id with no series. Expected values below are today's loader results.
 * Series D ([Ids.dChapters], in insertion order): 3 never-read chapters equal in everything but source order
 * (1, 2, 2), so every loader has to break a tie.
 */
object ChapterLoaderFixture {
    const val MISSING = 999_999

    data class Ids(
        val a: Int,
        val b: Int,
        val c: Int,
        val d: Int,
        val dChapters: List<Int>,
    )

    fun create(): Ids {
        val a = createLibraryManga("loader A ${System.nanoTime()}")
        val b = createLibraryManga("loader B ${System.nanoTime()}")
        val c = createLibraryManga("loader C ${System.nanoTime()}")
        val d = createLibraryManga("loader D ${System.nanoTime()}")
        // sourceOrder, dateUpload, fetchedAt, read, lastReadAt, number
        val rows =
            listOf(
                Triple(a, 1, listOf(100L, 10L, 1L, 50L)) to 1f,
                Triple(a, 2, listOf(300L, 20L, 1L, 70L)) to 2f,
                Triple(a, 3, listOf(300L, 20L, 0L, 0L)) to 3f,
                Triple(a, 4, listOf(200L, 20L, 0L, 0L)) to 4.5f,
                Triple(b, 1, listOf(100L, 10L, 0L, 0L)) to 1f,
                Triple(d, 1, listOf(100L, 10L, 0L, 0L)) to 5f,
                Triple(d, 2, listOf(100L, 10L, 0L, 0L)) to 5f,
                Triple(d, 2, listOf(100L, 10L, 0L, 0L)) to 5f,
            )
        val chapterIds =
            transaction {
                rows.mapIndexed { n, (r, number) ->
                    val (mangaId, sourceOrder, v) = r
                    val id =
                        ChapterTable.insertAndGetId {
                            it[url] = "$mangaId-$sourceOrder-$n"
                            it[name] = "Chapter $sourceOrder"
                            it[manga] = mangaId
                            it[ChapterTable.sourceOrder] = sourceOrder
                            it[date_upload] = v[0]
                            it[fetchedAt] = v[1]
                            it[isRead] = v[2] == 1L
                            it[lastReadAt] = v[3]
                            it[chapter_number] = number
                            it[memo] = JsonObject(emptyMap())
                        }
                    mangaId to id.value
                }
            }
        return Ids(a, b, c, d, chapterIds.filter { it.first == d }.map { it.second })
    }

    fun <V : Any> load(
        loader: KotlinDataLoader<Int, V>,
        ids: List<Int>,
    ): List<V?> {
        val dataLoader = loader.getDataLoader(GraphQLContext.newContext().build())
        val result = dataLoader.loadMany(ids)
        dataLoader.dispatch()
        return result.join()
    }
}
