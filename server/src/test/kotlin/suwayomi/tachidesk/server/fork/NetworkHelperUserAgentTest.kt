package suwayomi.tachidesk.server.fork

import eu.kanade.tachiyomi.network.NetworkHelper
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.ApplicationTest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.test.assertEquals

class NetworkHelperUserAgentTest : ApplicationTest() {
    @Test
    fun `sources start with the fork's default user agent`() {
        assertEquals(ForkUserAgent.DEFAULT, Injekt.get<NetworkHelper>().defaultUserAgentProvider())
    }
}
