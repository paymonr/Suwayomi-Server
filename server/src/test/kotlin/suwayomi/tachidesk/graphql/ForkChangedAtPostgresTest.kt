package suwayomi.tachidesk.graphql

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.PostgresTest
import suwayomi.tachidesk.test.createLibraryManga
import kotlin.test.assertEquals

class ForkChangedAtPostgresTest : PostgresTest() {
    private fun setStamp(
        mangaId: Int,
        stamp: Long,
    ) {
        transaction { exec("UPDATE suwayomi.manga SET fork_changed_at = $stamp WHERE id = $mangaId") }
    }

    private fun setInLibrary(
        mangaId: Int,
        inLibrary: Boolean,
    ) {
        transaction { exec("UPDATE suwayomi.manga SET in_library = $inLibrary WHERE id = $mangaId") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ids(query: String): List<Int> {
        val result = executeGraphQL(query)
        assertEquals(emptyList(), result.errors)
        return (result.dataAt("mangas", "nodes") as List<Map<String, Any?>>).map { it["id"] as Int }
    }

    @Test
    fun `changedAt is the series' stamp`() {
        val id = createLibraryManga("E2 ${System.nanoTime()}")
        setStamp(id, 1234)
        val result = executeGraphQL("{ manga(id: $id) { changedAt } }")
        assertEquals(emptyList(), result.errors)
        assertEquals("1234", result.dataAt("manga", "changedAt"))
    }

    @Test
    fun `the changedAt filter returns only the series changed since`() {
        val old = createLibraryManga("E2 old ${System.nanoTime()}")
        val new = createLibraryManga("E2 new ${System.nanoTime()}")
        setStamp(old, 1000)
        setStamp(new, 5000)
        assertEquals(
            listOf(new),
            ids("""{ mangas(filter: { changedAt: { greaterThan: "2000" }, id: { in: [$old, $new] } }) { nodes { id } } }"""),
        )
    }

    /** What suwayomi-dm sends: changed, and in the library or one of the series it already holds. */
    @Test
    fun `the suwayomi-dm query shape returns changed library series plus changed known ids`() {
        val library = createLibraryManga("E2 lib ${System.nanoTime()}")
        val left = createLibraryManga("E2 left ${System.nanoTime()}")
        val stranger = createLibraryManga("E2 stranger ${System.nanoTime()}")
        val quiet = createLibraryManga("E2 quiet ${System.nanoTime()}")
        setInLibrary(left, false)
        setInLibrary(stranger, false)
        setStamp(library, 5000)
        setStamp(left, 5000)
        setStamp(stranger, 5000)
        setStamp(quiet, 1000)
        val ids =
            ids(
                """{ mangas(filter: { and: [
                    { changedAt: { greaterThan: "2000" } },
                    { or: [{ inLibrary: { equalTo: true } }, { id: { in: [$left, $quiet] } }] }
                ], id: { in: [$library, $left, $stranger, $quiet] } }) { nodes { id } } }""",
            )
        assertEquals(listOf(library, left), ids)
    }
}
