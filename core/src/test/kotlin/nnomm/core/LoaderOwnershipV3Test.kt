package nnomm.core

import java.nio.file.*
import java.util.zip.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.*
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.store.AppStore

/** Independent verification probes for loader ownership (e47344c..master). */
class LoaderOwnershipV3Test {
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

    private class Crash : Error()

    private fun fixture(withLoader: Boolean, ini: ByteArray?): F {
        val home = Files.createTempDirectory("verify-own-").also { roots.add(it) }
        home.resolve("staging").createDirectories()
        val game = home.resolve("game").createDirectories().toRealPath()
        game.resolve("NuclearOption.exe").writeText("")
        if (withLoader) {
            game.resolve("BepInEx/core").createDirectories()
            game.resolve("BepInEx/core/BepInEx.dll").writeBytes(dll)
            game.resolve("winhttp.dll").writeText("player proxy")
        }
        if (ini != null) game.resolve("doorstop_config.ini").writeBytes(ini)
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

    private fun folder(home: Path, archive: Path, notices: MutableList<String>) =
        RealGameFolder(
            home,
            RealModStore(home),
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ): DownloadedFile = DownloadedFile(archive.toString())
            },
            running = { false },
            notice = { notices += it },
        )

    private fun restart(f: F) =
        F(f.home, f.game, folder(f.home, f.home.resolve("loader.zip"), f.notices), f.notices)

    private fun record(f: F) =
        JsonFile(
            f.home.resolve("loader-${Integer.toHexString(f.game.toString().hashCode())}.json"),
            ListSerializer(String.serializer()),
        )

    private fun pendingDir(f: F, dir: String) =
        f.home.resolve(dir).let { d ->
            if (d.isDirectory()) d.listDirectoryEntries() else emptyList()
        }

    private fun snapshot(game: Path): Map<String, String> =
        Files.walk(game).use { s ->
            s.filter { it.isRegularFile() && !game.relativize(it).toString().startsWith(".nnomm") }
                .toList()
                .associate { game.relativize(it).toString().replace('\\', '/') to digest(it) }
        }

    private fun write(f: F, relative: String, bytes: ByteArray) {
        val t = f.game.resolve(relative)
        t.parent.createDirectories()
        t.writeBytes(bytes)
    }

    private fun itIsPlayer(f: F, name: String, hash: String, owned: Set<String>): Boolean {
        val hashes =
            JsonFile(
                    f.home.resolve(
                        "loader-${Integer.toHexString(f.game.toString().hashCode())}.json.hashes"
                    ),
                    LoaderOwnership.serializer(),
                )
                .read()
                ?.hashes
                .orEmpty()
        return name !in owned || hashes[name] != hash
    }

    private fun digestBytes(b: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") {
            "%02x".format(it)
        }

    private fun hashes(f: F) =
        JsonFile(
                f.home.resolve(
                    "loader-${Integer.toHexString(f.game.toString().hashCode())}.json.hashes"
                ),
                LoaderOwnership.serializer(),
            )
            .read()

    // A. Play without mods, the restore of doorstop_config.ini fails once (file write refused),
    // then NNOMM starts (or the player uses Repair as instructed) before the restore succeeds.
    @Test
    fun probeA_failedVanillaRestoreThenOwnership() = runBlocking {
        for (via in listOf("startup", "repair", "install")) {
            val f = fixture(false, null)
            f.folder.installLoader(f.game.toString())
            val ini = f.game.resolve("doorstop_config.ini")
            val placed = ini.readBytes()
            assertTrue("doorstop_config.ini" in hashes(f)!!.hashes)
            val blocked = java.util.concurrent.atomic.AtomicBoolean(false)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val launcher =
                RealGameLauncher(
                    f.home,
                    scope,
                    listOf("sh", "-c", "sleep 0.3"),
                    detect = { false },
                    notice = { f.notices += it },
                    writeDoorstop = { p, b ->
                        if (blocked.get()) throw AccessDeniedException(p.toString())
                        Files.write(p, b)
                    },
                )
            launcher.start(AppState(game = GameInfo(f.game.toString())), true)
            blocked.set(true)
            withTimeout(5000) { while (launcher.running.value) delay(25) }
            delay(200)
            scope.cancel()
            val pendingRestore = DoorstopRestores(f.home).records().isNotEmpty()
            assertTrue(pendingRestore)
            assertFalse(ini.readBytes().contentEquals(placed))
            println(
                "PROBEA[$via] after Play without mods: restorePending=$pendingRestore iniDisabled=${!ini.readBytes().contentEquals(placed)}"
            )
            val op = runCatching {
                when (via) {
                    "startup" -> restart(f).folder.recoverPending()
                    "repair" -> f.folder.repairLoader(f.game.toString())
                    else -> f.folder.removeLoader(f.game.toString()).let {}
                }
            }
            println(
                "PROBEA[$via] op=${op.exceptionOrNull()} owned=${hashes(f)?.hashes?.keys} relinquished=${hashes(f)?.relinquished}"
            )
            assertTrue("doorstop_config.ini" in hashes(f)!!.hashes)
            assertTrue("doorstop_config.ini" !in hashes(f)!!.relinquished)
            // Restore succeeds later (next NNOMM start or the 3 s poll).
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            RealGameLauncher(f.home, scope2, detect = { false })
            scope2.cancel()
            println(
                "PROBEA[$via] after restore: iniIsNnommBytes=${ini.readBytes().contentEquals(placed)} restorePending=${DoorstopRestores(f.home).records().isNotEmpty()}"
            )
            assertContentEquals(placed, ini.readBytes())
            restart(f).folder.recoverPending()
            val removal = runCatching { f.folder.removeLoader(f.game.toString()) }
            assertTrue(removal.isSuccess)
            assertFalse(ini.exists())
            println(
                "PROBEA[$via] Uninstall=${removal.getOrNull() ?: removal.exceptionOrNull()} left=${snapshot(f.game).keys.sorted()} iniLeftIsNnommBytes=${ini.exists() && ini.readBytes().contentEquals(placed)}"
            )
        }
    }

    // C. Recovery of a Repair crash fails for a non-crash reason (target folder not writable);
    // the player restores access and restarts.
    @Test
    fun probeC_failedRecoveryThenRestart() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        val owned0 = hashes(f)!!.hashes
        var last: Path? = null
        DiskFaults.beforeMove = { last = it }
        DiskFaults.step = {
            if (it == "old-moved" && last?.fileName?.toString() == "BepInEx.dll") throw Crash()
        }
        try {
            f.folder.repairLoader(f.game.toString())
            println("PROBEC no crash")
        } catch (_: Crash) {} finally {
            DiskFaults.step = {}
            DiskFaults.beforeMove = {}
        }
        val core = f.game.resolve("BepInEx/core")
        println("PROBEC after crash dll exists=${core.resolve("BepInEx.dll").exists()}")
        val held = core.resolveSibling("held-core")
        Files.move(core, held)
        core.writeText("blocked parent")
        try {
            restart(f).folder.recoverPending()
        } finally {
            core.deleteExisting()
            Files.move(held, core)
        }
        assertTrue(f.folder.recoveryPending)
        assertEquals(owned0, hashes(f)!!.hashes)
        println(
            "PROBEC after failed recovery: notices=${f.notices} owned=${hashes(f)?.hashes?.keys} relinquished=${hashes(f)?.relinquished}"
        )
        restart(f).folder.recoverPending()
        println(
            "PROBEC after access restored: dllPresent=${core.resolve("BepInEx.dll").exists()} dllIsNnommBytes=${core.resolve("BepInEx.dll").exists() && digest(core.resolve("BepInEx.dll")) == owned0["BepInEx/core/BepInEx.dll"]} owned=${hashes(f)?.hashes?.keys}"
        )
        val removal = runCatching { f.folder.removeLoader(f.game.toString()) }
        assertTrue(removal.isSuccess)
        assertTrue(data.keys.none { f.game.resolve(it).exists() })
        println(
            "PROBEC Uninstall=${removal.getOrNull() ?: removal.exceptionOrNull()} left=${snapshot(f.game).keys.sorted()}"
        )
    }

    private fun journals(f: F) =
        f.home.resolve("transactions").let { d ->
            if (d.isDirectory()) d.listDirectoryEntries("*.txn").map { it.readText() }
            else emptyList()
        }

    private fun crashRepairThenRecoveryCrash(f: F, step: String, n: Int, m: Int) = runBlocking {
        var hits = 0
        DiskFaults.step = { if (it == step && ++hits == n) throw Crash() }
        assertFailsWith<Crash> { f.folder.repairLoader(f.game.toString()) }
        DiskFaults.step = {}
        println(
            "PROBE-B after op crash: files=${snapshot(f.game).keys.sorted()} journal=${journals(f)}"
        )
        var h = 0
        DiskFaults.step = { if (it == "recovery-staged-deleted" && ++h == m) throw Crash() }
        assertFailsWith<Crash> { restart(f).folder.recoverPending() }
        DiskFaults.step = {}
        println(
            "PROBE-B after recovery crash: files=${snapshot(f.game).keys.sorted()} journal=${journals(f)}"
        )
    }

    // B2. Repair crash, crash during recovery, then the player deletes the loader by hand.
    @Test
    fun probeB2_stuckAfterRecoveryCrashAndHandRemoval() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        crashRepairThenRecoveryCrash(f, "old-rename-before-journal", 1, 5)
        data.keys.forEach { f.game.resolve(it).deleteIfExists() }
        repeat(3) { restart(f).folder.recoverPending() }
        val r = restart(f)
        assertFalse(r.folder.recoveryPending)
        println("PROBEB2 recoveryPending=${r.folder.recoveryPending} journals=${journals(f).size}")
        for (op in listOf("install", "repair", "uninstall")) {
            val res = runCatching {
                when (op) {
                    "install" -> r.folder.installLoader(f.game.toString())
                    "repair" -> r.folder.repairLoader(f.game.toString())
                    else -> r.folder.removeLoader(f.game.toString()).let {}
                }
            }
            assertTrue(res.isSuccess, "$op failed: ${res.exceptionOrNull()}")
            println(
                "PROBEB2 $op -> ${res.exceptionOrNull()?.message ?: "ok"} help=${res.exceptionOrNull()?.let { nnomm.core.store.loaderFailureHelp(it) }}"
            )
        }
        println("PROBEB2 notices=${f.notices.distinct()}")
        // Play with mods on through AppStore (the loader is missing, so Play installs it).
        val port =
            object : GameFolder by r.folder {
                override suspend fun apply(state: AppState, profileId: String) {}

                override suspend fun apply(plan: GamePlan) {}

                override val applySucceeded = true
            }
        val app =
            AppStore(
                Demo.state().copy(game = r.folder.inspect(f.game.toString())),
                Demo.io().copy(gameFolder = port, persistence = null),
            )
        try {
            app.play()
            withTimeout(10000) {
                while (
                    !app.state.value.gameRunning &&
                        app.state.value.toasts.none { it.action == "Retry" }
                ) delay(10)
            }
            assertTrue(app.state.value.gameRunning, "Play remained blocked after recovery")
            println(
                "PROBEB2 Play: running=${app.state.value.gameRunning} toasts=${app.state.value.toasts.map { it.text + " [" + it.action + "]" }}"
            )
        } finally {
            app.shutdown()
        }
    }

    // B3. Repair crash, crash during recovery, then the player installs their own loader files.
    @Test
    fun probeB3_handInstallAfterRecoveryCrash() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        crashRepairThenRecoveryCrash(f, "old-rename-before-journal", 1, 1)
        data.keys.forEach {
            f.game.resolve(it).deleteIfExists()
            write(f, it, "player own $it".toByteArray())
        }
        restart(f).folder.recoverPending()
        val mine = data.keys.filter { f.game.resolve(it).readText() != "player own $it" }
        assertTrue(mine.isEmpty(), "Recovery displaced: $mine")
        println("PROBEB3 player files no longer in place after restart: $mine")
        println(
            "PROBEB3 recovery copies: " +
                Files.walk(f.game.resolve(".nnomm")).use { s ->
                    s.filter { it.isRegularFile() && it.readText().startsWith("player own") }
                        .map { f.game.relativize(it).toString() }
                        .toList()
                }
        )
        f.folder.removeLoader(f.game.toString())
        data.keys.forEach { assertEquals("player own $it", f.game.resolve(it).readText()) }
        println(
            "PROBEB3 after Uninstall: ${data.keys.associateWith { k -> f.game.resolve(k).let { if (it.exists()) it.readText().take(20) else "-" } }}"
        )
    }

    @Test
    fun reviewCrashAfterRollbackRenameBeforeCompletionMarker() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        var moves = 0
        DiskFaults.step = { if (it == "old-moved" && ++moves == 2) throw Crash() }
        assertFailsWith<Crash> { f.folder.repairLoader(f.game.toString()) }
        DiskFaults.step = { if (it == "recovery-rollback-restored") throw Crash() }
        assertFailsWith<Crash> { restart(f).folder.recoverPending() }
        DiskFaults.step = {}
        val target = f.game.resolve("BepInEx/core/BepInEx.dll")
        assertTrue(target.exists())
        target.deleteExisting()
        repeat(3) { restart(f).folder.recoverPending() }
        val r = restart(f)
        r.folder.installLoader(f.game.toString())
        assertTrue(target.exists(), "Retry should reinstall the deleted loader file")
        assertFalse(
            r.folder.recoveryPending,
            "A completed rollback followed by player deletion must not block recovery",
        )
    }

    @Test
    fun reviewChangedLoaderWithMissingDetectionDllKeepsGuard() = runBlocking {
        val failures = mutableListOf<String>()
        for (startup in listOf(false, true)) {
            val f = fixture(false, null)
            f.folder.installLoader(f.game.toString())
            f.game.resolve("BepInEx/core/BepInEx.dll").deleteExisting()
            f.game.resolve("BepInEx/core/Extra.dll").deleteExisting()
            f.game.resolve("winhttp.dll").writeText("player replacement")
            val r = restart(f)
            if (startup) r.folder.recoverPending()
            val before = snapshot(f.game)
            r.folder.installLoader(f.game.toString())
            if (before != snapshot(f.game))
                failures += "startup=$startup added=${snapshot(f.game).keys - before.keys}"
        }
        assertTrue(
            failures.isEmpty(),
            "Changed present loader files must keep presence guard even if detection DLL was removed: $failures",
        )
    }
}
