package suwayomi.tachidesk.server.database.fork

/*
 * Fork-only. Not in the migration package on purpose: upstream's runner loads every class there.
 */

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.server.serverConfig

/**
 * Schema this fork adds on top of upstream's migrations. Runs on every start, after the migrations.
 * Every statement is idempotent and every name starts with "fork_", so nothing here can collide with
 * an upstream migration, and a stock image still starts on a database this has touched.
 *
 * These indexes only make queries faster, so a failure is logged and skipped rather than stopping the server.
 */
object ForkSchema {
    private val logger = KotlinLogging.logger {}

    /** How long to wait for a table lock (Postgres) before skipping an index until the next start. */
    const val LOCK_TIMEOUT_MS = 15_000

    private val indexes =
        linkedMapOf(
            // browse/search lookups by (source, url); no upstream version indexes this
            "fork_manga_source_url" to "manga (source, url)",
            // one-row-per-series chapter queries (F2), same shape as upstream master's M0067
            "fork_chapter_manga_source_order" to "chapter (manga, source_order)",
            "fork_chapter_manga_fetched_at" to "chapter (manga, fetched_at DESC, source_order DESC)",
            "fork_chapter_manga_date_upload" to "chapter (manga, date_upload DESC, source_order DESC)",
        )

    val indexNames: List<String> get() = indexes.keys.toList()

    fun apply() {
        apply(indexes, LOCK_TIMEOUT_MS)
    }

    /** Returns the names of the indexes that couldn't be created or rebuilt. */
    internal fun apply(
        indexes: Map<String, String> = this.indexes,
        lockTimeoutMs: Int,
    ): List<String> {
        val postgres = serverConfig.databaseType.value == DatabaseType.POSTGRESQL
        val failed =
            indexes
                .filter { (name, definition) ->
                    try {
                        // one transaction per index: on Postgres a failed statement aborts the rest of its transaction
                        transaction {
                            // Exposed retries a failed transaction (3 attempts by default); each retry would wait for the lock again
                            maxAttempts = 1
                            if (postgres) {
                                exec("SET LOCAL lock_timeout = $lockTimeoutMs")
                                applyOnPostgres(name, definition)
                            } else {
                                exec("CREATE INDEX IF NOT EXISTS $name ON $definition")
                            }
                        }
                        false
                    } catch (e: Exception) {
                        logger.error(e) { "ForkSchema: skipped $name, queries that use it stay slower until the next start" }
                        true
                    }
                }.keys
                .toList()
        logger.info { "ForkSchema applied: ${(indexes.keys - failed.toSet()).joinToString()}" }
        return failed
    }

    // CREATE INDEX IF NOT EXISTS locks the table even when the index exists, so look in the catalog first
    private fun JdbcTransaction.applyOnPostgres(
        name: String,
        definition: String,
    ) {
        val valid =
            exec(
                "SELECT indisvalid FROM pg_index WHERE indexrelid = to_regclass('$name')",
            ) { rs -> if (rs.next()) rs.getBoolean(1) else null }
        when (valid) {
            true -> {
                return
            }

            false -> {
                // left behind by a failed CREATE INDEX CONCURRENTLY; IF NOT EXISTS would skip it forever
                logger.info { "ForkSchema: rebuilding invalid index $name" }
                exec("REINDEX INDEX $name")
            }

            null -> {
                logger.info { "ForkSchema: creating index $name, this can take a while on a large library" }
                exec("CREATE INDEX IF NOT EXISTS $name ON $definition")
            }
        }
    }
}
