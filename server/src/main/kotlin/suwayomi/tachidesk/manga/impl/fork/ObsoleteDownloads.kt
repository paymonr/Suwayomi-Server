package suwayomi.tachidesk.manga.impl.fork

import eu.kanade.tachiyomi.util.chapter.ChapterRecognition
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
import suwayomi.tachidesk.manga.model.table.MangaTable
import java.io.File
import java.io.IOException
import java.nio.file.Files
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
 * Kept:
 * - a file a listed chapter is named for: a downloaded one points to it, any other takes it up once downloaded or opened;
 * - when the replacement's pages aren't on disk, it shares its file name with another listed chapter (a relabel onto
 *   another chapter's name: the old file holds the only copy of its pages), or it is the old file itself (a case-only
 *   rename on a case-insensitive filesystem);
 * - when the names show different chapters: different volume, season or part numbers (numbering that restarts gives both
 *   the same number), or different chapter numbers read from the names alone (an extension that numbers them all 0);
 * - a chapter with no recognised number, which nothing can be matched to, and a chapter nothing replaces;
 * - a linked folder, and a folder with folders inside: neither is a chapter's.
 *
 * It goes by chapter numbers and names, never by source, so extension changes don't affect it. Only files a sync leaves
 * behind from now on are tracked; older leftovers stay where they are.
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
        val chapterName = varchar("chapter_name", 512)
        val recordedAt = long("recorded_at")

        override val primaryKey = PrimaryKey(manga, dirName)
    }

    private class Old(
        val number: Float,
        val dirName: String,
        val name: String,
    )

    private class Listed(
        val number: Float,
        val dirName: String,
        val name: String,
        val downloaded: Boolean,
    )

    // ChapterRecognition drops these from a name before reading its number, so chapter 1 of volumes 1 and 2 are both 1
    private val restartMarker = Regex("""(?<![a-z0-9])(vol|volume|season|s|part)[^a-z0-9]{0,3}([0-9]+)""")

    private fun restartMarkers(name: String): Map<String, String> =
        restartMarker.findAll(name.lowercase()).associate { match ->
            val kind =
                when (match.groupValues[1]) {
                    "vol", "volume" -> "volume"
                    "season", "s" -> "season"
                    else -> "part"
                }
            kind to match.groupValues[2].trimStart('0')
        }

    /** False when the names show different chapters, whatever numbers the source gave them. */
    private fun isSameChapter(
        mangaTitle: String,
        newName: String,
        oldName: String,
    ): Boolean {
        val new = restartMarkers(newName)
        val old = restartMarkers(oldName)
        if (new.keys.intersect(old.keys).any { new[it] != old[it] }) return false
        val newNumber = ChapterRecognition.parseChapterNumber(mangaTitle, newName, -1.0)
        val oldNumber = ChapterRecognition.parseChapterNumber(mangaTitle, oldName, -1.0)
        return newNumber < 0 || oldNumber < 0 || newNumber == oldNumber
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
                        .map { Old(it.chapterNumber, getChapterDirName(it.name, it.scanlator), it.name) }
                        .distinctBy { it.dirName }
                        .filter { isOnDisk(mangaDir, it.dirName) }
                }
            val (numbered, unnumbered) = onDisk.partition { it.number >= 0f }
            unnumbered.forEach {
                logger.info {
                    "Left \"${it.dirName}\" in \"$mangaDir\": its chapter has no recognised number, so no download can replace it"
                }
            }
            if (numbered.isEmpty()) return@guarded
            transaction {
                ObsoleteDownloadTable.batchUpsert(numbered) {
                    this[ObsoleteDownloadTable.manga] = mangaId
                    this[ObsoleteDownloadTable.chapterNumber] = it.number
                    this[ObsoleteDownloadTable.dirName] = it.dirName
                    this[ObsoleteDownloadTable.chapterName] = it.name
                    this[ObsoleteDownloadTable.recordedAt] = Instant.now().epochSecond
                }
            }
            deleteReplaced(mangaId, numbered.map { it.number }.toSet())
            withIOContext { numbered.filter { isOnDisk(mangaDir, it.dirName) } }.forEach {
                logger.info {
                    "Keeping \"${it.dirName}\" in \"$mangaDir\" until chapter ${it.number.asChapterNumber()} is downloaded again"
                }
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
                        .map {
                            Old(
                                it[ObsoleteDownloadTable.chapterNumber],
                                it[ObsoleteDownloadTable.dirName],
                                it[ObsoleteDownloadTable.chapterName],
                            )
                        }
                }
            if (waiting.isEmpty()) return

            val mangaDir = getMangaDownloadDir(mangaId)
            val mangaTitle =
                transaction { MangaTable.select(MangaTable.title).where { MangaTable.id eq mangaId }.single()[MangaTable.title] }
            val listed =
                transaction {
                    ChapterTable
                        .select(ChapterTable.chapter_number, ChapterTable.name, ChapterTable.scanlator, ChapterTable.isDownloaded)
                        .where { ChapterTable.manga eq mangaId }
                        .map {
                            Listed(
                                number = it[ChapterTable.chapter_number],
                                dirName = getChapterDirName(it[ChapterTable.name], it[ChapterTable.scanlator]),
                                name = it[ChapterTable.name],
                                downloaded = it[ChapterTable.isDownloaded],
                            )
                        }
                }
            val chaptersByFile = listed.groupingBy { it.dirName }.eachCount()
            val downloadedFiles = listed.filter { it.downloaded }.map { it.dirName }.toSet()

            withIOContext {
                // a replacement is downloaded, has its pages on disk, and is the only listed chapter with that file name
                val replacements =
                    listed.filter {
                        it.downloaded && it.number in numbers && chaptersByFile[it.dirName] == 1 &&
                            hasPages(mangaDir, it.dirName)
                    }

                waiting.forEach { old ->
                    if (old.dirName in downloadedFiles) {
                        // a downloaded chapter points to it again
                        forget(mangaId, old.dirName)
                        return@forEach
                    }
                    // a listed chapter with this name takes the file up once it's downloaded or opened
                    if (old.dirName in chaptersByFile) return@forEach

                    val candidates = replacements.filter { it.number == old.number && isSameChapter(mangaTitle, it.name, old.name) }
                    if (candidates.isEmpty()) return@forEach
                    val sameFile = candidates.map { isSameFile(mangaDir, it.dirName, old.dirName) }
                    if (true in sameFile) {
                        // the replacement is this very file under another name
                        forget(mangaId, old.dirName)
                        return@forEach
                    }
                    // couldn't tell; try again on the next download
                    if (null in sameFile) return@forEach

                    if (delete(mangaDir, old.dirName)) {
                        forget(mangaId, old.dirName)
                        logger.info {
                            "Deleted \"${old.dirName}\" in \"$mangaDir\": replaced by the download \"${candidates.first().dirName}\""
                        }
                    } else {
                        logger.warn {
                            "Couldn't delete \"${old.dirName}\" in \"$mangaDir\"; trying again when chapter ${old.number.asChapterNumber()} next downloads"
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

    private fun cbz(
        mangaDir: String,
        dirName: String,
    ) = File(mangaDir, "$dirName.cbz")

    private fun folder(
        mangaDir: String,
        dirName: String,
    ) = File(mangaDir, dirName)

    private fun isOnDisk(
        mangaDir: String,
        dirName: String,
    ): Boolean = isChapterName(dirName) && (cbz(mangaDir, dirName).isFile || folder(mangaDir, dirName).isDirectory)

    /** An empty cbz or folder doesn't replace anything. */
    private fun hasPages(
        mangaDir: String,
        dirName: String,
    ): Boolean =
        isChapterName(dirName) &&
            (
                cbz(mangaDir, dirName).let { it.isFile && it.length() > 0 } ||
                    folder(mangaDir, dirName).listFiles()?.any { it.isFile } == true
            )

    /** Null when it can't be told. */
    private fun isSameFile(
        mangaDir: String,
        dirName: String,
        otherDirName: String,
    ): Boolean? {
        fun existing(name: String) = cbz(mangaDir, name).takeIf { it.isFile } ?: folder(mangaDir, name).takeIf { it.isDirectory }
        val file = existing(dirName) ?: return false
        val other = existing(otherDirName) ?: return false
        return try {
            Files.isSameFile(file.toPath(), other.toPath())
        } catch (e: IOException) {
            null
        }
    }

    private fun delete(
        mangaDir: String,
        dirName: String,
    ): Boolean {
        if (!isChapterName(dirName)) return false
        val cbz = cbz(mangaDir, dirName)
        val folder = folder(mangaDir, dirName)
        val cbzGone = !cbz.exists() || cbz.delete()
        val folderGone =
            when {
                !folder.exists() -> true

                // a link points to someone else's files
                Files.isSymbolicLink(folder.toPath()) -> false

                // a chapter folder holds only its pages; one with folders inside isn't a chapter's
                else -> folder.listFiles()?.all { it.isFile } == true && folder.deleteRecursively()
            }
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
