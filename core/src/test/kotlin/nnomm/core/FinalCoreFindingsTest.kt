package nnomm.core

import io.ktor.client.plugins.*
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.settings.defaultSettings
import nnomm.core.store.AppStore

@OptIn(ExperimentalCoroutinesApi::class)
class FinalCoreFindingsTest {
    private class Folder : GameFolder by Demo.io().gameFolder {
        val plans = mutableListOf<GamePlan>()
        var loaders = 0
        var succeeds = true
        var recoveries = 0
        override val applySucceeded
            get() = succeeds

        override suspend fun apply(plan: GamePlan) {
            plans += plan
        }

        override suspend fun installLoader(folder: String) {
            loaders++
        }

        override suspend fun recoverPending() {
            recoveries++
        }
    }

    private fun initial(hidden: Boolean = false) =
        AppState(
            firstStart = FirstStart(true),
            game = GameInfo("sandbox", loaderPresent = false),
            settings =
                defaultSettings()
                    .copy(
                        general =
                            defaultSettings().general + ("showProfiles" to JsonPrimitive(!hidden))
                    ),
            installed =
                mapOf(
                    "m" to
                        InstalledMod(
                            "m",
                            versions = setOf("1"),
                            defaultVersion = "1",
                            profiles = mapOf("default" to ProfileMod(true, "1")),
                        )
                ),
        )

    private fun launcher(flow: MutableStateFlow<Boolean>) =
        object : GameLauncher {
            override val running = flow

            override suspend fun start(state: AppState, withoutMods: Boolean) {}
        }

    @Test
    fun freshStartupExternalPlayAndExitIsReadOnly() = runTest {
        for (hidden in listOf(false, true)) {
            val flow = MutableStateFlow(false)
            val folder = Folder()
            val app =
                AppStore(
                    initial(hidden),
                    Demo.io().copy(gameFolder = folder, launcher = launcher(flow)),
                    backgroundScope,
                )
            runCurrent()
            flow.value = true
            runCurrent()
            flow.value = false
            runCurrent()
            assertTrue(folder.plans.isEmpty(), "External play must not authorize apply")
            assertEquals(0, folder.loaders)
            assertFalse(app.state.value.deferredModChange)
            app.shutdown()
        }
    }

    @Test
    fun modActionDuringPlayAppliesOnExitWithoutInstallingLoader() = runTest {
        val flow = MutableStateFlow(true)
        val folder = Folder()
        val app =
            AppStore(
                initial().copy(gameRunning = true),
                Demo.io().copy(gameFolder = folder, launcher = launcher(flow)),
                backgroundScope,
            )
        runCurrent()
        app.setOn(listOf("m"), false)
        runCurrent()
        app.setOn(listOf("m"), true)
        runCurrent()
        assertTrue(app.state.value.deferredModChange)
        assertTrue(folder.plans.isEmpty())
        flow.value = false
        runCurrent()
        assertEquals(mapOf("m" to "1"), folder.plans.single().versions)
        assertEquals(0, folder.loaders)
        assertFalse(app.state.value.deferredModChange)
        app.shutdown()
    }

    @Test
    fun playWithModsInstallsLoaderAfterDeferredExitAppliedThePlan() = runTest {
        val flow = MutableStateFlow(true)
        val folder = Folder()
        val app =
            AppStore(
                initial().copy(gameRunning = true),
                Demo.io().copy(gameFolder = folder, launcher = launcher(flow)),
                backgroundScope,
            )
        runCurrent()
        app.setOn(listOf("m"), true)
        runCurrent()
        flow.value = false
        runCurrent()
        assertEquals(0, folder.loaders)
        app.play()
        runCurrent()
        assertEquals(1, folder.loaders)
        app.shutdown()
    }

    @Test
    fun refusedWriteSurvivesRestartAndRetriesAtExit() = runTest {
        val home = Files.createTempDirectory("nnomm-deferred-")
        try {
            val flow = MutableStateFlow(false)
            val folder = Folder().also { it.succeeds = false }
            val persistence = RealPersistence(home)
            val io =
                Demo.io()
                    .copy(gameFolder = folder, launcher = launcher(flow), persistence = persistence)
            val app = AppStore(initial(), io, backgroundScope)
            runCurrent()
            app.setOn(listOf("m"), false)
            runCurrent()
            app.shutdown()
            val saved = persistence.load()!!
            assertTrue(saved.deferredModChange)
            folder.plans.clear()
            folder.succeeds = true
            flow.value = true
            val reopened = AppStore(saved.copy(gameRunning = true), io, backgroundScope)
            runCurrent()
            flow.value = false
            runCurrent()
            assertEquals(emptyMap(), folder.plans.single().versions)
            assertEquals(0, folder.loaders)
            reopened.shutdown()
            assertFalse(persistence.load()!!.deferredModChange)
        } finally {
            deleteTree(home)
        }
    }

    @Test
    fun savedOfficialFallbackKeepsTimeAndReportsOffline() = runBlocking {
        val home = Files.createTempDirectory("nnomm-offline-")
        val client = networkClient("test")
        try {
            client.plugin(HttpSend).intercept { error("Network disabled by test") }
            val catalog = listOf(CatalogMod("m", "Saved"))
            JsonFile(home.resolve("official-registry.json"), SavedRegistry.serializer())
                .write(SavedRegistry(catalog, 123L))
            val app =
                AppStore(
                    initial(),
                    Demo.io()
                        .copy(
                            registry = RealRegistry(client, home),
                            gameFolder = Folder(),
                            clock = Clock { 999L },
                        ),
                )
            try {
                app.refreshRegistry()
                withTimeout(10000) {
                    while (app.state.value.connection.kind == ConnectionKind.LOADING) delay(10)
                }
                assertEquals(ConnectionKind.OFFLINE, app.state.value.connection.kind)
                assertEquals(123L, app.state.value.connection.readAtMillis)
                assertEquals("Saved", app.state.value.mod("m").name)
                assertFalse(home.resolve("registry.json").exists())
            } finally {
                app.shutdown()
            }
        } finally {
            client.close()
            deleteTree(home)
        }
    }

    @Test
    fun clearUsesDurableProvenanceAndActualPayloadHashes() = runBlocking {
        val home = Files.createTempDirectory("nnomm-provenance-")
        try {
            val archive = home.resolve("mod.zip")
            java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use {
                it.putNextEntry(java.util.zip.ZipEntry("m.dll"))
                it.write("shipped".toByteArray())
                it.closeEntry()
            }
            val payload = home.resolve("payload").createDirectories()
            payload.resolve("m.dll").writeText("shipped")
            val store = RealModStore(home)
            val hash = digest(archive)
            for (id in listOf("download", "equal-takeover", "edited-takeover")) store.put(
                id,
                "1",
                DownloadedFile(archive.toString(), PayloadOrigin.REGISTRY_DOWNLOAD, hash),
            )
            store.put(
                "equal-takeover",
                "1",
                DownloadedFile(payload.toString(), PayloadOrigin.TAKEOVER),
            )
            payload.resolve("m.dll").writeText("personal")
            store.put(
                "edited-takeover",
                "1",
                DownloadedFile(payload.toString(), PayloadOrigin.TAKEOVER),
            )
            for (origin in
                listOf(PayloadOrigin.LOCAL_FILE, PayloadOrigin.PACK, PayloadOrigin.UNKNOWN)) store
                .put(origin.name, "1", DownloadedFile(payload.toString(), origin))
            store.put(
                "unverified",
                "1",
                DownloadedFile(archive.toString(), PayloadOrigin.REGISTRY_DOWNLOAD),
            )
            // Reopening loses transient Undo protection, as after a normal later startup.
            val reopened = RealModStore(home)
            val cleared = reopened.unused(AppState()).map { it.parent.name }.toSet()
            assertEquals(setOf("download", "equal-takeover"), cleared)
            reopened.clearUnused(AppState())
            for (id in
                listOf(
                    "edited-takeover",
                    "LOCAL_FILE",
                    "PACK",
                    "UNKNOWN",
                    "unverified",
                )) assertTrue(reopened.hasVersion(id, "1"), id)
            assertTrue(home.resolve("provenance/download/1.json").exists())
        } finally {
            deleteTree(home)
        }
    }

    @Test
    fun homePreparationCleanupOnlyRemovesDisposableCopies() {
        val home = Files.createTempDirectory("nnomm-preparation-")
        try {
            for (name in
                listOf(
                    "player-abandoned",
                    "edits-abandoned",
                    "config-abandoned",
                    "download-active",
                )) {
                home.resolve("staging/$name").createDirectories().resolve("bytes").writeText("copy")
            }
            home
                .resolve("player-files/default/m")
                .createDirectories()
                .resolve("save")
                .writeText("unique")
            cleanHomePreparation(home)
            cleanHomePreparation(home)
            for (name in
                listOf("player-abandoned", "edits-abandoned", "config-abandoned")) assertFalse(
                home.resolve("staging/$name").exists()
            )
            assertTrue(home.resolve("staging/download-active/bytes").exists())
            assertEquals("unique", home.resolve("player-files/default/m/save").readText())
        } finally {
            deleteTree(home)
        }
    }
}
