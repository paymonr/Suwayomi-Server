package suwayomi.tachidesk.manga.impl.download.fileProvider.impl

import eu.kanade.tachiyomi.source.local.metadata.COMIC_INFO_FILE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.download.fileProvider.ChaptersFilesProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.FileType
import suwayomi.tachidesk.manga.impl.util.getChapterCachePath
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.impl.util.getMangaDownloadDir
import suwayomi.tachidesk.manga.impl.util.storage.FileDeletionHelper
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipEntry

private val applicationDirs: ApplicationDirs by injectLazy()

/** Fork (F11): page images are already compressed, so they're STORED; only ComicInfo.xml is deflated. */
internal fun writeCbz(
    pagesFolder: File,
    outputFile: File,
) {
    ZipArchiveOutputStream(outputFile).use { zipOut ->
        pagesFolder.listFiles()?.sortedBy { it.name }?.forEach {
            val entry = ZipArchiveEntry(it.name)
            entry.time = 0L
            entry.method = if (it.name == COMIC_INFO_FILE) ZipEntry.DEFLATED else ZipEntry.STORED
            try {
                zipOut.putArchiveEntry(entry)
                it.inputStream().use { inputStream ->
                    inputStream.copyTo(zipOut)
                }
            } finally {
                zipOut.closeArchiveEntry()
            }
        }
    }
}

class ArchiveProvider(
    mangaId: Int,
    chapterId: Int,
) : ChaptersFilesProvider<FileType.ZipFile>(mangaId, chapterId) {
    // resolved once per provider: every lookup runs several database queries
    private var cbzPath: String? = null

    private suspend fun cbzPath(): String = cbzPath ?: getChapterCbzPath(mangaId, chapterId).also { cbzPath = it }

    private fun openCbz(path: String): ZipFile = ZipFile.builder().setFile(path).get()

    override suspend fun getImageFiles(): List<FileType.ZipFile> =
        openCbz(cbzPath()).use { zipFile -> zipFile.entries.toList().map { FileType.ZipFile(it) } }

    override suspend fun getImageInputStream(image: FileType.ZipFile): InputStream {
        val zipFile = openCbz(cbzPath())
        return try {
            ArchiveEntryInputStream(zipFile.getInputStream(image.entry), zipFile)
        } catch (e: Throwable) {
            zipFile.close()
            throw e
        }
    }

    override suspend fun extractExistingDownload() {
        val outputFile = File(getChapterCbzPath(mangaId, chapterId))
        val chapterDownloadFolder = File(getChapterDownloadPath(mangaId, chapterId))

        if (!outputFile.exists()) {
            return
        }

        extractCbzFile(outputFile, chapterDownloadFolder)
    }

    override suspend fun handleSuccessfulDownload() {
        val mangaDownloadFolder = File(getMangaDownloadDir(mangaId))
        val outputFile = File(getChapterCbzPath(mangaId, chapterId))
        val chapterCacheFolder = File(getChapterCachePath(mangaId, chapterId))

        withContext(Dispatchers.IO) {
            mangaDownloadFolder.mkdirs()
            outputFile.createNewFile()
        }

        writeCbz(chapterCacheFolder, outputFile)

        if (chapterCacheFolder.exists() && chapterCacheFolder.isDirectory) {
            chapterCacheFolder.deleteRecursively()
        }
    }

    override suspend fun delete(): Boolean {
        val cbzFile = File(getChapterCbzPath(mangaId, chapterId))
        if (!cbzFile.exists()) {
            return true
        }

        val cbzDeleted = cbzFile.delete()
        if (cbzDeleted) {
            transaction {
                ChapterTable.update({ ChapterTable.id eq chapterId }) {
                    it[koreaderHash] = null
                }
            }
        }
        FileDeletionHelper.cleanupParentFoldersFor(cbzFile, applicationDirs.mangaDownloadsRoot)
        return cbzDeleted
    }

    override suspend fun getAsArchiveStream(): Pair<InputStream, Long> {
        val cbzFile =
            File(getChapterCbzPath(mangaId, chapterId))
                .takeIf { it.exists() }
                ?: throw IllegalArgumentException("CBZ file not found for chapter ID: $chapterId (Manga ID: $mangaId)")

        return cbzFile.inputStream() to cbzFile.length()
    }

    override suspend fun getArchiveSize(): Long {
        val cbzFile = File(getChapterCbzPath(mangaId, chapterId))
        return if (cbzFile.exists()) cbzFile.length() else 0L
    }

    private fun extractCbzFile(
        cbzFile: File,
        chapterFolder: File,
    ) {
        if (!chapterFolder.exists()) chapterFolder.mkdirs()
        ZipArchiveInputStream(cbzFile.inputStream()).use { zipInputStream ->
            var zipEntry = zipInputStream.nextEntry
            while (zipEntry != null) {
                val file = File(chapterFolder, zipEntry.name)
                if (!file.exists()) {
                    file.parentFile.mkdirs()
                    file.createNewFile()
                }
                file.outputStream().use { outputStream ->
                    zipInputStream.copyTo(outputStream)
                }
                zipEntry = zipInputStream.nextEntry
            }
        }
        cbzFile.delete()
    }
}

/** A page stream that owns its archive: closing the page closes the CBZ. */
private class ArchiveEntryInputStream(
    entry: InputStream,
    private val zipFile: ZipFile,
) : FilterInputStream(entry) {
    override fun close() {
        try {
            super.close()
        } finally {
            zipFile.close()
        }
    }
}
