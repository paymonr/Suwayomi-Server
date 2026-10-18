package suwayomi.tachidesk.manga.impl.download.fileProvider

import eu.kanade.tachiyomi.network.HttpException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
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
 * [onRetry] hears about each retry before its wait: its number from 1, the wait, and the failure before it.
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

/** Fork-only (F9): one line per page retry, so a source's retries and their HTTP statuses can be counted from the log. */
fun logPageRetry(
    sourceId: Long,
    chapterId: Int,
    page: Int,
    retry: Int,
    waitMs: Long,
    cause: Throwable,
) {
    logger.info {
        "Page retry $retry/${PAGE_RETRY_DELAYS_MS.size} in ${waitMs / 1000} s: source=$sourceId chapter=$chapterId page=$page " +
            "after ${cause::class.simpleName}: ${cause.message}"
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
