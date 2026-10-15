package suwayomi.tachidesk.manga.impl.extension

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.update.IUpdater
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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

    const val AUTO_UPDATE_PROPERTY = "suwayomi.fork.autoUpdateExtensions"

    /** Off unless `-Dsuwayomi.fork.autoUpdateExtensions=true`. */
    val autoUpdateEnabled: Boolean
        get() = System.getProperty(AUTO_UPDATE_PROPERTY)?.toBooleanStrictOrNull() ?: false

    /**
     * Refreshes every store's list and logs installed extensions with updates. With [autoUpdate], installs each one,
     * after waiting (up to [maxWaitMs]) for a running library update: installing reloads the source, and the update
     * would fail its series. One failed install doesn't stop the others.
     */
    suspend fun runOnce(
        autoUpdate: Boolean = autoUpdateEnabled,
        install: suspend (pkgName: String) -> Unit = { Extension.updateExtension(it) },
        libraryUpdateRunning: () -> Boolean = { Injekt.get<IUpdater>().getStatus().isRunning },
        pollMs: Long = 60_000L,
        maxWaitMs: Long = 3 * 60 * 60_000L,
    ): RefreshResult {
        ExtensionsList.fetchExtensions()
        val updatable = installedWithUpdates()
        val details =
            updatable.joinToString {
                "${it.name} ${it.versionName} -> ${ExtensionsList.updateMap[it.pkgName]?.versionName ?: "?"}"
            }
        logger.info { "Refreshed extension lists: ${updatable.size} installed with updates${if (details.isEmpty()) "" else ": $details"}" }
        val withUpdates = updatable.map { it.pkgName }
        if (!autoUpdate || updatable.isEmpty()) return RefreshResult(withUpdates)

        var waitedMs = 0L
        while (libraryUpdateRunning()) {
            if (waitedMs >= maxWaitMs) {
                logger.info {
                    "Extension auto-update skipped: a library update ran for over ${maxWaitMs / 60_000} min; the next refresh tries again"
                }
                return RefreshResult(withUpdates, deferred = true)
            }
            delay(pollMs)
            waitedMs += pollMs
        }

        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        updatable.forEach { extension ->
            try {
                install(extension.pkgName)
                updated += extension.pkgName
                logger.info { "Auto-updated extension ${extension.name}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += extension.pkgName
                logger.warn(e) { "Auto-update of extension ${extension.name} failed" }
            }
        }
        return RefreshResult(withUpdates, updated, failed)
    }

    private fun installedWithUpdates(): List<Updatable> =
        transaction {
            ExtensionTable
                .select(ExtensionTable.pkgName, ExtensionTable.name, ExtensionTable.versionName)
                .where { (ExtensionTable.isInstalled eq true) and (ExtensionTable.hasUpdate eq true) }
                .map { Updatable(it[ExtensionTable.pkgName], it[ExtensionTable.name], it[ExtensionTable.versionName]) }
        }
}
