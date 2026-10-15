package suwayomi.tachidesk.manga.impl.update

/**
 * Fork-only (F8): a scheduled library update checks a series less often once it has gone quiet, meaning no new chapter
 * has been found for a while. Replaying main's last 60 days (2026-10): 53–56% fewer checks, and 0.3–0.4% of new
 * chapters found more than a day late (worst ~66 h). Manual updates check everything.
 */
object ForkSmartUpdate {
    const val ENABLED_PROPERTY = "suwayomi.fork.smartUpdate"

    private const val DAY = 86_400L

    /** A due check may run this much early: runs take up to ~47 min on main, so the next one can start a little before 24 h. */
    const val SLACK_SECONDS = 3 * 3_600L

    /** On unless `-Dsuwayomi.fork.smartUpdate=false`. */
    val enabled: Boolean
        get() = System.getProperty(ENABLED_PROPERTY)?.toBooleanStrictOrNull() ?: true

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
}
