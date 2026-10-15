package suwayomi.tachidesk.manga.impl.update

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForkSmartUpdateTest {
    private val now = 2_000_000_000L
    private val hour = 3_600L
    private val day = 24 * hour

    @AfterEach
    fun clearProperty() {
        System.clearProperty(ForkSmartUpdate.ENABLED_PROPERTY)
    }

    private fun due(
        quietDays: Long?,
        checkedHoursAgo: Long,
    ) = ForkSmartUpdate.isDue(quietDays?.let { now - it * day }, now - checkedHoursAgo * hour, now)

    @Test
    fun `a series that found a chapter in the last 30 days is checked every run`() {
        assertTrue(due(quietDays = 29, checkedHoursAgo = 1))
    }

    @Test
    fun `a series quiet for 30 to 180 days is checked once a day`() {
        assertFalse(due(quietDays = 31, checkedHoursAgo = 20))
        assertTrue(due(quietDays = 179, checkedHoursAgo = 24))
    }

    @Test
    fun `a series quiet for over 180 days is checked every 3 days`() {
        assertFalse(due(quietDays = 181, checkedHoursAgo = 66))
        assertTrue(due(quietDays = 400, checkedHoursAgo = 72))
    }

    /** Review Focus 2: runs take up to ~47 min, so the run a day later can start a little under 24 h after the last check. */
    @Test
    fun `a check that comes due within 3 hours counts as due`() {
        assertTrue(due(quietDays = 60, checkedHoursAgo = 21))
        assertTrue(due(quietDays = 200, checkedHoursAgo = 69))
    }

    @Test
    fun `a series with no chapters, or never checked, is due`() {
        assertTrue(due(quietDays = null, checkedHoursAgo = 1))
        assertTrue(ForkSmartUpdate.isDue(now - 200 * day, lastCheckedAt = 0, now = now))
    }

    @Test
    fun `smart updates are on unless the property turns them off`() {
        assertTrue(ForkSmartUpdate.enabled)
        System.setProperty(ForkSmartUpdate.ENABLED_PROPERTY, "false")
        assertFalse(ForkSmartUpdate.enabled)
    }
}
