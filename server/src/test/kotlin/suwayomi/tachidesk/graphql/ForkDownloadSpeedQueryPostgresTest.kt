package suwayomi.tachidesk.graphql

import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.PostgresTest

class ForkDownloadSpeedQueryPostgresTest : PostgresTest() {
    @Test
    fun `downloadSpeedBySource answers per source for the window`() =
        ForkDownloadSpeedQueryCases.answersPerSourceForTheWindow(System.nanoTime())

    @Test
    fun `downloadSpeedByDay answers per local day for one source`() =
        ForkDownloadSpeedQueryCases.answersPerDayForOneSource(System.nanoTime(), System.nanoTime())
}
