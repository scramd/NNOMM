package nnomm.core

import java.nio.file.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.settings.defaultSettings
import nnomm.core.store.AppStore

/** Review round 5 probes. Each asserts the GOOD behaviour; a failure demonstrates a finding. */
class Review5ProbeTest {
    private val roots = mutableListOf<Path>()

    private fun temp(p: String = "r5-") = Files.createTempDirectory(p).also { roots.add(it) }

    @AfterTest
    fun cleanup() {
        DiskFaults.step = {}
        roots.asReversed().forEach { deleteTree(it) }
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

    private fun plan(g: Path, versions: Map<String, String>, profile: String = "default") =
        GamePlan(g.toString(), profile, versions, false)

    private fun containsText(root: Path, text: String) =
        root.exists() &&
            Files.walk(root).use { s ->
                s.anyMatch {
                    it.isRegularFile() && runCatching { it.readText() }.getOrNull() == text
                }
            }

    private fun where(root: Path, text: String): List<String> =
        if (!root.exists()) emptyList()
        else
            Files.walk(root).use { s ->
                s.filter { it.isRegularFile() && runCatching { it.readText() }.getOrNull() == text }
                    .map { root.relativize(it).toString() }
                    .toList()
            }

    private fun copyDir(from: Path, to: Path) {
        Files.walk(from).use { s ->
            s.forEach { p ->
                val t = to.resolve(from.relativize(p).toString())
                if (p.isDirectory()) t.createDirectories() else Files.copy(p, t)
            }
        }
    }

    private fun takeoverFoo(g: Path) {
        val foo = g.resolve("BepInEx/plugins/Foo").createDirectories()
        foo.resolve("meta.json").writeText("""{"id":"foo","artifact":{"version":"1"}}""")
        foo.resolve("Foo.dll").writeText("foo one")
        foo.resolve("data.json").writeText("PLAYER DATA")
    }

    private val fooCatalog =
        listOf(
            CatalogMod(
                "foo",
                "Foo",
                versions =
                    listOf(
                        ModVersion("2", downloadUrl = "https://example.org/foo2.zip"),
                        ModVersion("1", downloadUrl = "https://example.org/foo1.zip"),
                    ),
            )
        )

    private fun noneKept() =
        defaultSettings().let {
            it.copy(general = it.general + ("keepOld" to JsonPrimitive("None")))
        }

    // A2: same, then Clear with "Keep old versions: None".
    @Test
    fun takenOverPlayerFileSurvivesUpdateAndClear() = runBlocking {
        val home = temp()
        val g = game()
        takeoverFoo(g)
        val store = RealModStore(home, null) { fooCatalog }
        val f = RealGameFolder(home, store, noNet, catalog = { fooCatalog }, running = { false })
        f.takeOver(g.toString())
        g.resolve("BepInEx/plugins/Foo/data.json").writeText("NEW PLAYER DATA")
        payload(store, "foo", "2", mapOf("Foo.dll" to "foo two"))
        f.apply(plan(g, mapOf("foo" to "2")))
        assertTrue(f.applySucceeded)
        assertEquals("foo two", g.resolve("BepInEx/plugins/Foo/Foo.dll").readText())
        val state =
            AppState(
                settings = noneKept(),
                catalog = fooCatalog,
                game = GameInfo(g.toString()),
                installed =
                    mapOf(
                        "foo" to
                            InstalledMod(
                                "foo",
                                versions = setOf("1", "2"),
                                defaultVersion = "2",
                                profiles = mapOf("default" to ProfileMod(true, "2")),
                            )
                    ),
            )
        // A fresh store object has no in-memory Undo protection (as after a restart).
        RealModStore(home, null) { fooCatalog }.clearUnused(state)
        assertTrue(
            containsText(home.resolve("player-files"), "NEW PLAYER DATA") ||
                containsText(home.resolve("player-edits"), "NEW PLAYER DATA"),
            "Clear deleted the only copy of a player file from a taken-over mod",
        )
    }

    // A3: taken over, uninstalled (Remove everything / Uninstall), then Clear.
    @Test
    fun takenOverPlayerFileSurvivesUninstallAndClear() = runBlocking {
        val home = temp()
        val g = game()
        takeoverFoo(g)
        val store = RealModStore(home, null) { fooCatalog }
        val f = RealGameFolder(home, store, noNet, catalog = { fooCatalog }, running = { false })
        f.takeOver(g.toString())
        g.resolve("BepInEx/plugins/Foo/data.json").writeText("NEW PLAYER DATA")
        f.apply(plan(g, emptyMap()))
        assertTrue(f.applySucceeded)
        assertFalse(g.resolve("BepInEx/plugins/Foo").exists())
        RealModStore(home, null) { fooCatalog }
            .clearUnused(
                AppState(
                    settings = defaultSettings(),
                    catalog = fooCatalog,
                    game = GameInfo(g.toString()),
                )
            )
        assertTrue(
            containsText(home.resolve("player-files"), "NEW PLAYER DATA") ||
                containsText(home.resolve("player-edits"), "NEW PLAYER DATA"),
            "uninstall + Clear deleted the only copy of a player file from a taken-over mod",
        )
    }

    // B: game folder moved (Steam library move) or record lost: NNOMM's own mods re-adopted.
    @Test
    fun movedGameFolderTakeoverRecognisesOwnMods() = runBlocking {
        val home = temp()
        val g1 = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        val notes = mutableListOf<String>()
        val f = RealGameFolder(home, store, noNet, running = { false }, notice = { notes += it })
        f.apply(plan(g1, mapOf("moda" to "1")))
        val g2 = temp("moved-")
        copyDir(g1, g2)
        f.apply(plan(g2, mapOf("moda" to "1")))
        val found = f.takeOver(g2.toString())
        val merged =
            mapOf("moda" to "1") + found.associate { it.first.id to it.second.defaultVersion }
        f.apply(plan(g2, merged))
        val problemsOn = f.applyProblems.value
        // Player turns the registry mod off; the adopted duplicate stays on by default.
        f.apply(plan(g2, merged - "moda"))
        assertTrue(
            found.none { it.first.id.startsWith("local-") },
            "own registry mod re-adopted as a local copy",
        )
    }

    // C: home A applied; home B took over and applied; A's Take over must give A a way forward.
    @Test
    fun foreignApplyAfterOwnApplyLeavesTakeOverAWayForward() = runBlocking {
        val g = game()
        val ha = temp("ha-")
        val sa = RealModStore(ha)
        payload(sa, "moda", "1", mapOf("a.dll" to "a"))
        payload(sa, "modb", "1", mapOf("b.dll" to "b"))
        val notes = mutableListOf<String>()
        val fa = RealGameFolder(ha, sa, noNet, running = { false }, notice = { notes += "A: $it" })
        fa.apply(plan(g, mapOf("moda" to "1")))
        val hb = temp("hb-")
        val fb =
            RealGameFolder(
                hb,
                RealModStore(hb),
                noNet,
                running = { false },
                notice = { notes += "B: $it" },
            )
        val foundB = fb.takeOver(g.toString())
        fb.apply(plan(g, foundB.associate { it.first.id to it.second.defaultVersion }))
        fa.apply(plan(g, mapOf("moda" to "1", "modb" to "1")))
        val before = fa.applyProblems.value
        fa.takeOver(g.toString())
        fa.apply(plan(g, mapOf("moda" to "1", "modb" to "1")))
        assertTrue(fa.applySucceeded && g.resolve("BepInEx/plugins/modb/b.dll").exists())
    }

    // D: a player file next to a mod; the next version ships a file with the same name.
    @Test
    fun playerFileReplacedByNewShippedFileReturnsWithOldVersion() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "m", "1", mapOf("m.dll" to "one"))
        payload(store, "m", "2", mapOf("m.dll" to "two", "config.json" to "DEFAULT"))
        val notes = mutableListOf<String>()
        val f = RealGameFolder(home, store, noNet, running = { false }, notice = { notes += it })
        f.apply(plan(g, mapOf("m" to "1")))
        val cfg = g.resolve("BepInEx/plugins/m/config.json")
        cfg.writeText("PLAYER CONFIG")
        f.apply(plan(g, mapOf("m" to "2")))
        val afterUpdate = where(home, "PLAYER CONFIG")
        f.apply(plan(g, mapOf("m" to "1")))
        assertEquals(
            "PLAYER CONFIG",
            cfg.readText(),
            "Returning to the old version must restore its saved player file",
        )
        assertTrue(
            where(home, "PLAYER CONFIG").any {
                it.startsWith("player-edits") || it.startsWith("player-files")
            } || (cfg.exists() && cfg.readText() == "PLAYER CONFIG"),
            "player file only left in historical recovery",
        )
    }

    @Test
    fun duplicatePlacementNamesAreRefused() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "a", "1", mapOf("a.dll" to "a"))
        payload(store, "b", "1", mapOf("b.dll" to "b"))
        val adoption =
            Placement(
                entries = mapOf("Same" to ManagedMod("a", "1")),
                unplaced = mapOf("Same" to ManagedMod("b", "1")),
                gameRoot = g.toRealPath().toString(),
            )
        atomic(
            home.resolve(
                "adoption-${Integer.toHexString(g.toRealPath().toString().hashCode())}.json"
            ),
            json.encodeToString(Placement.serializer(), adoption),
        )
        val f = RealGameFolder(home, store, noNet, running = { false })
        f.apply(plan(g, mapOf("a" to "1", "b" to "1")))
        assertFalse(f.applySucceeded)
        assertEquals(setOf("a", "b"), f.applyProblems.value.keys)
        assertFalse(g.resolve("BepInEx/plugins/Same").exists())
    }

    @Test
    fun pendingJournalBlocksOnlyItsEntry() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "a", "1", mapOf("a.dll" to "a"))
        payload(store, "b", "1", mapOf("b.dll" to "b"))
        val target = g.resolve("BepInEx/plugins/a")
        val batch =
            TransactionBatch(
                listOf(
                    DiskTransaction(
                        target.toString(),
                        null,
                        home.resolve("missing/old").toString(),
                        existed = true,
                        gameTarget = true,
                        phase = "old-moved",
                    )
                )
            )
        atomic(
            home.resolve("game-transactions/pending.txn"),
            json.encodeToString(TransactionBatch.serializer(), batch),
        )
        val f = RealGameFolder(home, store, noNet, running = { false })
        f.apply(plan(g, mapOf("a" to "1", "b" to "1")))
        assertFalse(target.exists())
        assertTrue(g.resolve("BepInEx/plugins/b/b.dll").exists())
        assertTrue("a" in f.applyProblems.value)
        assertFalse(f.applySucceeded)
    }

    @Test
    fun takeoverUsesOnlyVerifiedShippedHashes() = runBlocking {
        val home = temp()
        val store = RealModStore(home)
        val src = temp()
        src.resolve("mod.dll").writeText("shipped")
        src.resolve("save.json").writeText("player")
        store.put("m", "1", DownloadedFile(src.toString(), PayloadOrigin.TAKEOVER))
        assertTrue(store.shippedHashes("m", "1").isEmpty())
        val proof =
            PayloadProvenance(
                PayloadOrigin.REGISTRY_DOWNLOAD,
                "verified-archive-hash",
                mapOf("mod.dll" to digest(src.resolve("mod.dll"))),
            )
        atomic(
            home.resolve("registry-payloads/m/1.json"),
            json.encodeToString(PayloadProvenance.serializer(), proof),
        )
        assertEquals(setOf("mod.dll"), store.shippedHashes("m", "1").keys)
    }

    private fun launcherWith(running: MutableStateFlow<Boolean>) =
        object : GameLauncher {
            override val running = running

            override suspend fun start(state: AppState, withoutMods: Boolean) {}
        }

    private fun stateWith(g: Path, loader: Boolean, vararg on: String) =
        AppState(
            game = GameInfo(g.toString(), loaderPresent = loader),
            settings = defaultSettings(),
            catalog = listOf(CatalogMod("moda", "moda", versions = listOf(ModVersion("1")))),
            installed =
                mapOf(
                    "moda" to
                        InstalledMod(
                            "moda",
                            versions = setOf("1"),
                            defaultVersion = "1",
                            profiles = mapOf("default" to ProfileMod("moda" in on, "1")),
                        )
                ),
        )

    // E: NNOMM restarted; the player plays from Steam and exits. No mod action was taken.
    @Test
    fun gameExitAloneDoesNotWriteGameFolder() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        val f = RealGameFolder(home, store, noNet, running = { false })
        f.apply(plan(g, mapOf("moda" to "1")))
        // The player removed the mod folder by hand (for example to test without it).
        deleteTree(g.resolve("BepInEx/plugins/moda"))
        val running = MutableStateFlow(false)
        val a =
            AppStore(
                stateWith(g, true, "moda"),
                Demo.io()
                    .copy(
                        modStore = store,
                        gameFolder = f,
                        launcher = launcherWith(running),
                        persistence = null,
                    ),
            )
        delay(300)
        running.value = true
        delay(300)
        running.value = false
        delay(1000)
        a.awaitDiskIdle()
        val rewritten = g.resolve("BepInEx/plugins/moda").exists()
        a.shutdown()
        assertFalse(rewritten)
    }

    // E2: same, the player removed BepInEx; game exit must not reinstall the loader.
    @Test
    fun gameExitAloneDoesNotInstallLoader() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        var loaderDownloads = 0
        val counting =
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ): DownloadedFile {
                    if (mod.id == "bepinex") loaderDownloads++
                    error("No network")
                }
            }
        val f = RealGameFolder(home, store, counting, running = { false })
        f.apply(plan(g, mapOf("moda" to "1")))
        val running = MutableStateFlow(false)
        val a =
            AppStore(
                stateWith(g, false, "moda"),
                Demo.io()
                    .copy(
                        modStore = store,
                        gameFolder = f,
                        launcher = launcherWith(running),
                        persistence = null,
                    ),
            )
        delay(300)
        running.value = true
        delay(300)
        running.value = false
        delay(1000)
        a.awaitDiskIdle()
        a.shutdown()
        assertEquals(0, loaderDownloads)
    }

    // F: the round-3 "Take over" action must not reopen first start.
    @Test
    fun takeOverActionKeepsFirstStartComplete() = runBlocking {
        val home = temp()
        val g = game()
        g.resolve("BepInEx/plugins/Other.dll").writeText("other")
        val store = RealModStore(home)
        val f = RealGameFolder(home, store, noNet, running = { false })
        val a =
            AppStore(
                stateWith(g, true),
                Demo.io()
                    .copy(
                        modStore = store,
                        gameFolder = f,
                        launcher = launcherWith(MutableStateFlow(false)),
                        persistence = null,
                    ),
            )
        assertTrue(a.state.value.firstStart.complete)
        a.takeOverExistingMods(g.toString())
        withTimeout(10000) { while (a.state.value.installed.size < 2) delay(50) }
        val complete = a.state.value.firstStart.complete
        a.shutdown()
        assertTrue(
            complete,
            "Take over put the app back into first start (overlays blocked, dialog on next start)",
        )
    }

    // G: acceptance-sol 2: copied game folder with an old record, fresh home, explicit takeover.
    @Test
    fun staleRecordFromCopiedFolderDoesNotBlockSwitchingOff() = runBlocking {
        val g = game()
        val old = temp("old-")
        val os = RealModStore(old)
        payload(os, "acr", "1.2.0", mapOf("acr.dll" to "acr"))
        payload(os, "hud", "1", mapOf("hud.dll" to "hud"))
        RealGameFolder(old, os, noNet, running = { false })
            .apply(plan(g, mapOf("acr" to "1.2.0", "hud" to "1")))
        val home = temp()
        val f = RealGameFolder(home, RealModStore(home), noNet, running = { false })
        val found = f.takeOver(g.toString())
        val on = found.associate { it.first.id to it.second.defaultVersion }
        val hud = found.single { it.first.name == "hud" }.first.id
        f.apply(plan(g, on - hud))
        assertTrue(
            f.applySucceeded &&
                !g.resolve("BepInEx/plugins/hud").exists() &&
                g.resolve("BepInEx/plugins/acr").exists()
        )
    }

    // H: Clear must not delete a store-replacement recovery copy whose only "duplicate" is in
    // a version that the same Clear deletes (recomputed) - and must not count game recovery.
    @Test
    fun clearKeepsGameRecoveryWhoseTwinIsOnlyInGameRecovery() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        val a = g.resolve(".nnomm/recovery/x.txn/old-1/m").createDirectories()
        a.resolve("save").writeText("UNIQUE GAME RECOVERY")
        val b = g.resolve(".nnomm/recovery/y.txn/old-1/m").createDirectories()
        b.resolve("save").writeText("UNIQUE GAME RECOVERY")
        for ((n, p) in listOf("x" to a, "y" to b)) {
            Files.setLastModifiedTime(p.parent, java.nio.file.attribute.FileTime.fromMillis(1000))
            atomic(
                home.resolve("recovery/$n.txn.json"),
                kotlinx.serialization.json
                    .buildJsonObject { put("copies", JsonPrimitive(p.parent.toString())) }
                    .toString(),
            )
        }
        store.clearUnused(AppState(settings = defaultSettings(), game = GameInfo(g.toString())))
        assertTrue(containsText(g, "UNIQUE GAME RECOVERY"))
    }

    // I: profile change of a loose taken-over file with an edit: edit must follow its profile.
    @Test
    fun looseEditReturnsWithItsProfile() = runBlocking {
        val home = temp()
        val g = game()
        val file = g.resolve("BepInEx/plugins/Mine.dll")
        file.writeText("original")
        val store = RealModStore(home)
        val f = RealGameFolder(home, store, noNet, running = { false })
        val id = f.takeOver(g.toString()).single().first.id
        file.writeText("EDIT IN DEFAULT")
        f.apply(plan(g, mapOf(id to "takeover"), "other"))
        val inOther = file.takeIf { it.exists() }?.readText()
        f.apply(plan(g, mapOf(id to "takeover"), "default"))
        assertEquals("EDIT IN DEFAULT", file.readText())
    }

    // J: a broken journal must not refuse every later apply forever.
    @Test
    fun brokenHomeJournalDoesNotBlockGameAppliesForever() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        home
            .resolve("transactions")
            .createDirectories()
            .resolve("broken.txn")
            .writeText("{not json")
        val notes = mutableListOf<String>()
        val f = RealGameFolder(home, store, noNet, running = { false }, notice = { notes += it })
        f.apply(plan(g, mapOf("moda" to "1")))
        f.recoverPending()
        f.apply(plan(g, mapOf("moda" to "1")))
        assertTrue(g.resolve("BepInEx/plugins/moda/a.dll").exists())
    }

    // K: Play refused because one entry failed: the player must learn why and how to go on.
    @Test
    fun playBlockedByFailedEntryExplainsWhy() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        val f = RealGameFolder(home, store, noNet, running = { false })
        var started = false
        val launcher =
            object : GameLauncher {
                override val running = MutableStateFlow(false)

                override suspend fun start(state: AppState, withoutMods: Boolean) {
                    started = true
                }
            }
        val a =
            AppStore(
                stateWith(g, true, "moda"),
                Demo.io()
                    .copy(modStore = store, gameFolder = f, launcher = launcher, persistence = null),
            )
        a.play()
        delay(2000)
        a.awaitDiskIdle()
        val toasts = a.state.value.toasts.map { it.text + " [" + it.action + "]" }
        assertFalse(started)
        assertTrue(toasts.any { it.contains("stored files are missing") && it.contains("Retry") })
        val turnOff = a.state.value.toasts.single { it.action == "Turn off" }
        a.runToast(turnOff.id)
        a.awaitDiskIdle()
        assertFalse(a.state.value.installed.getValue("moda").profiles.getValue("default").on)
        a.shutdown()
    }
}
