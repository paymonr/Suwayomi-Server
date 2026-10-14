package suwayomi.tachidesk.manga.impl.download.fileProvider

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.concurrent.CancellationException

/** Fork-only (F9): the waits before each retry of a page. Three retries, then the chapter fails as before. */
val PAGE_RETRY_DELAYS_MS = listOf(2_000L, 4_000L, 8_000L)

/**
 * Whether a page failure may pass on a later try: I/O errors, HTTP 5xx and 429.
 * Anything else (404, a parse error) fails at once. `Retry-After` can't be honored: [HttpException] carries no headers.
 */
fun isRetryablePageFailure(e: Throwable): Boolean =
    when (e) {
        is CancellationException -> false
        is HttpException -> e.code == 429 || e.code in 500..599
        is IOException -> true
        else -> e.cause?.let(::isRetryablePageFailure) ?: false
    }

/** Runs [block], waiting [delaysMs] and retrying while it fails with a retryable error; the last error is rethrown. */
suspend fun <T> withPageRetries(
    delaysMs: List<Long> = PAGE_RETRY_DELAYS_MS,
    wait: suspend (Long) -> Unit = { delay(it) },
    block: suspend () -> T,
): T {
    var retries = 0
    while (true) {
        try {
            return block()
        } catch (e: Exception) {
            if (retries >= delaysMs.size || !isRetryablePageFailure(e)) throw e
            wait(delaysMs[retries++])
        }
    }
}
