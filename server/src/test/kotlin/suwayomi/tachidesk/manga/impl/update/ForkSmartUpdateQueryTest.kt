package suwayomi.tachidesk.manga.impl.update

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.update.SmartUpdateFixture.DAY
import suwayomi.tachidesk.manga.impl.update.SmartUpdateFixture.HOUR
import suwayomi.tachidesk.manga.impl.update.SmartUpdateFixture.series
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.PostgresTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The checks shared by the H2 and Postgres classes. */
object SmartUpdateQueryChecks {
    fun onlyQuietSeriesCheckedRecentlyAreSkipped() {
        val now = 2_000_000_000L
        val active = series("active", lastFoundAt = now - 2 * DAY, lastCheckedAt = now - HOUR)
        val quietCheckedToday = series("quiet 60 d, checked 2 h ago", now - 60 * DAY, now - 2 * HOUR)
        val quietDue = series("quiet 60 d, checked 22 h ago", now - 60 * DAY, now - 22 * HOUR)
        val dormantCheckedYesterday = series("quiet 200 d, checked 2 d ago", now - 200 * DAY, now - 2 * DAY)
        val dormantDue = series("quiet 200 d, checked 3 d ago", now - 200 * DAY, now - 3 * DAY)
        val noChapters = series("no chapters", null, now - HOUR)
        val notAsked = series("quiet, not asked about", now - 60 * DAY, now - HOUR)

        val skipped =
            ForkSmartUpdate.skippableMangaIds(
                listOf(active, quietCheckedToday, quietDue, dormantCheckedYesterday, dormantDue, noChapters),
                now,
            )

        assertEquals(setOf(quietCheckedToday, dormantCheckedYesterday), skipped)
        assertFalse(notAsked in skipped)
    }
}

class ForkSmartUpdateQueryTest : ApplicationTest() {
    @AfterEach
    fun clear() = SmartUpdateFixture.clear()

    @Test
    fun `only quiet series checked recently are skipped`() = SmartUpdateQueryChecks.onlyQuietSeriesCheckedRecentlyAreSkipped()
}

class ForkSmartUpdatePostgresTest : PostgresTest() {
    @AfterEach
    fun clear() = SmartUpdateFixture.clear()

    @Test
    fun `only quiet series checked recently are skipped`() = SmartUpdateQueryChecks.onlyQuietSeriesCheckedRecentlyAreSkipped()
}
