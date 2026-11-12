package nnomm.core

import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import kotlin.test.*
import kotlinx.coroutines.*
import nnomm.core.demo.Demo
import nnomm.core.io.GameFolder
import nnomm.core.io.StatePersistence
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.core.settings.*
import nnomm.core.store.AppStore
import nnomm.core.store.loaderFailureHelp

class LastFindingsTest {
    @Test
    fun loaderFailureReasonsDoNotExposeDetailsOrInventNetworkFailures() {
        val errors =
            listOf(
                AccessDeniedException("private/path") to "Check that NNOMM can change files",
                FileSystemException("private/path", null, "No space left on device") to
                    "Free some space",
                java.util.zip.ZipException("private/path") to "could not be checked",
                java.net.UnknownHostException("private/host") to "Check your connection",
                IllegalStateException("private/path") to "Check the game folder",
            )
        errors.forEach { (error, expected) ->
            val help = loaderFailureHelp(Exception(error))
            assertTrue(help.contains(expected), help)
            assertFalse(help.contains("private"))
        }
    }

    @Test
    fun loaderInstallAndRemovalFailuresOfferWorkingRetry() = runBlocking {
        for (install in listOf(true, false)) {
            val io = Demo.io()
            var failing = true
            val calls = mutableListOf<String>()
            val folder =
                object : GameFolder by io.gameFolder {
                    override suspend fun installLoader(folder: String) {
                        calls += folder
                        if (failing) throw AccessDeniedException("secret")
                    }

                    override suspend fun removeLoader(folder: String) {
                        calls += folder
                        if (failing) throw AccessDeniedException("secret")
                    }
                }
            val state =
                Demo.state().let { s ->
                    s.copy(
                        game = s.game.copy(loaderPresent = false),
                        installed = if (install) s.installed else emptyMap(),
                    )
                }
            val app = AppStore(state, io.copy(gameFolder = folder, persistence = null))
            try {
                if (install) app.play() else app.removeEverything()
                withTimeout(5000) {
                    while (app.state.value.toasts.none { it.action == "Retry" }) delay(10)
                }
                val retry = app.state.value.toasts.last { it.action == "Retry" }
                assertTrue(retry.text.contains("Check that NNOMM can change files"), retry.text)
                assertFalse(retry.text.contains("connection"))
                assertFalse(retry.text.contains("secret"))
                if (!install) {
                    app.setSetting(
                        "gamePath",
                        kotlinx.serialization.json.JsonPrimitive("another-sandbox"),
                    )
                    withTimeout(5000) {
                        while (app.state.value.game.folder != "another-sandbox") delay(10)
                    }
                }
                failing = false
                app.runToast(retry.id)
                withTimeout(5000) { while (calls.size < 2) delay(10) }
                if (!install) assertEquals("another-sandbox", calls.last())
            } finally {
                app.shutdown()
            }
        }
    }

    @Test
    fun offCollisionOffersEnabledPeersByDisplayName() {
        val s = Demo.state()
        val off = "satellite-map"
        val peers = setOf("minimap", "delivery")
        val state =
            s.copy(
                applyProblems =
                    mapOf(
                        off to
                            "This mod is still in the game because other mods use the same game folder name. Turn off one of them and Retry."
                    ),
                placementConflicts = mapOf(off to peers),
            )
        val fixes = problemFixes(state, off, problems(state, off).single { it.key == "placement" })
        assertEquals("Retry", fixes.first().label)
        assertEquals(peers, fixes.drop(1).map { it.action.id }.toSet())
        fixes.drop(1).forEach {
            assertEquals("Turn off ${state.mod(it.action.id!!).name}", it.label)
        }
    }

    @Test
    fun storageUsesSharedUnitsAndMarksFailedEstimateAsOld() {
        val s =
            AppState(
                settings = defaultSettings(),
                storeUsedBytes = 1_950_000,
                storeUnusedKb = 53,
                storeScanStatus = LoadStatus.FAILED,
            )
        val store = settingHelp(SETTINGS.first { it.key == "store" }, s)
        val cache = settingHelp(SETTINGS.first { it.key == "cache" }, s)
        assertTrue(store.contains(size((1_950_000L + 1023) / 1024)))
        assertTrue(cache.contains("Last calculated: 53 KB"))
        assertFalse(cache.contains("Retry."))
        assertEquals("1 mod", plural(1, "mod", "mods"))
        assertEquals("2 mods", plural(2, "mod", "mods"))
    }

    @Test
    fun scrolledAwayMetadataIsSkipped() = runBlocking {
        val backgroundStarted = CompletableDeferred<Unit>()
        val backgroundRelease = CompletableDeferred<Unit>()
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val meta =
            object : nnomm.core.io.MetaSource {
                override suspend fun metadata(
                    mod: CatalogMod,
                    version: ModVersion,
                ): nnomm.core.io.VersionMetadata {
                    synchronized(calls) { calls += mod.id }
                    if (mod.id == "background") {
                        backgroundStarted.complete(Unit)
                        backgroundRelease.await()
                    }
                    if (mod.id == "a") {
                        first.complete(Unit)
                        release.await()
                    }
                    return nnomm.core.io.VersionMetadata(sizeKb = 1, dateMillis = null)
                }
            }
        val mods =
            listOf("background", "a", "b", "c").map {
                CatalogMod(
                    it,
                    it,
                    versions = listOf(ModVersion("1", downloadUrl = "https://example.com/$it")),
                )
            }
        val app =
            AppStore(
                AppState(catalog = mods, settings = defaultSettings()),
                Demo.io().copy(meta = meta, persistence = null),
                scope = this,
            )
        try {
            withTimeout(5000) { backgroundStarted.await() }
            app.loadMetadata(setOf("a"))
            withTimeout(5000) { first.await() }
            app.loadMetadata(setOf("a", "b"))
            app.loadMetadata(setOf("c"))
            release.complete(Unit)
            withTimeout(5000) { while (synchronized(calls) { "c" !in calls }) delay(10) }
            assertEquals(
                listOf("a", "c"),
                synchronized(calls) { calls.filter { it != "background" } },
            )
        } finally {
            release.complete(Unit)
            backgroundRelease.complete(Unit)
            app.shutdown()
        }
    }

    @Test
    fun removalUsesSingularAndExplainsNoFolder() = runBlocking {
        val s = Demo.state()
        val app =
            AppStore(
                s.copy(
                    game = GameInfo(),
                    installed = mapOf("minimap" to s.installed.getValue("minimap")),
                ),
                Demo.io().copy(persistence = null),
            )
        try {
            app.removeEverything()
            app.awaitDiskIdle()
            val text = app.state.value.toasts.joinToString { it.text }
            assertTrue(text.contains("Kept 1 mod in the Library"), text)
            assertFalse(text.contains("1 registry mods"))
            assertFalse(text.contains("1 local"))
            assertFalse(text.contains("Updated the Library"))
            assertTrue(text.contains("press Play"))
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun saveFailureWithoutFolderDoesNotClaimGameRemoval() = runBlocking {
        val s = Demo.state()
        val failingSave =
            object : StatePersistence {
                override suspend fun load(): AppState? = null

                var failures = 1

                override suspend fun save(state: AppState) {
                    // Fail the save inside the removal; let the shutdown flush succeed.
                    if (failures-- > 0) throw java.nio.file.AccessDeniedException("state.json")
                }
            }
        val app =
            AppStore(
                s.copy(
                    game = GameInfo(),
                    installed = mapOf("minimap" to s.installed.getValue("minimap")),
                ),
                Demo.io().copy(persistence = failingSave),
            )
        try {
            app.removeEverything()
            app.awaitDiskIdle()
            val text = app.state.value.toasts.joinToString { it.text }
            assertTrue(text.contains("The Library changed, but it could not be saved"), text)
            assertFalse(text.contains("removed from the game"), text)
        } finally {
            app.shutdown()
        }
    }
}
