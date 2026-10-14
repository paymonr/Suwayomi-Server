package suwayomi.tachidesk.server.fork

/**
 * Fork-only (F11): the User-Agent sources send until a Cloudflare solve replaces it.
 * Upstream's is Chrome 120 (Dec 2023). Override with `-Dsuwayomi.fork.userAgent=…`.
 */
object ForkUserAgent {
    const val PROPERTY = "suwayomi.fork.userAgent"
    const val DEFAULT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

    val value: String
        get() = System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() } ?: DEFAULT
}
