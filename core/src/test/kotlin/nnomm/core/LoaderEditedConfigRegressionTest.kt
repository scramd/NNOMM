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
        assertTrue(
            f.notices
                .single()
                .contains("NNOMM did not install the mod loader. You changed winhttp.dll.")
        )
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
                        it.text.contains(
                            "Could not install the mod loader. You changed winhttp.dll."
                        )
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

    @Test
    fun changedBinaryAndIncompleteLoaderLaunchesAfterApply() = runBlocking {
        val f = fixture()
        f.folder.installLoader(f.game.toString())
        f.game.resolve("winhttp.dll").writeText("player binary")
        f.game.resolve("BepInEx/core/Extra.dll").deleteExisting()
        val info = f.folder.inspect(f.game.toString())
        assertTrue(info.loaderPresent)
        assertFalse(info.loaderComplete)
        assertFalse(info.loaderResumeAllowed)
        assertEquals(listOf("winhttp.dll"), info.changedLoaderBinaries)
        val demo = nnomm.core.demo.Demo.io()
        var launched = false
        val app =
            nnomm.core.store.AppStore(
                nnomm.core.demo.Demo.state().copy(game = info),
                demo.copy(
                    gameFolder =
                        object : GameFolder by demo.gameFolder {
                            override suspend fun inspect(folder: String) = f.folder.inspect(folder)

                            override suspend fun installLoader(folder: String) =
                                f.folder.installLoader(folder)
                        },
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
            // A mod-only retry/deferred apply establishes the current placement plan.
            app.retryApply()
            app.awaitDiskIdle()
            app.play()
            withTimeout(10000) { while (!launched) delay(10) }
            repeat(2) {
                app.retryApply()
                app.awaitDiskIdle()
            }
            assertEquals(
                1,
                app.state.value.toasts.count {
                    it.text.startsWith("The mod loader is missing some files")
                },
            )
            assertFalse(app.state.value.toasts.any { it.text.startsWith("Could not install") })
            assertFalse(f.game.resolve("BepInEx/core/Extra.dll").exists())
            assertEquals("player binary", f.game.resolve("winhttp.dll").readText())
        } finally {
            app.shutdown()
        }
    }

    private suspend fun app(f: F, launched: () -> Unit = {}): nnomm.core.store.AppStore {
        val demo = nnomm.core.demo.Demo.io()
        return nnomm.core.store.AppStore(
            nnomm.core.demo.Demo.state().copy(game = f.folder.inspect(f.game.toString())),
            demo.copy(
                gameFolder =
                    object : GameFolder by f.folder {
                        override suspend fun apply(
                            state: nnomm.core.model.AppState,
                            profileId: String,
                        ) {}

                        override val applySucceeded
                            get() = true
                    },
                persistence = null,
                launcher =
                    object : GameLauncher by demo.launcher {
                        override suspend fun start(
                            state: nnomm.core.model.AppState,
                            withoutMods: Boolean,
                        ) {
                            launched()
                        }
                    },
            ),
        )
    }

    @Test
    fun probePresentIncompleteChangedBinaryPlay() = runBlocking {
        val f = fixture()
        f.folder.installLoader(f.game.toString())
        f.game.resolve("winhttp.dll").writeText("player doorstop 4.4")
        f.game.resolve("changelog.txt").deleteExisting()
        var launched = false
        val app = app(f) { launched = true }
        try {
            app.play()
            withTimeout(10000) { while (!launched) delay(10) }
            repeat(2) {
                app.retryApply()
                app.awaitDiskIdle()
            }
            assertEquals(
                1,
                app.state.value.toasts.count {
                    it.text ==
                        "The mod loader is missing some files, and you changed winhttp.dll, so NNOMM did not add them. If mods do not load, restore BepInEx 5.4.23.4."
                },
            )
            assertFalse(f.game.resolve("changelog.txt").exists())
            assertEquals("player doorstop 4.4", f.game.resolve("winhttp.dll").readText())
            assertFalse(app.state.value.toasts.any { it.text.contains("Installed the mod loader") })
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun probeForeignLoaderRepair() = runBlocking {
        val f = fixture()
        f.game.resolve("winhttp.dll").writeText("foreign proxy")
        val ini =
            "[General]\nenabled=true\ntarget_assembly=BepInEx\\core\\BepInEx.Unity.Mono.Preloader.dll\n"
        f.game.resolve("doorstop_config.ini").writeText(ini)
        f.game.resolve("BepInEx/core").createDirectories()
        f.game.resolve("BepInEx/core/BepInEx.Core.dll").writeText("bie6")
        val app = app(f)
        try {
            app.repairLoader()
            app.awaitDiskIdle()
            val toast = app.state.value.toasts.last()
            assertNull(toast.action)
            assertTrue(toast.text.contains("could not be identified"))
            assertFailsWith<LoaderInstallBlocked> { f.folder.installLoader(f.game.toString()) }
            assertFalse(f.game.resolve("BepInEx/core/BepInEx.dll").exists())
            assertEquals("foreign proxy", f.game.resolve("winhttp.dll").readText())
            assertEquals(ini, f.game.resolve("doorstop_config.ini").readText())
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun foreignBinariesBlockPlayAndModActionsWithoutBlamingPlayer() = runBlocking {
        for (name in listOf("winhttp.dll", "BepInEx/core/foreign.dll")) {
            val f = fixture()
            f.game.resolve(name).parent.createDirectories()
            f.game.resolve(name).writeText("foreign")
            var launched = false
            val app = app(f) { launched = true }
            try {
                app.setOn(listOf(app.state.value.installed.keys.first()), true)
                app.awaitDiskIdle()
                app.play()
                withTimeout(10000) { while (app.state.value.gameStarting) delay(10) }
                assertFalse(launched)
                val blocks =
                    app.state.value.toasts.filter { it.text.contains("another loader's files") }
                assertTrue(blocks.isNotEmpty())
                assertTrue(blocks.all { name in it.text && "You changed" !in it.text })
                assertFalse(f.game.resolve("BepInEx/core/BepInEx.dll").exists())
                assertFalse(
                    app.state.value.toasts.any { it.text.contains("Installed the mod loader") }
                )
            } finally {
                app.shutdown()
            }
        }
    }

    @Test
    fun foreignIniAloneAllowsInstallButRepairRefusesUnknownLoader() = runBlocking {
        val f = fixture()
        val ini = f.game.resolve("doorstop_config.ini")
        ini.writeText("foreign ini")
        assertFailsWith<LoaderRepairRefused> { f.folder.repairLoader(f.game.toString()) }
        f.folder.installLoader(f.game.toString())
        assertTrue(f.folder.inspect(f.game.toString()).loaderComplete)
        assertEquals("foreign ini", ini.readText())
    }

    @Test
    fun olderLoaderWithoutDetectionDllIsNotMixedWithPinnedArchive() = runBlocking {
        val f = fixture()
        f.game.resolve("BepInEx/core").createDirectories()
        f.game.resolve("BepInEx/core/Extra.dll").writeText("5.4.22")
        assertFailsWith<LoaderRepairRefused> { f.folder.repairLoader(f.game.toString()) }
        assertFailsWith<LoaderInstallBlocked> { f.folder.installLoader(f.game.toString()) }
        assertEquals("5.4.22", f.game.resolve("BepInEx/core/Extra.dll").readText())
        assertFalse(f.game.resolve("BepInEx/core/BepInEx.dll").exists())
    }

    @Test
    fun completeUnownedPinnedLoaderStillAllowsTakeoverAndPlay() = runBlocking {
        val f = fixture()
        for ((name, bytes) in data) {
            val file = f.game.resolve(name)
            file.parent.createDirectories()
            file.writeBytes(bytes)
        }
        f.folder.takeOver(f.game.toString())
        f.folder.installLoader(f.game.toString())
        assertTrue(f.folder.inspect(f.game.toString()).loaderPresent)
        var launched = false
        val app = app(f) { launched = true }
        try {
            app.play()
            withTimeout(10000) { while (!launched) delay(10) }
            assertFalse(app.state.value.toasts.any { it.text.contains("Installed the mod loader") })
            assertEquals(0, f.folder.removeLoader(f.game.toString()).removed)
            for ((name, bytes) in data) assertContentEquals(bytes, f.game.resolve(name).readBytes())
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun installButtonRechecksForeignFilesAfterRepairOffersInstall() = runBlocking {
        val f = fixture()
        val app = app(f)
        try {
            app.repairLoader()
            app.awaitDiskIdle()
            val offer = app.state.value.toasts.last()
            assertEquals("Install", offer.action)
            f.game.resolve("winhttp.dll").writeText("foreign proxy")
            app.runToast(offer.id)
            app.awaitDiskIdle()
            val blocked = app.state.value.toasts.last()
            assertTrue(blocked.text.contains("another loader's files"))
            assertFalse(blocked.text.contains("You changed"))
            assertFalse(f.game.resolve("BepInEx/core/BepInEx.dll").exists())
            assertEquals("foreign proxy", f.game.resolve("winhttp.dll").readText())
            // The same button's Retry can perform a normal fresh installation.
            f.game.resolve("winhttp.dll").deleteExisting()
            app.runToast(blocked.id)
            app.awaitDiskIdle()
            assertTrue(f.folder.inspect(f.game.toString()).loaderPresent)
            assertTrue(app.state.value.toasts.any { it.text.contains("Installed the mod loader") })
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun editedOwnedIniWithoutDetectionDllRefusesRepairButAllowsInstall() = runBlocking {
        val f = fixture()
        f.folder.installLoader(f.game.toString())
        val ini = f.game.resolve("doorstop_config.ini")
        ini.writeText("player ini")
        f.game.resolve("BepInEx/core/BepInEx.dll").deleteExisting()
        assertFailsWith<LoaderRepairRefused> { f.folder.repairLoader(f.game.toString()) }
        f.folder.installLoader(f.game.toString())
        assertTrue(f.folder.inspect(f.game.toString()).loaderPresent)
        assertEquals("player ini", ini.readText())
    }
}
