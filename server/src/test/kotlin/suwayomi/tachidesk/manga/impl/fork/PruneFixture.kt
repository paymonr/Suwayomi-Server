package suwayomi.tachidesk.manga.impl.fork

import eu.kanade.tachiyomi.source.local.LocalSource
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterMetaTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.manga.model.table.TrackRecordTable

/** One series per case the prune must delete or keep. NOW is the clock the tests pass to the prune. */
object PruneFixture {
    const val NOW = 2_000_000_000L

    data class Ids(
        val prunable: List<Int>,
        val kept: Map<String, Int>,
        val excluded: Int,
        val queued: Int,
    )

    private fun manga(
        tag: String,
        inLibrary: Boolean = false,
        source: Long = 1,
        lastFetchedAt: Long = 0,
        chaptersLastFetchedAt: Long = 0,
    ): Int =
        transaction {
            MangaTable
                .insertAndGetId {
                    it[title] = "prune $tag ${System.nanoTime()}"
                    it[url] = "prune-$tag-${System.nanoTime()}"
                    it[sourceReference] = source
                    it[MangaTable.inLibrary] = inLibrary
                    it[MangaTable.lastFetchedAt] = lastFetchedAt
                    it[MangaTable.chaptersLastFetchedAt] = chaptersLastFetchedAt
                }.value
        }

    /** Two chapters with two page rows each; [touch] marks the first chapter. Returns the first chapter's id. */
    private fun chapters(
        mangaId: Int,
        touch: (UpdateBuilder<*>) -> Unit = {},
    ): Int =
        transaction {
            val ids =
                (1..2).map { n ->
                    ChapterTable
                        .insertAndGetId {
                            it[url] = "$mangaId-$n"
                            it[name] = "Chapter $n"
                            it[manga] = mangaId
                            it[sourceOrder] = n
                            it[memo] = JsonObject(emptyMap())
                            if (n == 1) touch(it)
                        }.value
                }
            ids.forEach { chapterId ->
                PageTable.batchInsert(0 until 2) { i ->
                    this[PageTable.index] = i
                    this[PageTable.url] = "/p/$i"
                    this[PageTable.imageUrl] = null
                    this[PageTable.chapter] = chapterId
                }
            }
            ids.first()
        }

    fun create(): Ids {
        val prunable = List(2) { manga("plain $it").also { id -> chapters(id) } } + manga("no chapters")
        val kept = mutableMapOf<String, Int>()
        kept["library"] = manga("library", inLibrary = true).also { chapters(it) }
        kept["read"] = manga("read").also { chapters(it) { c -> c[ChapterTable.isRead] = true } }
        kept["bookmarked"] = manga("bookmarked").also { chapters(it) { c -> c[ChapterTable.isBookmarked] = true } }
        kept["downloaded"] = manga("downloaded").also { chapters(it) { c -> c[ChapterTable.isDownloaded] = true } }
        kept["page progress"] = manga("page progress").also { chapters(it) { c -> c[ChapterTable.lastPageRead] = 3 } }
        kept["read time"] = manga("read time").also { chapters(it) { c -> c[ChapterTable.lastReadAt] = 1_700_000_000L } }
        kept["recently opened"] = manga("recently opened", lastFetchedAt = NOW - 3_600).also { chapters(it) }
        kept["chapters fetched recently"] = manga("chapters fetched recently", chaptersLastFetchedAt = NOW - 3_600).also { chapters(it) }
        kept["local source"] = manga("local", source = LocalSource.ID).also { chapters(it) }
        kept["category"] =
            manga("category").also { id ->
                chapters(id)
                transaction {
                    val category = CategoryTable.insertAndGetId { it[name] = "prune ${System.nanoTime()}" }
                    CategoryMangaTable.insert {
                        it[CategoryMangaTable.category] = category
                        it[manga] = id
                    }
                }
            }
        kept["tracked"] =
            manga("tracked").also { id ->
                chapters(id)
                transaction {
                    TrackRecordTable.insert {
                        it[mangaId] = id
                        it[trackerId] = 1
                        it[remoteId] = 1
                        it[title] = "t"
                        it[lastChapterRead] = 0.0
                        it[totalChapters] = 0
                        it[status] = 0
                        it[score] = 0.0
                        it[remoteUrl] = ""
                        it[startDate] = 0
                        it[finishDate] = 0
                    }
                }
            }
        kept["manga meta"] =
            manga("manga meta").also { id ->
                chapters(id)
                transaction {
                    MangaMetaTable.insert {
                        it[key] = "k"
                        it[value] = "v"
                        it[ref] = id
                    }
                }
            }
        kept["chapter meta"] =
            manga("chapter meta").also { id ->
                val chapterId = chapters(id)
                transaction {
                    ChapterMetaTable.insert {
                        it[key] = "k"
                        it[value] = "v"
                        it[ref] = chapterId
                    }
                }
            }
        val excluded = manga("excluded").also { chapters(it) }
        val queued = manga("queued").also { chapters(it) }
        return Ids(prunable, kept, excluded, queued)
    }

    /** The prune's rule computed independently, in Kotlin over whole tables. */
    fun expected(
        exclude: Set<Int>,
        queued: Set<Int>,
    ): Set<Int> =
        transaction {
            val touched =
                ChapterTable
                    .selectAll()
                    .filter {
                        it[ChapterTable.isRead] || it[ChapterTable.isBookmarked] || it[ChapterTable.isDownloaded] ||
                            it[ChapterTable.lastPageRead] > 0 || it[ChapterTable.lastReadAt] > 0
                    }.map { it[ChapterTable.manga].value }
                    .toSet() +
                    CategoryMangaTable.selectAll().map { it[CategoryMangaTable.manga].value } +
                    TrackRecordTable.selectAll().map { it[TrackRecordTable.mangaId].value } +
                    MangaMetaTable.selectAll().map { it[MangaMetaTable.ref].value } +
                    ChapterMetaTable.selectAll().map { it[ChapterMetaTable.ref].value }.let { chapterIds ->
                        ChapterTable.selectAll().filter { it[ChapterTable.id].value in chapterIds }.map { it[ChapterTable.manga].value }
                    }
            val cutoff = NOW - NonLibraryMangaPrune.GRACE_SECONDS
            MangaTable
                .selectAll()
                .filter {
                    !it[MangaTable.inLibrary] && it[MangaTable.sourceReference] != LocalSource.ID &&
                        it[MangaTable.lastFetchedAt] < cutoff && it[MangaTable.chaptersLastFetchedAt] < cutoff
                }.map { it[MangaTable.id].value }
                .filter { it !in touched && it !in exclude && it !in queued }
                .toSet()
        }

    fun chaptersOf(mangaIds: Collection<Int>): Long =
        transaction { ChapterTable.selectAll().count { it[ChapterTable.manga].value in mangaIds }.toLong() }

    fun pagesOf(mangaIds: Collection<Int>): Long =
        transaction {
            val chapterIds =
                ChapterTable
                    .selectAll()
                    .filter { it[ChapterTable.manga].value in mangaIds }
                    .map { it[ChapterTable.id].value }
                    .toSet()
            PageTable.selectAll().count { it[PageTable.chapter].value in chapterIds }.toLong()
        }

    fun existing(mangaIds: Collection<Int>): Set<Int> =
        transaction {
            MangaTable
                .select(MangaTable.id)
                .map { it[MangaTable.id].value }
                .filter { it in mangaIds }
                .toSet()
        }

    fun allMangaIds(): Set<Int> = transaction { MangaTable.select(MangaTable.id).map { it[MangaTable.id].value }.toSet() }
}
