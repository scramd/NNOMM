package nnomm.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.*
import java.nio.file.attribute.FileTime
import java.util.zip.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import nnomm.core.demo.*
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.settings.*
import nnomm.core.store.AppStore

/** Regression scenarios from the independent round-three review. */
class Review3ProbeTest {
    private val temporary = mutableListOf<Path>()

    private fun temp(p: String = "nnomm-r3-") =
        Files.createTempDirectory(p).also { temporary += listOf(it) }

    @AfterTest
    fun cleanup() {
        temporary.asReversed().forEach { root ->
            if (root.exists())
                Files.walk(root).use { files ->
                    files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
        }
    }

    private val noNet =
        object : Downloader {
            override suspend fun download(
                mod: CatalogMod,
                version: ModVersion,
                progress: (Int) -> Unit,
            ): DownloadedFile = error("No network")
        }

    private fun game(): Path =
        temp("nnomm-g-").also {
            it.resolve("NuclearOption.exe").writeText("")
            it.resolve("BepInEx/plugins").createDirectories()
        }

    private suspend fun payload(
        store: RealModStore,
        id: String,
        v: String,
        files: Map<String, String>,
    ) {
        val src = temp("nnomm-src-")
        files.forEach { (k, value) ->
            src.resolve(k).also { it.parent.createDirectories() }.writeText(value)
        }
        store.put(id, v, DownloadedFile(src.toString()))
    }

    private fun plan(game: Path, vararg ids: String) =
        GamePlan(game.toString(), "default", ids.associateWith { "1" }, false)

    private fun bytes(root: Path): Long =
        if (!root.exists()) 0
        else
            Files.walk(root).use { s ->
                s.filter { it.isRegularFile() }.mapToLong { it.fileSize() }.sum()
            }

    private fun installed(vararg ids: String) =
        ids.associateWith {
            InstalledMod(
                it,
                versions = setOf("1"),
                defaultVersion = "1",
                profiles = mapOf("default" to ProfileMod(true, "1")),
            )
        }

    private fun containsText(root: Path, text: String) =
        root.exists() &&
            Files.walk(root).use { s ->
                s.anyMatch {
                    it.isRegularFile() && runCatching { it.readText() }.getOrNull() == text
                }
            }

    // R1: every apply copies the whole previous BepInEx tree to recovery; a changed log keeps it.
    @Test
    fun recoveryGrowsByWholeBepInExPerApplyAndClearKeepsIt() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        payload(store, "modb", "1", mapOf("b.dll" to "b"))
        g.resolve("BepInEx/cache").createDirectories()
        g.resolve("BepInEx/cache/big.bin").writeBytes(ByteArray(1_000_000) { it.toByte() })
        val folder = RealGameFolder(home, store, noNet, running = { false })
        val sizes = mutableListOf<Long>()
        val plans =
            listOf(
                arrayOf("moda"),
                arrayOf("moda", "modb"),
                arrayOf("moda"),
                arrayOf("moda", "modb"),
            )
        for ((i, ids) in plans.withIndex()) {
            g.resolve("BepInEx/LogOutput.log").writeText("game run $i")
            folder.apply(plan(g, *ids))
            sizes += bytes(home.resolve("recovery"))
        }
        Files.list(home.resolve("recovery"))
            .use { it.toList() }
            .forEach { Files.setLastModifiedTime(it, FileTime.fromMillis(0)) }
        store.clearUnused(
            AppState(
                game = GameInfo(g.toString()),
                installed = installed("moda", "modb"),
                settings = defaultSettings(),
            )
        )
        val after = bytes(home.resolve("recovery"))
        println("PROBE R1 recovery bytes after each apply $sizes; after Clear $after")
        assertTrue(sizes.last() < 1_000_000)
        assertTrue(after < 1_000_000)
    }

    // R2: an edit to a shipped file is replaced by the store copy on an unrelated apply.
    @Test
    fun editedShippedFileRevertsOnUnrelatedApply() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a", "settings.json" to "default"))
        payload(store, "modb", "1", mapOf("b.dll" to "b"))
        val folder = RealGameFolder(home, store, noNet, running = { false })
        folder.apply(plan(g, "moda"))
        val cfg = g.resolve("BepInEx/plugins/moda/settings.json")
        cfg.writeText("player choice")
        folder.apply(plan(g, "moda", "modb"))
        println("PROBE R2 shipped file after unrelated apply: ${cfg.readText()}")
        assertEquals("player choice", cfg.readText())
        assertFalse(containsText(home.resolve("player-files"), "player choice"))
        assertFalse(containsText(home.resolve("recovery"), "player choice"))
    }

    // R3: a file the player deletes from a mod folder comes back from player-files.
    @Test
    fun deletedPlayerFileComesBack() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        payload(store, "modb", "1", mapOf("b.dll" to "b"))
        val folder = RealGameFolder(home, store, noNet, running = { false })
        folder.apply(plan(g, "moda"))
        val extra = g.resolve("BepInEx/plugins/moda/cache.dat")
        extra.writeText("stale cache")
        folder.apply(plan(g, "moda", "modb"))
        assertTrue(extra.exists())
        extra.deleteExisting()
        folder.apply(plan(g, "moda"))
        println("PROBE R3 deleted file exists again: ${extra.exists()}")
        assertFalse(extra.exists())
    }

    // R4: a wanted mod whose store copy is missing is removed from the game.
    @Test
    fun missingStoreCopyRemovesWantedModFromGame() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        payload(store, "modb", "1", mapOf("b.dll" to "b"))
        val notes = mutableListOf<String>()
        val folder =
            RealGameFolder(home, store, noNet, running = { false }, notice = { notes += it })
        folder.apply(plan(g, "moda", "modb"))
        deleteTree(store.version("moda", "1"))
        folder.apply(plan(g, "moda"))
        println(
            "PROBE R4 moda in game: ${g.resolve("BepInEx/plugins/moda").exists()}; notices $notes"
        )
        assertTrue(g.resolve("BepInEx/plugins/moda").exists())
    }

    // R5: game exit forces apply; with the record gone, NNOMM puts back mods the player removed.
    @Test
    fun gameExitPutsBackModsRemovedByHand() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        val running = MutableStateFlow(false)
        val launcher =
            object : GameLauncher {
                override val running = running

                override suspend fun start(state: AppState, withoutMods: Boolean) {}
            }
        val folder = RealGameFolder(home, store, noNet, running = { running.value })
        val initial =
            AppState(
                game = GameInfo(g.toString(), loaderPresent = true),
                settings = defaultSettings(),
                catalog = listOf(CatalogMod("moda", "A", versions = listOf(ModVersion("1")))),
                installed =
                    installed("moda").mapValues {
                        it.value.copy(profiles = mapOf("default" to ProfileMod(false, "1")))
                    },
            )
        val a =
            AppStore(
                initial,
                Demo.io()
                    .copy(
                        modStore = store,
                        gameFolder = folder,
                        launcher = launcher,
                        persistence = null,
                    ),
            )
        a.setOn(listOf("moda"), true)
        a.awaitDiskIdle()
        assertTrue(g.resolve("BepInEx/plugins/moda/a.dll").exists())
        deleteTree(g.resolve("BepInEx/plugins"))
        running.value = true
        withTimeout(5000) { while (!a.state.value.gameRunning) delay(20) }
        running.value = false
        withTimeout(5000) { while (a.state.value.gameRunning) delay(20) }
        delay(300)
        a.awaitDiskIdle()
        assertFalse(g.resolve("BepInEx/plugins/moda/a.dll").exists())
        a.shutdown()
    }

    // R6: one symbolic link or one long path anywhere in BepInEx blocks every apply.
    @Test
    fun oneOddEntryInBepInExBlocksEveryApply() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        payload(store, "modb", "1", mapOf("b.dll" to "b"))
        val folder = RealGameFolder(home, store, noNet, running = { false })
        folder.apply(plan(g, "moda"))
        Files.createSymbolicLink(g.resolve("BepInEx/cache-link"), temp())
        val linked = runCatching { folder.apply(plan(g, "moda", "modb")) }
        println("PROBE R6 symlink apply: ${linked.exceptionOrNull()}")
        assertTrue(linked.isSuccess)
        assertTrue(g.resolve("BepInEx/plugins/modb").exists())

        val g2 = game()
        val base = g2.resolve("BepInEx/config")
        var dir = base
        while (dir.toString().length < 170) dir = dir.resolve("d".repeat(40))
        dir.createDirectories()
        val file = dir.resolve("x".repeat(220 - dir.toString().length) + ".cfg")
        file.writeText("player config")
        assertTrue(file.toString().length < 240)
        val long = runCatching { folder.apply(plan(g2, "moda")) }
        println(
            "PROBE R6 long path (${file.toString().length} chars) apply: ${long.exceptionOrNull()}"
        )
        assertTrue(long.isSuccess)
        assertTrue(g2.resolve("BepInEx/plugins/moda").exists())
    }

    // R7: a pack LOCAL item can overwrite an existing local mod's stored bytes.
    @Test
    fun packLocalReplacesExistingLocalPayload() = runBlocking {
        val home = temp()
        val store = RealModStore(home)
        payload(store, "local-mine-dll-1234", "takeover", mapOf("Mine.dll" to "player bytes"))
        val zip = temp().resolve("friendly.nnommpack")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("pack.json"))
            z.write(
                """{"format":"NNOMM/1","pack":{"name":"p","by":"x","version":"takeover","items":[{"kind":"local","id":"local-mine-dll-1234"}]}}"""
                    .toByteArray()
            )
            z.closeEntry()
            z.putNextEntry(ZipEntry("mods/local-mine-dll-1234/Mine.dll"))
            z.write("stranger bytes".toByteArray())
            z.closeEntry()
        }
        val client = networkClient("probe")
        try {
            val packs = RealPacks(home, store, client)
            packs.read(zip).use { preview ->
                packs.selectedLocals(preview, preview.localFiles.keys)
            }
        } finally {
            client.close()
        }
        val now = store.version("local-mine-dll-1234", "takeover").resolve("Mine.dll").readText()
        println("PROBE R7 existing local payload now: $now")
        assertEquals("player bytes", now)
    }

    // R8: taking over a folder replaces existing per-id records (profiles and versions).
    @Test
    fun takeoverReplacesExistingAssociations() = runTest {
        val io = Demo.io()
        val folder =
            object : GameFolder by io.gameFolder {
                override suspend fun inspect(folder: String) = GameInfo(folder)

                override suspend fun takeOver(folder: String) =
                    listOf(
                        CatalogMod("m", "M", versions = listOf(ModVersion("1"))) to
                            InstalledMod(
                                "m",
                                versions = setOf("1"),
                                defaultVersion = "1",
                                profiles = mapOf("default" to ProfileMod(true, "1")),
                            )
                    )
            }
        val initial =
            AppState(
                settings = defaultSettings(),
                profiles = listOf(Profile("default", "Default"), Profile("p2", "Two")),
                catalog =
                    listOf(
                        CatalogMod("m", "M", versions = listOf(ModVersion("1"), ModVersion("2")))
                    ),
                installed =
                    mapOf(
                        "m" to
                            InstalledMod(
                                "m",
                                versions = setOf("1", "2"),
                                defaultVersion = "2",
                                profiles =
                                    mapOf(
                                        "default" to ProfileMod(true, "2"),
                                        "p2" to ProfileMod(true, "2"),
                                    ),
                            )
                    ),
            )
        val a = AppStore(initial, io.copy(gameFolder = folder, persistence = null), backgroundScope)
        a.takeOverExistingMods("/second/game")
        runCurrent()
        advanceUntilIdle()
        val m = a.state.value.installed.getValue("m")
        println(
            "PROBE R8 after second takeover: $m; game ${a.state.value.game}; toasts ${a.state.value.toasts.map { it.text }}"
        )
        assertNotNull(m.profiles["p2"])
        assertEquals(setOf("1", "2"), m.versions)
        a.close()
    }

    // R9: Clear queued before an uninstall's disk step deletes the payload Undo needs.
    @Test
    fun clearBeforeUninstallDiskStepBreaksUndo() = runBlocking {
        val home = temp()
        payload(RealModStore(home), "m", "1", mapOf("m.dll" to "m"))
        payload(RealModStore(home), "n", "1", mapOf("n.dll" to "n"))
        val store = RealModStore(home) // a restart: no recent-install protection
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val io = Demo.io()
        val folder =
            object : GameFolder by io.gameFolder {
                override suspend fun inspect(folder: String) =
                    GameInfo(folder, loaderPresent = true)

                override suspend fun installLoader(folder: String) {}

                override suspend fun apply(plan: GamePlan) {
                    entered.complete(Unit)
                    gate.await()
                }
            }
        val initial =
            AppState(
                settings = defaultSettings(),
                game = GameInfo("/sandbox/game", loaderPresent = true),
                catalog =
                    listOf(
                        CatalogMod("m", "M", versions = listOf(ModVersion("1"))),
                        CatalogMod("n", "N", versions = listOf(ModVersion("1"))),
                    ),
                installed =
                    installed("m", "n").mapValues {
                        if (it.key == "n")
                            it.value.copy(profiles = mapOf("default" to ProfileMod(false, "1")))
                        else it.value
                    },
            )
        val a =
            AppStore(initial, io.copy(modStore = store, gameFolder = folder, persistence = null))
        a.toggle("n")
        withTimeout(5000) { entered.await() }
        a.clearUnusedFiles()
        a.uninstall(listOf("m"))
        gate.complete(Unit)
        a.awaitDiskIdle()
        val goneAfterClear = !store.version("m", "1").exists()
        a.undo()
        a.awaitDiskIdle()
        println(
            "PROBE R9 payload deleted: $goneAfterClear; m back in library: ${"m" in a.state.value.installed}; files: ${store.version("m", "1").exists()}"
        )
        assertFalse(goneAfterClear)
        assertTrue("m" in a.state.value.installed)
        assertTrue(store.version("m", "1").exists())
        a.shutdown()
    }

    // R10: crash after promotion, the game writes files, recovery deletes them without a copy.
    @Test
    fun startupRollbackDeletesFilesWrittenAfterTheCrash() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        payload(store, "modb", "1", mapOf("b.dll" to "b"))
        val folder = RealGameFolder(home, store, noNet, running = { false })
        folder.apply(plan(g, "moda"))
        DiskFaults.step = { if (it == "promoted") throw DiskCrash() }
        try {
            runCatching { folder.apply(plan(g, "moda", "modb")) }
        } finally {
            DiskFaults.step = {}
        }
        assertTrue(g.resolve("BepInEx/plugins/modb/b.dll").exists())
        // The player starts the game from Steam before NNOMM runs again.
        g.resolve("BepInEx/config").createDirectories()
        g.resolve("BepInEx/config/changed-in-game.cfg").writeText("player setting 42")
        g.resolve("BepInEx/plugins/modb/save.dat").writeText("player save 42")
        RealModStore(home) // any startup path runs recoverTransactions
        val kept =
            containsText(home, "player setting 42") ||
                containsText(g, "player setting 42") ||
                containsText(home, "player save 42") ||
                containsText(g, "player save 42")
        println("PROBE R10 player writes after crash survive anywhere: $kept")
        assertTrue(kept)
        folder.recoverPending()
        val placement =
            json.decodeFromString<Placement>(
                g.resolve("BepInEx/plugins/.nnomm-state.json").readText()
            )
        assertTrue(placement.entries.values.any { it.id == "modb" })
        assertEquals("player save 42", g.resolve("BepInEx/plugins/modb/save.dat").readText())
    }

    // R11: with an empty library, NNOMM cannot open while the game runs.
    @Test
    fun openFailsWhileGameRunsWithEmptyLibrary() = runBlocking {
        val bin = temp("nnomm-proc-").resolve("NuclearOption.exe")
        bin.writeText("#!/bin/sh\nsleep 60\n")
        bin.toFile().setExecutable(true)
        val process = ProcessBuilder(bin.toString(), "60").start()
        try {
            delay(300)
            assertTrue(gameProcessRunning(), "fake game process not detected")
            val home = temp()
            val g = game()
            g.resolve("BepInEx/plugins/Mod.dll").writeText("mod")
            // A player who finished first start earlier and later uninstalled every mod.
            val settings =
                defaultSettings().let {
                    it.copy(
                        general =
                            it.general +
                                mapOf(
                                    "gamePath" to JsonPrimitive(g.toString()),
                                    "sources" to JsonPrimitive("http://127.0.0.1:9/none"),
                                    "checkUpdates" to JsonPrimitive("Only when I ask"),
                                )
                    )
                }
            RealPersistence(home)
                .save(
                    AppState(
                        settings = settings,
                        firstStart = FirstStart(true),
                        game = GameInfo(g.toString()),
                    )
                )
            val result = runCatching { RealRuntime.open(home) }
            result.getOrNull()?.shutdown()
            println("PROBE R11 open while game runs: ${result.exceptionOrNull()}")
            assertTrue(result.isSuccess)
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    // R12: real registry names with a trailing space cannot download; 404s are retried.
    @Test
    fun trailingSpaceFileNameFailsAnd404IsRetried() = runBlocking {
        val home = temp()
        var missing = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok.dll") { ex ->
            val body = "MZ fake".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
            ex.close()
        }
        server.createContext("/missing.dll") { ex ->
            missing++
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.start()
        val client = networkClient("probe")
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val mod =
                decodeRegistry(
                        """[{"id":"byt0rpilot.MissileScreen","displayName":"M","artifacts":[{"fileName":"MissileScreen-v1.2.0.dll ","version":"1.2.0","downloadUrl":"$base/ok.dll"}]}]"""
                    )
                    .single()
            val d = RealDownloader(client, home)
            val spaced = runCatching { d.download(mod, mod.versions.single()) {} }
            println("PROBE R12 trailing-space name: ${spaced.exceptionOrNull()}")
            assertTrue(spaced.isSuccess)
            spaced.getOrNull()?.close()
            val gone = runCatching {
                d.download(
                    mod,
                    mod.versions
                        .single()
                        .copy(fileName = "x.dll", downloadUrl = "$base/missing.dll"),
                ) {}
            }
            println("PROBE R12 404 requests: $missing")
            assertTrue(gone.isFailure)
            assertEquals(1, missing)
        } finally {
            server.stop(0)
            client.close()
        }
    }

    // R13: one failed HEAD is cached as unknown for good.
    @Test
    fun failedMetadataIsNeverRetried() = runBlocking {
        val home = temp()
        var heads = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/m.dll") { ex ->
            heads++
            if (heads == 1) ex.sendResponseHeaders(503, -1)
            else {
                ex.responseHeaders.add("Content-Length", "4096")
                ex.sendResponseHeaders(200, -1)
            }
            ex.close()
        }
        server.start()
        val client = networkClient("probe")
        try {
            val v = ModVersion("1", downloadUrl = "http://127.0.0.1:${server.address.port}/m.dll")
            val mod = CatalogMod("m", "M", versions = listOf(v))
            val firstCache = RealMetaSource(client, home)
            firstCache.metadata(mod, v)
            firstCache.shutdown()
            val secondCache = RealMetaSource(client, home)
            val second = secondCache.metadata(mod, v)
            secondCache.shutdown()
            println("PROBE R13 second size ${second.sizeKb}, HEAD requests $heads")
            assertNull(second.sizeKb)
            assertEquals(1, heads)
            home
                .resolve("head.json")
                .writeText(
                    home
                        .resolve("head.json")
                        .readText()
                        .replace(Regex("\"retryAfter\"\\s*:\\s*\\d+"), "\"retryAfter\":1")
                )
            val retried = RealMetaSource(client, home)
            assertEquals(4, retried.metadata(mod, v).sizeKb)
            retried.shutdown()
            assertEquals(2, heads)
        } finally {
            server.stop(0)
            client.close()
        }
    }

    // R14: an add-on placed before its parent blocks the parent for good.
    @Test
    fun addonBeforeParentBlocksParent() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "parent", "1", mapOf("parent.dll" to "p"))
        payload(store, "child", "1", mapOf("child.nobp" to "c"))
        val catalog =
            listOf(
                CatalogMod("parent", "Parent", versions = listOf(ModVersion("1"))),
                CatalogMod(
                    "child",
                    "Child",
                    versions =
                        listOf(
                            ModVersion(
                                "1",
                                type = ModType.ADDON,
                                extends = PackageReference("parent", "1"),
                            )
                        ),
                ),
            )
        val notes = mutableListOf<String>()
        val folder =
            RealGameFolder(
                home,
                store,
                noNet,
                catalog = { catalog },
                running = { false },
                notice = { notes += it },
            )
        folder.apply(plan(g, "child")) // the smaller add-on download finishes first
        folder.apply(plan(g, "parent", "child"))
        val placed = g.resolve("BepInEx/plugins/parent/parent.dll").exists()
        folder.apply(plan(g)) // turn both off
        folder.apply(plan(g, "parent"))
        val later = g.resolve("BepInEx/plugins/parent/parent.dll").exists()
        println(
            "PROBE R14 parent placed: $placed; after both off and parent on: $later; notices $notes"
        )
        assertTrue(placed)
        assertTrue(later)
    }

    // R15: startup rolls back a game-folder transaction while the game runs.
    @Test
    fun startupRollbackRunsWhileGameRuns() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        payload(store, "modb", "1", mapOf("b.dll" to "b"))
        val folder = RealGameFolder(home, store, noNet, running = { false })
        folder.apply(plan(g, "moda"))
        DiskFaults.step = { if (it == "promoted") throw DiskCrash() }
        try {
            runCatching { folder.apply(plan(g, "moda", "modb")) }
        } finally {
            DiskFaults.step = {}
        }
        val settings =
            defaultSettings().let {
                it.copy(
                    general =
                        it.general +
                            mapOf(
                                "gamePath" to JsonPrimitive(g.toString()),
                                "sources" to JsonPrimitive("http://127.0.0.1:9/none"),
                                "checkUpdates" to JsonPrimitive("Only when I ask"),
                            )
                )
            }
        RealPersistence(home)
            .save(
                AppState(
                    settings = settings,
                    game = GameInfo(g.toString()),
                    installed = installed("moda", "modb"),
                )
            )
        val bin = temp("nnomm-proc-").resolve("NuclearOption.exe")
        bin.writeText("#!/bin/sh\nsleep 60\n")
        bin.toFile().setExecutable(true)
        val process = ProcessBuilder(bin.toString(), "60").start()
        try {
            delay(300)
            assertTrue(gameProcessRunning(), "fake game process not detected")
            assertTrue(g.resolve("BepInEx/plugins/modb/b.dll").exists())
            val runtime = RealRuntime.open(home)
            val running = runtime.store.state.value.gameRunning
            runtime.shutdown()
            println(
                "PROBE R15 game running at open: $running; modb still in game: ${g.resolve("BepInEx/plugins/modb").exists()}"
            )
            assertTrue(running)
            assertTrue(g.resolve("BepInEx/plugins/modb").exists())
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }
}
