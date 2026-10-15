package suwayomi.tachidesk.graphql.mutations

import graphql.ExecutionInput
import graphql.GraphQL
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.server.GraphQLSchemaProvider
import suwayomi.tachidesk.graphql.server.TachideskDataLoaderRegistryFactory
import suwayomi.tachidesk.graphql.server.toGraphQLContext
import suwayomi.tachidesk.manga.impl.fork.NonLibraryMangaPrune
import suwayomi.tachidesk.manga.impl.fork.PruneFixture
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.assertEquals

class ForkMaintenanceMutationTest : ApplicationTest() {
    private fun execute(query: String): Map<String, Any?> {
        val context = mapOf<Any, Any>(JavalinSetup.Attribute.TachideskUser to UserType.Admin(1))
        val graphQL = GraphQL.newGraphQL(runBlocking { GraphQLSchemaProvider.getSchema() }).build()
        val input =
            ExecutionInput
                .newExecutionInput()
                .query(query)
                .graphQLContext(context)
                .dataLoaderRegistry(TachideskDataLoaderRegistryFactory.create().generate(context.toGraphQLContext()))
                .build()
        val result = graphQL.execute(input)
        assertEquals(emptyList(), result.errors)
        return result.getData()
    }

    @Test
    fun `the mutation dry-runs by default and reports the service's counts`() {
        val ids = PruneFixture.create()
        val before = PruneFixture.allMangaIds()
        val expected =
            NonLibraryMangaPrune.prune(
                setOf(ids.excluded),
                dryRun = true,
                queuedMangaIds = { emptySet() },
                coverDirs = emptyList(),
            )

        val data =
            execute("""mutation { pruneNonLibraryManga(input: {excludeMangaIds: [${ids.excluded}]}) { dryRun mangaCount chapterCount } }""")

        @Suppress("UNCHECKED_CAST")
        val payload = data["pruneNonLibraryManga"] as Map<String, Any?>
        assertEquals(
            mapOf("dryRun" to true, "mangaCount" to expected.mangaIds.size, "chapterCount" to expected.chapterCount.toInt()),
            payload,
        )
        assertEquals(before, PruneFixture.allMangaIds())
    }

    @Test
    fun `dryRun false deletes`() {
        val ids = PruneFixture.create()
        val fixtureIds = ids.prunable + ids.kept.values + ids.excluded + ids.queued
        val exclude = PruneFixture.allMangaIds() - fixtureIds.toSet() + ids.excluded + ids.queued

        execute(
            """mutation { pruneNonLibraryManga(input: {excludeMangaIds: [${exclude.joinToString()}], dryRun: false}) { mangaCount } }""",
        )

        assertEquals(emptySet(), PruneFixture.existing(ids.prunable))
    }

    /** A series that was opened again without a refresh keeps its old fetch times; opening it must still protect it. */
    @Test
    fun `a series opened through the manga query in the last day survives a real run`() {
        val ids = PruneFixture.create()
        val (opened, other) = ids.prunable
        val fixtureIds = ids.prunable + ids.kept.values + ids.excluded + ids.queued
        val exclude = PruneFixture.allMangaIds() - fixtureIds.toSet() + ids.excluded + ids.queued

        execute("{ manga(id: $opened) { id } }")
        execute(
            """mutation { pruneNonLibraryManga(input: {excludeMangaIds: [${exclude.joinToString()}], dryRun: false}) { mangaCount } }""",
        )

        assertEquals(setOf(opened), PruneFixture.existing(listOf(opened, other)))
    }
}
