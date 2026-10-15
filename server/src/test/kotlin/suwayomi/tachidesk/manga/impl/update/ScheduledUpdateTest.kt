package suwayomi.tachidesk.manga.impl.update

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.impl.update.SmartUpdateFixture.DAY
import suwayomi.tachidesk.manga.impl.update.SmartUpdateFixture.HOUR
import suwayomi.tachidesk.manga.impl.update.SmartUpdateFixture.series
import suwayomi.tachidesk.manga.model.dataclass.IncludeOrExclude
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** F8 through the real Updater. The fixture's source doesn't exist, so every check fails at once and a run ends quickly. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduledUpdateTest : ApplicationTest() {
    private val updater by lazy { Injekt.get<IUpdater>() as Updater }
    private val exclusions by lazy {
        listOf(serverConfig.excludeUnreadChapters, serverConfig.excludeNotStarted, serverConfig.excludeCompleted)
    }
    private var previous = emptyList<Boolean>()
    private var categoryId = 0

    @BeforeAll
    fun setUp() {
        // upstream's exclusions are on by default and would drop these unread series before F8 sees them
        previous = exclusions.map { it.value }
        exclusions.forEach { it.value = false }
        // The series go in their own category, set to be included in updates. Another test class deletes every
        // category, Default too, from the shared test database, and an included category keeps other classes'
        // library series out of these runs.
        categoryId = Category.createCategory("smart update ${System.nanoTime()}")
        Category.updateCategory(categoryId, null, null, IncludeOrExclude.INCLUDE.value, null)
    }

    @AfterEach
    fun clearSeries() {
        updater.reset()
        SmartUpdateFixture.clear()
    }

    @AfterAll
    fun tearDown() {
        exclusions.zip(previous).forEach { (setting, value) -> setting.value = value }
        System.clearProperty(ForkSmartUpdate.ENABLED_PROPERTY)
        Category.removeCategory(categoryId)
    }

    private inner class Fixture {
        private val now = Instant.now().epochSecond
        val active = series("active", now - 2 * DAY, now - HOUR)
        val quiet = series("quiet, checked 2 h ago", now - 60 * DAY, now - 2 * HOUR)
        val dormantDue = series("dormant, checked 4 d ago", now - 200 * DAY, now - 4 * DAY)
        val noChapters = series("no chapters", null, now - HOUR)
        val all = listOf(active, quiet, dormantDue, noChapters)

        init {
            all.forEach { CategoryManga.addMangaToCategory(it, categoryId) }
        }
    }

    /** Starts a run with [start], waits for it to end, and returns which of [ids] it checked. */
    private fun checkedBy(
        ids: List<Int>,
        start: () -> Unit,
    ): Set<Int> {
        updater.reset()
        start()
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val status = updater.getStatus()
            // every job is tracked (PENDING) before any runs, so "jobs exist and none is pending or running" means done
            if (status.totalJobs > 0 && !status.isRunning) {
                return status.mangaUpdates
                    .map { it.manga.id }
                    .filter { it in ids }
                    .toSet()
            }
            Thread.sleep(50)
        }
        fail("the update run didn't finish within 60 s")
    }

    @Test
    fun `a scheduled run skips a quiet series that was checked recently`() {
        val f = Fixture()
        assertEquals(setOf(f.active, f.dormantDue, f.noChapters), checkedBy(f.all) { updater.autoUpdateTask() })
    }

    @Test
    fun `a manual update checks every series`() {
        val f = Fixture()
        val checked =
            checkedBy(f.all) { updater.addCategoriesToUpdateQueue(Category.getCategoryList(), clear = true, forceAll = false) }
        assertEquals(f.all.toSet(), checked)
    }

    @Test
    fun `with smart updates off a scheduled run checks every series`() {
        val f = Fixture()
        System.setProperty(ForkSmartUpdate.ENABLED_PROPERTY, "false")
        try {
            assertEquals(f.all.toSet(), checkedBy(f.all) { updater.autoUpdateTask() })
        } finally {
            System.clearProperty(ForkSmartUpdate.ENABLED_PROPERTY)
        }
    }

    /**
     * Characterization, Review Focus 1: a failed check doesn't move `chapters_last_fetched_at`, so a failing series is
     * checked again on the next run. Passes before and after the fix.
     */
    @Test
    fun `a series whose check failed is checked again on the next scheduled run`() {
        val f = Fixture()
        checkedBy(f.all) { updater.autoUpdateTask() }
        assertTrue(f.dormantDue in checkedBy(f.all) { updater.autoUpdateTask() })
    }
}
