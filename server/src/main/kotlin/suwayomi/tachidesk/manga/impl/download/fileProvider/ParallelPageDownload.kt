package suwayomi.tachidesk.manga.impl.download.fileProvider

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Fork-only: how many pages of one chapter the downloader fetches at once. `-Dsuwayomi.fork.downloadParallelPages=N`, 1–8. */
object ForkDownloadSettings {
    const val PARALLEL_PAGES_PROPERTY = "suwayomi.fork.downloadParallelPages"
    const val DEFAULT_PARALLEL_PAGES = 3

    val parallelPages: Int
        get() = System.getProperty(PARALLEL_PAGES_PROPERTY)?.toIntOrNull()?.coerceIn(1, 8) ?: DEFAULT_PARALLEL_PAGES
}

/**
 * Downloads every page in `0 until pageCount` that [isDone] reports missing, at most [parallelism] at a time.
 * Site rate limits still apply: they live in each extension's HTTP client.
 * [onPageDone] gets the number of finished pages, including ones already done, and it only ever goes up.
 * The first failure cancels the remaining pages and is rethrown.
 */
suspend fun downloadPagesInParallel(
    pageCount: Int,
    parallelism: Int,
    isDone: suspend (pageIndex: Int) -> Boolean,
    download: suspend (pageIndex: Int) -> Unit,
    onPageDone: suspend (finishedPages: Int) -> Unit,
) {
    val missing = (0 until pageCount).filterNot { isDone(it) }
    var finished = pageCount - missing.size
    val progressLock = Mutex()
    val permits = Semaphore(parallelism.coerceAtLeast(1))
    coroutineScope {
        missing
            .map { pageIndex ->
                async {
                    permits.withPermit { download(pageIndex) }
                    progressLock.withLock { onPageDone(++finished) }
                }
            }.awaitAll()
    }
}
