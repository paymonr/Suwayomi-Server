package suwayomi.tachidesk.server.database.fork

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import suwayomi.tachidesk.test.PostgresTest
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForkSchemaPostgresTest : PostgresTest() {
    private fun names(): List<String> =
        transaction {
            // a fork table's primary key brings its own index; only the indexes ForkSchema lists count here
            exec(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'suwayomi' AND indexname LIKE 'fork\\_%' AND indexname NOT LIKE '%\\_pkey'",
            ) { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }.orEmpty().sorted()
        }

    private fun isValid(name: String): Boolean? =
        transaction {
            exec("SELECT indisvalid FROM pg_index WHERE indexrelid = to_regclass('suwayomi.$name')") { rs ->
                if (rs.next()) rs.getBoolean(1) else null
            }
        }

    /** Holds the lock an UPDATE takes on [table] (what a suwayomi-dm graft does) in another connection while [block] runs. */
    private fun <T> whileTableIsBeingUpdated(
        table: String,
        block: () -> T,
    ): T =
        newConnection().use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("LOCK TABLE suwayomi.$table IN ROW EXCLUSIVE MODE") }
            try {
                block()
            } finally {
                c.rollback()
            }
        }

    @Test
    fun `databaseUp creates every fork index on postgres`() {
        assertEquals(ForkSchema.indexNames.sorted(), names())
    }

    @Test
    fun `databaseUp creates every fork table on postgres`() {
        val tables =
            transaction {
                exec("SELECT tablename FROM pg_tables WHERE schemaname = 'suwayomi' AND tablename LIKE 'fork\\_%'") { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }.orEmpty()
            }
        assertEquals(listOf("fork_chapter_download", "fork_obsolete_download"), tables.sorted())
    }

    @Test
    fun `existing fork tables don't wait for a series that is being updated`() {
        whileTableIsBeingUpdated("manga") {
            assertTimeoutPreemptively(Duration.ofSeconds(10)) { ForkSchema.apply() }
        }
    }

    @Test
    fun `an invalid fork index is rebuilt`() {
        transaction { exec("UPDATE pg_index SET indisvalid = false WHERE indexrelid = to_regclass('suwayomi.fork_manga_source_url')") }
        assertEquals(false, isValid("fork_manga_source_url"))

        ForkSchema.apply()

        assertEquals(true, isValid("fork_manga_source_url"))
    }

    @Test
    fun `existing indexes don't wait for a table that is being updated`() {
        whileTableIsBeingUpdated("chapter") {
            assertTimeoutPreemptively(Duration.ofSeconds(10)) { ForkSchema.apply(lockTimeoutMs = 60_000) }
        }
    }

    @Test
    fun `a missing index gives up after the lock timeout and startup goes on`() {
        transaction { exec("DROP INDEX suwayomi.fork_chapter_manga_fetched_at") }
        try {
            val started = System.nanoTime()
            val failed =
                whileTableIsBeingUpdated("chapter") {
                    assertTimeoutPreemptively(Duration.ofSeconds(20)) { ForkSchema.apply(lockTimeoutMs = 2_000) }
                }
            val tookMs = (System.nanoTime() - started) / 1_000_000
            // one wait, not one per retry: a retry would queue another lock request and block writers again
            assertTrue(tookMs < 4_000, "took $tookMs ms for a 2000 ms lock timeout")
            assertEquals(listOf("fork_chapter_manga_fetched_at"), failed)
            assertFalse("fork_chapter_manga_fetched_at" in names())
        } finally {
            ForkSchema.apply()
        }
        assertEquals(ForkSchema.indexNames.sorted(), names())
    }
}
