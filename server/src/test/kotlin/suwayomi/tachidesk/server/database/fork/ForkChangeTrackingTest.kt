package suwayomi.tachidesk.server.database.fork

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ForkChangeTrackingTest : ApplicationTest() {
    @Test
    fun `change tracking stays off on H2 and adds nothing`() {
        ForkChangeTracking.apply()
        assertFalse(ForkChangeTracking.active)
        val columns =
            transaction {
                exec("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(COLUMN_NAME) = 'FORK_CHANGED_AT'") { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        assertEquals(0, columns)
    }
}
