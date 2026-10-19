package suwayomi.tachidesk.manga.impl.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.util.lang.withIOContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.androidcompat.util.SafePath
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.moveTo
import kotlin.io.path.notExists

private val applicationDirs: ApplicationDirs by injectLazy()

private val logger = KotlinLogging.logger { }

private fun getMangaDir(
    title: String,
    sourceName: String,
): String {
    val sourceDir = SafePath.buildValidFilename(sourceName)
    val mangaDir = SafePath.buildValidFilename(title)

    return "$sourceDir/$mangaDir"
}

private suspend fun getMangaDir(mangaId: Int): String =
    suspendTransaction {
        val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
        val source = GetSource.getSourceOrStub(mangaEntry[MangaTable.sourceReference])

        getMangaDir(mangaEntry[MangaTable.title], source.toString())
    }

// internal for the fork's ObsoleteDownloads (F16), which matches files to chapters by this name
internal fun getChapterDirName(
    title: String,
    scanlator: String?,
): String =
    SafePath.buildValidFilename(
        when {
            scanlator != null -> "${scanlator}_$title"
            else -> title
        },
    )

private suspend fun getChapterDir(
    mangaId: Int,
    title: String,
    scanlator: String?,
): String = getMangaDir(mangaId) + "/" + getChapterDirName(title, scanlator)

private suspend fun getChapterDir(
    mangaId: Int,
    chapterId: Int,
): String =
    suspendTransaction {
        val chapterEntry = ChapterTable.selectAll().where { ChapterTable.id eq chapterId }.first()

        // Get manga directory and combine with chapter directory
        // Note: This creates a nested transaction, but Exposed handles this with useNestedTransactions=true
        getChapterDir(mangaId, chapterEntry[ChapterTable.name], chapterEntry[ChapterTable.scanlator])
    }

fun getThumbnailDownloadPath(mangaId: Int): String = applicationDirs.thumbnailDownloadsRoot + "/$mangaId"

fun getMangaDownloadDir(
    title: String,
    sourceName: String,
): String = applicationDirs.mangaDownloadsRoot + "/" + getMangaDir(title, sourceName)

suspend fun getMangaDownloadDir(mangaId: Int): String = applicationDirs.mangaDownloadsRoot + "/" + getMangaDir(mangaId)

fun getMangaCacheDir(
    title: String,
    sourceName: String,
): String = applicationDirs.tempMangaCacheRoot + "/" + getMangaDir(title, sourceName)

suspend fun getChapterDownloadPath(
    mangaId: Int,
    title: String,
    scanlator: String?,
): String = applicationDirs.mangaDownloadsRoot + "/" + getChapterDir(mangaId, title, scanlator)

suspend fun getChapterDownloadPath(
    mangaId: Int,
    chapterId: Int,
): String = applicationDirs.mangaDownloadsRoot + "/" + getChapterDir(mangaId, chapterId)

suspend fun getChapterCbzPath(
    mangaId: Int,
    title: String,
    scanlator: String?,
): String = getChapterDownloadPath(mangaId, title, scanlator) + ".cbz"

suspend fun getChapterCbzPath(
    mangaId: Int,
    chapterId: Int,
): String = getChapterDownloadPath(mangaId, chapterId) + ".cbz"

suspend fun getChapterCachePath(
    mangaId: Int,
    title: String,
    scanlator: String?,
): String = applicationDirs.tempMangaCacheRoot + "/" + getChapterDir(mangaId, title, scanlator)

suspend fun getChapterCachePath(
    mangaId: Int,
    chapterId: Int,
): String = applicationDirs.tempMangaCacheRoot + "/" + getChapterDir(mangaId, chapterId)

private fun updateDir(
    currentDir: String,
    newDir: String,
): Boolean {
    val currentDirPath = Path(currentDir)
    val newDirPath = Path(newDir)

    if (currentDirPath.notExists()) {
        return true
    }

    return try {
        currentDirPath.moveTo(newDirPath)

        if (newDirPath.notExists()) {
            return false
        }

        return true
    } catch (e: Exception) {
        logger.error(e) { "updateDownloadDir: failed to rename download folder from \"$currentDir\" to \"$newDir\"" }
        false
    }
}

private fun updateDownloadDir(
    currentDownloadDir: String,
    newDownloadDir: String,
    currentCacheDir: String,
    newCacheDir: String,
): Boolean {
    val renamed = updateDir(currentDownloadDir, newDownloadDir)

    val tryToKeepCachedFilesUsable = renamed
    if (tryToKeepCachedFilesUsable) {
        updateDir(currentCacheDir, newCacheDir)
    }

    return renamed
}

// internal for the fork's ObsoleteDownloads (F16), which deletes files under the same lock
internal val mutexByManga = ConcurrentHashMap<Int, Mutex>()

/**
 * Renames download folders of one series' chapters, e.g. during one chapter-list sync.
 * The series folder is looked up once, on the first rename, not six times per renamed chapter.
 */
class ChapterDownloadDirs(
    private val mangaId: Int,
) {
    private var mangaDir: String? = null

    private suspend fun mangaDir(): String = mangaDir ?: getMangaDir(mangaId).also { mangaDir = it }

    /** return value says if the chapter's download is still usable under its new name */
    suspend fun update(
        oldChapter: ChapterDataClass,
        newChapter: ChapterDataClass,
    ): Boolean {
        if (oldChapter.mangaId != mangaId || newChapter.mangaId != mangaId) {
            return false
        }

        // the folder name comes only from name and scanlator; if neither changed there's nothing to rename
        if (oldChapter.name == newChapter.name && oldChapter.scanlator == newChapter.scanlator) {
            return true
        }

        return mutexByManga.getOrPut(mangaId) { Mutex() }.withLock {
            val mangaDir = mangaDir()
            val oldName = getChapterDirName(oldChapter.name, oldChapter.scanlator)
            val newName = getChapterDirName(newChapter.name, newChapter.scanlator)

            val currentDownloadDir = "${applicationDirs.mangaDownloadsRoot}/$mangaDir/$oldName"
            val newDownloadDir = "${applicationDirs.mangaDownloadsRoot}/$mangaDir/$newName"
            val currentCacheDir = "${applicationDirs.tempMangaCacheRoot}/$mangaDir/$oldName"
            val newCacheDir = "${applicationDirs.tempMangaCacheRoot}/$mangaDir/$newName"

            withIOContext {
                val dirRename = updateDownloadDir(currentDownloadDir, newDownloadDir, currentCacheDir, newCacheDir)
                val cbzRename = updateDownloadDir("$currentDownloadDir.cbz", "$newDownloadDir.cbz", currentCacheDir, newCacheDir)

                dirRename && cbzRename
            }
        }
    }
}

/** return value says if rename/move was successful */
suspend fun updateMangaDownloadDir(
    mangaId: Int,
    title: String,
    sourceName: String,
    newTitle: String,
): Boolean =
    mutexByManga.getOrPut(mangaId) { Mutex() }.withLock {
        val currentDownloadDir = getMangaDownloadDir(title, sourceName)
        val newDownloadDir = getMangaDownloadDir(newTitle, sourceName)

        val currentCacheDir = getMangaCacheDir(title, sourceName)
        val newCacheDir = getMangaCacheDir(newTitle, sourceName)

        withIOContext {
            updateDownloadDir(currentDownloadDir, newDownloadDir, currentCacheDir, newCacheDir)
        }
    }
