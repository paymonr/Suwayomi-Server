package suwayomi.tachidesk.manga.impl.extension

import io.javalin.Javalin
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.test.ApplicationTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** F3 against a fixture store (legacy format: repo.json + index.min.json) served by a local Javalin. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForkExtensionRefreshTest : ApplicationTest() {
    private lateinit var store: Javalin

    @Volatile private var listing = emptyList<String>()

    @Volatile private var indexStatus = 200

    @Volatile private var repoStatus = 200

    /** A second store's listing, for a package two stores list. */
    @Volatile private var otherListing = emptyList<String>()
    private val base get() = "http://127.0.0.1:${store.port()}/repo"
    private val indexUrl get() = "$base/repo.json"
    private val otherIndexUrl get() = "http://127.0.0.1:${store.port()}/other/repo.json"
    private val prefix = "eu.kanade.tachiyomi.extension.en.f3"

    @BeforeAll
    fun startStore() {
        store =
            Javalin
                .create { config ->
                    config.routes.get("/repo/repo.json") { ctx ->
                        ctx.status(repoStatus).contentType("application/json").result(
                            """{"index_v2":null,"meta":{"name":"F3 fixture","shortName":"F3","website":"https://fixture.invalid","signingKeyFingerprint":"00"}}""",
                        )
                    }
                    config.routes.get("/repo/index.min.json") { ctx ->
                        ctx.status(indexStatus).contentType("application/json").result(listing.joinToString(",", "[", "]"))
                    }
                    config.routes.get("/other/repo.json") { ctx ->
                        ctx.contentType("application/json").result(
                            """{"index_v2":null,"meta":{"name":"F3 other","shortName":"F3O","website":"https://fixture.invalid","signingKeyFingerprint":"00"}}""",
                        )
                    }
                    config.routes.get("/other/index.min.json") { ctx ->
                        ctx.contentType("application/json").result(otherListing.joinToString(",", "[", "]"))
                    }
                }.start(0)
        ExtensionStoreService.upsert(
            ExtensionStore(
                indexUrl,
                "F3 fixture",
                "F3",
                "00",
                ExtensionStore.Contact("https://fixture.invalid", null),
                isLegacy = true,
                extensionListUrl = null,
            ),
        )
        ExtensionStoreService.upsert(
            ExtensionStore(
                otherIndexUrl,
                "F3 other",
                "F3O",
                "00",
                ExtensionStore.Contact("https://fixture.invalid", null),
                isLegacy = true,
                extensionListUrl = null,
            ),
        )
    }

    @AfterEach
    fun deleteFixtureExtensions() {
        transaction { ExtensionTable.deleteWhere { ExtensionTable.pkgName like "$prefix%" } }
        listing = emptyList()
        otherListing = emptyList()
        indexStatus = 200
        repoStatus = 200
        ExtensionsList.updateMap.keys.removeIf { it.startsWith(prefix) }
    }

    @AfterAll
    fun stopStore() {
        // captured first: inside deleteWhere the table is the receiver, so `indexUrl` would mean its column
        val fixtureStores = listOf(indexUrl, otherIndexUrl)
        transaction { ExtensionStoreTable.deleteWhere { ExtensionStoreTable.indexUrl inList fixtureStores } }
        store.stop()
    }

    private fun pkg(name: String) = "$prefix$name"

    /** A store index entry for [pkg] at version 1.4.[code]. */
    private fun entry(
        pkg: String,
        code: Long,
    ) = """{"name":"Tachiyomi: ${pkg.removePrefix(prefix)}","pkg":"$pkg","apk":"${pkg.removePrefix(prefix)}-v1.4.$code.apk",""" +
        """"lang":"en","version":"1.4.$code","code":$code,"nsfw":0}"""

    private fun installed(
        pkg: String,
        code: Long,
        hasUpdate: Boolean = false,
    ) {
        transaction {
            ExtensionTable.insert {
                it[name] = pkg.removePrefix(prefix)
                it[pkgName] = pkg
                it[versionName] = "1.4.$code"
                it[versionCode] = code
                it[lang] = "en"
                it[contentWarning] = 0
                it[isInstalled] = true
                it[this.hasUpdate] = hasUpdate
                it[storeIndexUrl] = indexUrl
                it[apkName] = "${pkg.removePrefix(prefix)}-v1.4.$code.apk"
                it[apkUrl] = "$base/apk/${pkg.removePrefix(prefix)}-v1.4.$code.apk"
                it[extensionLib] = "1.4"
            }
        }
    }

    private fun row(pkg: String) = transaction { ExtensionTable.selectAll().where { ExtensionTable.pkgName eq pkg }.firstOrNull() }

    @Test
    fun `a newer version in a store is found without a client request`() {
        val p = pkg("newer")
        installed(p, 1)
        listing = listOf(entry(p, 2))

        val result = runBlocking { ForkExtensionRefresh.runOnce() }

        assertEquals(listOf(p), result.withUpdates)
        assertEquals(true, row(p)!![ExtensionTable.hasUpdate])
    }

    @Test
    fun `an extension already on the store's version has no update`() {
        val p = pkg("current")
        installed(p, 3)
        listing = listOf(entry(p, 3))

        assertEquals(emptyList(), runBlocking { ForkExtensionRefresh.runOnce() }.withUpdates)
    }

    /** Review Focus 3: upstream marks an unreachable store's extensions obsolete and deletes its uninstalled entries. */
    @Test
    fun `a store that can't be reached leaves its extensions as they were`() {
        val kept = pkg("kept")
        val available = pkg("available")
        installed(kept, 1)
        listing = listOf(entry(kept, 1), entry(available, 1))
        runBlocking { ForkExtensionRefresh.runOnce() } // gives `available` its (uninstalled) row
        indexStatus = 500

        runBlocking { ForkExtensionRefresh.runOnce() }

        assertEquals(false, row(kept)!![ExtensionTable.isObsolete])
        assertNotNull(row(available), "the uninstalled entry was deleted")
    }

    @Test
    fun `without auto-update nothing is installed`() {
        val p = pkg("manual")
        installed(p, 1)
        listing = listOf(entry(p, 2))

        val result = runBlocking { ForkExtensionRefresh.runOnce(autoUpdate = false, install = { fail("installed without auto-update") }) }

        assertEquals(listOf(p), result.withUpdates)
        assertEquals(emptyList(), result.updated)
    }

    /** Review Focus 4. */
    @Test
    fun `auto-update installs each extension with an update, even when one fails`() {
        val broken = pkg("broken")
        val fine = pkg("fine")
        installed(broken, 1)
        installed(fine, 1)
        listing = listOf(entry(broken, 2), entry(fine, 2))
        val installs = mutableListOf<String>()

        val result =
            runBlocking {
                ForkExtensionRefresh.runOnce(autoUpdate = true, libraryUpdateRunning = { false }, install = { p ->
                    installs += p
                    if (p == broken) error("bad apk")
                })
            }

        assertEquals(setOf(broken, fine), installs.toSet())
        assertEquals(listOf(fine), result.updated)
        assertEquals(listOf(broken), result.failed)
    }

    /** Review Focus 4: a new version needing an Android class that AndroidCompat lacks fails with a LinkageError, not an Exception. */
    @Test
    fun `auto-update carries on past an extension that fails to load`() {
        val broken = pkg("noclass")
        val fine = pkg("loads")
        installed(broken, 1)
        installed(fine, 1)
        listing = listOf(entry(broken, 2), entry(fine, 2))

        val result =
            runBlocking {
                ForkExtensionRefresh.runOnce(autoUpdate = true, libraryUpdateRunning = { false }, install = { p ->
                    if (p == broken) throw NoClassDefFoundError("android/graphics/Missing")
                })
            }

        assertEquals(listOf(fine), result.updated)
        assertEquals(listOf(broken), result.failed)
    }

    /** The wait can last a whole library update; an extension uninstalled meanwhile mustn't come back. */
    @Test
    fun `auto-update skips an extension uninstalled while it waited`() {
        val p = pkg("removed")
        installed(p, 1)
        listing = listOf(entry(p, 2))
        val polls = AtomicInteger()

        val result =
            runBlocking {
                ForkExtensionRefresh.runOnce(
                    autoUpdate = true,
                    install = { fail("reinstalled an extension that was uninstalled") },
                    libraryUpdateRunning = {
                        if (polls.incrementAndGet() == 1) Extension.uninstallExtension(p)
                        polls.get() == 1
                    },
                    pollMs = 10,
                    maxWaitMs = 1_000,
                )
            }

        assertEquals(emptyList(), result.updated)
        assertEquals(false, row(p)!![ExtensionTable.isInstalled])
    }

    /** Review Focus 5: installing reloads the source, which would fail its series in a running library update. */
    @Test
    fun `auto-update waits for a running library update to finish`() {
        val p = pkg("waits")
        installed(p, 1)
        listing = listOf(entry(p, 2))
        val polls = AtomicInteger()

        val result =
            runBlocking {
                ForkExtensionRefresh.runOnce(
                    autoUpdate = true,
                    install = {},
                    libraryUpdateRunning = { polls.incrementAndGet() <= 2 },
                    pollMs = 10,
                    maxWaitMs = 1_000,
                )
            }

        assertEquals(listOf(p), result.updated)
        assertEquals(3, polls.get())
    }

    /** Review Focus 5. */
    @Test
    fun `auto-update gives up for this refresh if the library update keeps running`() {
        val p = pkg("givesup")
        installed(p, 1)
        listing = listOf(entry(p, 2))

        val result =
            runBlocking {
                ForkExtensionRefresh.runOnce(
                    autoUpdate = true,
                    install = { fail("installed during a library update") },
                    libraryUpdateRunning = { true },
                    pollMs = 10,
                    maxWaitMs = 50,
                )
            }

        assertTrue(result.deferred)
        assertEquals(emptyList(), result.updated)
    }

    @Test
    fun `auto-update is off unless the property turns it on`() {
        try {
            assertFalse(ForkExtensionRefresh.autoUpdateEnabled)
            System.setProperty(ForkExtensionRefresh.AUTO_UPDATE_PROPERTY, "true")
            assertTrue(ForkExtensionRefresh.autoUpdateEnabled)
        } finally {
            System.clearProperty(ForkExtensionRefresh.AUTO_UPDATE_PROPERTY)
        }
    }

    /** Review M5: a store whose repo.json fails is dropped before its index is read; its extensions stay as they were too. */
    @Test
    fun `a store whose repo json can't be read leaves its extensions as they were`() {
        val kept = pkg("keptrepo")
        val available = pkg("availablerepo")
        installed(kept, 1)
        listing = listOf(entry(kept, 1), entry(available, 1))
        runBlocking { ForkExtensionRefresh.runOnce() }
        repoStatus = 500

        runBlocking { ForkExtensionRefresh.runOnce() }

        assertEquals(false, row(kept)!![ExtensionTable.isObsolete])
        assertNotNull(row(available), "the uninstalled entry was deleted")
    }

    /** Review M4: another store listing the package (older) doesn't flag or repoint it while its own store is down. */
    @Test
    fun `an extension stays with its own store while that store can't be reached`() {
        val p = pkg("shared")
        installed(p, 2)
        otherListing = listOf(entry(p, 1))
        indexStatus = 500

        runBlocking { ForkExtensionRefresh.runOnce() }

        val row = row(p)!!
        assertEquals(false, row[ExtensionTable.isObsolete])
        assertEquals(indexUrl, row[ExtensionTable.storeIndexUrl])
    }

    /** Review M3: after a restart with its store down, a stale "has update" flag has no update information to install from. */
    @Test
    fun `auto-update skips an extension whose store wasn't read this time`() {
        val p = pkg("stale")
        installed(p, 1, hasUpdate = true)
        indexStatus = 500

        val result =
            runBlocking {
                ForkExtensionRefresh.runOnce(
                    autoUpdate = true,
                    libraryUpdateRunning = { false },
                    install = { fail("installed without update information") },
                )
            }

        assertEquals(listOf(p), result.withUpdates)
        assertEquals(emptyList(), result.failed)
    }

    /** Review M2: a library update that starts between installs stops the rest until the next refresh. */
    @Test
    fun `auto-update stops when a library update starts between installs`() {
        val first = pkg("first")
        val second = pkg("second")
        installed(first, 1)
        installed(second, 1)
        listing = listOf(entry(first, 2), entry(second, 2))
        val polls = AtomicInteger()
        val installs = mutableListOf<String>()

        val result =
            runBlocking {
                ForkExtensionRefresh.runOnce(
                    autoUpdate = true,
                    install = { installs += it },
                    libraryUpdateRunning = { polls.incrementAndGet() > 1 },
                    pollMs = 10,
                    maxWaitMs = 1_000,
                )
            }

        assertEquals(1, installs.size)
        assertEquals(installs, result.updated)
        assertTrue(result.deferred)
    }

    /** Review M8. */
    @Test
    fun `the auto-update property is read case-insensitively`() {
        try {
            System.setProperty(ForkExtensionRefresh.AUTO_UPDATE_PROPERTY, " TRUE ")
            assertTrue(ForkExtensionRefresh.autoUpdateEnabled)
            System.setProperty(ForkExtensionRefresh.AUTO_UPDATE_PROPERTY, "yes please")
            assertFalse(ForkExtensionRefresh.autoUpdateEnabled)
        } finally {
            System.clearProperty(ForkExtensionRefresh.AUTO_UPDATE_PROPERTY)
        }
    }
}
