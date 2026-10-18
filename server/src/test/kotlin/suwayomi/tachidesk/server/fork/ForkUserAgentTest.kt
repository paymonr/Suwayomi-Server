package suwayomi.tachidesk.server.fork

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.KcefWebSettings
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForkUserAgentTest {
    @AfterEach
    fun clear() {
        System.clearProperty(ForkUserAgent.PROPERTY)
    }

    @Test
    fun `without the property the default applies`() {
        assertEquals(ForkUserAgent.DEFAULT, ForkUserAgent.value)
    }

    /** WebViews send the user agent with these client hints unless an extension sets its own; a string that disagrees looks like a bot. */
    @Test
    fun `the default agrees with the WebView's default client hints`() {
        val hints = KcefWebSettings.defaultUserAgentMetadata()
        val ua = ForkUserAgent.DEFAULT
        assertTrue("(Linux; ${hints.platform}" in ua, "platform ${hints.platform}: $ua")
        assertEquals(hints.mobile, " Mobile " in ua, "mobile ${hints.mobile}: $ua")
        assertEquals(
            hints.fullVersion?.substringBefore('.'),
            Regex("""Chrome/(\d+)\.""").find(ua)?.groupValues?.get(1),
            "Chrome version: $ua",
        )
    }

    @Test
    fun `the property overrides it`() {
        System.setProperty(ForkUserAgent.PROPERTY, "Test/1.0")
        assertEquals("Test/1.0", ForkUserAgent.value)
    }

    @Test
    fun `a blank property is ignored`() {
        System.setProperty(ForkUserAgent.PROPERTY, " ")
        assertEquals(ForkUserAgent.DEFAULT, ForkUserAgent.value)
    }
}
