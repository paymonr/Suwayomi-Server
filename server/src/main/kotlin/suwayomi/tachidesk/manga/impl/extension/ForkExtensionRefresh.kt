package suwayomi.tachidesk.manga.impl.extension

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.update.IUpdater
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.util.HAScheduler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Fork-only (F3): refresh every store's extension list on a schedule and log the installed extensions that have updates. */
object ForkExtensionRefresh {
    private val logger = KotlinLogging.logger {}

    const val INTERVAL_PROPERTY = "suwayomi.fork.extensionRefreshHours"
    const val DEFAULT_INTERVAL_HOURS = 6L
    private val FIRST_RUN_DELAY = 10.minutes
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduledRun = Mutex()

    /** Hours between refreshes (`-Dsuwayomi.fork.extensionRefreshHours=N`, whole hours); 0 turns the schedule off. */
    val intervalHours: Long
        get() {
            val value = System.getProperty(INTERVAL_PROPERTY)?.trim()
            if (value.isNullOrEmpty()) return DEFAULT_INTERVAL_HOURS
            return value.toLongOrNull()?.coerceAtLeast(0)
                ?: DEFAULT_INTERVAL_HOURS.also {
                    logger.warn { "Ignoring -D$INTERVAL_PROPERTY=$value: expected whole hours; using $DEFAULT_INTERVAL_HOURS" }
                }
        }

    /** Schedules [runOnce], first 10 min after start; returns the scheduler's task id, or null when it's off. */
    fun schedule(): String? {
        val hours = intervalHours
        if (hours == 0L) {
            logger.info { "Scheduled extension refresh is off ($INTERVAL_PROPERTY=0)" }
            return null
        }
        return HAScheduler.schedule(
            { scope.launch { runScheduled() } },
            hours.hours.inWholeMilliseconds,
            FIRST_RUN_DELAY.inWholeMilliseconds,
            "fork-extension-refresh",
        )
    }

    /**
     * One scheduled refresh at a time: a refresh can wait up to 3 h for a library update, longer than a short interval.
     * A failure is logged, not thrown. Returns false if the previous refresh was still running, so this one was skipped.
     */
    internal suspend fun runScheduled(run: suspend () -> Unit = { runOnce() }): Boolean {
        if (!scheduledRun.tryLock()) {
            logger.info { "Scheduled extension refresh skipped: the previous one is still running" }
            return false
        }
        try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Scheduled extension refresh failed" }
        } finally {
            scheduledRun.unlock()
        }
        return true
    }

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

    /** Off unless `-Dsuwayomi.fork.autoUpdateExtensions=true` (any case). A value it can't read is logged and leaves it off. */
    val autoUpdateEnabled: Boolean
        get() =
            when (val value = System.getProperty(AUTO_UPDATE_PROPERTY)?.trim()?.lowercase()) {
                null, "", "false" -> false
                "true" -> true
                else -> false.also { logger.warn { "Ignoring -D$AUTO_UPDATE_PROPERTY=$value: expected true or false" } }
            }

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
        // The wait can last a whole library update, so look again: an extension uninstalled or updated by hand meanwhile
        // has no update left to install. One whose store wasn't read this time (say, after a restart with the store down)
        // has no update information to install from, so it waits for a refresh that reads its store.
        val (toInstall, unread) = installedWithUpdates().partition { ExtensionsList.updateMap.containsKey(it.pkgName) }
        if (unread.isNotEmpty()) {
            logger.info {
                "Extension auto-update skipped ${unread.joinToString { it.name }}: no update information from their store this time"
            }
        }
        for ((index, extension) in toInstall.withIndex()) {
            // a library update that started meanwhile would fail the reloaded source's series
            if (index > 0 && libraryUpdateRunning()) {
                logger.info {
                    "Extension auto-update paused: a library update started; the next refresh installs the other ${toInstall.size - index}"
                }
                return RefreshResult(withUpdates, updated, failed, deferred = true)
            }
            try {
                install(extension.pkgName)
                updated += extension.pkgName
                logger.info { "Auto-updated extension ${extension.name}" }
            } catch (e: Throwable) {
                // Loading a new version can also fail with a LinkageError (a class AndroidCompat lacks, a failing static
                // initializer); that one extension fails, the rest still update.
                if (e is CancellationException || (e !is Exception && e !is LinkageError)) throw e
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
