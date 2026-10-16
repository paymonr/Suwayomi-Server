package suwayomi.tachidesk

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import suwayomi.tachidesk.server.util.CEFManager
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class CefTest {
    @Test
    fun downloadedJbrIsValidForJcef() =
        runTest {
            val tempDownload = Files.createTempDirectory("kcef")
            val module =
                module {
                    single {
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        }
                    }
                }
            // other test classes leave the app's Koin running, with a Json like this one; only start (and stop) one if none is.
            // Don't load this module over the app's: unloading it afterwards would take the app's Json with it.
            val startedHere = GlobalContext.getOrNull() == null
            if (startedHere) {
                startKoin {
                    modules(module)
                }
            }
            try {
                CEFManager.downloadRelease(tempDownload)
                assertTrue { CEFManager.isInstallationValid(tempDownload / "release") }
            } finally {
                tempDownload.deleteRecursively()
                if (startedHere) stopKoin()
            }
        }
}
