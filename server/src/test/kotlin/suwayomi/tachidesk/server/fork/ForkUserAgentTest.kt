package suwayomi.tachidesk.server.fork

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.KcefWebSettings
import kotlin.test.assertEquals

class ForkUserAgentTest {
    @AfterEach
    fun clear() {
        System.clearProperty(ForkUserAgent.PROPERTY)
    }

    @Test
    fun `without the property the default applies`() {
        assertEquals(ForkUserAgent.DEFAULT, ForkUserAgent.value)
    }

    /**
     * keiyoushi extensions (AllManga) give their WebViews client hints worked out from the user agent, except the brand
     * list (`Sec-CH-UA`), which keeps the glue's default; a user agent of another Chrome version then disagrees with it.
     */
    @Test
    fun `the default's Chrome version is the one the WebView's brand list claims`() {
        val chromium =
            KcefWebSettings
                .defaultUserAgentMetadata()
                .brands
                ?.single { it.brand == "Chromium" }
                ?.version
        assertEquals(chromium, Regex("""Chrome/(\d+)\.""").find(ForkUserAgent.DEFAULT)?.groupValues?.get(1), ForkUserAgent.DEFAULT)
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
