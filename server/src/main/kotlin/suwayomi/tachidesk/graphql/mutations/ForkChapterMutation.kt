package suwayomi.tachidesk.graphql.mutations

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

/** Fork-only chapter mutations (Batch E). */
class ForkChapterMutation {
    data class SetChaptersDownloadStateInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
        val isDownloaded: Boolean,
        @GraphQLDescription("Pages in the downloaded chapter: required, above 0, when isDownloaded is true; ignored when false")
        val pageCount: Int? = null,
    )

    data class SetChaptersDownloadStatePayload(
        val clientMutationId: String?,
        val chapters: List<ChapterType>,
    )

    @RequireAuth
    @GraphQLDescription(
        "Fork-only: set chapters' downloaded flag (and page count) without touching any file, for tools that place " +
            "downloads themselves, like suwayomi-dm's grafts. All or nothing: fails if any id doesn't exist.",
    )
    fun setChaptersDownloadState(input: SetChaptersDownloadStateInput): CompletableFuture<SetChaptersDownloadStatePayload> =
        future {
            val ids = input.ids.distinct()
            require(ids.isNotEmpty()) { "ids is empty" }
            val pageCount = input.pageCount
            if (input.isDownloaded) require(pageCount != null && pageCount > 0) { "pageCount must be above 0 when isDownloaded is true" }
            val chapters =
                transaction {
                    val existing =
                        ChapterTable
                            .select(
                                ChapterTable.id,
                            ).where { ChapterTable.id inList ids }
                            .map { it[ChapterTable.id].value }
                    val missing = ids - existing.toSet()
                    require(missing.isEmpty()) { "no chapter with id ${missing.joinToString()}" }
                    ChapterTable.update({ ChapterTable.id inList ids }) {
                        it[ChapterTable.isDownloaded] = input.isDownloaded
                        if (input.isDownloaded) it[ChapterTable.pageCount] = pageCount!!
                    }
                    ChapterTable
                        .selectAll()
                        .where { ChapterTable.id inList ids }
                        .orderBy(ChapterTable.id to SortOrder.ASC)
                        .map { ChapterType(it) }
                }
            SetChaptersDownloadStatePayload(input.clientMutationId, chapters)
        }
}
