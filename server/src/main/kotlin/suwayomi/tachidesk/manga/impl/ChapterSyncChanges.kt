package suwayomi.tachidesk.manga.impl

import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass

/**
 * True when a fetched chapter differs from the stored row in any field the chapter-list sync writes.
 * Read state, downloads, page count and fetch time aren't written by the sync, so they don't count.
 */
internal fun ChapterDataClass.differsInSyncedFields(fetched: ChapterDataClass): Boolean =
    name != fetched.name ||
        uploadDate != fetched.uploadDate ||
        chapterNumber != fetched.chapterNumber ||
        scanlator != fetched.scanlator ||
        index != fetched.index ||
        realUrl != fetched.realUrl ||
        memo != fetched.memo
