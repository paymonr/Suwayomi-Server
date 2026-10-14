package suwayomi.tachidesk.manga.impl.fork

import eu.kanade.tachiyomi.source.local.LocalSource
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
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
        queuedMangaIds: () -> Set<Int> = { DownloadManager.getStatus().queue.mapTo(HashSet()) { it.mangaId } },
        coverDirs: List<File> = listOf(File(applicationDirs.tempThumbnailCacheRoot), File(applicationDirs.thumbnailDownloadsRoot)),
    ): Result {
        val fetchedBefore = nowEpochSeconds - GRACE_SECONDS
        val queued = queuedMangaIds()
        val ids = candidates(fetchedBefore).filterNot { it in excludeMangaIds || it in queued }
        var chapterCount = 0L
        val pruned = mutableListOf<Int>()
        ids.chunked(BATCH).forEach { batch ->
            val result = pruneBatch(batch, fetchedBefore, excludeMangaIds, queuedMangaIds(), dryRun)
            pruned += result.mangaIds
            chapterCount += result.chapterCount
        }
        if (!dryRun) deleteCovers(pruned.toSet(), coverDirs)
        return Result(pruned, chapterCount)
    }

    /**
     * Deletes the series of [batch] that still match the rule. A batch runs minutes after the candidates were listed, so
     * the rule, the exclusions and the download queue are checked again in the batch's own transaction.
     */
    internal fun pruneBatch(
        batch: List<Int>,
        fetchedBefore: Long,
        excludeMangaIds: Set<Int>,
        queuedMangaIds: Set<Int>,
        dryRun: Boolean,
    ): Result =
        transaction {
            val ids = candidates(fetchedBefore, only = batch).filterNot { it in excludeMangaIds || it in queuedMangaIds }
            if (ids.isEmpty()) return@transaction Result(emptyList(), 0)
            val chapterIds = ChapterTable.select(ChapterTable.id).where { ChapterTable.manga inList ids }
            val chapterCount = chapterIds.count()
            if (!dryRun) {
                PageTable.deleteWhere { PageTable.chapter inSubQuery chapterIds }
                ChapterTable.deleteWhere { ChapterTable.manga inList ids }
                MangaTable.deleteWhere { MangaTable.id inList ids }
            }
            Result(ids, chapterCount)
        }

    private fun candidates(
        fetchedBefore: Long,
        only: List<Int>? = null,
    ): List<Int> =
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
                    (if (only != null) MangaTable.id inList only else Op.TRUE) and
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
