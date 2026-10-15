package suwayomi.tachidesk.test

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.server.database.databaseUp
import suwayomi.tachidesk.server.serverConfig

/**
 * Runs a test class against a real Postgres 16 (embedded binaries, no Docker), migrated the same way
 * production is. Production code calling `transaction {}` uses this database while the class runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PostgresTest : ApplicationTest() {
    private lateinit var postgres: EmbeddedPostgres
    private var previousDatabase: Database? = null
    private lateinit var previousType: DatabaseType
    private lateinit var previousUsername: String
    protected lateinit var database: Database

    @BeforeAll
    fun startPostgres() {
        postgres = EmbeddedPostgres.builder().start()
        previousDatabase = TransactionManager.defaultDatabase
        previousType = serverConfig.databaseType.value
        previousUsername = serverConfig.databaseUsername.value
        serverConfig.databaseType.value = DatabaseType.POSTGRESQL
        serverConfig.databaseUsername.value = "postgres"
        database =
            Database.connect(
                url = postgres.getJdbcUrl("postgres", "postgres"),
                driver = "org.postgresql.Driver",
                user = "postgres",
                databaseConfig =
                    DatabaseConfig {
                        useNestedTransactions = true
                        @OptIn(ExperimentalKeywordApi::class)
                        preserveKeywordCasing = false
                        defaultSchema = Schema("suwayomi")
                    },
            )
        TransactionManager.defaultDatabase = database
        databaseUp(database)
    }

    /** A separate connection to the same database, outside Exposed (e.g. to hold a lock while production code runs). */
    protected fun newConnection(): java.sql.Connection = postgres.postgresDatabase.connection

    @AfterAll
    fun stopPostgres() {
        // unregister first: with no explicit default, Exposed falls back to the most recently registered database
        TransactionManager.closeAndUnregister(database)
        TransactionManager.defaultDatabase = previousDatabase
        serverConfig.databaseType.value = previousType
        serverConfig.databaseUsername.value = previousUsername
        postgres.close()
    }
}
