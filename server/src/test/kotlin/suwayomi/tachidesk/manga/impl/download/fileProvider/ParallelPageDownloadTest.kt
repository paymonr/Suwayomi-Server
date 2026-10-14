package suwayomi.tachidesk.manga.impl.download.fileProvider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.download.Downloader
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParallelPageDownloadTest {
    @Test
    fun `every missing page is downloaded once and done pages are skipped`() =
        runTest {
            val downloaded = Collections.synchronizedList(mutableListOf<Int>())
            downloadPagesInParallel(
                pageCount = 10,
                parallelism = 3,
                isDone = { it == 0 || it == 7 },
                download = { downloaded += it },
                onPageDone = {},
            )
            assertEquals((0 until 10).filter { it != 0 && it != 7 }, downloaded.sorted())
        }

    @Test
    fun `never more than parallelism pages at once`() =
        runTest {
            val inFlight = AtomicInteger()
            val maxInFlight = AtomicInteger()
            downloadPagesInParallel(20, parallelism = 3, isDone = { false }, download = {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
                delay(10)
                inFlight.decrementAndGet()
            }, onPageDone = {})
            assertEquals(3, maxInFlight.get())
        }

    @Test
    fun `progress only goes up and ends at the page count`() =
        runTest {
            val progress = Collections.synchronizedList(mutableListOf<Int>())
            downloadPagesInParallel(12, parallelism = 4, isDone = { it < 2 }, download = { delay((12 - it).toLong()) }, onPageDone = {
                progress +=
                    it
            })
            assertEquals((3..12).toList(), progress)
        }

    @Test
    fun `a failure cancels the other pages and is rethrown`() =
        runTest {
            val cancelled = AtomicInteger()
            assertFailsWith<IllegalStateException> {
                downloadPagesInParallel(6, parallelism = 3, isDone = { false }, download = { page ->
                    if (page == 1) error("page 1 failed")
                    try {
                        delay(10_000)
                    } catch (e: CancellationException) {
                        cancelled.incrementAndGet()
                        throw e
                    }
                }, onPageDone = {})
            }
            assertTrue(cancelled.get() >= 1)
        }

    @Test
    fun `parallelism is read from the fork property and clamped`() {
        val key = ForkDownloadSettings.PARALLEL_PAGES_PROPERTY
        try {
            System.clearProperty(key)
            assertEquals(3, ForkDownloadSettings.parallelPages)
            System.setProperty(key, "50")
            assertEquals(8, ForkDownloadSettings.parallelPages)
            System.setProperty(key, "0")
            assertEquals(1, ForkDownloadSettings.parallelPages)
        } finally {
            System.clearProperty(key)
        }
    }

    @Test
    fun `page downloads run on the IO dispatcher`() =
        runTest {
            val interceptors = Collections.synchronizedList(mutableListOf<CoroutineContext.Element?>())
            downloadPagesInParallel(3, parallelism = 2, isDone = { false }, download = {
                interceptors += currentCoroutineContext()[ContinuationInterceptor]
            }, onPageDone = {})
            assertEquals(List(3) { Dispatchers.IO }, interceptors.toList())
        }

    /** Stop and Pause reach the helper as exceptions thrown by the downloader's step() inside onPageDone. */
    private fun stopOrPauseCancelsPagesInFlight(signal: () -> Exception) =
        runTest {
            val started = AtomicInteger()
            val cancelled = AtomicInteger()
            val finishedAfterSignal = AtomicInteger()
            val thrown =
                runCatching {
                    downloadPagesInParallel(6, parallelism = 3, isDone = { false }, download = { page ->
                        if (page != 0) {
                            started.incrementAndGet()
                            try {
                                delay(10_000)
                            } catch (e: CancellationException) {
                                cancelled.incrementAndGet()
                                throw e
                            }
                            finishedAfterSignal.incrementAndGet()
                        }
                    }, onPageDone = { throw signal() })
                }.exceptionOrNull()
            assertEquals(signal()::class, thrown?.let { it::class })
            assertEquals(started.get(), cancelled.get())
            assertEquals(0, finishedAfterSignal.get())
        }

    @Test
    fun `a stop from onPageDone cancels the pages in flight and is rethrown`() =
        stopOrPauseCancelsPagesInFlight { Downloader.StopDownloadException() }

    @Test
    fun `a pause from onPageDone cancels the pages in flight and is rethrown`() =
        stopOrPauseCancelsPagesInFlight { Downloader.PauseDownloadException() }

    @Test
    fun `cancelling the downloader job cancels the pages in flight`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cancelled = AtomicInteger()
            val job =
                launch(Dispatchers.Default) {
                    downloadPagesInParallel(4, parallelism = 2, isDone = { false }, download = {
                        started.complete(Unit)
                        try {
                            delay(10_000)
                        } catch (e: CancellationException) {
                            cancelled.incrementAndGet()
                            throw e
                        }
                    }, onPageDone = {})
                }
            started.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(cancelled.get() >= 1)
        }
}
