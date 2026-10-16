package suwayomi.tachidesk.graphql

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import kotlinx.coroutines.runBlocking
import suwayomi.tachidesk.graphql.server.GraphQLSchemaProvider
import suwayomi.tachidesk.graphql.server.TachideskDataLoaderRegistryFactory
import suwayomi.tachidesk.graphql.server.toGraphQLContext
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.user.UserType

/** Runs [query] as the admin user, the way the server does. */
fun executeGraphQL(query: String): ExecutionResult {
    val context = mapOf<Any, Any>(JavalinSetup.Attribute.TachideskUser to UserType.Admin(1))
    val graphQL = GraphQL.newGraphQL(runBlocking { GraphQLSchemaProvider.getSchema() }).build()
    return graphQL.execute(
        ExecutionInput
            .newExecutionInput()
            .query(query)
            .graphQLContext(context)
            .dataLoaderRegistry(TachideskDataLoaderRegistryFactory.create().generate(context.toGraphQLContext()))
            .build(),
    )
}

@Suppress("UNCHECKED_CAST")
fun ExecutionResult.dataAt(vararg path: String): Any? = path.fold(getData<Any?>()) { node, key -> (node as Map<String, Any?>?)?.get(key) }
