package suwayomi.tachidesk.graphql

import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.createLibraryManga
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForkChangedAtTest : ApplicationTest() {
    @Test
    fun `changedAt is null on H2`() {
        val id = createLibraryManga("E2 h2 ${System.nanoTime()}")
        val result = executeGraphQL("{ manga(id: $id) { changedAt } }")
        assertEquals(emptyList(), result.errors)
        assertNull(result.dataAt("manga", "changedAt"))
    }

    @Test
    fun `the changedAt filter fails on H2 with a clear message`() {
        val result = executeGraphQL("""{ mangas(filter: { changedAt: { greaterThan: "1" } }) { nodes { id } } }""")
        assertTrue(result.errors.any { "change tracking" in it.message }, result.errors.toString())
    }
}
