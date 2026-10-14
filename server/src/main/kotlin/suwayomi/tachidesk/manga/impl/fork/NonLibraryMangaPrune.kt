package suwayomi.tachidesk.manga.impl.fork

import eu.kanade.tachiyomi.source.local.LocalSource
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.download.DownloadManager
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.ChapterMetaTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.manga.model.table.TrackRecordTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.time.Instant

/**
 * Fork-only (F12): deletes series that aren't in the library and that nobody has touched, with their chapters and pages.
 * Browsing a source stores every series it shows; on main those rows hold 62% of all chapters.
 *
 * Kept: library series; any read, bookmarked, downloaded or partly read chapter; categories, tracking, manga or chapter
 * meta; series fetched within [GRACE_SECONDS] (someone may be looking at it); series with a queued download; the
 * Local source; and [excludeMangaIds] (suwayomi-dm passes the ids it references).
 *
 * A series that was only listed while browsing has no fetch time (`last_fetched_at` stays 0 until it's opened), so the
 * grace period doesn't cover it: a browse listing left open across a prune can show series that no longer exist until
 * it's reloaded.
 */
object NonLibraryMangaPrune {
    const val GRACE_SECONDS = 24 * 60 * 60L
    private const val BATCH = 500

    private val applicationDirs: ApplicationDirs by injectLazy()

    data class Result(
        val mangaIds: List<Int>,
        val chapterCount: Long,
    )

    fun prune(
        excludeMangaIds: Set<Int>,
        dryRun: Boolean,
        nowEpochSeconds: Long = Instant.now().epochSecond,
        queuedMangaIds: Set<Int> = DownloadManager.getStatus().queue.mapTo(HashSet()) { it.mangaId },
        coverDirs: List<File> = listOf(File(applicationDirs.tempThumbnailCacheRoot), File(applicationDirs.thumbnailDownloadsRoot)),
    ): Result {
        val ids = candidates(nowEpochSeconds - GRACE_SECONDS).filterNot { it in excludeMangaIds || it in queuedMangaIds }
        var chapterCount = 0L
        ids.chunked(BATCH).forEach { batch ->
            transaction {
                val chapterIds = ChapterTable.select(ChapterTable.id).where { ChapterTable.manga inList batch }
                chapterCount += chapterIds.count()
                if (!dryRun) {
                    PageTable.deleteWhere { PageTable.chapter inSubQuery chapterIds }
                    ChapterTable.deleteWhere { ChapterTable.manga inList batch }
                    MangaTable.deleteWhere { MangaTable.id inList batch }
                }
            }
        }
        if (!dryRun) deleteCovers(ids.toSet(), coverDirs)
        return Result(ids, chapterCount)
    }

    private fun candidates(fetchedBefore: Long): List<Int> =
        transaction {
            val touchedChapter =
                ChapterTable.select(ChapterTable.id).where {
                    (ChapterTable.manga eq MangaTable.id) and
                        (
                            (ChapterTable.isRead eq true) or (ChapterTable.isBookmarked eq true) or
                                (ChapterTable.isDownloaded eq true) or (ChapterTable.lastPageRead greater 0) or
                                (ChapterTable.lastReadAt greater 0L)
                        )
                }
            val chapterMeta =
                ChapterMetaTable
                    .join(ChapterTable, JoinType.INNER, ChapterMetaTable.ref, ChapterTable.id)
                    .select(ChapterMetaTable.id)
                    .where { ChapterTable.manga eq MangaTable.id }
            MangaTable
                .select(MangaTable.id)
                .where {
                    (MangaTable.inLibrary eq false) and (MangaTable.sourceReference neq LocalSource.ID) and
                        (MangaTable.lastFetchedAt less fetchedBefore) and (MangaTable.chaptersLastFetchedAt less fetchedBefore) and
                        notExists(touchedChapter) and
                        notExists(CategoryMangaTable.select(CategoryMangaTable.id).where { CategoryMangaTable.manga eq MangaTable.id }) and
                        notExists(TrackRecordTable.select(TrackRecordTable.id).where { TrackRecordTable.mangaId eq MangaTable.id }) and
                        notExists(MangaMetaTable.select(MangaMetaTable.id).where { MangaMetaTable.ref eq MangaTable.id }) and
                        notExists(chapterMeta)
                }.map { it[MangaTable.id].value }
        }

    /** One listing per folder: cover files are named "<mangaId>.<ext>" (or ".tmp" while being written). */
    private fun deleteCovers(
        ids: Set<Int>,
        dirs: List<File>,
    ) {
        dirs.forEach { dir ->
            dir
                .listFiles()
                .orEmpty()
                .filter { it.nameWithoutExtension.toIntOrNull() in ids }
                .forEach { it.delete() }
        }
    }
}
