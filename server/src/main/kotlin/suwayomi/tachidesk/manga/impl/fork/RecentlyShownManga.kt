package suwayomi.tachidesk.manga.impl.fork

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork-only (F12): when a client was last shown each series, in a browse listing or by opening it with the `manga` query.
 * Opening a series that's already loaded doesn't fetch it again, so its fetch times alone can't tell the prune it's in use.
 * Held in memory: a restart forgets it. Once over [evictAbove] entries, series shown before the grace period are dropped.
 */
open class ShownMangaRegister(
    private val evictAbove: Int = 10_000,
) {
    private val shownAt = ConcurrentHashMap<Int, Long>()

    fun record(
        mangaIds: Collection<Int>,
        nowEpochSeconds: Long = Instant.now().epochSecond,
    ) {
        mangaIds.forEach { shownAt.merge(it, nowEpochSeconds, ::maxOf) }
        if (shownAt.size > evictAbove) {
            val cutoff = nowEpochSeconds - NonLibraryMangaPrune.GRACE_SECONDS
            shownAt.values.removeIf { it < cutoff }
        }
    }

    /** The series shown at or after [cutoffEpochSeconds]. */
    fun since(cutoffEpochSeconds: Long): Set<Int> = shownAt.filterValues { it >= cutoffEpochSeconds }.keys
}

object RecentlyShownManga : ShownMangaRegister()
