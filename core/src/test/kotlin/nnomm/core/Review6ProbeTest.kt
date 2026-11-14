package nnomm.core

import java.nio.file.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.settings.defaultSettings
import nnomm.core.store.AppStore

/** Offline regressions ported from the final core review probes. */
class Review6ProbeTest {
    private val base = Files.createTempDirectory("review6-")
    private val roots = mutableListOf<Path>()

    private fun temp(p: String = "r6-") = Files.createTempDirectory(base, p).also { roots.add(it) }

    @AfterTest
    fun cleanup() {
        roots.asReversed().forEach { deleteTree(it) }
        deleteTree(base)
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
        origin: PayloadOrigin = PayloadOrigin.UNKNOWN,
    ) {
        val src = temp("src-")
        files.forEach { (k, value) ->
            src.resolve(k).also { it.parent.createDirectories() }.writeText(value)
        }
        store.put(id, v, DownloadedFile(src.toString(), origin))
    }

    private suspend fun bigPayload(store: RealModStore, id: String, mb: Int) {
        val src = temp("big-")
        val r = java.util.Random(id.hashCode().toLong())
        val buf = ByteArray(1 shl 20)
        Files.newOutputStream(src.resolve("$id.bin")).use { out ->
            repeat(mb) {
                r.nextBytes(buf)
                out.write(buf)
            }
        }
        src.resolve("$id.dll").writeText(id)
        store.put(id, "1", DownloadedFile(src.toString(), PayloadOrigin.UNKNOWN))
        deleteTree(src)
    }

    private fun plan(g: Path, versions: Map<String, String>, profile: String = "default") =
        GamePlan(g.toString(), profile, versions, false)

    private fun state(
        g: Path,
        ids: List<String>,
        on: Boolean = true,
        source: ModSource = ModSource.REGISTRY,
    ) =
        AppState(
            game = GameInfo(g.toString(), loaderPresent = true),
            settings = defaultSettings(),
            catalog = ids.map { CatalogMod(it, it, versions = listOf(ModVersion("1"))) },
            installed =
                ids.associateWith {
                    InstalledMod(
                        it,
                        source = source,
                        versions = setOf("1"),
                        defaultVersion = "1",
                        profiles = mapOf("default" to ProfileMod(on, "1")),
                    )
                },
        )

    private fun launcher(fail: Throwable? = null) =
        object : GameLauncher {
            override val running = MutableStateFlow(false)

            override suspend fun start(state: AppState, withoutMods: Boolean) {
                if (fail != null) throw fail
            }
        }

    // P1: every disk operation now recomputes store usage, which hashes the store and the
    // game's BepInEx folder. How long does one switch take with a mid-sized library?
    @Test
    fun switchCostWithLargeStore() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        val ids = (1..6).map { "big$it" }
        ids.forEach { bigPayload(store, it, 48) }
        val f = RealGameFolder(home, store, noNet, running = { false })
        f.apply(plan(g, ids.associateWith { "1" }))
        val s = state(g, ids)
        var t = System.nanoTime()
        store.unusedBytes(s)
        println("PROBE P1 unusedBytes alone: ${(System.nanoTime() - t) / 1_000_000} ms")
        t = System.nanoTime()
        store.usedBytes()
        store.installedBytes(s)
        println("PROBE P1 used+installed: ${(System.nanoTime() - t) / 1_000_000} ms")
        var estimates = 0
        val counted =
            object : ModStore by store {
                override suspend fun unusedBytes(state: AppState): Long {
                    estimates++
                    return store.unusedBytes(state)
                }
            }
        val a =
            AppStore(
                s,
                Demo.io()
                    .copy(
                        modStore = counted,
                        gameFolder = f,
                        launcher = launcher(),
                        persistence = null,
                    ),
            )
        a.awaitDiskIdle()
        t = System.nanoTime()
        a.setOn(listOf("big1"), false)
        a.awaitDiskIdle()
        println(
            "PROBE P1 one switch (apply + usage refresh): ${(System.nanoTime() - t) / 1_000_000} ms"
        )
        assertTrue(
            (System.nanoTime() - t) / 1_000_000 < 30_000,
            "Switch exceeded generous time budget",
        )
        t = System.nanoTime()
        a.awaitDiskIdle()
        println("PROBE P1 empty disk op: ${(System.nanoTime() - t) / 1_000_000} ms")
        assertTrue(
            (System.nanoTime() - t) / 1_000_000 < 30_000,
            "Routine disk work exceeded generous time budget",
        )
        assertNull(a.state.value.storeUnusedMb)
        assertEquals(0, estimates, "Routine disk actions must not hash Clear candidates")
        a.shutdown()
    }

    // P2: explicit Take over with a populated store: matchingVersion hashes every stored version
    // for every game entry.
    @Test
    fun explicitTakeoverCostWithLargeStore() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        val ids = (1..6).map { "big$it" }
        ids.forEach { bigPayload(store, it, 48) }
        // Six small unknown entries the player added by hand.
        (1..6).forEach {
            g.resolve("BepInEx/plugins/Hand$it")
                .createDirectories()
                .resolve("h$it.dll")
                .writeText("h$it")
        }
        val f = RealGameFolder(home, store, noNet, running = { false })
        val t = System.nanoTime()
        val found = f.takeOver(g.toString())
        assertEquals(6, found.size)
        assertTrue(
            (System.nanoTime() - t) / 1_000_000 < 30_000,
            "Takeover exceeded generous time budget",
        )
        println(
            "PROBE P2 takeover of 6 small entries with 288\u00a0MB store: ${(System.nanoTime() - t) / 1_000_000} ms, found ${found.size}"
        )
    }

    // P3: two stored versions with identical bytes; record lost; Take over; then apply.
    @Test
    fun identicalStoredVersionsAfterRecordLoss() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1.0.0", mapOf("a.dll" to "same"))
        payload(store, "moda", "1.0.1", mapOf("a.dll" to "same"))
        val notes = mutableListOf<String>()
        val f = RealGameFolder(home, store, noNet, running = { false }, notice = { notes += it })
        f.apply(plan(g, mapOf("moda" to "1.0.1")))
        println("PROBE P3 first apply ok=${f.applySucceeded}")
        g.resolve("BepInEx/plugins/.nnomm-state.json").deleteIfExists()
        g.resolve("BepInEx/plugins/.nnomm-state.json.bak").deleteIfExists()
        Files.list(g.resolve("BepInEx/plugins")).use {
            println("PROBE P3 plugins ${it.toList().map { p -> p.name }}")
        }
        val found = f.takeOver(g.toString())
        println(
            "PROBE P3 found ${found.map { it.first.id + "@" + it.second.defaultVersion }} notes $notes"
        )
        assertEquals("1.0.1", found.single().second.defaultVersion)
        notes.clear()
        f.apply(plan(g, mapOf("moda" to "1.0.1")))
        println(
            "PROBE P3 apply after takeover ok=${f.applySucceeded} problems=${f.applyProblems.value} notes=$notes"
        )
        f.apply(plan(g, emptyMap()))
        assertTrue(f.applySucceeded, f.applyProblems.value.toString())
        assertFalse(g.resolve("BepInEx/plugins/moda").exists())
        println(
            "PROBE P3 turn off ok=${f.applySucceeded} moda still in game=${g.resolve("BepInEx/plugins/moda").exists()} problems=${f.applyProblems.value} notes=$notes"
        )
    }

    // P4: Play fails in the launcher (not in apply): what does the player read?
    @Test
    fun launchFailureMessage() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"))
        val f = RealGameFolder(home, store, noNet, running = { false })
        val a =
            AppStore(
                state(g, listOf("moda")),
                Demo.io()
                    .copy(
                        modStore = store,
                        gameFolder = f,
                        launcher =
                            launcher(
                                DoorstopSettingsFailure(
                                    java.nio.file.AccessDeniedException("doorstop_config.ini")
                                )
                            ),
                        persistence = null,
                    ),
            )
        a.play(withoutMods = true)
        delay(1500)
        a.awaitDiskIdle()
        assertTrue(a.state.value.toasts.any { it.text.contains("Close programs") })
        assertFalse(a.state.value.toasts.any { it.text.contains("Use Repair") })
        assertFalse(a.state.value.toasts.any { it.text.contains("Some changes are pending") })
        println("PROBE P4 toasts ${a.state.value.toasts.map { it.text + " [" + it.action + "]" }}")
        a.shutdown()
    }

    // P5: Remove everything when the game record needs takeover (apply refused).
    @Test
    fun removeEverythingWhenApplyRefused() = runBlocking {
        val home = temp()
        val g = game()
        val other = temp("other-")
        val so = RealModStore(other)
        payload(so, "moda", "1", mapOf("a.dll" to "a"))
        RealGameFolder(other, so, noNet, running = { false }).apply(plan(g, mapOf("moda" to "1")))
        val store = RealModStore(home)
        payload(store, "moda", "1", mapOf("a.dll" to "a"), PayloadOrigin.REGISTRY_DOWNLOAD)
        val notes = mutableListOf<String>()
        val f = RealGameFolder(home, store, noNet, running = { false }, notice = { notes += it })
        val a =
            AppStore(
                state(g, listOf("moda")),
                Demo.io()
                    .copy(
                        modStore = store,
                        gameFolder = f,
                        launcher = launcher(),
                        persistence = null,
                    ),
            )
        a.removeEverything()
        delay(500)
        a.awaitDiskIdle()
        assertTrue("moda" in a.state.value.installed)
        assertTrue(g.resolve("BepInEx/plugins/moda").exists())
        println(
            "PROBE P5 installed=${a.state.value.installed.keys} game has moda=${g.resolve("BepInEx/plugins/moda").exists()} deferred=${a.state.value.deferredModChange} toasts=${a.state.value.toasts.map { it.text }} notes=$notes"
        )
        a.shutdown()
    }

    // P6: normal Remove everything with a registry, a local and a taken-over mod.
    @Test
    fun removeEverythingRemovesPersonalModsToo() = runBlocking {
        val home = temp()
        val g = game()
        g.resolve("BepInEx/plugins/Hand").createDirectories().resolve("hand.dll").writeText("hand")
        g.resolve("BepInEx/core").createDirectories().resolve("BepInEx.dll").writeText("core")
        g.resolve("winhttp.dll").writeText("w")
        g.resolve("doorstop_config.ini").writeText("[General]\nenabled = true\n")
        val store = RealModStore(home)
        payload(store, "reg", "1", mapOf("r.dll" to "r"), PayloadOrigin.REGISTRY_DOWNLOAD)
        payload(store, "local-x", "1", mapOf("x.dll" to "x"), PayloadOrigin.LOCAL_FILE)
        val f = RealGameFolder(home, store, noNet, running = { false })
        val found = f.takeOver(g.toString())
        val base = state(g, listOf("reg", "local-x"))
        val s =
            base.copy(
                catalog = base.catalog + found.map { it.first },
                installed =
                    base.installed +
                        ("local-x" to
                            base.installed.getValue("local-x").copy(source = ModSource.LOCAL)) +
                        found.associate { it.first.id to it.second },
            )
        val a =
            AppStore(
                s,
                Demo.io()
                    .copy(
                        modStore = store,
                        gameFolder = f,
                        launcher = launcher(),
                        persistence = null,
                    ),
            )
        a.retryApply()
        a.awaitDiskIdle()
        Files.list(g.resolve("BepInEx/plugins")).use {
            println("PROBE P6 before ${it.toList().map { p -> p.name }}")
        }
        a.removeEverything()
        delay(500)
        a.awaitDiskIdle()
        Files.list(g.resolve("BepInEx/plugins")).use {
            println("PROBE P6 after ${it.toList().map { p -> p.name }}")
        }
        println(
            "PROBE P6 installed=${a.state.value.installed.mapValues { it.value.profiles.mapValues { p -> p.value.on } }} toasts=${a.state.value.toasts.map { it.text }}"
        )
        assertTrue(a.state.value.installed.isEmpty())
        assertTrue(f.applySucceeded, f.applyProblems.value.toString())
        a.shutdown()
    }

    // P7: a taken-over NOMM mod (meta.json) is updated; the new version no longer ships Legacy.dll.
    @Test
    fun takenOverModUpdateCarriesOldShippedFiles() = runBlocking {
        val home = temp()
        val g = game()
        val foo = g.resolve("BepInEx/plugins/Foo").createDirectories()
        foo.resolve("meta.json").writeText("""{"id":"foo","artifact":{"version":"1"}}""")
        foo.resolve("Foo.dll").writeText("foo one")
        foo.resolve("Legacy.dll").writeText("legacy one")
        val store = RealModStore(home)
        val notes = mutableListOf<String>()
        val f =
            RealGameFolder(
                home,
                store,
                noNet,
                running = { false },
                notice = { notes += it },
                catalog = {
                    listOf(
                        CatalogMod(
                            "foo",
                            "Foo",
                            versions = listOf(ModVersion("2"), ModVersion("1")),
                        )
                    )
                },
            )
        val found = f.takeOver(g.toString())
        println("PROBE P7 found ${found.map { it.first.id + "@" + it.second.defaultVersion }}")
        f.apply(plan(g, mapOf("foo" to "1")))
        payload(store, "foo", "2", mapOf("Foo.dll" to "foo two"), PayloadOrigin.REGISTRY_DOWNLOAD)
        f.apply(plan(g, mapOf("foo" to "2")))
        val files =
            Files.walk(g.resolve("BepInEx/plugins")).use {
                it.filter { p -> p.isRegularFile() && !p.name.startsWith(".") }
                    .map { p ->
                        g.resolve("BepInEx/plugins").relativize(p).toString() + "=" + p.readText()
                    }
                    .toList()
            }
        assertTrue(f.applySucceeded, f.applyProblems.value.toString())
        assertFalse(files.any { it.contains("Legacy.dll") || it.contains("meta.json") })
        assertTrue(notes.none { it.contains(home.toString()) || it.contains("local-") })
        println("PROBE P7 after update ok=${f.applySucceeded} files=$files notes=$notes")
    }

    // P8: GitHub release notes: is the Full Changelog line dropped?
    @Test
    fun releaseNotesDropFullChangelog() {
        val text = javaClass.getResource("/configuration-manager.atom")!!.readText()
        val mod =
            CatalogMod(
                "cm",
                "CM",
                versions =
                    listOf(
                        ModVersion(
                            "19.1",
                            downloadUrl =
                                "https://github.com/BepInEx/BepInEx.ConfigurationManager/releases/download/v19.1/x.zip",
                        )
                    ),
            )
        val notes = parseAtom(text, mod)
        assertTrue(notes.isNotEmpty())
        assertTrue(
            notes
                .flatMap { it.lines }
                .none {
                    it.contains("Full Changelog", true) || it.contains("first contribution", true)
                }
        )
        println("PROBE P8 ${notes.map { it.version + " " + it.date + " " + it.lines }}")
    }

    // P9: a malformed home journal: what does the player read, and how often?
    @Test
    fun brokenJournalNoticeRepeats() = runBlocking {
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
        f.apply(plan(g, emptyMap()))
        f.apply(plan(g, mapOf("moda" to "1")))
        assertEquals(1, notes.size)
        assertTrue(notes.none { it.contains(home.toString()) || it.contains("json", true) })
        assertFalse(home.resolve("transactions/broken.txn").exists())
        println("PROBE P9 ${notes.size} notices: ${notes.distinct()}")
    }

    @Test
    fun legacySavedExtrasCanEnableNewVersion() = runBlocking {
        for (infer in listOf(true, false)) {
            val home = temp()
            val g = game()
            val store = RealModStore(home)
            payload(store, "m", "1", mapOf("m.dll" to "one"))
            payload(store, "m", "2", mapOf("m.dll" to "two", "config.json" to "DEFAULT"))
            val f = RealGameFolder(home, store, noNet, running = { false })
            f.apply(plan(g, mapOf("m" to "1")))
            g.resolve("BepInEx/plugins/m/config.json").writeText("PLAYER CONFIG")
            f.apply(plan(g, emptyMap()))
            // Old versions wrote only .generation, not .version.
            home.resolve("player-files/default/m.version").deleteExisting()
            if (!infer) home.resolve("player-files/default/m.generation").deleteExisting()
            f.apply(plan(g, mapOf("m" to "2")))
            assertTrue(f.applySucceeded, f.applyProblems.value.toString())
            assertTrue(g.resolve("BepInEx/plugins/m/m.dll").exists())
        }
    }

    @Test
    fun metadataLoadsWithoutVisibleCards() = runBlocking {
        val asked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val catalog =
            (1..8).map { id ->
                CatalogMod(
                    "m$id",
                    "Mod $id",
                    versions =
                        listOf(
                            ModVersion("2", downloadUrl = "https://example.invalid/$id/2"),
                            ModVersion("1", downloadUrl = "https://example.invalid/$id/1"),
                        ),
                )
            }
        val io =
            Demo.io()
                .copy(
                    persistence = null,
                    meta =
                        object : MetaSource {
                            override suspend fun metadata(
                                mod: CatalogMod,
                                version: ModVersion,
                            ): VersionMetadata {
                                asked += "${mod.id}/${version.version}"
                                return VersionMetadata(sizeKb = null, dateMillis = 1L)
                            }
                        },
                )
        val a = AppStore(AppState(catalog = catalog, settings = defaultSettings()), io)
        try {
            withTimeout(5000) { while (asked.size < 16) delay(10) }
        } finally {
            a.shutdown()
        }
        assertEquals(16, asked.size)
    }

    @Test
    fun collisionDoesNotBlockOtherMods() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        for (id in listOf("a", "b", "c")) payload(store, id, "1", mapOf("$id.dll" to id))
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
        f.apply(plan(g, mapOf("a" to "1", "b" to "1", "c" to "1")))
        assertFalse(f.applySucceeded)
        assertEquals(setOf("a", "b"), f.applyProblems.value.keys)
        assertEquals("c", g.resolve("BepInEx/plugins/c/c.dll").readText())
    }

    @Test
    fun tagsPreserveAcronyms() {
        for (tag in listOf("ECM", "GFX", "BOTE", "FFB", "SARH", "ARH", "XYZ")) assertEquals(
            tag,
            nnomm.core.rules.displayTag(tag),
        )
        assertEquals("ECM tools", nnomm.core.rules.displayTag("ecm tools"))
    }

    @Test
    fun verifiedTakeoverLearnsShippedFilesWithoutReplacingSnapshot() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        val foo = g.resolve("BepInEx/plugins/Foo").createDirectories()
        foo.resolve("meta.json").writeText("""{"id":"foo","artifact":{"version":"1"}}""")
        foo.resolve("Foo.dll").writeText("player edit")
        foo.resolve("Legacy.dll").writeText("old shipped")
        val archive = temp().resolve("foo.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            for ((name, bytes) in mapOf("Foo.dll" to "original", "Legacy.dll" to "old shipped")) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes.toByteArray())
                zip.closeEntry()
            }
        }
        val artifact =
            ModVersion("1", hash = digest(archive), downloadUrl = "https://example.invalid/foo.zip")
        val mod = CatalogMod("foo", "Foo", versions = listOf(artifact, ModVersion("2")))
        var downloads = 0
        val downloader =
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ): DownloadedFile {
                    downloads++
                    return DownloadedFile(
                        archive.toString(),
                        PayloadOrigin.REGISTRY_DOWNLOAD,
                        digest(archive),
                    )
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val f =
            RealGameFolder(
                home,
                store,
                downloader,
                catalog = { listOf(mod) },
                running = { false },
                backgroundScope = scope,
            )
        try {
            f.takeOver(g.toString())
            withTimeout(5000) { while (store.unprovenTakeover("foo", "1")) delay(10) }
            f.takeOver(g.toString())
            assertEquals(1, downloads)
            assertEquals("player edit", store.version("foo", "1").resolve("Foo.dll").readText())
            f.apply(plan(g, mapOf("foo" to "1")))
            payload(store, "foo", "2", mapOf("Foo.dll" to "new"))
            f.apply(plan(g, mapOf("foo" to "2")))
            assertTrue(f.applySucceeded, f.applyProblems.value.toString())
            assertFalse(foo.resolve("Legacy.dll").exists())
            assertFalse(foo.resolve("meta.json").exists())
            assertEquals("new", foo.resolve("Foo.dll").readText())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun takeoverHashesEachCandidateAtMostOnce() = runBlocking {
        val home = temp()
        val store = RealModStore(home)
        payload(store, "m", "1", mapOf("m.dll" to "same"))
        payload(store, "m", "2", mapOf("m.dll" to "same"))
        val input = temp().also { it.resolve("m.dll").writeText("same") }
        val calls = mutableMapOf<Path, Int>()
        val matcher = store.versionMatcher { calls[it] = (calls[it] ?: 0) + 1 }
        repeat(6) { assertEquals("m" to "2", matcher(input, false, null)) }
        assertEquals(2, calls.size)
        assertTrue(calls.values.all { it == 1 })
        assertEquals("m" to "1", matcher(input, false, "m" to "1"))
        assertEquals("m" to "1", store.versionMatcher(mapOf("m" to "1"))(input, false, null))
    }

    @Test
    fun unprovenTakeoverEditsReturnOnlyWithOriginalVersion() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        val foo = g.resolve("BepInEx/plugins/Foo").createDirectories()
        foo.resolve("meta.json").writeText("""{"id":"foo","artifact":{"version":"1"}}""")
        foo.resolve("Foo.dll").writeText("original")
        val f = RealGameFolder(home, store, noNet, running = { false })
        f.takeOver(g.toString())
        f.apply(plan(g, mapOf("foo" to "1")))
        foo.resolve("Foo.dll").writeText("player edit")
        foo.resolve("personal.json").writeText("personal")
        f.apply(plan(g, emptyMap()))
        payload(store, "foo", "2", mapOf("Foo.dll" to "new"))
        f.apply(plan(g, mapOf("foo" to "2")))
        assertTrue(f.applySucceeded, f.applyProblems.value.toString())
        assertEquals("new", foo.resolve("Foo.dll").readText())
        assertFalse(foo.resolve("personal.json").exists())
        f.apply(plan(g, mapOf("foo" to "1")))
        assertTrue(f.applySucceeded, f.applyProblems.value.toString())
        assertEquals("player edit", foo.resolve("Foo.dll").readText())
        assertEquals("personal", foo.resolve("personal.json").readText())
    }

    @Test
    fun closingRemovalWhenLoaderRemovalFails() = runBlocking {
        val home = temp()
        val g = game()
        val store = RealModStore(home)
        payload(store, "reg", "1", mapOf("r.dll" to "r"), PayloadOrigin.REGISTRY_DOWNLOAD)
        val f = RealGameFolder(home, store, noNet, running = { false })
        f.apply(plan(g, mapOf("reg" to "1")))
        val failLoader =
            object : GameFolder by f {
                override suspend fun removeLoader(folder: String): nnomm.core.io.LoaderRemoval {
                    error("Loader file is locked")
                }
            }
        var saved: AppState? = null
        val persistence =
            object : StatePersistence {
                override suspend fun load(): AppState? = saved

                override suspend fun save(state: AppState) {
                    saved = state
                }
            }
        val a =
            AppStore(
                state(g, listOf("reg")),
                Demo.io()
                    .copy(
                        modStore = store,
                        gameFolder = failLoader,
                        launcher = launcher(),
                        persistence = persistence,
                    ),
            )
        try {
            a.removeEverything()
            a.awaitDiskIdle()
            println(
                "CLOSING loader failure: installed=" +
                    a.state.value.installed +
                    " gameEntry=" +
                    g.resolve("BepInEx/plugins/reg").exists() +
                    " deferred=" +
                    a.state.value.deferredModChange +
                    " toasts=" +
                    a.state.value.toasts.map { it.text }
            )
            assertFalse(g.resolve("BepInEx/plugins/reg").exists())
            assertTrue(a.state.value.installed.isEmpty())
            assertTrue(
                a.state.value.toasts.any {
                    it.text.contains("Removed 1 mod from the Library and every profile.")
                }
            )
            assertNotNull(saved)
            assertTrue(saved!!.installed.isEmpty())
            val retry = a.state.value.toasts.last { it.action == "Retry" }
            a.runToast(retry.id)
            a.awaitDiskIdle()
            assertTrue(a.state.value.installed.isEmpty())
        } finally {
            a.shutdown()
        }
    }

    @Test
    fun closingUnchangedTakeoverDoesNotParkStoreBytes() = runBlocking {
        val home = temp()
        val g = game()
        val foo = g.resolve("BepInEx/plugins/Foo").createDirectories()
        foo.resolve("meta.json").writeText("""{"id":"foo","artifact":{"version":"1"}}""")
        foo.resolve("Foo.dll").writeText("one".repeat(700000))
        val store = RealModStore(home)
        val notices = mutableListOf<String>()
        val folder =
            RealGameFolder(home, store, noNet, running = { false }, notice = { notices += it })
        folder.takeOver(g.toString())
        payload(store, "foo", "2", mapOf("Foo.dll" to "two"))
        for (v in listOf("1", "2", "1", "2")) {
            folder.apply(plan(g, mapOf("foo" to v)))
            assertTrue(folder.applySucceeded)
        }
        val parks = home.resolve("player-edits/default/foo")
        if (parks.exists())
            Files.list(parks).use { paths ->
                assertTrue(paths.noneMatch { it.name.startsWith("1-") })
            }
        assertTrue(notices.none { it.contains("set aside") })
        // A real player edit is retained once and restored with its original version.
        folder.apply(plan(g, mapOf("foo" to "1")))
        foo.resolve("personal.json").writeText("personal")
        folder.apply(plan(g, mapOf("foo" to "2")))
        val before = treeHash(parks)
        folder.apply(plan(g, mapOf("foo" to "1")))
        assertEquals("personal", foo.resolve("personal.json").readText())
        folder.apply(plan(g, mapOf("foo" to "2")))
        assertEquals(before, treeHash(parks))
    }

    @Test
    fun closingVisibleMetadataBypassesBackgroundQueue() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val visible = CompletableDeferred<Unit>()
        val mods =
            (1..30).map {
                CatalogMod(
                    "m$it",
                    "Mod $it",
                    versions = listOf(ModVersion("1", downloadUrl = "https://example.invalid/$it")),
                )
            }
        val meta =
            object : MetaSource {
                override suspend fun metadata(
                    mod: CatalogMod,
                    version: ModVersion,
                ): VersionMetadata {
                    if (mod.id == "m1") {
                        started.complete(Unit)
                        release.await()
                    }
                    if (mod.id == "m30") visible.complete(Unit)
                    return VersionMetadata(sizeKb = null, dateMillis = 1)
                }
            }
        val app =
            AppStore(
                AppState(catalog = mods, settings = defaultSettings()),
                Demo.io().copy(meta = meta, persistence = null),
            )
        try {
            withTimeout(5000) { started.await() }
            app.loadMetadata(setOf("m30"))
            withTimeout(5000) { visible.await() }
        } finally {
            release.complete(Unit)
            app.shutdown()
        }
    }

    @Test
    fun closingShippedLearningIsSerialAndPreparationIsCleaned() = runBlocking {
        val home = temp()
        home
            .resolve("staging/learn-shipped-abandoned")
            .createDirectories()
            .resolve("a")
            .writeText("partial")
        cleanHomePreparation(home)
        assertFalse(home.resolve("staging/learn-shipped-abandoned").exists())
        val g = game()
        val mods =
            (1..3).map {
                CatalogMod(
                    "m$it",
                    "Mod $it",
                    versions = listOf(ModVersion("1", hash = "a".repeat(64))),
                )
            }
        mods.forEach { m ->
            g.resolve("BepInEx/plugins/${m.id}").createDirectories().also {
                it.resolve("meta.json").writeText("""{"id":"${m.id}","artifact":{"version":"1"}}""")
                it.resolve("a.dll").writeText(m.id)
            }
        }
        val count = java.util.concurrent.atomic.AtomicInteger()
        val max = java.util.concurrent.atomic.AtomicInteger()
        val done = java.util.concurrent.atomic.AtomicInteger()
        val downloader =
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ): DownloadedFile {
                    val active = count.incrementAndGet()
                    max.updateAndGet { maxOf(it, active) }
                    try {
                        delay(100)
                        error("offline")
                    } finally {
                        count.decrementAndGet()
                        done.incrementAndGet()
                    }
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val folder =
            RealGameFolder(
                home,
                RealModStore(home),
                downloader,
                catalog = { mods },
                running = { false },
                backgroundScope = scope,
                backgroundReady = { ready.get() },
            )
        try {
            folder.takeOver(g.toString())
            delay(300)
            assertEquals(0, count.get())
            ready.set(true)
            withTimeout(5000) { while (done.get() < 3) delay(10) }
            assertEquals(1, max.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun closingPlayWithoutFolderAndLoaderFailureArePlain() = runBlocking {
        val app =
            AppStore(AppState(settings = defaultSettings()), Demo.io().copy(persistence = null))
        try {
            app.play()
            assertEquals(
                "Choose the game folder in Settings first.",
                app.state.value.toasts.last().text,
            )
            assertFalse(app.state.value.gameStarting)
        } finally {
            app.shutdown()
        }
        val g = game()
        val failure =
            object : GameFolder by Demo.io().gameFolder {
                override suspend fun installLoader(folder: String) {
                    error("private /secret/path")
                }
            }
        val other =
            AppStore(
                state(g, listOf("reg")).copy(game = GameInfo(g.toString())),
                Demo.io().copy(gameFolder = failure, persistence = null),
            )
        try {
            other.play()
            withTimeout(5000) {
                while (
                    other.state.value.toasts.none {
                        it.text.startsWith("Could not install the mod loader.")
                    }
                ) delay(10)
            }
            assertTrue(
                other.state.value.toasts.any {
                    it.text.startsWith("Could not install the mod loader.") && it.action == "Retry"
                }
            )
            assertTrue(
                other.state.value.toasts.none {
                    it.text.contains("secret") || it.text.contains("take over")
                }
            )
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun closingRemovalWithoutFolderIgnoresStaleApplyFlag() = runBlocking {
        val g = game()
        val refused =
            object : GameFolder by Demo.io().gameFolder {
                override val applySucceeded = false

                override suspend fun apply(plan: GamePlan) {
                    error("must not apply without folder")
                }
            }
        val app =
            AppStore(
                state(g, listOf("reg")).copy(game = GameInfo()),
                Demo.io()
                    .copy(
                        gameFolder = refused,
                        persistence = null,
                        modStore =
                            object : ModStore by Demo.io().modStore {
                                override suspend fun keepInLibrary(
                                    id: String,
                                    versions: Set<String>,
                                ) = false
                            },
                    ),
            )
        try {
            app.removeEverything()
            app.awaitDiskIdle()
            assertTrue(app.state.value.installed.isEmpty())
            assertTrue(
                app.state.value.toasts.any { it.text.contains("No game folder is selected") }
            )
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun closingStorageFailureKeepsEstimateAndCanRetry() = runBlocking {
        var fail = true
        val store =
            object : ModStore by Demo.io().modStore {
                override suspend fun unusedBytes(state: AppState): Long {
                    if (fail) error("scan failed")
                    return 9 * 1024 * 1024
                }
            }
        val app =
            AppStore(
                AppState(settings = defaultSettings(), storeUnusedMb = 7),
                Demo.io().copy(modStore = store, persistence = null),
            )
        try {
            app.refreshStoreUsage()
            app.awaitDiskIdle()
            assertEquals(LoadStatus.FAILED, app.state.value.storeScanStatus)
            assertEquals(7L, app.state.value.storeUnusedMb)
            fail = false
            app.refreshStoreUsage()
            app.awaitDiskIdle()
            assertEquals(LoadStatus.READY, app.state.value.storeScanStatus)
            assertEquals(9L, app.state.value.storeUnusedMb)
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun closingRemovalReportsGameStartingDuringApply() = runBlocking {
        val g = game()
        val launch =
            object : GameLauncher {
                override val running = MutableStateFlow(false)

                override suspend fun start(state: AppState, withoutMods: Boolean) {}
            }
        val folder =
            object : GameFolder by Demo.io().gameFolder {
                override val applySucceeded = true

                override suspend fun apply(plan: GamePlan) {
                    launch.running.value = true
                }
            }
        val app =
            AppStore(
                state(g, listOf("reg")),
                Demo.io().copy(gameFolder = folder, launcher = launch, persistence = null),
            )
        try {
            app.removeEverything()
            app.awaitDiskIdle()
            assertTrue(app.state.value.installed.containsKey("reg"))
            assertTrue(
                app.state.value.toasts.any {
                    it.text.contains("Close Nuclear Option before removing mods.")
                }
            )
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun closingSearchUsesSharedRemovalNameAndComputedStorage() {
        val state = AppState(settings = defaultSettings(), storeUsedBytes = 53 * 1024)
        assertTrue(
            nnomm.core.rules.palItems(state, "Remove all mods").any {
                it.label == "Remove all mods"
            }
        )
        val help =
            nnomm.core.settings.settingHelp(
                nnomm.core.settings.SETTINGS.first { it.key == "store" },
                state,
            )
        assertTrue(help.contains("53\u00a0KB"))
        assertFalse(help.contains("438"))
    }
}
