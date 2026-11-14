package nnomm.core

import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.core.store.AppStore

class FinalAcceptanceTest {
    @Test
    fun oldestDateIsRequiredAndRequestedOnce() = runBlocking {
        val now = System.currentTimeMillis()
        val mod =
            CatalogMod(
                "test",
                "Test",
                versions =
                    listOf(
                        ModVersion("2", downloadUrl = "https://example.org/2", dateMillis = now),
                        ModVersion("1", downloadUrl = "https://example.org/1"),
                    ),
            )
        assertNull(mod.added)
        assertTrue(
            browseSections(AppState(catalog = listOf(mod), nowMillis = now))
                .filter { it.title == "New" }
                .all { it.mods.isEmpty() }
        )
        val calls = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val store =
            AppStore(
                Demo.state().copy(catalog = listOf(mod)),
                Demo.io()
                    .copy(
                        meta =
                            object : MetaSource {
                                override suspend fun metadata(
                                    mod: CatalogMod,
                                    version: ModVersion,
                                ): VersionMetadata {
                                    calls.merge(version.version, 1, Int::plus)
                                    return VersionMetadata(
                                        1,
                                        if (version.version == "1") now - 100L * 86400000 else now,
                                    )
                                }
                            }
                    ),
            )
        try {
            store.loadMetadata()
            withTimeout(5000) { while (store.state.value.catalog.single().added == null) delay(10) }
            store.loadMetadata()
            assertEquals(1, calls["1"])
            assertEquals(now - 100L * 86400000, store.state.value.catalog.single().added)
        } finally {
            store.shutdown()
        }
    }

    @Test
    fun removeEverythingRemovesEveryModAndKeepsStoredCopiesForNow() = runBlocking {
        val root = Files.createTempDirectory("final-removal-")
        val storage = RealModStore(root.resolve("home"))
        val game = root.resolve("game").createDirectories()
        game.resolve("NuclearOption.exe").writeText("")
        val io = Demo.io()
        val folder =
            RealGameFolder(root.resolve("home"), storage, io.downloader, running = { false })
        val ids = listOf("local", "taken", "unknown", "download")
        val origins =
            listOf(
                PayloadOrigin.LOCAL_FILE,
                PayloadOrigin.TAKEOVER,
                PayloadOrigin.UNKNOWN,
                PayloadOrigin.REGISTRY_DOWNLOAD,
            )
        val payload = root.resolve("payload").createDirectories()
        payload.resolve("a.dll").writeText("bytes")
        ids.zip(origins).forEach { (id, origin) ->
            storage.put(id, "1", DownloadedFile(payload.toString(), origin))
        }
        val initial =
            Demo.state()
                .copy(
                    game = GameInfo(game.toString(), loaderPresent = true),
                    installed =
                        ids.associateWith { id ->
                            InstalledMod(
                                id,
                                if (id == "local") ModSource.LOCAL else ModSource.REGISTRY,
                                versions = setOf("1"),
                                defaultVersion = "1",
                                profiles = mapOf("default" to ProfileMod(true, "1")),
                            )
                        },
                )
        folder.apply(gamePlan(initial))
        val store = AppStore(initial, io.copy(modStore = storage, gameFolder = folder))
        try {
            store.removeEverything()
            store.awaitDiskIdle()
            val state = store.state.value
            // Remove all mods empties the Library, also for mods that cannot be downloaded again.
            assertTrue(state.installed.isEmpty())
            assertTrue(
                Files.walk(game.resolve("BepInEx/plugins")).use { files ->
                    files.noneMatch { it.fileName.toString().endsWith(".dll") }
                }
            )
            // As with Uninstall, the stored copies stay until the player clears unused files.
            for (id in ids) assertTrue(storage.version(id, "1").resolve("a.dll").exists())
            assertEquals(0L, storage.installedBytes(state))
        } finally {
            store.shutdown()
            deleteTree(root)
        }
    }

    @Test
    fun realManifestTagsUseSentenceCaseAndAcronyms() {
        val mods =
            cleanCatalogTags(decodeRegistry(javaClass.getResource("/manifest.json")!!.readText()))
        mods.flatMap { it.tags }.forEach { assertEquals(displayTag(it), it) }
        listOf("HUD", "UI", "QoL", "VR", "AI", "PvP", "PvE", "FPS", "ATC", "AWACS").forEach {
            assertEquals(it, displayTag(it.lowercase()))
        }
        assertEquals("Flight instruments", displayTag("FLIGHT INSTRUMENTS"))
        assertEquals("1 year ago", ago(365))
        val counts =
            tagCounts(AppState(catalog = mods), BrowseFilter(type = "addon")).values.toList()
        assertEquals(counts.sortedDescending(), counts)
    }
}
