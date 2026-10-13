package suwayomi.tachidesk.manga.impl.download.fileProvider

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PagePresenceTest {
    private val dir = Files.createTempDirectory("pages").toFile()

    @Test
    fun `a finished page counts`() {
        dir.resolve("001.jpg").writeBytes(byteArrayOf(1))
        assertTrue(isPageFilePresent(dir.path, "001"))
    }

    @Test
    fun `an interrupted page does not count`() {
        // ImageResponse.saveImage writes "<page>.tmp" first; a stop or crash can leave it behind
        dir.resolve("002.tmp").writeBytes(byteArrayOf(1))
        assertFalse(isPageFilePresent(dir.path, "002"))
    }

    @Test
    fun `a missing page does not count`() {
        assertFalse(isPageFilePresent(dir.path, "003"))
    }
}
