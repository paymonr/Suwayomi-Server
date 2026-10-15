package suwayomi.tachidesk.graphql.types

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.expediagroup.graphql.dataloader.KotlinDataLoaderRegistryFactory
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.GraphQLContext
import kotlinx.coroutines.runBlocking
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.graphql.dataLoaders.ChapterCountForMangaDataLoader
import suwayomi.tachidesk.graphql.server.GraphQLSchemaProvider
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F2 through real GraphQL execution: a selection of only totalCount (however it is spelled) must not load
 * the series' chapter list. The full-list loader is swapped for one that always fails, so using it is an error.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MangaChaptersCountQueryTest : ApplicationTest() {
    private class FullListLoaderMustNotRun : KotlinDataLoader<Int, ChapterNodeList> {
        override val dataLoaderName = "ChaptersForMangaDataLoader"

        override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterNodeList> =
            DataLoaderFactory.newDataLoader { _ -> CompletableFuture.failedFuture(IllegalStateException("full chapter list loaded")) }
    }

    private lateinit var graphQL: GraphQL
    private var mangaId = 0

    @BeforeAll
    fun setUp() {
        graphQL = GraphQL.newGraphQL(runBlocking { GraphQLSchemaProvider.getSchema() }).build()
        mangaId = createLibraryManga("count query ${System.nanoTime()}")
        createChapters(mangaId, 7, read = false)
    }

    private fun execute(chaptersSelection: String): Pair<Any?, List<String>> {
        val context = GraphQLContext.newContext().build()
        val registry = KotlinDataLoaderRegistryFactory(ChapterCountForMangaDataLoader(), FullListLoaderMustNotRun()).generate(context)
        val result =
            graphQL.execute(
                ExecutionInput
                    .newExecutionInput()
                    .query("{ mangas(condition: { id: $mangaId }) { nodes { chapters $chaptersSelection } } }")
                    // what JavalinSetup puts there for a signed-in request; @RequireAuth reads it
                    .graphQLContext(mapOf<Any, Any>(JavalinSetup.Attribute.TachideskUser to UserType.Admin(1)))
                    .dataLoaderRegistry(registry)
                    .build(),
            )

        @Suppress("UNCHECKED_CAST")
        val chapters =
            (((result.getData<Map<String, Any?>>()?.get("mangas") as? Map<String, Any?>)?.get("nodes") as? List<Map<String, Any?>>))
                ?.single()
                ?.get("chapters")
        return chapters to result.errors.map { it.message }
    }

    private fun assertCountOnly(chaptersSelection: String) {
        val (chapters, errors) = execute(chaptersSelection)
        assertEquals(emptyList(), errors)
        @Suppress("UNCHECKED_CAST")
        assertEquals(7, (chapters as Map<String, Any?>)["totalCount"])
    }

    @Test
    fun `totalCount alone`() = assertCountOnly("{ totalCount }")

    @Test
    fun `totalCount with __typename`() = assertCountOnly("{ __typename totalCount }")

    @Test
    fun `totalCount in an inline fragment`() = assertCountOnly("{ ... on ChapterNodeList { totalCount } }")

    @Test
    fun `totalCount behind an alias`() = assertCountOnly("{ count: totalCount totalCount }")

    @Test
    fun `asking for nodes uses the full list (control)`() {
        val (_, errors) = execute("{ totalCount nodes { id } }")
        assertTrue(errors.any { "full chapter list loaded" in it }, "errors: $errors")
    }
}
