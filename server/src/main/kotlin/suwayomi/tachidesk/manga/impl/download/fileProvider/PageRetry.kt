package suwayomi.tachidesk.manga.impl.download.fileProvider

import eu.kanade.tachiyomi.network.HttpException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.manga.impl.fork.ChapterDownloadStats
import java.io.IOException
import java.util.concurrent.CancellationException

private val logger = KotlinLogging.logger {}

/** Fork-only (F9): the waits before each retry of a page. Three retries, then the chapter fails as before. */
val PAGE_RETRY_DELAYS_MS = listOf(2_000L, 4_000L, 8_000L)

private const val MAX_CAUSES = 16

private const val WAIT_CHECK_EVERY_MS = 500L

/**
 * Whether a page failure may pass on a later try: network I/O errors, and HTTP 5xx and 429 from the page itself.
 * Anything else (404, a parse error) fails at once. `Retry-After` can't be honored: [HttpException] carries no headers.
 */
fun isRetryablePageFailure(e: Throwable): Boolean =
    when (e) {
        is CancellationException -> false

        is HttpException -> e.code == 429 || e.code in 500..599

        // OkHttp's await and UncaughtExceptionInterceptor wrap an interceptor's failure (e.g. a failed Cloudflare solve, or
        // the solver's own HttpException) in IOExceptions. Only a chain of I/O errors alone is a network failure.
        is IOException -> generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSES).all { it is IOException }

        else -> e.cause?.let(::isRetryablePageFailure) ?: false
    }

/**
 * Runs [block], waiting [delaysMs] and retrying while it fails with a retryable error; the last error is rethrown.
 * [onRetry] hears about each retry before its wait: its number from 1, the wait, and the failure before it. It must not
 * throw: its exception would replace the page's failure and end the retries.
 */
suspend fun <T> withPageRetries(
    delaysMs: List<Long> = PAGE_RETRY_DELAYS_MS,
    wait: suspend (Long) -> Unit = { delay(it) },
    onRetry: (retry: Int, waitMs: Long, cause: Exception) -> Unit = { _, _, _ -> },
    block: suspend () -> T,
): T {
    var retries = 0
    while (true) {
        try {
            return block()
        } catch (e: Exception) {
            if (retries >= delaysMs.size || !isRetryablePageFailure(e)) throw e
            val waitMs = delaysMs[retries++]
            onRetry(retries, waitMs, e)
            wait(waitMs)
        }
    }
}

/** [withPageRetries]' `onRetry` for page [page] (from 0) of a queued chapter: logs each retry with the chapter's source. */
fun pageRetryReporter(
    download: DownloadQueueItem,
    page: Int,
): (Int, Long, Exception) -> Unit =
    { retry, waitMs, cause ->
        ChapterDownloadStats.countPageRetry(download.chapterId) // Fork (download speeds)
        logPageRetry(
            sourceId = download.sourceId,
            chapterId = download.chapterId,
            page = page,
            retry = retry,
            waitMs = waitMs,
            cause = cause,
        )
    }

private const val MAX_LOGGED_MESSAGE = 200

/**
 * Fork-only (F9): one line per page retry, so a source's retries and their HTTP statuses can be counted from the log.
 * [page] counts from 0 and is logged from 1, as page files and readers count. A failure that wraps others (OkHttp wraps
 * network errors in an IOException) also names the innermost one. Only the message's first line is logged, cut short.
 */
fun logPageRetry(
    sourceId: Long,
    chapterId: Int,
    page: Int,
    retry: Int,
    waitMs: Long,
    cause: Throwable,
) {
    val innermost = generateSequence(cause) { it.cause }.take(MAX_CAUSES).last()
    val kind = if (innermost === cause) "${cause::class.simpleName}" else "${cause::class.simpleName} (${innermost::class.simpleName})"
    val message =
        cause.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(MAX_LOGGED_MESSAGE)
    logger.info {
        "Page retry $retry/${PAGE_RETRY_DELAYS_MS.size} in ${waitMs / 1000} s: source=$sourceId chapter=$chapterId page=${page + 1} " +
            "after $kind: $message"
    }
}

/** Waits [ms], calling [check] every half second so a check that throws (the chapter was dequeued or moved down) ends the wait. */
suspend fun delayChecking(
    ms: Long,
    check: suspend () -> Unit,
) {
    var left = ms
    while (left > 0) {
        val slice = minOf(left, WAIT_CHECK_EVERY_MS)
        delay(slice)
        left -= slice
        check()
    }
}
