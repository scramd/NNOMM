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
    @Test
    fun takeoverInstallProfilesUninstallUndoRepairClearRestart() = runBlocking {
        val home = Files.createTempDirectory("nnomm-store-")
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
