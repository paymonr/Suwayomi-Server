package suwayomi.tachidesk.graphql.dataLoaders

/*
 * Fork-only (Batch E): `manga.fork_changed_at`, kept by ForkChangeTracking. It exists on Postgres only, so it
 * isn't a MangaTable column: upstream's queries select MangaTable, and must keep working wherever it's missing.
 */

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import graphql.GraphQLContext
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.queries.filter.LongFilter
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompare
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.database.fork.ForkChangeTracking

/** The manga table seen through its fork-only column. Used only while [ForkChangeTracking.active]. */
object ForkMangaChanges : IntIdTable("manga") {
    val changedAt = long(ForkChangeTracking.COLUMN)
}

/** The series whose stamp matches [filter]. Change tracking runs on Postgres only, so elsewhere this fails with a clear message. */
fun changedAtOp(filter: LongFilter): Op<Boolean>? {
    check(ForkChangeTracking.active) { "changedAt needs change tracking, which runs on PostgreSQL only" }
    val op = andFilterWithCompare(ForkMangaChanges.changedAt, filter) ?: return null
    return MangaTable.id inSubQuery ForkMangaChanges.select(ForkMangaChanges.id).where { op }
}

// V is non-null like upstream's loaders (KotlinDataLoader bounds it by Any); missing stamps come back as null entries
class MangaChangedAtDataLoader : KotlinDataLoader<Int, Long> {
    override val dataLoaderName = "MangaChangedAtDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Long> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                if (!ForkChangeTracking.active) return@future ids.map { null }
                transaction {
                    val stamps =
                        ForkMangaChanges
                            .select(ForkMangaChanges.id, ForkMangaChanges.changedAt)
                            .where { ForkMangaChanges.id inList ids }
                            .associate { it[ForkMangaChanges.id].value to it[ForkMangaChanges.changedAt] }
                    ids.map { stamps[it] }
                }
            }
        }
}
