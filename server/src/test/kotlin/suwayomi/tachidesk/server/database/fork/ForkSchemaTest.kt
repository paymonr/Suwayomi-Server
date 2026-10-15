package suwayomi.tachidesk.server.database.fork

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForkSchemaTest : ApplicationTest() {
    private fun forkIndexNames(): List<String> =
        transaction {
            exec("SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(INDEX_NAME) LIKE 'fork\\_%' ESCAPE '\\'") { rs ->
                buildList { while (rs.next()) add(rs.getString(1).lowercase()) }
            }.orEmpty().distinct().sorted()
        }

    @Test
    fun `databaseUp creates every fork index`() {
        assertEquals(ForkSchema.indexNames.sorted(), forkIndexNames())
    }

    @Test
    fun `applying again is harmless`() {
        ForkSchema.apply()
        ForkSchema.apply()
        assertEquals(ForkSchema.indexNames.sorted(), forkIndexNames())
    }

    @Test
    fun `a failing index is skipped, not fatal, and the rest are still created`() {
        try {
            val failed =
                ForkSchema.apply(
                    linkedMapOf("fork_test_broken" to "no_such_table (x)", "fork_test_ok" to "manga (title)"),
                    lockTimeoutMs = 1_000,
                )
            assertEquals(listOf("fork_test_broken"), failed)
            assertTrue("fork_test_ok" in forkIndexNames())
        } finally {
            transaction { exec("DROP INDEX IF EXISTS fork_test_ok") }
        }
    }
}
