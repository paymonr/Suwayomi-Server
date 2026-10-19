package suwayomi.tachidesk.manga.impl.fork

import eu.kanade.tachiyomi.util.lang.withIOContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.util.getChapterDirName
import suwayomi.tachidesk.manga.impl.util.getMangaDownloadDir
import suwayomi.tachidesk.manga.impl.util.mutexByManga
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import java.io.File
import java.time.Instant

/**
 * Fork-only (F16): deletes a chapter's old download once a newer download of the same chapter is in place.
 *
 * A chapter-list sync can stop pointing to a downloaded file and leave it on disk: the source dropped the chapter, listed
 * it again under a new url from another scanlator, or renamed it while the new name was taken, so the rename was refused.
 * Upstream leaves those files for good; on main they had grown to 163 GiB of copies and older versions by 2026-10-18.
 * The sync hands those chapters to [dropped], which remembers each file still on disk. Once a chapter with the same number
 * is downloaded, at that sync or later ([afterDownload]), the old file is deleted.
 *
 * Kept: a file a downloaded chapter points to; a file whose replacement isn't on disk, or is shared with another downloaded
 * chapter (a relabel onto another chapter's name: the old file holds the only copy of its pages); a chapter from another
 * volume, season or part than its would-be replacement (numbering that restarts gives both the same number); a chapter
 * with no recognised number, which nothing can be matched to; and a chapter nothing replaces. It goes by chapter numbers
 * and names, never by source, so extension changes don't affect it.
 */
object ObsoleteDownloads {
    private val logger = KotlinLogging.logger {}

    /** Created by ForkSchema. */
    const val TABLE = "fork_obsolete_download"

    private object ObsoleteDownloadTable : Table(TABLE) {
        val manga = integer("manga")
        val chapterNumber = float("chapter_number")

        /** the chapter's download in its series folder: a folder of that name, or that name plus ".cbz" */
        val dirName = varchar("dir_name", 1024)
        val recordedAt = long("recorded_at")

        override val primaryKey = PrimaryKey(manga, dirName)
    }

    // ChapterRecognition drops these from a name before reading its number, so chapter 1 of volumes 1 and 2 are both 1
    private val restartMarker = Regex("""(?<![a-z0-9])(vol|volume|season|s|part)[^a-z0-9]?([0-9]+)""")

    private fun restartMarkers(dirName: String): Map<String, String> =
        restartMarker.findAll(dirName.lowercase()).associate { match ->
            val kind =
                when (match.groupValues[1]) {
                    "vol", "volume" -> "volume"
                    "season", "s" -> "season"
                    else -> "part"
                }
            kind to match.groupValues[2].trimStart('0')
        }

    /** False when the names carry different volume, season or part numbers. */
    private fun canReplace(
        newDirName: String,
        oldDirName: String,
    ): Boolean {
        val new = restartMarkers(newDirName)
        val old = restartMarkers(oldDirName)
        return new.keys.intersect(old.keys).all { new[it] == old[it] }
    }

    private fun Float.asChapterNumber(): String = if (this % 1f == 0f) toInt().toString() else toString()

    /** The sync stopped pointing to these downloaded chapters' files. Never throws, apart from cancellation. */
    suspend fun dropped(
        mangaId: Int,
        chapters: List<ChapterDataClass>,
    ) {
        if (chapters.isEmpty()) return
        guarded("keeping track of manga $mangaId's old downloads") {
            val mangaDir = getMangaDownloadDir(mangaId)
            val onDisk =
                withIOContext {
                    chapters
                        .map { it.chapterNumber to getChapterDirName(it.name, it.scanlator) }
                        .distinctBy { (_, dirName) -> dirName }
                        .filter { (_, dirName) -> isOnDisk(mangaDir, dirName) }
                }
            val (numbered, unnumbered) = onDisk.partition { (number, _) -> number >= 0f }
            unnumbered.forEach { (_, dirName) ->
                logger.info { "Left \"$dirName\" in \"$mangaDir\": its chapter has no recognised number, so no download can replace it" }
            }
            if (numbered.isEmpty()) return@guarded
            transaction {
                ObsoleteDownloadTable.batchUpsert(numbered) { (number, dirName) ->
                    this[ObsoleteDownloadTable.manga] = mangaId
                    this[ObsoleteDownloadTable.chapterNumber] = number
                    this[ObsoleteDownloadTable.dirName] = dirName
                    this[ObsoleteDownloadTable.recordedAt] = Instant.now().epochSecond
                }
            }
            deleteReplaced(mangaId, numbered.map { (number, _) -> number }.toSet())
            withIOContext { numbered.filter { (_, dirName) -> isOnDisk(mangaDir, dirName) } }.forEach { (number, dirName) ->
                logger.info { "Keeping \"$dirName\" in \"$mangaDir\" until chapter ${number.asChapterNumber()} is downloaded again" }
            }
        }
    }

    /** A chapter finished downloading. Never throws, apart from cancellation. */
    suspend fun afterDownload(
        mangaId: Int,
        chapterId: Int,
    ) {
        guarded("deleting old downloads after chapter $chapterId of manga $mangaId downloaded") {
            val number =
                transaction {
                    ChapterTable
                        .select(ChapterTable.chapter_number)
                        .where { ChapterTable.id eq chapterId }
                        .singleOrNull()
                        ?.get(ChapterTable.chapter_number)
                }
            if (number != null && number >= 0f) deleteReplaced(mangaId, setOf(number))
        }
    }

    private suspend fun deleteReplaced(
        mangaId: Int,
        numbers: Set<Float>,
    ) {
        // the lock chapter renames take, so no rename lands on a file while it's being deleted
        mutexByManga.getOrPut(mangaId) { Mutex() }.withLock {
            val waiting =
                transaction {
                    ObsoleteDownloadTable
                        .selectAll()
                        .where { (ObsoleteDownloadTable.manga eq mangaId) and (ObsoleteDownloadTable.chapterNumber inList numbers) }
                        .map { it[ObsoleteDownloadTable.chapterNumber] to it[ObsoleteDownloadTable.dirName] }
                }
            if (waiting.isEmpty()) return

            val mangaDir = getMangaDownloadDir(mangaId)
            val downloaded =
                transaction {
                    ChapterTable
                        .select(ChapterTable.chapter_number, ChapterTable.name, ChapterTable.scanlator)
                        .where { (ChapterTable.manga eq mangaId) and (ChapterTable.isDownloaded eq true) }
                        .map { it[ChapterTable.chapter_number] to getChapterDirName(it[ChapterTable.name], it[ChapterTable.scanlator]) }
                }
            val chaptersByFile = downloaded.groupingBy { (_, dirName) -> dirName }.eachCount()

            withIOContext {
                // a replacement counts once its file is on disk and it's the only downloaded chapter pointing to that file
                val replacements =
                    downloaded.filter { (number, dirName) ->
                        number in numbers && chaptersByFile[dirName] == 1 &&
                            isOnDisk(mangaDir, dirName)
                    }

                waiting.forEach { (number, dirName) ->
                    if (dirName in chaptersByFile) {
                        // a downloaded chapter points to it again
                        forget(mangaId, dirName)
                    } else {
                        val replacement =
                            replacements.firstOrNull { (newNumber, newDirName) -> newNumber == number && canReplace(newDirName, dirName) }
                                ?: return@forEach
                        if (delete(mangaDir, dirName)) {
                            forget(mangaId, dirName)
                            logger.info { "Deleted \"$dirName\" in \"$mangaDir\": replaced by the download \"${replacement.second}\"" }
                        } else {
                            logger.warn {
                                "Couldn't delete \"$dirName\" in \"$mangaDir\"; trying again when chapter ${number.asChapterNumber()} next downloads"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun forget(
        mangaId: Int,
        dirName: String,
    ) {
        transaction {
            ObsoleteDownloadTable.deleteWhere { (ObsoleteDownloadTable.manga eq mangaId) and (ObsoleteDownloadTable.dirName eq dirName) }
        }
    }

    /** A blank name would point at the series folder itself. */
    private fun isChapterName(dirName: String): Boolean = dirName.isNotBlank() && dirName != "." && dirName != ".." && '/' !in dirName

    private fun isOnDisk(
        mangaDir: String,
        dirName: String,
    ): Boolean = isChapterName(dirName) && (File(mangaDir, "$dirName.cbz").isFile || File(mangaDir, dirName).isDirectory)

    private fun delete(
        mangaDir: String,
        dirName: String,
    ): Boolean {
        if (!isChapterName(dirName)) return false
        val cbz = File(mangaDir, "$dirName.cbz")
        val folder = File(mangaDir, dirName)
        val cbzGone = !cbz.isFile || cbz.delete()
        // a chapter folder holds only its pages; one with folders inside isn't a chapter's
        val folderGone = !folder.isDirectory || (folder.listFiles()?.none { it.isDirectory } == true && folder.deleteRecursively())
        return cbzGone && folderGone
    }

    private inline fun guarded(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed $what" }
        }
    }
}
