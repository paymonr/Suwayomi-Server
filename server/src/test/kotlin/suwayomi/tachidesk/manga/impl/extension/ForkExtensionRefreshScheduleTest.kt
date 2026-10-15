package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.util.HAScheduler
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ForkExtensionRefreshScheduleTest : ApplicationTest() {
    @AfterEach
    fun clearProperty() {
        System.clearProperty(ForkExtensionRefresh.INTERVAL_PROPERTY)
    }

    @Test
    fun `the refresh runs every 6 hours unless the property changes it`() {
        assertEquals(6L, ForkExtensionRefresh.intervalHours)
        System.setProperty(ForkExtensionRefresh.INTERVAL_PROPERTY, "12")
        assertEquals(12L, ForkExtensionRefresh.intervalHours)
    }

    @Test
    fun `an interval of 0 turns the schedule off`() {
        System.setProperty(ForkExtensionRefresh.INTERVAL_PROPERTY, "0")
        assertNull(ForkExtensionRefresh.schedule())
    }

    @Test
    fun `the refresh is scheduled with the server's scheduler`() {
        val id = assertNotNull(ForkExtensionRefresh.schedule())
        assertNotNull(HAScheduler.deschedule(id), "not in the scheduler")
    }
}
