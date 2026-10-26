package suwayomi.tachidesk.graphql

import org.junit.jupiter.api.Test
import suwayomi.tachidesk.test.ApplicationTest

class ForkDownloadSpeedQueryTest : ApplicationTest() {
    @Test
    fun `downloadSpeedBySource answers per source for the window`() =
        ForkDownloadSpeedQueryCases.answersPerSourceForTheWindow(System.nanoTime())

    @Test
    fun `downloadSpeedByDay answers per local day for one source`() =
        ForkDownloadSpeedQueryCases.answersPerDayForOneSource(System.nanoTime(), System.nanoTime())

    @Test
    fun `downloadSpeedBySource answers without until`() = ForkDownloadSpeedQueryCases.answersWithoutUntil(System.nanoTime())

    @Test
    fun `downloadSpeedByDay refuses days outside 1 to 365`() = ForkDownloadSpeedQueryCases.refusesDaysOutOfRange()
}
