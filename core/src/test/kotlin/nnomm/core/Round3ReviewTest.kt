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
import nnomm.core.store.AppStore

class Round3ReviewTest {
    private val root = Files.createTempDirectory("nnomm-round3-")
    private val noNet =
        object : Downloader {
            override suspend fun download(
                mod: CatalogMod,
                version: ModVersion,
                progress: (Int) -> Unit,
            ): DownloadedFile = error("no network")
        }

    private fun game() =
        root.resolve("game").createDirectories().also {
            it.resolve("NuclearOption.exe").writeText("")
        }

    private suspend fun put(s: RealModStore) {
        val p = root.resolve("payload").createDirectories()
        p.resolve("m.dll").writeText("shipped")
        s.put("m", "1", DownloadedFile(p.toString()))
    }

    private fun plan(g: Path) = GamePlan(g.toString(), "default", mapOf("m" to "1"), false)

    private fun contains(p: Path, t: String) =
        p.exists() &&
            Files.walk(p).use { files ->
                files
                    .filter { it.isRegularFile() }
                    .anyMatch { runCatching { it.readText() == t }.getOrDefault(false) }
            }

    @AfterTest
    fun reset() {
        DiskFaults.step = {}
        deleteTree(root)
    }

    @Test
    fun clearMustKeepPersonalizedRecognizedTakeover() = runBlocking {
        val h = root.resolve("home")
        val g = game()
        val dir = g.resolve("BepInEx/plugins/m").createDirectories()
        dir.resolve("m.dll").writeText("PERSONALIZED TAKEN OVER DLL")
        dir.resolve("meta.json").writeText("""{"id":"m","artifact":{"version":"1"}}""")
        val cat =
            listOf(
                CatalogMod(
                    "m",
                    "M",
                    versions = listOf(ModVersion("1", downloadUrl = "https://example.org/m.zip")),
                )
            )
        val s = RealModStore(h, null) { cat }
        val f = RealGameFolder(h, s, noNet, catalog = { cat }, running = { false })
        f.takeOver(g.toString())
        f.apply(plan(g).copy(versions = emptyMap()))
        delay(9200)
        s.clearUnused(AppState(catalog = cat, game = GameInfo(g.toString())))
        assertTrue(
            contains(h, "PERSONALIZED TAKEN OVER DLL") ||
                contains(g, "PERSONALIZED TAKEN OVER DLL"),
            "Clear deleted last copy of recognized takeover",
        )
    }

    @Test
    fun failedApplyMustNotOverwriteArchivedProfileExtrasOnRetry() = runBlocking {
        val h = root.resolve("home")
        val g = game()
        val s = RealModStore(h)
        put(s)
        val f = RealGameFolder(h, s, noNet, running = { false })
        val p = plan(g)
        f.apply(p)
        g.resolve("BepInEx/plugins/m/save").writeText("A")
        f.apply(p.copy(profileId = "b"))
        g.resolve("BepInEx/plugins/m/save").writeText("B")
        var fired = false
        DiskFaults.step = {
            if (it == "prepared" && pendingTransactions(h.resolve("game-transactions")) && !fired) {
                fired = true
                throw java.io.IOException("injected")
            }
        }
        f.apply(p)
        DiskFaults.step = {}
        f.apply(p)
        assertEquals("A", g.resolve("BepInEx/plugins/m/save").readText())
        f.apply(p.copy(profileId = "b"))
        assertEquals("B", g.resolve("BepInEx/plugins/m/save").readText())
    }

    @Test
    fun retryMustClearRemovedEntryFailure() = runBlocking {
        val h = root.resolve("home")
        val g = game()
        val s = RealModStore(h)
        put(s)
        val f = RealGameFolder(h, s, noNet, running = { false })
        val p = plan(g)
        f.apply(p)
        val link = g.resolve("BepInEx/plugins/m/link")
        Files.createSymbolicLink(link, root.resolve("payload"))
        f.apply(p.copy(versions = emptyMap()))
        assertFalse(f.applySucceeded)
        Files.delete(link)
        f.apply(p.copy(versions = emptyMap()))
        assertTrue(f.applySucceeded)
    }

    @Test
    fun externalGameExitAloneMustNotApplyUnknownStartupPlan() = runBlocking {
        val h = root.resolve("home")
        val g = game()
        val s = RealModStore(h)
        put(s)
        val f = RealGameFolder(h, s, noNet, running = { false })
        f.apply(plan(g))
        deleteTree(g.resolve("BepInEx/plugins/m"))
        val running = MutableStateFlow(true)
        val initial =
            AppState(
                firstStart = FirstStart(true),
                game = GameInfo(g.toString(), loaderPresent = true),
                gameRunning = true,
                settings = nnomm.core.settings.defaultSettings(),
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
        val launcher =
            object : GameLauncher {
                override val running = running

                override suspend fun start(state: AppState, withoutMods: Boolean) {}
            }
        val app =
            AppStore(initial, Demo.io().copy(modStore = s, gameFolder = f, launcher = launcher))
        delay(300)
        running.value = false
        delay(700)
        app.shutdown()
        assertFalse(
            g.resolve("BepInEx/plugins/m").exists(),
            "Game exit alone installed the stored mod",
        )
    }

    @Test
    fun disabledTakeoverCanBeEnabledDirectly() = runBlocking {
        val h = root.resolve("home")
        val g = game()
        g.resolve("BepInEx/disabledPlugins/Foo")
            .createDirectories()
            .resolve("Foo.dll")
            .writeText("original")
        val s = RealModStore(h)
        val f = RealGameFolder(h, s, noNet, running = { false })
        val adopted = f.takeOver(g.toString()).single()
        f.apply(GamePlan(g.toString(), "default", mapOf(adopted.first.id to "takeover"), false))
        assertEquals("original", g.resolve("BepInEx/plugins/Foo/Foo.dll").readText())
    }

    @Test
    fun takeoverMustNotResetFirstStartCompletion() = runBlocking {
        val g = game()
        val h = root.resolve("home")
        val s = RealModStore(h)
        val f = RealGameFolder(h, s, noNet, running = { false })
        val app =
            AppStore(
                AppState(
                    firstStart = FirstStart(true),
                    game = GameInfo(g.toString()),
                    settings = nnomm.core.settings.defaultSettings(),
                ),
                Demo.io().copy(gameFolder = f, modStore = s),
            )
        app.takeOverExistingMods(g.toString())
        delay(600)
        app.shutdown()
        assertTrue(app.state.value.firstStart.complete, "Take over reset completed first start")
    }

    @Test
    fun rootLinkRefusalMustNotBlockIndependentEntries() = runBlocking {
        val h = root.resolve("home")
        val g = game()
        val s = RealModStore(h)
        put(s)
        val f = RealGameFolder(h, s, noNet, running = { false })
        val p = plan(g)
        f.apply(p)
        s.put("good", "1", DownloadedFile(root.resolve("payload").toString()))
        deleteTree(g.resolve("BepInEx/plugins/m"))
        Files.createSymbolicLink(g.resolve("BepInEx/plugins/m"), root.resolve("payload"))
        runCatching { f.apply(p.copy(versions = mapOf("m" to "1", "good" to "1"))) }
        assertTrue(
            g.resolve("BepInEx/plugins/good/m.dll").exists(),
            "Root symlink prevented an unrelated safe entry from applying",
        )
    }
}
