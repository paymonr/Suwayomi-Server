package suwayomi.tachidesk.server.database.fork

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.PostgresTest
import kotlin.test.assertEquals

class ForkSchemaPostgresTest : PostgresTest() {
    @Test
    fun `databaseUp creates every fork index on postgres`() {
        val names =
            transaction {
                exec("SELECT indexname FROM pg_indexes WHERE schemaname = 'suwayomi' AND indexname LIKE 'fork\\_%'") { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }.orEmpty().sorted()
            }
        assertEquals(ForkSchema.indexNames.sorted(), names)
    }
}
