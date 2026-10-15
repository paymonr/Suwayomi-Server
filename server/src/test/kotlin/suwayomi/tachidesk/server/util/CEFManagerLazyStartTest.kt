package suwayomi.tachidesk.server.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import xyz.nulldev.androidcompat.webkit.CefHelper
import kotlin.test.Test
import kotlin.test.assertEquals

class CEFManagerLazyStartTest {
    private var prepared = 0
    private var starts = 0

    private fun fakeStart() {
        starts++
        CefHelper.cefApp.value = Result.failure(CEFManager.CefException("fake start"))
    }

    // What every WebView does first: KcefWebViewProvider and KcefWebView both call createClient().
    private fun CoroutineScope.webViewRequest() = async { runCatching { CefHelper.createClient() }.exceptionOrNull() }

    @AfterEach
    fun reset() {
        System.clearProperty(ForkLazyChromium.ENABLED_PROPERTY)
        runBlocking { CEFManager.initAsync(enabled = false, prepare = {}, start = {}) } // drops a pending start
        CefHelper.cefApp.value = Result.success(null)
    }

    @Test
    fun `Chromium doesn't start at init, only when a WebView asks for it`() =
        runTest {
            CEFManager.initAsync(enabled = true, lazyStart = true, prepare = {}, start = ::fakeStart, startScope = backgroundScope)
            advanceUntilIdle()
            assertEquals(0, starts)
            assertEquals("fake start", webViewRequest().await()?.message)
            assertEquals(1, starts)
        }

    @Test
    fun `the installation is checked at init, before any WebView asks`() =
        runTest {
            CEFManager.initAsync(
                enabled = true,
                lazyStart = true,
                prepare = { prepared++ },
                start = ::fakeStart,
                startScope = backgroundScope,
            )
            assertEquals(1, prepared)
            assertEquals(0, starts)
        }

    @Test
    fun `with the property off, Chromium starts at init as upstream does`() =
        runTest {
            System.setProperty(ForkLazyChromium.ENABLED_PROPERTY, "false")
            CEFManager.initAsync(enabled = true, prepare = {}, start = ::fakeStart, startScope = backgroundScope)
            assertEquals(1, starts)
        }

    @Test
    fun `turning CEF off drops a start that hasn't happened yet`() =
        runTest {
            CEFManager.initAsync(enabled = true, lazyStart = true, prepare = {}, start = ::fakeStart, startScope = backgroundScope)
            CEFManager.initAsync(enabled = false, lazyStart = true, prepare = {}, start = ::fakeStart, startScope = backgroundScope)
            assertEquals("CEF is disabled", webViewRequest().await()?.message)
            advanceUntilIdle()
            assertEquals(0, starts)
        }

    @Test
    fun `a failed start reaches the waiting WebView instead of leaving it waiting`() =
        runTest {
            CEFManager.initAsync(
                enabled = true,
                lazyStart = true,
                prepare = {},
                start = {
                    starts++
                    throw CEFManager.CefException("no libcef")
                },
                startScope = backgroundScope,
            )
            assertEquals("no libcef", webViewRequest().await()?.message)
            assertEquals(1, starts)
        }
}
