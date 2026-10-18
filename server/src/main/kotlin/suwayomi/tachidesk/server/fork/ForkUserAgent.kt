package suwayomi.tachidesk.server.fork

/**
 * Fork-only (F11): the User-Agent sources send until a Cloudflare solve replaces it. Override with
 * `-Dsuwayomi.fork.userAgent=…`. The default is upstream's since its WebView glue (#2357): Android Chrome 149, as Mihon
 * sends, matching the client hints WebViews send (`KcefWebSettings.defaultUserAgentMetadata`).
 */
object ForkUserAgent {
    const val PROPERTY = "suwayomi.fork.userAgent"
    const val DEFAULT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"

    val value: String
        get() = System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() } ?: DEFAULT
}
