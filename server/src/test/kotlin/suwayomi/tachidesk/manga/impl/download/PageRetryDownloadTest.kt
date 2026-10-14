package suwayomi.tachidesk.manga.impl.download

import eu.kanade.tachiyomi.network.HttpException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.util.source.GetSource.registerSource
import suwayomi.tachidesk.manga.impl.util.source.GetSource.unregisterSource
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** F9 end to end: real waits (2 s + 4 s) through ChapterDownloadHelper. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageRetryDownloadTest : ApplicationTest() {
    private val pageCount = 3
    private var nextSourceId = 7_000_090L
    private var previousCbz = false

    @BeforeAll
    fun setUp() {
        previousCbz = serverConfig.downloadAsCbz.value
        serverConfig.downloadAsCbz.value = false
    }

    @AfterAll
    fun tearDown() {
        serverConfig.downloadAsCbz.value = previousCbz
    }

    private fun withSource(
        statusFor: (page: Int, attempt: Int) -> Int,
        block: (FakePageSource, FakeChapter) -> Unit,
    ) {
        val source = FakePageSource(nextSourceId++, pageCount, statusFor)
        registerSource(source.id to source)
        try {
            block(source, createChapterWithPages(source.id, pageCount, "F9 ${source.id}"))
        } finally {
            unregisterSource(source.id)
        }
    }

    @Test
    fun `a page that answers 503 twice is retried and the chapter completes`() =
        withSource({ page, attempt -> if (page == 1 && attempt <= 2) 503 else 200 }) { source, c ->
            val progress = downloadChapter(c, source.id, pageCount)
            assertEquals(3, source.attemptsFor(1))
            assertEquals(1f, progress.last())
            assertContentEquals(FakePageSource.pageBytes(1), pageAsRead(c, 1))
        }

    @Test
    fun `a missing page fails the chapter without retrying`() =
        withSource({ page, _ -> if (page == 2) 404 else 200 }) { source, c ->
            val e = assertFailsWith<HttpException> { downloadChapter(c, source.id, pageCount) }
            assertEquals(404, e.code)
            assertEquals(1, source.attemptsFor(2))
        }
}
