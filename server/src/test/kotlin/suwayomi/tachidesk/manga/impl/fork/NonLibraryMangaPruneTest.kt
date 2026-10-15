package suwayomi.tachidesk.manga.impl.fork

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The checks shared by the H2 and Postgres classes. */
object PruneChecks {
    fun dryRunMatchesAndDeletesNothing() {
        val ids = PruneFixture.create()
        val queued = setOf(ids.queued)
        val exclude = setOf(ids.excluded)
        val before = PruneFixture.allMangaIds()

        val result =
            NonLibraryMangaPrune.prune(
                exclude,
                dryRun = true,
                nowEpochSeconds = PruneFixture.NOW,
                queuedMangaIds = { queued },
                coverDirs = emptyList(),
            )

        val expected = PruneFixture.expected(exclude, queued)
        assertEquals(expected, result.mangaIds.toSet())
        assertEquals(PruneFixture.chaptersOf(expected), result.chapterCount)
        assertEquals(before, PruneFixture.allMangaIds())
    }

    fun realRunDeletesPrunableWithChaptersAndPages() {
        val ids = PruneFixture.create()
        val fixtureIds = ids.prunable + ids.kept.values + ids.excluded + ids.queued
        val exclude = PruneFixture.allMangaIds() - fixtureIds.toSet() + ids.excluded

        val result =
            NonLibraryMangaPrune.prune(
                exclude,
                dryRun = false,
                nowEpochSeconds = PruneFixture.NOW,
                queuedMangaIds = { setOf(ids.queued) },
                coverDirs = emptyList(),
            )

        assertEquals(ids.prunable.toSet(), result.mangaIds.toSet())
        assertEquals(4L, result.chapterCount) // two prunable series with 2 chapters each, one with none
        assertEquals(emptySet(), PruneFixture.existing(ids.prunable))
        assertEquals(0L, PruneFixture.chaptersOf(ids.prunable))
        assertEquals(0L, PruneFixture.pagesOf(ids.prunable))
    }

    fun everyKeptCaseSurvivesARealRun() {
        val ids = PruneFixture.create()
        val fixtureIds = ids.prunable + ids.kept.values + ids.excluded + ids.queued
        val exclude = PruneFixture.allMangaIds() - fixtureIds.toSet() + ids.excluded

        NonLibraryMangaPrune.prune(
            exclude,
            dryRun = false,
            nowEpochSeconds = PruneFixture.NOW,
            queuedMangaIds = { setOf(ids.queued) },
            coverDirs = emptyList(),
        )

        val survivors = ids.kept + mapOf("excluded" to ids.excluded, "queued" to ids.queued)
        val gone = survivors.filterValues { it !in PruneFixture.existing(survivors.values) }.keys
        assertEquals(emptySet(), gone, "deleted but should have been kept")
        survivors.values.forEach { assertTrue(PruneFixture.chaptersOf(listOf(it)) == 2L, "chapters of $it") }
    }

    /** Review finding: a batch is deleted minutes after the candidates are listed; a series touched in between must survive. */
    fun aSeriesTouchedAfterListingSurvivesItsBatch() {
        val ids = PruneFixture.create()
        val (touched, untouched) = ids.prunable
        val fetchedBefore = PruneFixture.NOW - NonLibraryMangaPrune.GRACE_SECONDS
        transaction { MangaTable.update({ MangaTable.id eq touched }) { it[inLibrary] = true } }

        val result = NonLibraryMangaPrune.pruneBatch(listOf(touched, untouched), fetchedBefore, emptySet(), emptySet(), dryRun = false)

        assertEquals(listOf(untouched), result.mangaIds)
        assertEquals(setOf(touched), PruneFixture.existing(listOf(touched, untouched)))
        assertEquals(2L, PruneFixture.chaptersOf(listOf(touched)))
    }
}

class NonLibraryMangaPruneTest : ApplicationTest() {
    @Test
    fun `a series touched after the candidates are listed survives its batch`() = PruneChecks.aSeriesTouchedAfterListingSurvivesItsBatch()

    @Test
    fun `a dry run counts what a real run would delete and deletes nothing`() = PruneChecks.dryRunMatchesAndDeletesNothing()

    @Test
    fun `a real run deletes untouched non-library series with their chapters and pages`() =
        PruneChecks.realRunDeletesPrunableWithChaptersAndPages()

    @Test
    fun `every kept case survives a real run`() = PruneChecks.everyKeptCaseSurvivesARealRun()

    @Test
    fun `cached covers of pruned series are deleted and others kept`() {
        val ids = PruneFixture.create()
        val fixtureIds = ids.prunable + ids.kept.values + ids.excluded + ids.queued
        val exclude = PruneFixture.allMangaIds() - fixtureIds.toSet() + ids.excluded
        val pruned = ids.prunable.first()
        val kept = ids.kept.getValue("library")
        val dir = createTempDirectory("f12-covers").toFile()
        val prunedCover = File(dir, "$pruned.jpeg").apply { writeText("x") }
        val prunedPartial = File(dir, "$pruned.tmp").apply { writeText("x") }
        val keptCover = File(dir, "$kept.png").apply { writeText("x") }
        val lookalike = File(dir, "${pruned}0.jpg").apply { writeText("x") } // another id that starts with the same digits

        NonLibraryMangaPrune.prune(
            exclude,
            dryRun = false,
            nowEpochSeconds = PruneFixture.NOW,
            queuedMangaIds = { setOf(ids.queued) },
            coverDirs = listOf(dir),
        )

        assertEquals(listOf(false, false, true, true), listOf(prunedCover, prunedPartial, keptCover, lookalike).map { it.exists() })
    }
}
