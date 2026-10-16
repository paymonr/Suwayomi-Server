package suwayomi.tachidesk.manga.impl.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.graphql.dataAt
import suwayomi.tachidesk.graphql.executeGraphQL
import suwayomi.tachidesk.graphql.types.DownloadFailureType
import suwayomi.tachidesk.graphql.types.DownloadType
import suwayomi.tachidesk.manga.impl.download.model.DownloadFailure
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdate
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdateType
import suwayomi.tachidesk.manga.impl.util.source.GetSource.registerSource
import suwayomi.tachidesk.manga.impl.util.source.GetSource.unregisterSource
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DownloadFailureReasonTest : ApplicationTest() {
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

    @Test
    fun `a chapter that fails three times reaches ERROR with its failure`() {
        val source = FakePageSource(7_000_300L, 3) { _, _ -> 404 }
        registerSource(source.id to source)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val c = createChapterWithPages(source.id, 3, "E4 ${source.id}")
            val item = DownloadQueueItem(c.chapterId, 1, c.mangaId, source.id, 3)
            val updates = CopyOnWriteArrayList<DownloadUpdate>()
            val done = CompletableDeferred<Unit>()
            Downloader(scope, source.id, CopyOnWriteArrayList(listOf(item)), { _, u -> u?.let(updates::add) }, { done.complete(Unit) }, {})
                .start()
            runBlocking { withTimeout(30_000) { done.await() } }

            val error = updates.single { it.type == DownloadUpdateType.ERROR }.downloadQueueItem
            assertEquals(3, error.tries)
            assertEquals(DownloadFailure("eu.kanade.tachiyomi.network.HttpException", "HTTP error 404"), error.failure)
        } finally {
            scope.cancel()
            unregisterSource(source.id)
        }
    }

    @Test
    fun `DownloadType carries the queue item's failure`() {
        val item = DownloadQueueItem(1, 1, 1, 1L, 3).apply { failure = DownloadFailure("x.Boom", "it broke") }
        assertEquals(DownloadFailureType("x.Boom", "it broke"), DownloadType(item, 0).failureReason)
    }

    /** Introspection, not downloadStatus: that reads the live DownloadManager. */
    @Test
    fun `failureReason is in the schema`() {
        val result = executeGraphQL("""{ __type(name: "DownloadType") { fields { name } } }""")
        assertEquals(emptyList(), result.errors)
        @Suppress("UNCHECKED_CAST")
        val names = (result.dataAt("__type", "fields") as List<Map<String, Any?>>).map { it["name"] }
        assertTrue("failureReason" in names, names.toString())
    }
}
