package suwayomi.tachidesk.server.database.fork

/*
 * Fork-only. Not in the migration package on purpose: upstream's runner loads every class there.
 */

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.server.serverConfig

/**
 * Keeps `manga.fork_changed_at` (epoch ms) current: the last time a series changed in a way a library listing
 * shows. That's its own listed fields, its chapters' listed fields, chapters added or removed, and its categories.
 * suwayomi-dm asks for the series changed since its last snapshot instead of the whole library (Batch E, D1).
 *
 * - Postgres only. Triggers in the database do the work, so a stock image on the same database keeps the stamp
 *   current too. H2 triggers are Java classes, and a stock image on an H2 database naming fork classes would fail,
 *   so H2 gets nothing and [active] stays false.
 * - Fetch times and reading progress don't count: they change on every library update or page turn.
 * - Chapter and category changes count only for series in the library. Adding a series to the library stamps it.
 * - Trigger definitions are fixed by name: a changed definition needs a new name (`…_v2`) and a drop of the old one.
 */
object ForkChangeTracking {
    private val logger = KotlinLogging.logger {}

    const val COLUMN = "fork_changed_at"

    private const val NOW_MS = "(extract(epoch FROM now()) * 1000)::bigint"

    private const val SERIES_FIELDS =
        "title, url, source, thumbnail_url, artist, author, description, genre, status, in_library, in_library_at"
    private const val CHAPTER_FIELDS = "name, scanlator, chapter_number, date_upload, is_downloaded, page_count, read, bookmark"

    private fun changed(fields: String): String {
        val names = fields.split(", ")
        return "ROW(${names.joinToString { "OLD.$it" }}) IS DISTINCT FROM ROW(${names.joinToString { "NEW.$it" }})"
    }

    private val functions =
        listOf(
            """
            CREATE OR REPLACE FUNCTION suwayomi.fork_stamp_manga() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                NEW.fork_changed_at := $NOW_MS;
                RETURN NEW;
            END $$
            """.trimIndent(),
            // SECURITY DEFINER: suwayomi-dm's graft role may update only chapter.is_downloaded and page_count
            """
            CREATE OR REPLACE FUNCTION suwayomi.fork_touch_library_manga() RETURNS trigger LANGUAGE plpgsql
                SECURITY DEFINER SET search_path = suwayomi, pg_temp AS $$
            DECLARE
                stamp bigint := $NOW_MS;
                target integer;
            BEGIN
                IF TG_OP = 'DELETE' THEN target := OLD.manga; ELSE target := NEW.manga; END IF;
                UPDATE manga SET fork_changed_at = stamp
                 WHERE id = target AND in_library AND fork_changed_at IS DISTINCT FROM stamp;
                RETURN NULL;
            END $$
            """.trimIndent(),
        )

    private val triggers =
        linkedMapOf(
            "fork_manga_changed_insert" to "BEFORE INSERT ON suwayomi.manga FOR EACH ROW EXECUTE FUNCTION suwayomi.fork_stamp_manga()",
            "fork_manga_changed_update" to
                "BEFORE UPDATE ON suwayomi.manga FOR EACH ROW WHEN (${changed(
                    SERIES_FIELDS,
                )}) EXECUTE FUNCTION suwayomi.fork_stamp_manga()",
            "fork_chapter_changed_insert" to
                "AFTER INSERT ON suwayomi.chapter FOR EACH ROW EXECUTE FUNCTION suwayomi.fork_touch_library_manga()",
            "fork_chapter_changed_update" to
                "AFTER UPDATE ON suwayomi.chapter FOR EACH ROW WHEN (${changed(CHAPTER_FIELDS)}) " +
                "EXECUTE FUNCTION suwayomi.fork_touch_library_manga()",
            "fork_chapter_changed_delete" to
                "AFTER DELETE ON suwayomi.chapter FOR EACH ROW EXECUTE FUNCTION suwayomi.fork_touch_library_manga()",
            "fork_category_manga_changed_insert" to
                "AFTER INSERT ON suwayomi.categorymanga FOR EACH ROW EXECUTE FUNCTION suwayomi.fork_touch_library_manga()",
            "fork_category_manga_changed_delete" to
                "AFTER DELETE ON suwayomi.categorymanga FOR EACH ROW EXECUTE FUNCTION suwayomi.fork_touch_library_manga()",
        )

    val triggerNames: List<String> get() = triggers.keys.toList()

    @Volatile
    private var installed = false

    /** Whether this server's database keeps [COLUMN] current: Postgres, after [apply] succeeded. */
    val active: Boolean
        get() = installed && serverConfig.databaseType.value == DatabaseType.POSTGRESQL

    /** Runs on every start, after the migrations. A failure is logged and leaves it off until the next start. */
    fun apply(lockTimeoutMs: Int = ForkSchema.LOCK_TIMEOUT_MS) {
        installed = false
        if (serverConfig.databaseType.value != DatabaseType.POSTGRESQL) return
        installed =
            try {
                transaction {
                    // Exposed retries a failed transaction (3 attempts by default); each retry would wait for the locks again
                    maxAttempts = 1
                    exec("SET LOCAL lock_timeout = $lockTimeoutMs")
                    // ADD COLUMN IF NOT EXISTS locks the table even when the column exists, so look first
                    if (!hasColumn()) exec("ALTER TABLE suwayomi.manga ADD COLUMN $COLUMN BIGINT NOT NULL DEFAULT 0")
                    functions.forEach { exec(it) }
                    val existing = triggersPresent(enabledOnly = false)
                    triggers.filterKeys { it !in existing }.forEach { (name, definition) -> exec("CREATE TRIGGER $name $definition") }
                    hasColumn() && triggersPresent(enabledOnly = true).containsAll(triggers.keys)
                }
            } catch (e: Exception) {
                logger.error(e) { "ForkChangeTracking: off until the next start; suwayomi-dm fetches whole library snapshots meanwhile" }
                false
            }
        logger.info { "ForkChangeTracking: ${if (installed) "on" else "off"}" }
    }

    private fun JdbcTransaction.hasColumn(): Boolean =
        exec(
            "SELECT 1 FROM information_schema.columns " +
                "WHERE table_schema = 'suwayomi' AND table_name = 'manga' AND column_name = '$COLUMN'",
        ) { it.next() } == true

    private fun JdbcTransaction.triggersPresent(enabledOnly: Boolean): Set<String> =
        exec(
            "SELECT tgname FROM pg_trigger WHERE NOT tgisinternal AND tgname LIKE 'fork\\_%'" +
                if (enabledOnly) " AND tgenabled <> 'D'" else "",
        ) { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }.orEmpty()
}
