package suwayomi.tachidesk.manga.impl.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.util.HAScheduler
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /** Review M8. */
    @Test
    fun `the interval property ignores spaces and values it can't read`() {
        System.setProperty(ForkExtensionRefresh.INTERVAL_PROPERTY, " 12 ")
        assertEquals(12L, ForkExtensionRefresh.intervalHours)
        System.setProperty(ForkExtensionRefresh.INTERVAL_PROPERTY, "1.5")
        assertEquals(6L, ForkExtensionRefresh.intervalHours)
    }

    /** Review M1: a refresh can wait up to 3 h for a library update, longer than a short interval. */
    @Test
    fun `a scheduled refresh is skipped while the previous one still runs`() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val runs = AtomicInteger()
            val first = async(Dispatchers.Default) { ForkExtensionRefresh.runScheduled { runs.incrementAndGet().also { release.await() } } }
            while (runs.get() == 0) delay(5)

            assertFalse(ForkExtensionRefresh.runScheduled { runs.incrementAndGet() })
            release.complete(Unit)
            assertTrue(first.await())
            assertTrue(ForkExtensionRefresh.runScheduled { runs.incrementAndGet() })
            assertEquals(2, runs.get())
        }

    /** Review M5: the scheduled task logs a failure instead of throwing, and the next refresh still runs. */
    @Test
    fun `a failing scheduled refresh doesn't stop the next one`() =
        runBlocking {
            assertTrue(ForkExtensionRefresh.runScheduled { error("store index broken") })
            assertTrue(ForkExtensionRefresh.runScheduled {})
        }
}
