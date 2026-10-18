package suwayomi.tachidesk.manga.impl.download.fileProvider

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageRetryTest {
    private class Run(
        val result: Result<String>,
        val waits: List<Long>,
        val attempts: Int,
        val reported: List<Triple<Int, Long, Throwable>>,
    )

    /** Runs a page whose n-th attempt throws failures[n], and succeeds once they run out. */
    private fun runPage(vararg failures: Throwable): Run {
        val waits = mutableListOf<Long>()
        val reported = mutableListOf<Triple<Int, Long, Throwable>>()
        var attempts = 0
        val result =
            runCatching {
                runBlocking {
                    withPageRetries(wait = { waits += it }, onRetry = {
                        retry,
                        waitMs,
                        cause,
                        ->
                        reported += Triple(retry, waitMs, cause)
                    }) {
                        failures.getOrNull(attempts++)?.let { throw it }
                        "page"
                    }
                }
            }
        return Run(result, waits, attempts, reported)
    }

    @Test
    fun `each retry is reported with its number, its wait and the failure before it`() {
        val first = HttpException(429)
        val second = SocketTimeoutException("read timed out")
        val run = runPage(first, second)
        assertEquals(listOf(Triple(1, 2_000L, first), Triple(2, 4_000L, second)), run.reported)
    }

    @Test
    fun `a page given up after three retries reports the three retries, not the last failure`() {
        val run = runPage(*Array(10) { HttpException(502) })
        assertEquals(listOf(1, 2, 3), run.reported.map { it.first })
    }

    @Test
    fun `a failure that is not retried is not reported`() {
        assertEquals(emptyList(), runPage(HttpException(404)).reported)
    }

    @Test
    fun `the retry log line names the source, chapter, page and HTTP status`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            logPageRetry(
                sourceId = 2131019126180322627L,
                chapterId = 673520,
                page = 4,
                retry = 1,
                waitMs = 2_000L,
                cause = HttpException(429),
            )
        } finally {
            root.detachAppender(appender)
        }
        val line = appender.list.single { it.formattedMessage.startsWith("Page retry") }
        assertEquals(Level.INFO, line.level)
        assertEquals(
            "Page retry 1/3 in 2 s: source=2131019126180322627 chapter=673520 page=4 after HttpException: HTTP error 429",
            line.formattedMessage,
        )
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
    fun `a network error wrapped by the network layer is retried`() {
        val run = runPage(IOException("reset", IOException("connection reset")))
        assertEquals("page", run.result.getOrThrow())
        assertEquals(1, run.waits.size)
    }

    /** OkHttp's await and UncaughtExceptionInterceptor wrap an interceptor's failure in IOExceptions; retrying it repeats the solve. */
    @Test
    fun `a failure inside an interceptor, such as a failed Cloudflare solve, is not retried`() {
        val run = runPage(IOException("solve failed", IOException(IllegalStateException("cloudflare bypass failed"))))
        assertEquals(1, run.attempts)
        assertEquals(emptyList(), run.waits)
    }

    @Test
    fun `a solver's own HTTP error wrapped by the network layer is not retried`() {
        val run = runPage(IOException("solver", IOException(HttpException(500))))
        assertEquals(1, run.attempts)
        assertEquals(emptyList(), run.waits)
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
