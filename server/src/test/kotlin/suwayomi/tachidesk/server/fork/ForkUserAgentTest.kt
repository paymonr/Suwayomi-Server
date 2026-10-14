package suwayomi.tachidesk.server.fork

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForkUserAgentTest {
    @AfterEach
    fun clear() {
        System.clearProperty(ForkUserAgent.PROPERTY)
    }

    @Test
    fun `the default is a current Chrome`() {
        assertEquals(ForkUserAgent.DEFAULT, ForkUserAgent.value)
        assertTrue("Chrome/156.0.0.0" in ForkUserAgent.DEFAULT)
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
