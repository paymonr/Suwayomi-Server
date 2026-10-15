package suwayomi.tachidesk.manga.impl.update

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable

/** Library series with a chosen chapter history, for F8's tests. */
object SmartUpdateFixture {
    const val HOUR = 3_600L
    const val DAY = 24 * HOUR

    /** No such source: a real update of these series fails at once. */
    const val SOURCE = 7_000_300L

    /** A library series whose newest of 3 chapters was first found at [lastFoundAt] (no chapters if null). */
    fun series(
        tag: String,
        lastFoundAt: Long?,
        lastCheckedAt: Long,
    ): Int =
        transaction {
            val mangaId =
                MangaTable
                    .insertAndGetId {
                        it[title] = "smart $tag ${System.nanoTime()}"
                        it[url] = "smart-$tag-${System.nanoTime()}"
                        it[sourceReference] = SOURCE
                        it[inLibrary] = true
                        it[chaptersLastFetchedAt] = lastCheckedAt
                    }.value
            if (lastFoundAt != null) {
                (1..3).forEach { n ->
                    ChapterTable.insert {
                        it[url] = "$mangaId-$n"
                        it[name] = "Chapter $n"
                        it[manga] = mangaId
                        it[sourceOrder] = n
                        it[memo] = JsonObject(emptyMap())
                        it[fetchedAt] = lastFoundAt - (3 - n) * 7 * DAY
                    }
                }
            }
            mangaId
        }
}
