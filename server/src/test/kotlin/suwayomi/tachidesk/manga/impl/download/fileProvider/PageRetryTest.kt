package suwayomi.tachidesk.manga.impl.download.fileProvider

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageRetryTest {
    private class Run(
        val result: Result<String>,
        val waits: List<Long>,
        val attempts: Int,
    )

    /** Runs a page whose n-th attempt throws failures[n], and succeeds once they run out. */
    private fun runPage(vararg failures: Throwable): Run {
        val waits = mutableListOf<Long>()
        var attempts = 0
        val result =
            runCatching {
                runBlocking {
                    withPageRetries(wait = { waits += it }) {
                        failures.getOrNull(attempts++)?.let { throw it }
                        "page"
                    }
                }
            }
        return Run(result, waits, attempts)
    }

    @Test
    fun `a page that fails twice with 503 is returned after waiting 2 then 4 seconds`() {
        val run = runPage(HttpException(503), HttpException(503))
        assertEquals("page", run.result.getOrThrow())
        assertEquals(listOf(2_000L, 4_000L), run.waits)
    }

    @Test
    fun `a page that keeps failing is given up after three retries`() {
        val run = runPage(*Array(10) { HttpException(500) })
        assertEquals(500, (run.result.exceptionOrNull() as HttpException).code)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), run.waits)
        assertEquals(4, run.attempts)
    }

    @Test
    fun `429 and io errors are retried`() {
        val run = runPage(HttpException(429), SocketTimeoutException("read timed out"))
        assertEquals("page", run.result.getOrThrow())
        assertEquals(2, run.waits.size)
    }

    @Test
    fun `an error caused by an io error is retried`() {
        val run = runPage(IllegalStateException("wrapped", IOException("connection reset")))
        assertEquals("page", run.result.getOrThrow())
        assertEquals(1, run.waits.size)
    }

    @Test
    fun `a 404 fails at once without waiting`() {
        val run = runPage(HttpException(404))
        assertEquals(404, (run.result.exceptionOrNull() as HttpException).code)
        assertEquals(emptyList(), run.waits)
        assertEquals(1, run.attempts)
    }

    @Test
    fun `stopping a download while a page waits to retry stops it at once`() =
        runBlocking {
            val job = launch { withPageRetries { throw HttpException(503) } }
            delay(200) // the page failed once and sits in its 2 s wait
            val started = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(System.nanoTime() - started < 1_000_000_000L, "cancel took too long")
            assertTrue(job.isCancelled)
        }
}
