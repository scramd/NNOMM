package nnomm.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.demo.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.rules.*

class AcceptanceFeaturesTest {
    @Test
    fun placementErrorsUseNamesAndUsefulActions() {
        val text =
            placementMessage("Nuclear Minimap", java.io.IOException("internal-id /private/path"))
        assertTrue(text.contains("Nuclear Minimap"))
        assertTrue(text.contains("try again"))
        assertFalse(text.contains("/private/path"))
        assertTrue(
            placementMessage("Minimap", IllegalArgumentException("Stored files are missing"))
                .contains("Choose Repair")
        )
    }

    @Test
    fun sharedPackCanBeFollowedAndUpdated() = runBlocking {
        val home = Files.createTempDirectory("pack-follow-")
        val client = networkClient("test")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val file = home.resolve("shared.nommpack")
        server.createContext("/pack") { exchange ->
            val bytes = file.readBytes()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val packs = RealPacks(home, RealModStore(home), client)
        val store = nnomm.core.store.AppStore(Demo.state(), Demo.io().copy(packSource = packs))
        try {
            val state = store.state.value
            val address = "http://127.0.0.1:${server.address.port}/pack"
            val original =
                Pack(
                    "Shared",
                    "Tester",
                    "1.0",
                    listOf(PackItem(PackKind.VER, "minimap", "0.5.2")),
                    followAddress = address,
                )
            packs.write(file, original, state)
            packs.read(file).use { preview -> store.importPack(preview.pack) }
            val profile = store.state.value.profile()
            assertEquals(address, profile.pack?.followAddress)
            val newer =
                original.copy(
                    version = "2.0",
                    items = original.items + PackItem(PackKind.VER, "wingman", "0.6.0"),
                )
            packs.write(file, newer, state)
            store.checkPackUpdates()
            withTimeout(5000) {
                while (store.state.value.profile().pack?.nextVersion != "2.0") delay(10)
            }
            val update = assertNotNull(packs.following(profile.pack!!))
            assertEquals("2.0", update.version)
            assertTrue(
                packUpdateRows(store.state.value, update).any {
                    it.id == "wingman" && it.kind == PackKind.ADD
                }
            )
            store.updatePack(update)
            assertEquals("2.0", store.state.value.profile().pack?.version)
            assertNull(packs.following(store.state.value.profile().pack!!))
            file.writeText(json.encodeToString(listOf(PackageReference("minimap", "0.5.2"))))
            packs.read(file).use { assertEquals("minimap", it.pack.items.single().id) }
            file.writeText(
                "{\"format\":\"NNOMM/1\",\"pack\":" +
                    json.encodeToString(newer.copy(version = "3.0")) +
                    "}"
            )
            assertEquals("3.0", packs.following(store.state.value.profile().pack!!)?.version)
        } finally {
            store.shutdown()
            server.stop(0)
            client.close()
            deleteTree(home)
        }
    }

    @Test
    fun configMappingExportsGeneratedConfigAndImportsOnlyToSnapshot() = runBlocking {
        val home = Files.createTempDirectory("pack-settings-")
        val client = networkClient("test")
        val store = demoStore()
        try {
            val game = home.resolve("game").createDirectories()
            game
                .resolve("BepInEx/config")
                .createDirectories()
                .resolve("minimap.cfg")
                .writeText("Zoom = 3")
            game.resolve("BepInEx/config/unrelated.cfg").writeText("Private = true")
            val mods = RealModStore(home)
            mods
                .version("minimap", "1")
                .createDirectories()
                .resolve("minimap.dll")
                .writeText("fixture")
            val packs = RealPacks(home, mods, client)
            val state = store.state.value.copy(game = GameInfo(game.toString()))
            val pack =
                Pack(
                    "Settings",
                    "Tester",
                    "1",
                    listOf(PackItem(PackKind.VER, "minimap", "1")),
                    includeSettings = true,
                )
            val file = home.resolve("settings.nommpack")
            packs.write(file, pack, state)
            packs.read(file).use { preview ->
                assertEquals(
                    "Zoom = 3",
                    preview.modSettingsFolder!!.resolve("minimap.cfg").readText(),
                )
                assertFalse(preview.modSettingsFolder!!.resolve("unrelated.cfg").exists())
                packs.importSettings(preview, "destination")
                store.importPack(
                    preview.pack,
                    newProfileId = "destination",
                    packSettings = preview.settings,
                )
                assertTrue(store.state.value.settings.bool("ownSettingsP", "destination"))
            }
            assertEquals(
                "Zoom = 3",
                home.resolve("profiles/destination/config/minimap.cfg").readText(),
            )
            assertEquals("Private = true", game.resolve("BepInEx/config/unrelated.cfg").readText())
        } finally {
            store.shutdown()
            client.close()
            deleteTree(home)
        }
    }

    @Test
    fun consolePortChangesOnlyTheSelectedSandboxConfig() = runBlocking {
        val home = Files.createTempDirectory("console-port-")
        try {
            val game = home.resolve("game").createDirectories()
            game.resolve("NuclearOption.exe").writeText("")
            val config = game.resolve("BepInEx/config").createDirectories().resolve("BepInEx.cfg")
            config.writeText("[Logging.Console]\nEnabled = false\n[Other]\nEnabled = true\n")
            val io = Demo.io()
            val folder =
                RealGameFolder(home, RealModStore(home), io.downloader, running = { false })
            val store =
                nnomm.core.store.AppStore(
                    Demo.state().copy(game = GameInfo(game.toString())),
                    io.copy(gameFolder = folder),
                )
            try {
                store.setSetting("console", JsonPrimitive(true))
                store.awaitDiskIdle()
                assertTrue(config.readText().contains("[Logging.Console]\nEnabled = false"))
                assertFalse(home.resolve("console-backups").exists())
                store.setSetting("console", JsonPrimitive(false))
                store.awaitDiskIdle()
                assertTrue(config.readText().contains("[Logging.Console]\nEnabled = false"))
                config.writeText("[Logging.Console]\nEnabled = true\n")
                store.play(withoutMods = true)
                withTimeout(5000) {
                    while (!config.readText().contains("Enabled = false")) delay(10)
                }
            } finally {
                store.shutdown()
            }
        } finally {
            deleteTree(home)
        }
    }

    @Test
    fun consoleChangesOnlyItsSectionAndReportIncludesLog() = runBlocking {
        val source =
            "[Other]\nEnabled = true\n[Logging.Console]\n# keep\nEnabled = false\n[Logging.Disk]\nEnabled = true\n"
        val enabled = consoleConfig(source, true)
        assertTrue(enabled.contains("[Other]\nEnabled = true"))
        assertTrue(enabled.contains("# keep\nEnabled = true"))
        assertEquals(enabled, consoleConfig(enabled, true))
        val home = Files.createTempDirectory("report-")
        val store = demoStore()
        try {
            home
                .resolve("BepInEx")
                .createDirectories()
                .resolve("LogOutput.log")
                .writeText("Last mod message")
            val state =
                store.state.value.copy(game = GameInfo(home.toString(), "0.34", true, "5.4"))
            val report = problemReport(state)
            assertTrue(report.contains("NNOMM ${state.appVersion}"))
            assertTrue(report.contains("Last mod message"))
            assertTrue(report.contains(state.profile().name))
            assertTrue(report.contains(state.mod(state.installed.keys.first()).name))
        } finally {
            store.shutdown()
            deleteTree(home)
        }
    }
}
