package suwayomi.tachidesk.manga.impl.fork

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.MangaList.insertOrUpdate
import suwayomi.tachidesk.test.ApplicationTest
import java.time.Instant
import kotlin.test.assertEquals

class RecentlyShownMangaTest : ApplicationTest() {
    private val t0 = 2_000_000_000L
    private val grace = NonLibraryMangaPrune.GRACE_SECONDS

    @Test
    fun `a series shown within the grace period is returned and one shown before it is not`() {
        val register = ShownMangaRegister()
        register.record(listOf(1), t0 - grace - 1)
        register.record(listOf(2), t0 - 3_600)

        assertEquals(setOf(2), register.since(t0 - grace))
    }

    @Test
    fun `showing a series again moves its time forward, never back`() {
        val register = ShownMangaRegister()
        register.record(listOf(1), t0)
        register.record(listOf(1), t0 - 2 * grace)

        assertEquals(setOf(1), register.since(t0 - 3_600))
    }

    @Test
    fun `once over its size the register forgets series shown before the grace period`() {
        val register = ShownMangaRegister(evictAbove = 2)
        register.record(listOf(1, 2), t0)
        register.record(listOf(3), t0 + grace + 1)

        assertEquals(setOf(3), register.since(0))
    }

    @Test
    fun `browsing a source records the series it listed`() {
        val listed =
            List(3) { n ->
                SManga.create().apply {
                    url = "/shown-$n-${System.nanoTime()}"
                    title = "shown $n"
                }
            }

        val ids = MangasPage(listed, false).insertOrUpdate(sourceId = 7_000_120L)

        assertEquals(emptySet(), ids.toSet() - RecentlyShownManga.since(Instant.now().epochSecond - 60))
    }
}
