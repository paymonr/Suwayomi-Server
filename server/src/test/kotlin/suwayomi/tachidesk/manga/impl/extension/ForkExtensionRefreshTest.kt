package suwayomi.tachidesk.manga.impl.extension

import io.javalin.Javalin
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** F3 against a fixture store (legacy format: repo.json + index.min.json) served by a local Javalin. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForkExtensionRefreshTest : ApplicationTest() {
    private lateinit var store: Javalin

    @Volatile private var listing = emptyList<String>()

    @Volatile private var indexStatus = 200
    private val base get() = "http://127.0.0.1:${store.port()}/repo"
    private val indexUrl get() = "$base/repo.json"
    private val prefix = "eu.kanade.tachiyomi.extension.en.f3"

    @BeforeAll
    fun startStore() {
        store =
            Javalin
                .create { config ->
                    config.routes.get("/repo/repo.json") { ctx ->
                        ctx.contentType("application/json").result(
                            """{"index_v2":null,"meta":{"name":"F3 fixture","shortName":"F3","website":"https://fixture.invalid","signingKeyFingerprint":"00"}}""",
                        )
                    }
                    config.routes.get("/repo/index.min.json") { ctx ->
                        ctx.status(indexStatus).contentType("application/json").result(listing.joinToString(",", "[", "]"))
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
    }

    @AfterEach
    fun deleteFixtureExtensions() {
        transaction { ExtensionTable.deleteWhere { ExtensionTable.pkgName like "$prefix%" } }
        listing = emptyList()
        indexStatus = 200
    }

    @AfterAll
    fun stopStore() {
        transaction { ExtensionStoreTable.deleteWhere { ExtensionStoreTable.indexUrl eq indexUrl } }
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
                it[storeIndexUrl] = indexUrl
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
}
