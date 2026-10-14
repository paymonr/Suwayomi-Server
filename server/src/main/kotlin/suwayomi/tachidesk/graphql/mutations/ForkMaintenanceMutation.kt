package suwayomi.tachidesk.graphql.mutations

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.manga.impl.fork.NonLibraryMangaPrune
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

/** Fork-only maintenance mutations. */
class ForkMaintenanceMutation {
    data class PruneNonLibraryMangaInput(
        val clientMutationId: String? = null,
        @GraphQLDescription("Series ids to keep whatever their state, e.g. the ones suwayomi-dm references")
        val excludeMangaIds: List<Int>? = null,
        @GraphQLDescription("Only count what would be deleted. Defaults to true")
        val dryRun: Boolean? = null,
    )

    data class PruneNonLibraryMangaPayload(
        val clientMutationId: String?,
        val dryRun: Boolean,
        val mangaCount: Int,
        val chapterCount: Int,
    )

    @RequireAuth
    @GraphQLDescription(
        "Fork-only: delete series that aren't in the library and have no reads, progress, bookmarks, downloads, " +
            "categories, tracking or meta, with their chapters, pages and cached covers. Dry run unless dryRun is false.",
    )
    fun pruneNonLibraryManga(input: PruneNonLibraryMangaInput): CompletableFuture<PruneNonLibraryMangaPayload> =
        future {
            val dryRun = input.dryRun ?: true
            val result = NonLibraryMangaPrune.prune(input.excludeMangaIds.orEmpty().toSet(), dryRun)
            PruneNonLibraryMangaPayload(input.clientMutationId, dryRun, result.mangaIds.size, result.chapterCount.toInt())
        }
}
