package suwayomi.tachidesk.server.fork

/**
 * Fork-only (F11): the User-Agent sources send until a Cloudflare solve replaces it. Override with
 * `-Dsuwayomi.fork.userAgent=…`. The default is upstream's since its WebView glue (#2357): Android Chrome 149, as Mihon
 * sends. Keep an override on Chrome 149: extensions that set WebView client hints (keiyoushi's) work them out from this
 * string, but the brand list stays the glue's Chromium 149 (`KcefWebSettings.defaultUserAgentMetadata`).
 */
object ForkUserAgent {
    const val PROPERTY = "suwayomi.fork.userAgent"
    const val DEFAULT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"

    val value: String
        get() = System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() } ?: DEFAULT
}
