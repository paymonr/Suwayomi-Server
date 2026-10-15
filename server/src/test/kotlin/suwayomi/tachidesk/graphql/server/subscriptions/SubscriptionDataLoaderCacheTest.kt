package suwayomi.tachidesk.graphql.server.subscriptions

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.expediagroup.graphql.dataloader.KotlinDataLoaderRegistryFactory
import com.expediagroup.graphql.generator.SchemaGeneratorConfig
import com.expediagroup.graphql.generator.TopLevelObject
import com.expediagroup.graphql.generator.execution.FlowSubscriptionExecutionStrategy
import com.expediagroup.graphql.generator.hooks.FlowSubscriptionSchemaGeneratorHooks
import com.expediagroup.graphql.generator.toSchema
import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import com.expediagroup.graphql.server.types.GraphQLRequest
import graphql.GraphQL
import graphql.GraphQLContext
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

object SubscriptionTestStore {
    val values = ConcurrentHashMap<Int, String>()
    val failing: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    val loaders: MutableList<DataLoader<Int, String>> = Collections.synchronizedList(mutableListOf())
    var events: Flow<Int> = emptyFlow()
}

class SubscriptionTestValueLoader : KotlinDataLoader<Int, String> {
    override val dataLoaderName = "SubscriptionTestValueLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, String> =
        DataLoaderFactory
            .newDataLoader<Int, String> { ids ->
                if (ids.any { it in SubscriptionTestStore.failing }) {
                    CompletableFuture.failedFuture(IllegalStateException("load failed"))
                } else {
                    CompletableFuture.completedFuture(ids.map { SubscriptionTestStore.values.getValue(it) })
                }
            }.also { SubscriptionTestStore.loaders += it }
}

class SubscriptionTestItem(
    val id: Int,
) {
    // nullable, so a failed load is a field error inside the event instead of ending the subscription
    fun value(env: DataFetchingEnvironment): CompletableFuture<String?> =
        env.getValueFromDataLoader<Int, String?>("SubscriptionTestValueLoader", id)
}

class SubscriptionTestQuery {
    fun ping(): String = "pong"
}

class SubscriptionTestSubscription {
    fun items(): Flow<SubscriptionTestItem> = SubscriptionTestStore.events.map { SubscriptionTestItem(it) }
}

class SubscriptionDataLoaderCacheTest {
    private val graphQL =
        GraphQL
            .newGraphQL(
                toSchema(
                    SchemaGeneratorConfig(
                        supportedPackages = listOf("suwayomi.tachidesk.graphql.server.subscriptions"),
                        hooks = FlowSubscriptionSchemaGeneratorHooks(),
                    ),
                    queries = listOf(TopLevelObject(SubscriptionTestQuery())),
                    subscriptions = listOf(TopLevelObject(SubscriptionTestSubscription())),
                ),
            ).subscriptionExecutionStrategy(FlowSubscriptionExecutionStrategy())
            .build()
    private val handler = GraphQLSubscriptionHandler(graphQL, KotlinDataLoaderRegistryFactory(SubscriptionTestValueLoader()))

    /** Each event's `value`, or "error" for an event that came back with errors. */
    private fun values(): List<String?> =
        assertTimeoutPreemptively(Duration.ofSeconds(30)) {
            runBlocking {
                handler.executeSubscription(GraphQLRequest(query = "subscription { items { value } }")).toList().map { response ->
                    if (!response.errors.isNullOrEmpty()) {
                        "error"
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        ((response.data as Map<String, Any?>)["items"] as Map<String, Any?>)["value"] as String?
                    }
                }
            }
        }

    @Test
    fun `a later event sees a value that changed after an earlier event`() {
        SubscriptionTestStore.values[1] = "old"
        SubscriptionTestStore.events =
            flow {
                emit(1)
                SubscriptionTestStore.values[1] = "new"
                emit(1)
            }
        assertEquals(listOf("old", "new"), values())
    }

    @Test
    fun `loader caches stay empty over 1000 events`() {
        SubscriptionTestStore.loaders.clear()
        (1..1000).forEach { SubscriptionTestStore.values[it] = "v$it" }
        SubscriptionTestStore.events = (1..1000).asFlow()
        assertEquals(1000, values().size)
        assertTrue(SubscriptionTestStore.loaders.sumOf { it.cacheMap.size() } <= 1)
    }

    @Test
    fun `an event after a failed one still sees fresh values`() {
        SubscriptionTestStore.values[3] = "before"
        SubscriptionTestStore.failing += 4
        SubscriptionTestStore.events =
            flow {
                emit(3)
                emit(4)
                SubscriptionTestStore.values[3] = "after"
                emit(3)
            }
        try {
            assertEquals(listOf("before", "error", "after"), values())
        } finally {
            SubscriptionTestStore.failing -= 4
        }
    }
}
