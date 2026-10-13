package suwayomi.tachidesk.manga.impl.download.fileProvider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
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
}
