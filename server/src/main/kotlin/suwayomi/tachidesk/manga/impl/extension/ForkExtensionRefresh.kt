package suwayomi.tachidesk.manga.impl.extension

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.ExtensionTable

/** Fork-only (F3): refresh every store's extension list on a schedule and log the installed extensions that have updates. */
object ForkExtensionRefresh {
    private val logger = KotlinLogging.logger {}

    data class RefreshResult(
        /** Package names of installed extensions with a newer version in a store. */
        val withUpdates: List<String>,
        val updated: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        /** Auto-update waited for a library update that didn't finish in time; the next refresh tries again. */
        val deferred: Boolean = false,
    )

    private data class Updatable(
        val pkgName: String,
        val name: String,
        val versionName: String,
    )

    suspend fun runOnce(): RefreshResult {
        ExtensionsList.fetchExtensions()
        val updatable = installedWithUpdates()
        val details =
            updatable.joinToString {
                "${it.name} ${it.versionName} -> ${ExtensionsList.updateMap[it.pkgName]?.versionName ?: "?"}"
            }
        logger.info { "Refreshed extension lists: ${updatable.size} installed with updates${if (details.isEmpty()) "" else ": $details"}" }
        return RefreshResult(updatable.map { it.pkgName })
    }

    private fun installedWithUpdates(): List<Updatable> =
        transaction {
            ExtensionTable
                .select(ExtensionTable.pkgName, ExtensionTable.name, ExtensionTable.versionName)
                .where { (ExtensionTable.isInstalled eq true) and (ExtensionTable.hasUpdate eq true) }
                .map { Updatable(it[ExtensionTable.pkgName], it[ExtensionTable.name], it[ExtensionTable.versionName]) }
        }
}
