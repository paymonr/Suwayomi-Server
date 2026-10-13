package suwayomi.tachidesk.server.database.fork

/*
 * Fork-only. Not in the migration package on purpose: upstream's runner loads every class there.
 */

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Schema this fork adds on top of upstream's migrations. Runs on every start, after the migrations.
 * Every statement is idempotent and every name starts with "fork_", so nothing here can collide with
 * an upstream migration, and a stock image still starts on a database this has touched.
 */
object ForkSchema {
    private val logger = KotlinLogging.logger {}

    private val indexes =
        linkedMapOf(
            // browse/search lookups by (source, url); no upstream version indexes this
            "fork_manga_source_url" to "manga (source, url)",
            // one-row-per-series chapter queries (F2), same shape as upstream master's M0067
            "fork_chapter_manga_source_order" to "chapter (manga, source_order)",
            "fork_chapter_manga_fetched_at" to "chapter (manga, fetched_at DESC, source_order DESC)",
            "fork_chapter_manga_date_upload" to "chapter (manga, date_upload DESC, source_order DESC)",
        )

    val indexNames: List<String> get() = indexes.keys.toList()

    fun apply() {
        transaction {
            indexes.forEach { (name, definition) -> exec("CREATE INDEX IF NOT EXISTS $name ON $definition") }
        }
        logger.info { "ForkSchema applied: ${indexes.keys.joinToString()}" }
    }
}
