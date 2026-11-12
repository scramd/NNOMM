package nnomm.core

import java.nio.file.*
import java.util.zip.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import nnomm.core.io.*
import nnomm.core.io.real.*

/** Play while a loader or mod recovery is still pending. */
class LoaderPendingRecoveryTest {
    private class Crash : Error()

    private val shipped =
        "[General]\nenabled=true\ntarget_assembly=BepInEx\\core\\BepInEx.Preloader.dll\n"
            .toByteArray()
    private val dll = "5.4.23.4".toByteArray(Charsets.UTF_16LE)
    private val roots = mutableListOf<Path>()
    private val data =
        mapOf(
            ".doorstop_version" to "4.3.0".toByteArray(),
            "BepInEx/core/0Harmony.dll" to "harmony".toByteArray(),
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
        val archive: Path,
        var offline: Boolean = false,
    )

    private fun fixture(): F {
        val home = Files.createTempDirectory("loader-pending-").also { roots.add(it) }
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
        return F(home, game, archive)
    }

    private fun folder(f: F, notices: MutableList<String>) =
        RealGameFolder(
            f.home,
            RealModStore(f.home),
            object : Downloader {
                override suspend fun download(
                    mod: nnomm.core.model.CatalogMod,
                    version: nnomm.core.model.ModVersion,
                    progress: (Int) -> Unit,
                ): DownloadedFile {
                    if (f.offline) throw java.io.IOException("offline")
                    return DownloadedFile(f.archive.toString())
                }
            },
            running = { false },
            notice = { notices += it },
        )

    private suspend fun app(gf: RealGameFolder, game: Path, launched: () -> Unit) =
        Demo(gf, game, launched)

    @Suppress("FunctionName")
    private suspend fun Demo(
        gf: RealGameFolder,
        game: Path,
        launched: () -> Unit,
    ): nnomm.core.store.AppStore {
        val demo = nnomm.core.demo.Demo.io()
        return nnomm.core.store.AppStore(
            nnomm.core.demo.Demo.state().copy(game = gf.inspect(game.toString())),
            demo.copy(
                gameFolder =
                    object : GameFolder by gf {
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

    // A complete NNOMM-owned loader and a leftover mod journal: Play recovers, downloads nothing.
    @Test
    fun pendingModRecoveryWithCompleteLoaderPlaysOffline() = runBlocking {
        val f = fixture()
        val notices = mutableListOf<String>()
        val gf = folder(f, notices)
        gf.installLoader(f.game.toString())
        val before = gf.inspect(f.game.toString())
        assertTrue(before.loaderPresent && before.loaderComplete && before.loaderResumeAllowed)
        val txns = f.home.resolve("transactions").createDirectories()
        txns
            .resolve("probe.txn")
            .writeText(
                """{"changes":[],"commitPath":"${f.game.resolve("BepInEx/plugins/.probe-commit")}"}"""
            )
        f.offline = true
        val info = gf.inspect(f.game.toString())
        assertTrue(info.loaderRecoveryPending)
        var launched = false
        val app = app(gf, f.game) { launched = true }
        try {
            app.play()
            withTimeout(10000) {
                while (
                    !launched &&
                        app.state.value.toasts.none { it.text.startsWith("Could not install") }
                ) delay(10)
            }
            app.awaitDiskIdle()
            assertTrue(launched)
            assertFalse(txns.resolve("probe.txn").exists())
            assertFalse(app.state.value.toasts.any { it.text.startsWith("Could not") })
            assertFalse(app.state.value.toasts.any { it.text.contains("Installed the mod loader") })
        } finally {
            app.shutdown()
        }
    }

    // A fresh install crashed after BepInEx.dll was placed and startup recovery failed once.
    @Test
    fun interruptedFreshInstallIsCompletedAndAnnounced() = runBlocking {
        val f = fixture()
        val notices = mutableListOf<String>()
        var promoted = 0
        DiskFaults.step = { if (it == "promoted" && ++promoted == 4) throw Crash() }
        assertFailsWith<Crash> { folder(f, notices).installLoader(f.game.toString()) }
        DiskFaults.step = {}
        assertTrue(f.game.resolve("BepInEx/core/BepInEx.dll").exists())
        assertTrue(data.keys.any { !f.game.resolve(it).exists() })
        val gf = folder(f, notices)
        DiskFaults.step = {
            if (it == "recovery-staged-deleted") throw java.io.IOException("locked")
        }
        gf.recoverPending()
        DiskFaults.step = {}
        assertTrue(gf.recoveryPending)
        val info = gf.inspect(f.game.toString())
        assertTrue(info.loaderRecoveryPending)
        assertFalse(info.loaderComplete)
        var launched = false
        val app = app(gf, f.game) { launched = true }
        try {
            app.play()
            withTimeout(10000) {
                while (
                    !launched && app.state.value.toasts.none { it.text.startsWith("Could not") }
                ) delay(10)
            }
            app.awaitDiskIdle()
            val after = gf.inspect(f.game.toString())
            assertTrue(launched)
            assertTrue(after.loaderPresent && after.loaderComplete && !after.loaderRecoveryPending)
            for (name in data.keys) assertTrue(f.game.resolve(name).exists(), name)
            assertTrue(app.state.value.toasts.any { it.text.contains("Installed the mod loader") })
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun recoveredRepairOfCompleteLoaderDoesNotClaimInstall() = runBlocking {
        val f = fixture()
        val notices = mutableListOf<String>()
        val gf = folder(f, notices)
        gf.installLoader(f.game.toString())
        assertTrue(gf.inspect(f.game.toString()).loaderComplete)
        DiskFaults.step = { if (it == "loader-ownership-report-deleted") throw Crash() }
        assertFailsWith<Crash> { gf.repairLoader(f.game.toString()) }
        DiskFaults.step = {}
        assertTrue(data.keys.all { f.game.resolve(it).exists() })
        DiskFaults.step = {
            if (it == "loader-ownership-report-deleted") throw java.io.IOException("locked")
        }
        gf.recoverPending()
        DiskFaults.step = {}
        assertTrue(gf.inspect(f.game.toString()).loaderRecoveryPending)
        f.offline = true
        var launched = false
        val app = app(gf, f.game) { launched = true }
        try {
            app.play()
            withTimeout(10000) { while (!launched) delay(10) }
            app.awaitDiskIdle()
            assertFalse(app.state.value.toasts.any { it.text.contains("Installed the mod loader") })
        } finally {
            app.shutdown()
        }
    }
}
