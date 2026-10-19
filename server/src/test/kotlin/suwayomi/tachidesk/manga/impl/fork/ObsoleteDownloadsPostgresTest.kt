package suwayomi.tachidesk.manga.impl.fork

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.fork.ObsoleteDownloadsScenarios.source
import suwayomi.tachidesk.manga.impl.util.source.GetSource.registerSource
import suwayomi.tachidesk.manga.impl.util.source.GetSource.unregisterSource
import suwayomi.tachidesk.test.PostgresTest

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObsoleteDownloadsPostgresTest : PostgresTest() {
    @BeforeAll fun register() = registerSource(source.id to source)

    @AfterAll fun unregister() = unregisterSource(source.id)

    @Test fun `a new scanlator's cbz replaces the old one once it downloads`() =
        ObsoleteDownloadsScenarios.newScanlatorReplacesTheOldCbzOnceItDownloads()

    @Test fun `a new scanlator's folder replaces the old one once it downloads`() =
        ObsoleteDownloadsScenarios.newScanlatorReplacesTheOldFolderOnceItDownloads()

    @Test fun `an already downloaded replacement removes the old file at once`() =
        ObsoleteDownloadsScenarios.anAlreadyDownloadedReplacementRemovesTheOldFileAtOnce()

    @Test fun `a removed chapter with nothing replacing it keeps its file`() =
        ObsoleteDownloadsScenarios.aRemovedChapterWithNothingReplacingItKeepsItsFile()

    @Test fun `a refused rename keeps the old file until the chapter downloads again`() =
        ObsoleteDownloadsScenarios.aRefusedRenameKeepsTheOldFileUntilTheChapterDownloadsAgain()

    @Test fun `a download shared with another chapter replaces nothing`() =
        ObsoleteDownloadsScenarios.aDownloadSharedWithAnotherChapterReplacesNothing()

    @Test fun `a file a downloaded chapter points to is never deleted`() =
        ObsoleteDownloadsScenarios.aFileADownloadedChapterPointsToIsNeverDeleted()

    @Test fun `a chapter without a recognised number is left alone`() =
        ObsoleteDownloadsScenarios
            .aChapterWithoutARecognisedNumberIsLeftAlone()

    @Test fun `chapters from different volumes don't replace each other`() =
        ObsoleteDownloadsScenarios.chaptersFromDifferentVolumesDontReplaceEachOther()

    @Test fun `episodes from different seasons don't replace each other`() =
        ObsoleteDownloadsScenarios.episodesFromDifferentSeasonsDontReplaceEachOther()

    @Test fun `the same volume's chapter from another group still replaces it`() =
        ObsoleteDownloadsScenarios.theSameVolumesChapterFromAnotherGroupStillReplacesIt()

    @Test fun `volumes written with a space don't replace each other`() =
        ObsoleteDownloadsScenarios.volumesWrittenWithASpaceDontReplaceEachOther()

    @Test fun `names giving different numbers don't replace each other`() =
        ObsoleteDownloadsScenarios.namesGivingDifferentNumbersDontReplaceEachOther()

    @Test fun `a replacement that is the old file keeps it`() = ObsoleteDownloadsScenarios.aReplacementThatIsTheOldFileKeepsIt()

    @Test fun `a replacement missing on disk replaces nothing`() = ObsoleteDownloadsScenarios.aReplacementMissingOnDiskReplacesNothing()

    @Test fun `an empty replacement replaces nothing`() = ObsoleteDownloadsScenarios.anEmptyReplacementReplacesNothing()

    @Test fun `a file a listed chapter is named for is kept`() = ObsoleteDownloadsScenarios.aFileAListedChapterIsNamedForIsKept()

    @Test fun `a chapter relabelled with another number isn't replaced by its old number`() =
        ObsoleteDownloadsScenarios.aChapterRelabelledWithAnotherNumberIsntReplacedByItsOldNumber()

    @Test fun `a relabel onto another version's name keeps its pages`() =
        ObsoleteDownloadsScenarios.aRelabelOntoAnotherVersionsNameKeepsItsPages()

    @Test fun `a linked chapter folder isn't followed`() = ObsoleteDownloadsScenarios.aLinkedChapterFolderIsntFollowed()

    @Test fun `without its table, syncs and downloads still work`() = ObsoleteDownloadsScenarios.withoutItsTableSyncsAndDownloadsStillWork()
}
