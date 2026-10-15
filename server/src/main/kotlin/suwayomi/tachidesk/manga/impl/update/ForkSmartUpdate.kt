package suwayomi.tachidesk.manga.impl.update

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import java.time.Instant

/**
 * Fork-only (F8): a scheduled library update checks a series less often once it has gone quiet, meaning no new chapter
 * has been found for a while. Replaying main's last 60 days (2026-10): 53–56% fewer checks, and 0.3–0.4% of new
 * chapters found more than a day late (worst ~66 h). Manual updates check everything.
 */
object ForkSmartUpdate {
    private val logger = KotlinLogging.logger {}

    const val ENABLED_PROPERTY = "suwayomi.fork.smartUpdate"

    private const val DAY = 86_400L

    /** A due check may run this much early: runs take up to ~47 min on main, so the next one can start a little before 24 h. */
    const val SLACK_SECONDS = 3 * 3_600L

    /** On unless `-Dsuwayomi.fork.smartUpdate=false` (any case). A value it can't read is logged and leaves it on. */
    val enabled: Boolean
        get() =
            when (val value = System.getProperty(ENABLED_PROPERTY)?.trim()?.lowercase()) {
                null, "", "true" -> true
                "false" -> false
                else -> true.also { logger.warn { "Ignoring -D$ENABLED_PROPERTY=$value: expected true or false" } }
            }

    /**
     * Whether a scheduled run at [now] checks a series.
     * - [lastFoundAt]: when its newest chapter was first found; null if it has no chapters.
     * - [lastCheckedAt]: its last successful chapter-list fetch (`chapters_last_fetched_at`). A failed check doesn't move
     *   it, so a failing series stays due.
     *
     * Quiet under 30 days: every run. 30–180 days: daily. Over 180 days: every 3 days.
     */
    fun isDue(
        lastFoundAt: Long?,
        lastCheckedAt: Long,
        now: Long,
    ): Boolean {
        if (lastFoundAt == null) return true
        val quiet = now - lastFoundAt
        val checkEvery =
            when {
                quiet < 30 * DAY -> return true
                quiet < 180 * DAY -> DAY
                else -> 3 * DAY
            }
        return now - lastCheckedAt >= checkEvery - SLACK_SECONDS
    }

    /**
     * The series among [mangaIds] that a scheduled run at [now] can skip. Only library series are looked at, and a
     * series this doesn't find is never skipped. The newest chapter per series comes from the `fork_chapter_manga_fetched_at`
     * index (~20 ms for 2,778 library series on main, against ~200 ms for a join over their ~245k chapters).
     */
    fun skippableMangaIds(
        mangaIds: Collection<Int>,
        now: Long = Instant.now().epochSecond,
    ): Set<Int> {
        if (mangaIds.isEmpty()) return emptySet()
        val asked = mangaIds.toSet()
        val lastFoundAt =
            wrapAsExpression<Long>(ChapterTable.select(ChapterTable.fetchedAt.max()).where { ChapterTable.manga eq MangaTable.id })
        return transaction {
            MangaTable
                .select(MangaTable.id, MangaTable.chaptersLastFetchedAt, lastFoundAt)
                .where { MangaTable.inLibrary eq true }
                .mapNotNull { row ->
                    row[MangaTable.id].value.takeIf {
                        it in asked && !isDue(row[lastFoundAt], row[MangaTable.chaptersLastFetchedAt], now)
                    }
                }.toSet()
        }
    }

    /** [skippableMangaIds], or nothing if [query] fails: skipping only saves work, so a failure must not lose the run. */
    fun skippableMangaIdsOrNone(
        mangaIds: Collection<Int>,
        query: (Collection<Int>) -> Set<Int> = { skippableMangaIds(it) },
    ): Set<Int> =
        try {
            query(mangaIds)
        } catch (e: Exception) {
            logger.warn(e) { "Smart update: couldn't find quiet series, checking all of them" }
            emptySet()
        }
}
