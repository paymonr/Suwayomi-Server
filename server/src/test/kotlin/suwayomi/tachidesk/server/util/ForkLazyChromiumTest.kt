package suwayomi.tachidesk.server.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForkLazyChromiumTest {
    @AfterEach
    fun clearProperty() {
        System.clearProperty(ForkLazyChromium.ENABLED_PROPERTY)
    }

    // Stands in for CefHelper.cefApp: null until an app has started.
    private val app = MutableStateFlow<String?>(null)
    private var starts = 0

    private suspend fun fakeStart() {
        starts++
        app.value = "app"
    }

    @Test
    fun `nothing starts while no WebView is waiting`() =
        runTest {
            ForkLazyChromium.startOnFirstWaiter(backgroundScope, app, ::fakeStart)
            advanceUntilIdle()
            assertEquals(0, starts)
        }

    @Test
    fun `the first WebView request starts it, once, and every waiter gets the app`() =
        runTest {
            ForkLazyChromium.startOnFirstWaiter(backgroundScope, app, ::fakeStart)
            val first = async { app.first { it != null } }
            val second = async { app.first { it != null } }
            assertEquals("app", first.await())
            assertEquals("app", second.await())
            assertEquals(1, starts)
        }

    @Test
    fun `a WebView already waiting starts it straight away`() =
        runTest {
            val waiter = async { app.first { it != null } }
            runCurrent()
            ForkLazyChromium.startOnFirstWaiter(backgroundScope, app, ::fakeStart)
            assertEquals("app", waiter.await())
            assertEquals(1, starts)
        }

    @Test
    fun `a WebView that gives up while it starts doesn't start it a second time`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            ForkLazyChromium.startOnFirstWaiter(backgroundScope, app) {
                starts++
                release.await()
                app.value = "app"
            }
            val impatient = launch { app.first { it != null } }
            runCurrent()
            impatient.cancel()
            release.complete(Unit)
            assertEquals("app", app.first { it != null })
            assertEquals(1, starts)
        }

    @Test
    fun `a cancelled start never runs, even when a WebView asks later`() =
        runTest {
            ForkLazyChromium.startOnFirstWaiter(backgroundScope, app, ::fakeStart).cancel()
            val waiter = launch { app.first { it != null } }
            advanceUntilIdle()
            assertEquals(0, starts)
            waiter.cancel()
        }

    @Test
    fun `lazy start is on unless the property turns it off, in any case`() {
        assertTrue(ForkLazyChromium.enabled)
        System.setProperty(ForkLazyChromium.ENABLED_PROPERTY, " FALSE ")
        assertFalse(ForkLazyChromium.enabled)
        System.setProperty(ForkLazyChromium.ENABLED_PROPERTY, "true")
        assertTrue(ForkLazyChromium.enabled)
        System.setProperty(ForkLazyChromium.ENABLED_PROPERTY, "nonsense")
        assertTrue(ForkLazyChromium.enabled)
    }
}
