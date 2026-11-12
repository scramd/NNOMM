package nnomm.core

import java.nio.file.*
import java.util.zip.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*

/** Assertions ported from both reviewers' executable probes; fixtures never use a game install. */
class ReviewProbeTest {
    private val temporary = mutableListOf<java.nio.file.Path>()

    @kotlin.test.AfterTest
    fun cleanupTemporaryFiles() {
        temporary.asReversed().forEach { root ->
            if (root.exists())
                Files.walk(root).use { files ->
                    files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
        }
    }

    private fun temp() = Files.createTempDirectory("nnomm-review-").also { temporary.add(it) }

    private val noNet =
        object : Downloader {
            override suspend fun download(
                mod: CatalogMod,
                version: ModVersion,
                progress: (Int) -> Unit,
            ): DownloadedFile = error("No network")
        }

    private fun game() = temp().also { it.resolve("NuclearOption.exe").writeText("") }

    private suspend fun payload(
        store: RealModStore,
        home: Path,
        id: String = "m",
        text: String = "good",
    ) {
        val dir = Files.createTempDirectory(home, "input-")
        dir.resolve("mod.dll").writeText(text)
        store.put(id, "1", DownloadedFile(dir.toString()))
    }

    private fun state(game: Path, on: Boolean = true) =
        AppState(
            game = GameInfo(game.toString()),
            installed =
                mapOf(
                    "m" to
                        InstalledMod(
                            "m",
                            versions = setOf("1"),
                            defaultVersion = "1",
                            profiles =
                                mapOf(
                                    "default" to ProfileMod(on, "1"),
                                    "b" to ProfileMod(false, "1"),
                                ),
                        )
                ),
            profiles = listOf(Profile("default", "Default"), Profile("b", "B")),
        )

    @Test
    fun takeoverKeepsLooseNamesUnknownFilesAndArchivesInert() = runBlocking {
        val home = temp()
        val game = game()
        val plugins = game.resolve("BepInEx/plugins").createDirectories()
        plugins.resolve("Existing").createDirectories().resolve("mod.dll").writeText("good")
        for (name in listOf("Loose.dll", "Mod.pdb", "inert.zip")) plugins
            .resolve(name)
            .writeText("raw-$name")
        val folder = RealGameFolder(home, RealModStore(home), noNet, running = { false })
        val found = folder.takeOver(game.toString())
        assertEquals(4, found.size)
        assertFalse(plugins.resolve(".nnomm-state.json").exists())
        folder.apply(
            AppState(
                game = GameInfo(game.toString()),
                catalog = found.map { it.first },
                installed = found.associate { it.second.id to it.second },
            ),
            "default",
        )
        assertEquals("good", plugins.resolve("Existing/mod.dll").readText())
        for (name in listOf("Loose.dll", "Mod.pdb", "inert.zip")) assertEquals(
            "raw-$name",
            plugins.resolve(name).readText(),
        )
    }

    @Test
    fun copyIsolationAndSameVersionRepair() = runBlocking {
        val home = temp()
        val game = game()
        val store = RealModStore(home)
        payload(store, home)
        val folder = RealGameFolder(home, store, noNet, running = { false })
        val state = state(game)
        folder.apply(state, "default")
        val placed = game.resolve("BepInEx/plugins/m/mod.dll")
        placed.writeText("corrupt")
        assertEquals("good", store.version("m", "1").resolve("mod.dll").readText())
        payload(store, home)
        folder.apply(state, "default")
        assertEquals("good", placed.readText())
    }

    @Test
    fun playerFilesReturnOnlyToTheirProfile() = runBlocking {
        val home = temp()
        val game = game()
        val store = RealModStore(home)
        payload(store, home)
        val folder = RealGameFolder(home, store, noNet, running = { false })
        val state = state(game)
        folder.apply(state, "default")
        val file = game.resolve("BepInEx/plugins/m/liveries/mine.png")
        file.parent.createDirectories()
        file.writeText("player")
        folder.apply(state, "b")
        assertFalse(file.exists())
        folder.apply(state, "default")
        assertEquals("player", file.readText())
    }

    @Test
    fun staleOwnershipCannotRemoveFiles() = runBlocking {
        val home = temp()
        val game = game()
        val store = RealModStore(home)
        payload(store, home)
        RealGameFolder(home, store, noNet, running = { false }).apply(state(game), "default")
        val fresh = temp()
        val freshStore = RealModStore(fresh)
        RealGameFolder(fresh, freshStore, noNet, running = { false })
            .apply(AppState(game = GameInfo(game.toString())), "default")
        assertEquals("good", game.resolve("BepInEx/plugins/m/mod.dll").readText())
    }

    @Test
    fun collisionDoesNotBlockOtherMods() = runBlocking {
        val home = temp()
        val game = game()
        val store = RealModStore(home)
        payload(store, home, "a")
        payload(store, home, "b")
        game.resolve("BepInEx/plugins/a").createDirectories().resolve("player").writeText("keep")
        val state =
            AppState(
                game = GameInfo(game.toString()),
                installed =
                    listOf("a", "b").associateWith {
                        InstalledMod(
                            it,
                            versions = setOf("1"),
                            defaultVersion = "1",
                            profiles = mapOf("default" to ProfileMod(true, "1")),
                        )
                    },
            )
        RealGameFolder(home, store, noNet, running = { false }).apply(state, "default")
        assertEquals("keep", game.resolve("BepInEx/plugins/a/player").readText())
        assertTrue(game.resolve("BepInEx/plugins/b/mod.dll").exists())
    }

    @Test
    fun duplicateTakeoverRetainsBothCopies() = runBlocking {
        val home = temp()
        val game = game()
        for (dir in listOf("plugins", "disabledPlugins")) game
            .resolve("BepInEx/$dir")
            .createDirectories()
            .resolve("Same.dll")
            .writeText("same")
        val found =
            RealGameFolder(home, RealModStore(home), noNet, running = { false })
                .takeOver(game.toString())
        assertEquals(1, found.size)
        assertTrue(found.single().second.profiles.getValue("default").on)
        assertTrue(game.resolve("BepInEx/disabledPlugins/Same.dll").exists())
    }

    @Test
    fun twoLocalPayloadsRemainOwnedByPreview() = runBlocking {
        val home = temp()
        val store = RealModStore(home)
        val client = networkClient("test")
        try {
            payload(store, home, "local-a")
            payload(store, home, "local-b", text = "other")
            val packs = RealPacks(home, store, client)
            val path = home.resolve("p.nommpack")
            packs.write(
                path,
                Pack(
                    "P",
                    "A",
                    "1",
                    listOf(
                        PackItem(PackKind.LOCAL, "local-a", "1"),
                        PackItem(PackKind.LOCAL, "local-b", "1"),
                    ),
                ),
                AppState(),
            )
            packs.read(path).use { preview ->
                assertEquals(2, packs.selectedLocals(preview, preview.localFiles.keys).size)
                assertTrue(Path.of(preview.localFiles.values.first()).exists())
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidZipCannotReplaceGoodVersion() = runBlocking {
        val home = temp()
        val store = RealModStore(home)
        payload(store, home)
        val html = home.resolve("bad.zip")
        html.writeText("<html>error</html>")
        assertFails { store.put("m", "1", DownloadedFile(html.toString())) }
        assertEquals("good", store.version("m", "1").resolve("mod.dll").readText())
    }

    @Test
    fun symlinkedRootsAreAccepted() = runBlocking {
        val home = temp()
        val real = game()
        val link = temp().resolve("steam")
        Files.createSymbolicLink(link, real)
        assertEquals(
            real.toString(),
            RealGameFolder(home, RealModStore(home), noNet, running = { false })
                .inspect(link.toString())
                .folder,
        )
        val linkedHome = temp().resolve("home")
        Files.createSymbolicLink(linkedHome, home)
        payload(RealModStore(linkedHome), linkedHome)
    }

    @Test
    fun windowsAliasesAndTraversalAreRejected() = runBlocking {
        for (name in
            listOf("CON", "nul.dll", "COM1", "bad.", "bad ", "../escape", "C:\\evil")) assertFails {
            component(name)
        }
        val home = temp()
        val zip = home.resolve("bad.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            for (name in listOf("A.dll", "a.dll")) {
                out.putNextEntry(ZipEntry(name))
                out.write(1)
                out.closeEntry()
            }
        }
        assertFails { RealModStore(home).put("m", "1", DownloadedFile(zip.toString())) }
        Unit
    }

    @Test
    fun storePromotionRollsBackAtEveryMutationBoundary() = runBlocking {
        for (step in listOf("prepared", "old-moved", "promoted")) {
            val home = temp()
            val store = RealModStore(home)
            payload(store, home)
            DiskFaults.step = { if (it == step) throw DiskCrash() }
            try {
                assertFails { payload(store, home, text = "new") }
            } finally {
                DiskFaults.step = {}
            }
            val reopened = RealModStore(home)
            assertEquals(
                if (step == "promoted") "new" else "good",
                reopened.version("m", "1").resolve("mod.dll").readText(),
            )
        }
    }

    @Test
    fun applyRollsBackAtEveryMutationBoundary() = runBlocking {
        for (step in listOf("prepared", "old-moved", "promoted")) {
            val home = temp()
            val game = game()
            val store = RealModStore(home)
            payload(store, home)
            val folder = RealGameFolder(home, store, noNet, running = { false })
            folder.apply(state(game), "default")
            DiskFaults.step = { if (it == step) throw DiskCrash() }
            try {
                assertFails { folder.apply(state(game, false), "default") }
            } finally {
                DiskFaults.step = {}
            }
            val reopened = RealModStore(home)
            RealGameFolder(home, reopened, noNet, running = { false }).inspect(game.toString())
            assertEquals("good", game.resolve("BepInEx/plugins/m/mod.dll").readText())
            assertFalse(game.resolve("BepInEx/plugins/.nnomm-staging").exists())
        }
    }

    @Test
    fun missingGameFolderIsInspectable() = runBlocking {
        val home = temp()
        assertFalse(
            RealGameFolder(home, RealModStore(home), noNet)
                .inspect(home.resolve("missing").toString())
                .loaderPresent
        )
    }

    @Test
    fun packSettingsRejectPrivateFields() {
        val settings =
            SettingsValues(
                mapOf(
                    "gamePath" to kotlinx.serialization.json.JsonPrimitive("private"),
                    "launchArgs" to kotlinx.serialization.json.JsonPrimitive("malicious"),
                    "sources" to kotlinx.serialization.json.JsonPrimitive("https://evil"),
                )
            )
        assertTrue(packSettings(settings).general.isEmpty())
    }

    @Test
    fun configTransitionRollsBackWithPlugins() = runBlocking {
        for (point in listOf("prepared", "old-moved", "promoted")) {
            val home = temp()
            val game = game()
            val store = RealModStore(home)
            payload(store, home)
            val config = game.resolve("BepInEx/config").createDirectories().resolve("mod.cfg")
            config.writeText("shared")
            home
                .resolve("profiles/b/config")
                .createDirectories()
                .resolve("mod.cfg")
                .writeText("profile-b")
            val folder = RealGameFolder(home, store, noNet, running = { false })
            folder.apply(state(game), "default")
            // Snapshot replacement is itself a transaction; inject in the final game-tree
            // transaction.
            DiskFaults.step = { step ->
                val journals = home.resolve("game-transactions")
                val configSwap =
                    journals.isDirectory() &&
                        Files.list(journals).use { paths ->
                            paths.anyMatch {
                                it.name.endsWith(".txn") &&
                                    it.readText().contains("\"target\": \"${config.parent}\"")
                            }
                        }
                if (configSwap && step == point) throw DiskCrash()
            }
            try {
                assertFails {
                    folder.apply(
                        GamePlan(game.toString(), "b", mapOf("m" to "1"), true, setOf("m"))
                    )
                }
            } finally {
                DiskFaults.step = {}
            }
            folder.recoverPending()
            assertEquals(if (point == "promoted") "profile-b" else "shared", config.readText())
        }
    }

    @Test
    fun loaderTransactionRollsBackAllFiles() = runBlocking {
        for (point in listOf("prepared", "old-moved", "promoted")) {
            val home = temp()
            val game = game()
            val archive = home.resolve("loader.zip")
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                for ((name, text) in
                    mapOf("BepInEx/core/BepInEx.dll" to "loader", "winhttp.dll" to "new")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
            val proxy = game.resolve("winhttp.dll")
            proxy.writeText("old")
            val ini = game.resolve("doorstop_config.ini")
            ini.writeText("player")
            val dl =
                object : Downloader {
                    override suspend fun download(
                        mod: CatalogMod,
                        version: ModVersion,
                        progress: (Int) -> Unit,
                    ) = DownloadedFile(archive.toString())
                }
            val store = RealModStore(home)
            val folder = RealGameFolder(home, store, dl, running = { false })
            DiskFaults.step = { if (it == point) throw DiskCrash() }
            try {
                assertFails { folder.repairLoader(game.toString()) }
            } finally {
                DiskFaults.step = {}
            }
            RealModStore(home)
            assertEquals("old", proxy.readText())
            assertEquals("player", ini.readText())
            assertFalse(game.resolve("BepInEx/core/BepInEx.dll").exists())
            assertTrue(archive.exists())
        }
    }

    @Test
    fun doorstopWaitsForObservedExitAndRecoversAtStartup() = runBlocking {
        val home = temp()
        val game = game()
        val ini = game.resolve("doorstop_config.ini")
        val original = "enabled=true\n"
        ini.writeText(original)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val launcher =
            RealGameLauncher(home, scope, listOf("sh", "-c", "sleep 2"), detect = { false })
        launcher.start(AppState(game = GameInfo(game.toString())), true)
        scope.cancel()
        delay(100)
        assertTrue(ini.readText().contains("false"))
        val next = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            RealGameLauncher(home, next, detect = { false })
            assertEquals(original, ini.readText())
        } finally {
            next.cancel()
        }
    }

    @Test
    fun clearNeverRemovesTheLastPlayerCopy() = runBlocking {
        val home = temp()
        val store = RealModStore(home)
        payload(store, home)
        val recovery = home.resolve("recovery/player").createDirectories()
        recovery.resolve("save.dat").writeText("player")
        Files.setLastModifiedTime(
            recovery,
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 20000),
        )
        val before = store.usedBytes()
        assertTrue(before > 4)
        val state =
            AppState(
                installed =
                    mapOf("m" to InstalledMod("m", versions = setOf("1"), defaultVersion = "1"))
            )
        store.clearUnused(state)
        assertEquals("player", recovery.resolve("save.dat").readText())
        home
            .resolve("player-files/default/m")
            .createDirectories()
            .resolve("save.dat")
            .writeText("player")
        store.clearUnused(state)
        assertFalse(recovery.exists())
        assertEquals("player", home.resolve("player-files/default/m/save.dat").readText())
    }

    @Test
    fun addonTurnsOffWithoutBecomingParentPlayerData() = runBlocking {
        val home = temp()
        val game = game()
        val store = RealModStore(home)
        payload(store, home, "parent")
        payload(store, home, "child")
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
        val folder = RealGameFolder(home, store, noNet, catalog = { catalog }, running = { false })
        val initial =
            AppState(
                game = GameInfo(game.toString()),
                catalog = catalog,
                installed =
                    catalog.associate { mod ->
                        mod.id to
                            InstalledMod(
                                mod.id,
                                versions = setOf("1"),
                                defaultVersion = "1",
                                profiles = mapOf("default" to ProfileMod(true, "1")),
                            )
                    },
            )
        folder.apply(initial, "default")
        val addon = game.resolve("BepInEx/plugins/parent/addons/child/mod.dll")
        assertTrue(addon.exists())
        val off =
            initial.copy(
                installed =
                    initial.installed.mapValues { (_, m) ->
                        if (m.id == "child")
                            m.copy(profiles = mapOf("default" to ProfileMod(false, "1")))
                        else m
                    }
            )
        folder.apply(off, "default")
        assertFalse(addon.exists())
        assertTrue(game.resolve("BepInEx/plugins/parent/mod.dll").exists())
    }

    @Test
    fun interruptedAdoptionIsRepeatableWithoutGameMutation() = runBlocking {
        for (point in listOf("prepared", "old-moved", "promoted")) {
            val home = temp()
            val game = game()
            val original =
                game.resolve("BepInEx/plugins/Original").createDirectories().resolve("save.dat")
            original.writeText("player")
            val store = RealModStore(home)
            val folder = RealGameFolder(home, store, noNet, running = { false })
            var transaction = 0
            DiskFaults.step = { step ->
                if (step == "prepared") transaction++
                if (transaction == 2 && step == point) throw DiskCrash()
            }
            try {
                assertFails { folder.takeOver(game.toString()) }
            } finally {
                DiskFaults.step = {}
            }
            val reopened = RealModStore(home)
            val adopted =
                RealGameFolder(home, reopened, noNet, running = { false }).takeOver(game.toString())
            assertEquals(1, adopted.size)
            assertEquals("player", original.readText())
            assertFalse(game.resolve("BepInEx/plugins/.nnomm-state.json").exists())
        }
    }

    @Test
    fun differentLocalBytesInSecondGameNeverReplaceFirstPayload() = runBlocking {
        val home = temp()
        val store = RealModStore(home)
        val folder = RealGameFolder(home, store, noNet, running = { false })
        val games = listOf(game(), game())
        games.forEachIndexed { i, g ->
            g.resolve("BepInEx/plugins/Same")
                .createDirectories()
                .resolve("mod.dll")
                .writeText("game-$i")
        }
        val first = folder.takeOver(games[0].toString()).single().second
        val second = folder.takeOver(games[1].toString()).single().second
        assertNotEquals(first.id, second.id)
        assertEquals(
            "game-0",
            store.version(first.id, first.defaultVersion).resolve("mod.dll").readText(),
        )
        assertEquals(
            "game-1",
            store.version(second.id, second.defaultVersion).resolve("mod.dll").readText(),
        )
    }
}
