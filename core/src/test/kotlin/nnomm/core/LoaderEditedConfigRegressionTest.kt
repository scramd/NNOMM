package nnomm.core

import java.nio.file.*
import java.util.zip.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import nnomm.core.io.*
import nnomm.core.io.real.*

class LoaderEditedConfigRegressionTest {
    private val shipped =
        "[General]\nenabled=true\ntarget_assembly=BepInEx\\core\\BepInEx.Preloader.dll\n"
            .toByteArray()
    private val dll = "5.4.23.4".toByteArray(Charsets.UTF_16LE)
    private val roots = mutableListOf<Path>()
    private val data =
        mapOf(
            ".doorstop_version" to "4.3.0".toByteArray(),
            "BepInEx/core/BepInEx.dll" to dll,
            "BepInEx/core/Extra.dll" to "extra".toByteArray(),
            "changelog.txt" to "log".toByteArray(),
            "doorstop_config.ini" to shipped,
            "winhttp.dll" to "shipped proxy".toByteArray(),
        )

    @AfterTest
    fun clean() {
        DiskFaults.beforeMove = {}
        DiskFaults.step = {}
        roots.forEach { it.toFile().deleteRecursively() }
    }

    private class F(
        val home: Path,
        val game: Path,
        val folder: RealGameFolder,
        val notices: MutableList<String>,
    )

    private fun fixture(): F {
        val home = Files.createTempDirectory("loader-edited-config-").also { roots.add(it) }
        home.resolve("staging").createDirectories()
        val game = home.resolve("game").createDirectories().toRealPath()
        game.resolve("NuclearOption.exe").writeText("")
        val archive = home.resolve("loader.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { z ->
            for ((n, d) in data) {
                z.putNextEntry(ZipEntry(n))
                z.write(d)
                z.closeEntry()
            }
        }
        val notices = mutableListOf<String>()
        return F(home, game, folder(home, archive, notices), notices)
    }

    private fun folder(
        home: Path,
        archive: Path,
        notices: MutableList<String>,
        readHash: (Path) -> String = ::digest,
    ) =
        RealGameFolder(
            home,
            RealModStore(home),
            object : Downloader {
                override suspend fun download(
                    mod: nnomm.core.model.CatalogMod,
                    version: nnomm.core.model.ModVersion,
                    progress: (Int) -> Unit,
                ) = DownloadedFile(archive.toString())
            },
            running = { false },
            notice = { notices += it },
            readLoaderHash = readHash,
        )

    private fun restart(f: F) =
        F(f.home, f.game, folder(f.home, f.home.resolve("loader.zip"), f.notices), f.notices)

    // V2 side effect: a player-edited INI (relinquished) plus a deleted BepInEx folder.
    @Test
    fun probeEditedIniThenDeletedBepInExFolder() = runBlocking {
        val f = fixture()
        f.folder.installLoader(f.game.toString())
        val ini = f.game.resolve("doorstop_config.ini")
        ini.writeText(ini.readText() + "# player note\n")
        f.game.resolve("BepInEx").toFile().deleteRecursively()
        val r = restart(f)
        r.folder.recoverPending()
        val info0 = r.folder.inspect(f.game.toString())
        assertFalse(info0.loaderPresent)
        val edited = ini.readBytes()
        r.folder.installLoader(f.game.toString())
        assertTrue(r.folder.inspect(f.game.toString()).loaderPresent)
        assertTrue(r.folder.inspect(f.game.toString()).loaderComplete)
        assertContentEquals(edited, ini.readBytes())
        r.folder.repairLoader(f.game.toString())
        assertContentEquals(edited, ini.readBytes())
        r.folder.removeLoader(f.game.toString())
        assertContentEquals(edited, ini.readBytes())
    }

    // Same, but the player only edits the INI and the loader DLL alone is deleted (e.g. antivirus).
    @Test
    fun probeEditedIniThenMissingBepInExDll() = runBlocking {
        val f = fixture()
        f.folder.installLoader(f.game.toString())
        val ini = f.game.resolve("doorstop_config.ini")
        ini.writeText(ini.readText() + "# player note\n")
        f.game.resolve("BepInEx/core/BepInEx.dll").deleteExisting()
        f.notices.clear()
        val edited = ini.readBytes()
        f.folder.installLoader(f.game.toString())
        assertTrue(f.folder.inspect(f.game.toString()).loaderPresent)
        assertTrue(f.folder.inspect(f.game.toString()).loaderComplete)
        assertContentEquals(edited, ini.readBytes())
    }

    @Test
    fun editedIniDoesNotBlockResumeWithDetectionDllPresent() = runBlocking {
        val f = fixture()
        f.folder.installLoader(f.game.toString())
        val ini = f.game.resolve("doorstop_config.ini")
        ini.writeText(ini.readText() + "# player note\n")
        val edited = ini.readBytes()
        f.game.resolve("BepInEx/core/Extra.dll").deleteExisting()
        assertTrue(f.folder.inspect(f.game.toString()).loaderResumeAllowed)
        f.folder.installLoader(f.game.toString())
        assertTrue(f.folder.inspect(f.game.toString()).loaderComplete)
        assertContentEquals(edited, ini.readBytes())
    }

    @Test
    fun changedBinaryNamesTheBlockAndPlayDoesNotLaunch() = runBlocking {
        val f = fixture()
        f.folder.installLoader(f.game.toString())
        f.game.resolve("winhttp.dll").writeText("player binary")
        f.game.resolve("BepInEx/core/BepInEx.dll").deleteExisting()
        f.folder.installLoader(f.game.toString())
        assertFalse(f.game.resolve("BepInEx/core/BepInEx.dll").exists())
        assertTrue(f.notices.single().contains("because you changed winhttp.dll"))
        val demo = nnomm.core.demo.Demo.io()
        var launched = false
        val app =
            nnomm.core.store.AppStore(
                nnomm.core.demo.Demo.state().copy(game = f.folder.inspect(f.game.toString())),
                demo.copy(
                    gameFolder = f.folder,
                    persistence = null,
                    launcher =
                        object : GameLauncher by demo.launcher {
                            override suspend fun start(
                                state: nnomm.core.model.AppState,
                                withoutMods: Boolean,
                            ) {
                                launched = true
                            }
                        },
                ),
            )
        try {
            app.play()
            withTimeout(10000) {
                while (
                    app.state.value.toasts.none {
                        it.text.contains("because you changed winhttp.dll")
                    }
                ) delay(10)
            }
            assertFalse(launched)
            val notice = app.state.value.toasts.last().text
            assertTrue(notice.contains("Restore BepInEx 5.4.23.4 or remove the changed files"))
            assertFalse(app.state.value.gameStarting)
            // The notice's next step must work even before a watcher refreshes GameInfo.
            f.game.resolve("winhttp.dll").deleteExisting()
            app.runToast(app.state.value.toasts.last().id)
            withTimeout(10000) {
                while (
                    app.state.value.toasts.none { it.text.contains("Installed the mod loader") }
                ) delay(10)
            }
            assertTrue(f.folder.inspect(f.game.toString()).loaderPresent)
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun repairWithoutDetectionDllOffersWorkingInstall() = runBlocking {
        val f = fixture()
        f.folder.installLoader(f.game.toString())
        val ini = f.game.resolve("doorstop_config.ini")
        ini.writeText(ini.readText() + "# player note\n")
        val edited = ini.readBytes()
        f.game.resolve("BepInEx/core/BepInEx.dll").deleteExisting()
        assertFailsWith<LoaderNotInstalled> { f.folder.repairLoader(f.game.toString()) }
        val demo = nnomm.core.demo.Demo.io()
        val app =
            nnomm.core.store.AppStore(
                nnomm.core.demo.Demo.state().copy(game = f.folder.inspect(f.game.toString())),
                demo.copy(gameFolder = f.folder, persistence = null),
            )
        try {
            app.repairLoader()
            app.awaitDiskIdle()
            val toast = app.state.value.toasts.last()
            assertEquals("Install", toast.action)
            assertTrue(toast.text.contains("not installed"))
            assertFalse(toast.text.contains("could not be identified"))
            app.runToast(toast.id)
            app.awaitDiskIdle()
            assertTrue(app.state.value.game.loaderPresent)
            assertTrue(app.state.value.toasts.any { it.text.contains("Installed the mod loader") })
            assertContentEquals(edited, ini.readBytes())
        } finally {
            app.shutdown()
        }
    }
}
