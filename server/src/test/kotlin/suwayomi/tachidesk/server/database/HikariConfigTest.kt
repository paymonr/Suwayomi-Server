package suwayomi.tachidesk.server.database

import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.assertEquals

class HikariConfigTest : ApplicationTest() {
    private val mysqlOnly = setOf("cachePrepStmts", "useServerPrepStmts", "prepStmtCacheSize", "prepStmtCacheSqlLimit")

    @Test
    fun `no MySQL-only driver properties reach H2 or Postgres`() {
        DatabaseType.entries.forEach { type ->
            val passed = DBManager.hikariConfig(type).dataSourceProperties.stringPropertyNames()
            assertEquals(emptySet(), passed intersect mysqlOnly, "$type")
        }
    }
}
