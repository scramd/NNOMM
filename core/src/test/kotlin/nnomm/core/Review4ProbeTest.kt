package nnomm.core

import java.nio.file.*
import java.nio.file.attribute.FileTime
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import nnomm.core.demo.*
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.settings.*
import nnomm.core.store.AppStore

/** Review round 4 probes. Each asserts the GOOD behaviour; a failure demonstrates a finding. */
class Review4ProbeTest {
    private val roots = mutableListOf<Path>()
    private val base =
        Path.of(System.getProperty("user.home"), "nnomm-sandbox/review4/tmp").also {
            it.createDirectories()
        }

    private fun temp(p: String = "r4-") = Files.createTempDirectory(base, p).also { roots.add(it) }

    private fun shm(p: String = "r4-") =
        Files.createTempDirectory(Path.of("/dev/shm"), p).also { roots.add(it) }

    @AfterTest
    fun cleanup() {
        DiskFaults.step = {}
        roots.asReversed().forEach { root ->
            if (root.exists())
                Files.walk(root).use { files ->
                    files.sorted(Comparator.reverseOrder()).forEach {
                        it.toFile().setWritable(true)
                        Files.deleteIfExists(it)
                    }
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
        temp("g-").also {
            it.resolve("NuclearOption.exe").writeText("")
            it.resolve("BepInEx/plugins").createDirectories()
        }

    private suspend fun payload(
        store: RealModStore,
        id: String,
        v: String,
        files: Map<String, String>,
    ) {
        val src = temp("src-")
        files.forEach { (k, value) ->
            src.resolve(k).also { it.parent.createDirectories() }.writeText(value)
        }
        store.put(id, v, DownloadedFile(src.toString()))
    }

    private fun plan(game: Path, profile: String, own: Boolean, vararg ids: String) =
        GamePlan(game.toString(), profile, ids.associateWith { "1" }, own)

    private fun containsText(root: Path, text: String) =
        root.exists() &&
            Files.walk(root).use { s ->
                s.anyMatch {
                    it.isRegularFile() && runCatching { it.readText() }.getOrNull() == text
                }
            }

    private fun record(g: Path) =
        json.decodeFromString<Placement>(g.resolve("BepInEx/plugins/.nnomm-state.json").readText())

    private fun hasJournal(dir: Path, text: String? = null) =
        dir.isDirectory() &&
            Files.list(dir).use { s ->
                s.anyMatch {
                    it.name.endsWith(".txn") && (text == null || it.readText().contains(text))
                }
            }

    // N1: home and game on different file systems (AppData on C:, Steam library on D:).
    @Test
    fun crossDeviceCrashRecoveryMustComplete() = runBlocking {
        val home = shm()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        payload(store, "modb", "1", mapOf("b.dll" to "b"))
        val f = RealGameFolder(home, store, noNet, running = { false })
        f.apply(plan(g, "default", false, "moda"))
        DiskFaults.step = {
            if (it == "promoted" && hasJournal(home.resolve("game-transactions"))) throw DiskCrash()
        }
        try {
            runCatching { f.apply(plan(g, "default", false)) }
        } finally {
            DiskFaults.step = {}
        }
        val f2 = RealGameFolder(home, RealModStore(home), noNet, running = { false })
        val rec = runCatching { f2.recoverPending() }
        val next = runCatching { f2.apply(plan(g, "default", false, "modb")) }
        println(
            "PROBE N1 recover: ${rec.exceptionOrNull()}; next apply: ${next.exceptionOrNull()}; modb placed ${g.resolve("BepInEx/plugins/modb").exists()}; journal left ${hasJournal(home.resolve("game-transactions"))}"
        )
        assertTrue(rec.isSuccess, "cross-device recovery failed")
        assertTrue(next.isSuccess && g.resolve("BepInEx/plugins/modb").exists())
    }

    // N2: an interrupted config swap; the per-entry commit already recorded the new profile.
    @Test
    fun interruptedConfigSwapMustFinishOnRetry() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        payload(store, "modb", "1", mapOf("b.dll" to "b"))
        val cfg = g.resolve("BepInEx/config/x.cfg").also { it.parent.createDirectories() }
        cfg.writeText("shared")
        val f = RealGameFolder(home, store, noNet, running = { false })
        f.apply(plan(g, "a", true, "moda"))
        cfg.writeText("A")
        f.apply(plan(g, "b", true, "modb"))
        cfg.writeText("B")
        f.apply(plan(g, "a", true, "moda"))
        assertEquals("A", cfg.readText())
        val target = "\"target\": \"${g.resolve("BepInEx/config")}\""
        DiskFaults.step = {
            if (it == "prepared" && hasJournal(home.resolve("game-transactions"), target))
                throw DiskCrash()
        }
        try {
            runCatching { f.apply(plan(g, "b", true, "modb")) }
        } finally {
            DiskFaults.step = {}
        }
        val f2 = RealGameFolder(home, RealModStore(home), noNet, running = { false })
        f2.recoverPending()
        f2.apply(plan(g, "b", true, "modb"))
        val whileB = cfg.readText()
        f2.apply(plan(g, "a", true, "moda"))
        val bSaved = containsText(home.resolve("profiles/b"), "B")
        println(
            "PROBE N2 game config with profile b active after retry: $whileB; profile b's saved config still has B: $bSaved; record ${record(g).profileId}"
        )
        assertEquals("B", whileB)
        assertTrue(bSaved)
    }

    // N3: an edited shipped file; notice says the kept files return with this profile.
    @Test
    fun editedShippedFileMustReturnWhenNoticeSaysSo() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a", "settings.json" to "default"))
        val notes = mutableListOf<String>()
        val f = RealGameFolder(home, store, noNet, running = { false }, notice = { notes += it })
        f.apply(plan(g, "default", false, "moda"))
        val s = g.resolve("BepInEx/plugins/moda/settings.json")
        s.writeText("player choice")
        f.apply(plan(g, "default", false))
        f.apply(plan(g, "default", false, "moda"))
        println("PROBE N3 notices $notes; settings.json after off/on: ${s.readText()}")
        assertTrue(notes.any { it.contains("return with this profile") })
        assertEquals("player choice", s.readText())
    }

    // N4: player files saved for profile A never return when both profiles use the same version,
    // and the next capture overwrites them.
    @Test
    fun savedProfileFilesMustNotBeOverwritten() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        val f = RealGameFolder(home, store, noNet, running = { false })
        f.apply(plan(g, "a", false, "moda"))
        g.resolve("BepInEx/plugins/moda/saveA.dat").writeText("save from A")
        f.apply(plan(g, "b", false)) // B has moda off: A's save is kept in player-files/a
        f.apply(plan(g, "b", false, "moda")) // turned on in B
        g.resolve("BepInEx/plugins/moda/saveB.dat").writeText("save from B")
        f.apply(plan(g, "a", false, "moda")) // back to A: entry unchanged
        val returned = g.resolve("BepInEx/plugins/moda/saveA.dat").exists()
        f.apply(plan(g, "a", false)) // turned off in A: capture replaces A's saved set
        val inPlayerFiles = containsText(home.resolve("player-files"), "save from A")
        val inRecovery = containsText(home.resolve("recovery"), "save from A")
        println(
            "PROBE N4 A's save back in A: $returned; in player-files: $inPlayerFiles; only in recovery: $inRecovery"
        )
        assertTrue(inPlayerFiles || returned)
    }

    private fun launcherWith(
        running: MutableStateFlow<Boolean>,
        started: CompletableDeferred<Unit>,
    ) =
        object : GameLauncher {
            override val running = running

            override suspend fun start(state: AppState, withoutMods: Boolean) {
                started.complete(Unit)
            }
        }

    private fun stateWith(g: Path, vararg on: String) =
        AppState(
            game = GameInfo(g.toString(), loaderPresent = true),
            settings = defaultSettings(),
            catalog =
                listOf("moda", "modb").map {
                    CatalogMod(it, it, versions = listOf(ModVersion("1")))
                },
            installed =
                listOf("moda", "modb").associateWith {
                    InstalledMod(
                        it,
                        versions = setOf("1"),
                        defaultVersion = "1",
                        profiles = mapOf("default" to ProfileMod(it in on, "1")),
                    )
                },
        )

    // N5: changes saved in an earlier session (made while the game ran) never reach the game.
    @Test
    fun pendingChangeFromEarlierSessionMustApplyBeforePlay() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        payload(store, "modb", "1", mapOf("b.dll" to "b"))
        val f = RealGameFolder(home, store, noNet, running = { false })
        val started = CompletableDeferred<Unit>()
        // Saved state says moda is on; the game folder has nothing yet.
        val a =
            AppStore(
                stateWith(g, "moda"),
                Demo.io()
                    .copy(
                        modStore = store,
                        gameFolder = f,
                        launcher = launcherWith(MutableStateFlow(false), started),
                        persistence = null,
                    ),
            )
        a.play()
        withTimeout(5000) { started.await() }
        val placed = g.resolve("BepInEx/plugins/moda/a.dll").exists()
        println("PROBE N5 moda placed before launch: $placed")
        a.shutdown()
        assertTrue(placed)
    }

    private fun storeWith(
        store: RealModStore,
        f: GameFolder,
        g: Path,
        started: CompletableDeferred<Unit>,
    ) =
        AppStore(
            stateWith(g),
            Demo.io()
                .copy(
                    modStore = store,
                    gameFolder = f,
                    launcher = launcherWith(MutableStateFlow(false), started),
                    persistence = null,
                ),
        )

    // N6a: the game was started outside NNOMM and the 3 s watcher has not seen it yet:
    // RealGameFolder.apply returns silently, AppStore records the plan as applied.
    @Test
    fun applySkippedForRunningGameMustBeRetried() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        var folderSeesGame = true
        val f = RealGameFolder(home, store, noNet, running = { folderSeesGame })
        val started = CompletableDeferred<Unit>()
        val a = storeWith(store, f, g, started)
        a.setOn(listOf("moda"), true)
        a.awaitDiskIdle()
        folderSeesGame = false // game closed again
        a.play()
        withTimeout(5000) { started.await() }
        val moda = g.resolve("BepInEx/plugins/moda/a.dll").exists()
        println("PROBE N6a at next launch moda placed: $moda")
        a.shutdown()
        assertTrue(moda)
    }

    // N6b: one entry fails once (transient read error); nothing retries it.
    @Test
    fun failedEntryMustBeRetried() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        payload(store, "modb", "1", mapOf("b.dll" to "b"))
        val f = RealGameFolder(home, store, noNet, running = { false })
        val started = CompletableDeferred<Unit>()
        val a = storeWith(store, f, g, started)
        val locked = store.version("modb", "1").resolve("b.dll").toFile()
        locked.setReadable(false)
        a.setOn(listOf("moda", "modb"), true)
        a.awaitDiskIdle()
        locked.setReadable(true)
        a.play()
        withTimeout(5000) { started.await() }
        val moda = g.resolve("BepInEx/plugins/moda/a.dll").exists()
        val modb = g.resolve("BepInEx/plugins/modb/b.dll").exists()
        println("PROBE N6b at launch moda $moda modb $modb problems ${a.state.value.applyProblems}")
        a.shutdown()
        assertTrue(moda && modb)
    }

    // N1b: same cross-device crash; the next NNOMM start.
    @Test
    fun crossDeviceCrashMustNotBlockStartup() = runBlocking {
        val home = shm()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        val f = RealGameFolder(home, store, noNet, running = { false })
        f.apply(plan(g, "default", false, "moda"))
        DiskFaults.step = {
            if (it == "promoted" && hasJournal(home.resolve("game-transactions"))) throw DiskCrash()
        }
        try {
            runCatching { f.apply(plan(g, "default", false)) }
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
                    firstStart = FirstStart(true),
                    game = GameInfo(g.toString()),
                )
            )
        val r = runCatching { RealRuntime.open(home) }
        r.getOrNull()?.shutdown()
        println("PROBE N1b open after cross-device crash: ${r.exceptionOrNull()}")
        assertTrue(r.isSuccess)
    }

    // N7: a second takeover adopts NNOMM's own placed mods again as new local mods.
    @Test
    fun secondTakeoverMustNotDuplicateManagedMods() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "foo", "1", mapOf("Foo.dll" to "foo"))
        val f = RealGameFolder(home, store, noNet, running = { false })
        f.apply(plan(g, "default", false, "foo"))
        val found = f.takeOver(g.toString())
        println("PROBE N7 second takeover returns ${found.map { it.first.id }}")
        assertTrue(found.none { it.first.id.startsWith("local-") })
    }

    // N7b: player uninstalled every mod while the game ran and closed NNOMM first; next start
    // with an empty library takes the uninstalled mods over again and turns them on.
    @Test
    fun startupMustNotReadoptModsThePlayerUninstalled() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "foo", "1", mapOf("Foo.dll" to "foo"))
        RealGameFolder(home, store, noNet, running = { false })
            .apply(plan(g, "default", false, "foo"))
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
        val runtime = RealRuntime.open(home)
        val installed = runtime.store.state.value.installed
        runtime.shutdown()
        println("PROBE N7b library after restart: ${installed.mapValues { it.value.profiles }}")
        assertTrue(installed.isEmpty())
    }

    // N8: a symbolic-link folder inside a managed mod folder during removal.
    @Test
    fun removalWithLinkInsideModMustStayConsistent() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        val notes = mutableListOf<String>()
        val f = RealGameFolder(home, store, noNet, running = { false }, notice = { notes += it })
        f.apply(plan(g, "default", false, "moda"))
        val outside = temp("outside-")
        outside.resolve("keep.txt").writeText("outside data")
        Files.createSymbolicLink(g.resolve("BepInEx/plugins/moda/linked"), outside)
        val r = runCatching { f.apply(plan(g, "default", false)) }
        val present = g.resolve("BepInEx/plugins/moda").exists()
        val recorded = record(g).entries.keys
        val leftovers =
            g.resolve(".nnomm-staging").let { s ->
                if (s.exists()) Files.list(s).use { it.map { p -> p.name }.toList() }
                else emptyList()
            }
        println(
            "PROBE N8 apply ${r.exceptionOrNull()}; moda in game: $present; record entries $recorded; staging leftovers $leftovers; outside kept ${outside.resolve("keep.txt").exists()}; notices $notes"
        )
        assertTrue(outside.resolve("keep.txt").exists())
        assertEquals(present, "moda" in recorded, "record disagrees with the game folder")
        assertTrue(leftovers.isEmpty(), "entry copy left in the game folder")
    }

    // N9: a failing home write (store put) while the game runs replays a deferred game journal.
    @Test
    fun failedStoreWriteMustNotReplayGameJournalWhileRunning() = runBlocking {
        val home = temp()
        val g = game()
        val config = g.resolve("BepInEx/config").also { it.createDirectories() }
        config.resolve("x.cfg").writeText("player")
        val src = temp("cfg-")
        src.resolve("x.cfg").writeText("profile")
        DiskFaults.step = { if (it == "old-moved") throw DiskCrash() }
        try {
            runCatching { replaceTree(config, src, home.resolve("transactions")) }
        } finally {
            DiskFaults.step = {}
        }
        assertFalse(config.exists())
        val store = RealModStore(home) // startup during play: game journal deferred
        assertTrue(hasJournal(home.resolve("transactions")))
        DiskFaults.step = {
            if (it == "old-moved") throw java.io.IOException("simulated write failure")
        }
        try {
            runCatching { payload(store, "m", "1", mapOf("m.dll" to "m")) }
        } finally {
            DiskFaults.step = {}
        }
        println(
            "PROBE N9 game config restored while the game runs: ${config.exists()}; game journal still pending: ${hasJournal(home.resolve("transactions"), "BepInEx")}"
        )
        assertFalse(config.exists(), "game folder changed by a store write during play")
    }

    // N10: Replace-local Undo: protection starts when the put starts, the toast after it ends.
    @Test
    fun replaceUndoPayloadMustSurviveClearWhileToastShows() = runBlocking {
        val home = temp()
        val store = RealModStore(home)
        payload(store, "local-x", "v1", mapOf("x.dll" to "one"))
        val field = RealModStore::class.java.getDeclaredField("undoUntil")
        field.isAccessible = true
        (field.get(store) as MutableMap<*, *>).clear() // v1 was installed in an earlier session
        val big = temp("big-")
        repeat(1500) { big.resolve("f$it.txt").writeText("x$it") }
        val t0 = System.currentTimeMillis()
        store.put("local-x", "v2", DownloadedFile(big.toString()))
        val t1 = System.currentTimeMillis()
        delay(t1 + 7800 - System.currentTimeMillis()) // "Replaced X" toast (8000 ms) still shown
        val state =
            AppState(
                settings =
                    defaultSettings().let {
                        it.copy(general = it.general + ("keepOld" to JsonPrimitive("None")))
                    },
                installed =
                    mapOf(
                        "local-x" to
                            InstalledMod(
                                "local-x",
                                ModSource.LOCAL,
                                versions = setOf("v1", "v2"),
                                defaultVersion = "v2",
                                profiles = mapOf("default" to ProfileMod(true, "v2")),
                            )
                    ),
            )
        store.clearUnused(state)
        val kept = store.hasVersion("local-x", "v1")
        println("PROBE N10 put took ${t1 - t0} ms; v1 still stored at Undo time: $kept")
        assertTrue(kept)
    }

    // N11: apply-time trimming deletes recovery copies whose bytes exist nowhere else.
    @Test
    fun recoveryTrimMustKeepUniqueCopies() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        val unique = home.resolve("recovery/displaced-x.txn/moda/save.dat")
        unique.parent.createDirectories()
        unique.writeText("only copy")
        Files.setLastModifiedTime(
            home.resolve("recovery/displaced-x.txn"),
            FileTime.fromMillis(1000),
        )
        repeat(100) { home.resolve("recovery/dup-$it").createDirectories() }
        val notes = mutableListOf<String>()
        RealGameFolder(home, store, noNet, running = { false }, notice = { notes += it })
            .apply(plan(g, "default", false, "moda"))
        println("PROBE N11 unique recovery kept: ${unique.exists()}; notices $notes")
        assertTrue(unique.exists())
    }

    // N12: parent turned off while its add-on stays on.
    @Test
    fun parentRemovalMustKeepRecordTruthful() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "parent", "1", mapOf("parent.dll" to "p"))
        payload(store, "child", "1", mapOf("child.dll" to "c"))
        val catalog =
            listOf(
                CatalogMod("parent", "Parent", versions = listOf(ModVersion("1"))),
                CatalogMod(
                    "child",
                    "Child",
                    versions = listOf(ModVersion("1", extends = PackageReference("parent", "1"))),
                ),
            )
        val f = RealGameFolder(home, store, noNet, catalog = { catalog }, running = { false })
        f.apply(plan(g, "default", false, "parent", "child"))
        f.apply(plan(g, "default", false, "child"))
        val childOnDisk = g.resolve("BepInEx/plugins/parent/addons/child").exists()
        val recorded = record(g).entries.keys
        println(
            "PROBE N12 child on disk $childOnDisk; record $recorded; problems ${f.applyProblems.value}"
        )
        assertEquals(childOnDisk, "parent/addons/child" in recorded)
    }

    // N13: ownership record deleted (player removed the plugins folder): no path forward.
    @Test
    fun missingOwnershipMustNotBlockForever() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        payload(store, "modb", "1", mapOf("b.dll" to "b"))
        val notes = mutableListOf<String>()
        val f = RealGameFolder(home, store, noNet, running = { false }, notice = { notes += it })
        f.apply(plan(g, "default", false, "moda"))
        deleteTree(g.resolve("BepInEx/plugins"))
        f.apply(plan(g, "default", false, "moda", "modb"))
        // Missing ownership requires explicit takeover; repeated apply alone must not adopt.
        f.takeOver(g.toString())
        f.apply(plan(g, "default", false, "modb"))
        println(
            "PROBE N13 modb placed: ${g.resolve("BepInEx/plugins/modb").exists()}; notices $notes"
        )
        assertTrue(g.resolve("BepInEx/plugins/modb").exists())
    }

    // N14: first-start takeover of a mod that NOMM had disabled (BepInEx/disabledPlugins).
    @Test
    fun adoptedDisabledModMustBePlacedWhenTurnedOn() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        g.resolve("BepInEx/disabledPlugins/Foo").createDirectories()
        g.resolve("BepInEx/disabledPlugins/Foo/Foo.dll").writeText("foo")
        val f = RealGameFolder(home, store, noNet, running = { false })
        val found = f.takeOver(g.toString())
        val id = found.single().first.id
        println("PROBE N14 adopted ${found.map { it.first.id to it.second.profiles }}")
        f.apply(GamePlan(g.toString(), "default", emptyMap(), false))
        f.apply(GamePlan(g.toString(), "default", mapOf(id to "takeover"), false))
        val placed = g.resolve("BepInEx/plugins/Foo/Foo.dll").exists()
        println("PROBE N14 turned on; placed in plugins: $placed; record ${record(g).entries}")
        assertTrue(placed)
    }
}
