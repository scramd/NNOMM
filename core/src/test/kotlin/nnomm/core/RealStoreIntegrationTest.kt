package nnomm.core

import java.nio.file.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.store.AppStore

class RealStoreIntegrationTest {
    private val temporary = mutableListOf<java.nio.file.Path>()

    @kotlin.test.AfterTest
    fun cleanupTemporaryFiles() {
        temporary.asReversed().forEach { nnomm.core.io.real.deleteTree(it) }
    }

    @Test
    fun registryRefreshKeepsMetadataOnlineAndOffline() = runBlocking {
        val raw =
            CatalogMod(
                "m",
                "Mod",
                versions = listOf(ModVersion("1", downloadUrl = "https://example.com/mod.dll")),
            )
        val enriched =
            raw.copy(versions = listOf(raw.versions.single().copy(sizeKb = 52, dateMillis = 1234)))
        var offline = false
        val registry =
            object : Registry {
                override suspend fun read(): List<CatalogMod> {
                    if (offline) error("offline")
                    return listOf(raw)
                }

                override suspend fun saved() = listOf(raw) to 1L

                override suspend fun save(mods: List<CatalogMod>, readAtMillis: Long) {}
            }
        val store =
            AppStore(AppState(catalog = listOf(enriched)), Demo.io().copy(registry = registry))
        try {
            for (failure in listOf(false, true)) {
                offline = failure
                store.refreshRegistry()
                withTimeout(5000) {
                    while (store.state.value.connection.kind == ConnectionKind.LOADING) delay(10)
                }
                val version = store.state.value.catalog.single().versions.single()
                assertEquals(52L, version.sizeKb)
                assertEquals(1234L, version.dateMillis)
            }
        } finally {
            store.shutdown()
        }
    }

    @Test
    fun firstStartWaitsForConfirmationAndPersistsIt() = runBlocking {
        val home = Files.createTempDirectory("nnomm-first-").also { temporary.add(it) }
        val game = home.resolve("game").createDirectories()
        game.resolve("NuclearOption.exe").writeText("")
        game
            .resolve("BepInEx/plugins/Existing")
            .createDirectories()
            .resolve("old.dll")
            .writeText("old")
        val persistence = RealPersistence(home)
        persistence.write(
            nnomm.core.settings.defaultSettings().let {
                it.copy(
                    general =
                        it.general +
                            mapOf(
                                "gamePath" to
                                    kotlinx.serialization.json.JsonPrimitive(game.toString()),
                                "checkUpdates" to
                                    kotlinx.serialization.json.JsonPrimitive("Only when I ask"),
                                "store" to
                                    kotlinx.serialization.json.JsonPrimitive(
                                        home.resolve("mods").toString()
                                    ),
                                "sources" to kotlinx.serialization.json.JsonPrimitive(""),
                            )
                )
            }
        )
        val runtime = RealRuntime.open(home)
        try {
            assertFailsWith<IllegalArgumentException> { runtime.store.io.registry.read() }
            assertFalse(runtime.store.state.value.firstStart.complete)
            assertTrue(runtime.store.state.value.installed.isEmpty())
            assertFalse(home.resolve("mods").exists())
            assertEquals("old", game.resolve("BepInEx/plugins/Existing/old.dll").readText())
            runtime.store.confirmFirstStart(game.toString())
            assertTrue(runtime.store.state.value.firstStart.complete)
            assertEquals(1, runtime.store.state.value.firstStart.existingOn)
            assertEquals("old", game.resolve("BepInEx/plugins/Existing/old.dll").readText())
            assertFalse(game.resolve("BepInEx/plugins/.nnomm-state.json").exists())
        } finally {
            runtime.shutdown()
        }
        val reopened = RealRuntime.open(home)
        try {
            assertTrue(reopened.store.state.value.firstStart.complete)
            assertEquals(1, reopened.store.state.value.installed.size)
        } finally {
            reopened.shutdown()
        }
    }

    @Test
    fun takeoverInstallProfilesUninstallUndoRepairClearRestart() = runBlocking {
        val home = Files.createTempDirectory("nnomm-store-").also { temporary.add(it) }
        val game = home.resolve("game").createDirectories()
        game.resolve("NuclearOption.exe").writeText("")
        game.resolve("BepInEx/core").createDirectories().resolve("BepInEx.dll").writeText("loader")
        game
            .resolve("BepInEx/plugins/Existing")
            .createDirectories()
            .resolve("old.dll")
            .writeText("old")
        val payload = home.resolve("payload").createDirectories()
        payload.resolve("mod.dll").writeText("good")
        val mod = CatalogMod("m", "Mod", versions = listOf(ModVersion("1")))
        val mods = RealModStore(home)
        val downloader =
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ) = DownloadedFile(payload.toString())
            }
        val folder = RealGameFolder(home, mods, downloader, running = { false })
        val persistence = RealPersistence(home)
        val io =
            Demo.io()
                .copy(
                    modStore = mods,
                    gameFolder = folder,
                    downloader = downloader,
                    persistence = persistence,
                    settingsFile = persistence,
                )
        val store =
            AppStore(
                AppState(
                    catalog = listOf(mod),
                    settings =
                        nnomm.core.settings.defaultSettings().let {
                            it.copy(
                                general =
                                    it.general +
                                        mapOf(
                                            "checkUpdates" to
                                                kotlinx.serialization.json.JsonPrimitive(
                                                    "Only when I ask"
                                                ),
                                            "store" to
                                                kotlinx.serialization.json.JsonPrimitive(
                                                    home.resolve("mods").toString()
                                                ),
                                        )
                            )
                        },
                ),
                io,
            )
        suspend fun until(predicate: () -> Boolean) {
            withTimeout(10000) {
                while (
                    !try {
                        predicate()
                    } catch (_: NoSuchFileException) {
                        false
                    }
                ) delay(20)
            }
            store.awaitDiskIdle()
        }
        try {
            store.takeOverExistingMods(game.toString())
            until { store.state.value.installed.isNotEmpty() }
            store.install("m")
            val placed = game.resolve("BepInEx/plugins/m/mod.dll")
            until { placed.exists() }
            assertEquals("old", game.resolve("BepInEx/plugins/Existing/old.dll").readText())
            val other = store.newProfile("Other", copyCurrent = false)
            until { !placed.exists() }
            store.useProfile("default")
            until { placed.exists() }
            store.uninstall(listOf("m"))
            until { !placed.exists() }
            store.undo()
            until { placed.exists() }
            placed.writeText("corrupt")
            store.repair("m")
            until { placed.exists() && placed.readText() == "good" }
            store.clearUnusedFiles()
            store.shutdown()
            val loaded = persistence.load()!!
            assertTrue("m" in loaded.installed)
            assertTrue(loaded.profiles.any { it.id == other })
            assertEquals("good", placed.readText())
            val reopened = AppStore(loaded, io)
            val newId = reopened.newProfile("New", copyCurrent = true)
            assertNotEquals(other, newId)
            reopened.shutdown()
            val runtime = RealRuntime.open(home)
            try {
                assertTrue("m" in runtime.store.state.value.installed)
                assertEquals("good", placed.readText())
            } finally {
                runtime.shutdown()
            }
        } finally {
            store.shutdown()
        }
    }
}
