package suwayomi.tachidesk.manga.impl.fork

import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.PostgresTest

class NonLibraryMangaPrunePostgresTest : PostgresTest() {
    @Test
    fun `a dry run counts what a real run would delete and deletes nothing`() = PruneChecks.dryRunMatchesAndDeletesNothing()

    @Test
    fun `a real run deletes untouched non-library series with their chapters and pages`() =
        PruneChecks.realRunDeletesPrunableWithChaptersAndPages()

    @Test
    fun `every kept case survives a real run`() = PruneChecks.everyKeptCaseSurvivesARealRun()

    @Test
    fun `a series touched after the candidates are listed survives its batch`() = PruneChecks.aSeriesTouchedAfterListingSurvivesItsBatch()
}
