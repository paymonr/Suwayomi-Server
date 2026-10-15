package suwayomi.tachidesk.server.util

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fork-only (F10a): Chromium starts on the first WebView request instead of at server start. On main (2026-10) its
 * helper processes alone take ~115 MiB, and most of the time nothing needs them.
 */
object ForkLazyChromium {
    private val logger = KotlinLogging.logger {}

    const val ENABLED_PROPERTY = "suwayomi.fork.lazyChromium"

    /** On unless `-Dsuwayomi.fork.lazyChromium=false` (any case). A value it can't read is logged and leaves it on. */
    val enabled: Boolean
        get() =
            when (val value = System.getProperty(ENABLED_PROPERTY)?.trim()?.lowercase()) {
                null, "", "true" -> true
                "false" -> false
                else -> true.also { logger.warn { "Ignoring -D$ENABLED_PROPERTY=$value: expected true or false" } }
            }

    /**
     * Runs [start] once in [scope], as soon as something collects [app]. Every WebView waits on [app]
     * (`CefHelper.cefApp`) until it holds a CEF app, so its first collector is the first WebView request, and
     * AndroidCompat needs no change. Cancel the returned job to drop a start that hasn't happened yet.
     */
    fun startOnFirstWaiter(
        scope: CoroutineScope,
        app: MutableStateFlow<*>,
        start: suspend () -> Unit,
    ): Job =
        scope.launch {
            app.subscriptionCount.first { it > 0 }
            logger.info { "Starting Chromium for the first WebView request" }
            start()
        }
}
