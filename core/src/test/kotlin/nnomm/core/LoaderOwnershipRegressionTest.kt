package nnomm.core

import java.nio.file.*
import java.util.zip.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.encodeToString
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.store.AppStore
import nnomm.core.store.loaderFailureHelp

/** Regression ports of the independent loader ownership probes. */
class LoaderOwnershipRegressionTest {
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

    private fun fixture(withLoader: Boolean, ini: ByteArray?, home0: Path? = null): F {
        val home = home0 ?: Files.createTempDirectory("verify-loader-").also { roots.add(it) }
        home.resolve("staging").createDirectories()
        val game = home.resolve("game").createDirectories()
        if (!game.resolve("NuclearOption.exe").exists())
            game.resolve("NuclearOption.exe").writeText("")
        if (withLoader) {
            game.resolve("BepInEx/core").createDirectories()
            game.resolve("BepInEx/core/BepInEx.dll").writeBytes(dll)
            game.resolve("winhttp.dll").writeText("player proxy")
        }
        if (ini != null) game.resolve("doorstop_config.ini").writeBytes(ini)
        val archive = home.resolve("loader.zip")
        if (!archive.exists())
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
        allowDownload: Boolean = true,
    ) =
        RealGameFolder(
            home,
            RealModStore(home),
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ): DownloadedFile {
                    check(allowDownload) { "Recovery must not download" }
                    return DownloadedFile(archive.toString())
                }
            },
            running = { false },
            notice = { notices += it },
        )

    private fun restart(f: F, allowDownload: Boolean = false) =
        F(
            f.home,
            f.game,
            folder(f.home, f.home.resolve("loader.zip"), f.notices, allowDownload),
            f.notices,
        )

    private fun record(f: F) =
        JsonFile(
            f.home.resolve("loader-${Integer.toHexString(f.game.toString().hashCode())}.json"),
            ListSerializer(String.serializer()),
        )

    private fun pending(f: F) =
        f.home.resolve("loader-pending").let { d ->
            if (d.isDirectory()) d.listDirectoryEntries("*.json") else emptyList()
        }

    private fun snapshot(game: Path): Map<String, String> =
        Files.walk(game).use { s ->
            s.filter { it.isRegularFile() && !game.relativize(it).toString().startsWith(".nnomm") }
                .toList()
                .associate { game.relativize(it).toString().replace('\\', '/') to digest(it) }
        }

    // A: Install refused before any promotion leaves the pending record. The player then
    // extracts the same official BepInEx zip by hand. Does NNOMM claim and later delete it?
    @Test
    fun refusedInstallNeverClaimsManualInstall() = runBlocking {
        val lost = mutableListOf<String>()
        for (next in listOf("retry-install", "recoverPending", "uninstall", "repair")) {
            val f = fixture(false, null)
            val ini = f.game.resolve("doorstop_config.ini")
            DiskFaults.beforeMove = { if (it == ini) throw AccessDeniedException(it.toString()) }
            val failure = assertFails { f.folder.installLoader(f.game.toString()) }
            DiskFaults.beforeMove = {}
            assertTrue(pending(f).isEmpty(), "A refused batch must settle its pending record")
            // The player installs BepInEx by hand from the official zip (same bytes).
            for ((n, d) in data) f.game
                .resolve(n)
                .also { it.parent.createDirectories() }
                .writeBytes(d)
            when (next) {
                "retry-install" -> f.folder.installLoader(f.game.toString())
                "recoverPending" -> restart(f).folder.recoverPending()
                "repair" -> f.folder.repairLoader(f.game.toString())
                "uninstall" -> {}
            }
            val removal = f.folder.removeLoader(f.game.toString())
            val left = data.keys.filter { f.game.resolve(it).exists() }
            if (left != data.keys.toList()) lost += next
        }
        assertTrue(lost.isEmpty(), "player's manual install deleted after: $lost")
    }

    // B: partial Install (proxy refused), then NNOMM restarts. Play with mods.
    @Test
    fun playAfterRestartResumesPartialInstall() = runBlocking {
        val f = fixture(false, null)
        val proxy = f.game.resolve("winhttp.dll")
        DiskFaults.beforeMove = { if (it == proxy) throw AccessDeniedException(it.toString()) }
        assertFails { f.folder.installLoader(f.game.toString()) }
        DiskFaults.beforeMove = {}
        val r = restart(f, allowDownload = true)
        var installCalls = 0
        val counted =
            object : GameFolder by r.folder {
                override suspend fun installLoader(folder: String) {
                    installCalls++
                    r.folder.installLoader(folder)
                }

                // Isolate the AppStore decision: mod placement itself succeeds.
                override suspend fun apply(state: AppState, profileId: String) {}

                override suspend fun apply(plan: GamePlan) {}

                override val applySucceeded = true
            }
        val io = Demo.io()
        val start = Demo.state().copy(game = r.folder.inspect(f.game.toString()))
        val app = AppStore(start, io.copy(gameFolder = counted, persistence = null))
        try {
            app.play()
            withTimeout(10000) {
                while (
                    !app.state.value.gameRunning &&
                        app.state.value.toasts.none { it.action == "Retry" }
                ) delay(10)
            }
            delay(200)
            assertTrue(
                proxy.exists() || !app.state.value.gameRunning,
                "Play started with mods while winhttp.dll is missing",
            )
        } finally {
            app.shutdown()
        }
    }

    // C: a stuck, unrelated home journal (store on an unavailable drive) now blocks loader IO.
    @Test
    fun unrelatedStuckJournalDoesNotBlockLoader() = runBlocking {
        val f = fixture(false, null)
        val journals = f.home.resolve("transactions").createDirectories()
        val unrelated = f.home.resolve("store/some-mod/1.0")
        val batch =
            TransactionBatch(
                listOf(
                    DiskTransaction(
                        unrelated.toString(),
                        null,
                        f.home.resolve("gone-drive/.nnomm-staging/old-x-0").toString(),
                        existed = true,
                        phase = "old-moved",
                    )
                )
            )
        journals.resolve("stuck.txn").writeText(nnomm.core.io.real.json.encodeToString(batch))
        val results = mutableListOf<String>()
        for (op in listOf("install", "repair")) {
            val r = runCatching {
                if (op == "install") f.folder.installLoader(f.game.toString())
                else f.folder.repairLoader(f.game.toString())
            }
            results +=
                "$op=${r.exceptionOrNull()?.message ?: "ok"} help='${r.exceptionOrNull()?.let(::loaderFailureHelp)}'"
        }
        assertTrue(
            f.folder.inspect(f.game.toString()).loaderPresent,
            "unrelated journal blocked loader install",
        )
        assertTrue(results.all { it.contains("=ok") }, results.toString())
        f.folder.removeLoader(f.game.toString())
        assertFalse(f.folder.inspect(f.game.toString()).loaderPresent)
    }

    // D: crash matrix. Each fault step, each occurrence, Install / Repair / Uninstall.
    @Test
    fun crashMatrix() = runBlocking {
        val steps =
            listOf(
                "prepared",
                "old-rename-before-journal",
                "old-moved",
                "new-rename-before-journal",
                "promoted",
                "ownership-published",
                "journal-deleted",
                "recovery-staged-deleted",
                "loader-removal-prepared",
                "loader-removal-published",
            )
        val problems = mutableListOf<String>()
        var runs = 0
        for (scenario in
            listOf(
                "install-fresh",
                "install-playerproxy",
                "repair-adopted-disabled",
                "repair-adopted-missing-ini",
                "repair-owned",
                "uninstall-owned",
                "uninstall-adopted-repaired",
            )) for (step in steps) for (n in 1..7) {
            val f =
                when (scenario) {
                    "install-fresh" -> fixture(false, null)
                    "install-playerproxy" ->
                        fixture(false, null).also {
                            it.game.resolve("winhttp.dll").writeBytes(data.getValue("winhttp.dll"))
                        }
                    "repair-adopted-disabled" ->
                        fixture(
                            true,
                            "[General]\nenabled=false\ntarget_assembly=player.dll\n".toByteArray(),
                        )
                    "repair-adopted-missing-ini" -> fixture(true, null)
                    "repair-owned" ->
                        fixture(false, null).also {
                            it.folder.installLoader(it.game.toString())
                            it.game.resolve("winhttp.dll").writeText("damaged")
                        }
                    "uninstall-owned" ->
                        fixture(false, null).also { it.folder.installLoader(it.game.toString()) }
                    else ->
                        fixture(
                                true,
                                "[General]\nenabled=false\ntarget_assembly=player.dll\n"
                                    .toByteArray(),
                            )
                            .also { it.folder.repairLoader(it.game.toString()) }
                }
            val player = f.game.resolve("player.txt").apply { writeText("keep me") }
            // Files the player owns before the operation: neither NNOMM record nor pending.
            val ownedBefore = record(f).read().orEmpty().toSet()
            val playerFiles =
                snapshot(f.game).filter { (name, hash) ->
                    name !in ownedBefore || (name == "winhttp.dll" && scenario == "repair-owned")
                }
            var hits = 0
            DiskFaults.step = { if (it == step && ++hits == n) throw Crash() }
            val crashed =
                try {
                    when {
                        scenario.startsWith("install") -> f.folder.installLoader(f.game.toString())
                        scenario.startsWith("repair") -> f.folder.repairLoader(f.game.toString())
                        else -> f.folder.removeLoader(f.game.toString())
                    }
                    false
                } catch (_: Crash) {
                    true
                } catch (e: Exception) {
                    problems += "$scenario/$step#$n op-exception=$e"
                    true
                } finally {
                    DiskFaults.step = {}
                }
            if (!crashed) continue
            runs++
            val r = restart(f)
            r.folder.recoverPending()
            val rec = record(f).read().orEmpty()
            val claimedPlayer =
                rec.filter {
                    it in playerFiles &&
                        snapshot(f.game)[it] == playerFiles[it] &&
                        it !in ownedBefore
                }
            if (claimedPlayer.isNotEmpty())
                problems += "$scenario/$step#$n claimed player files $claimedPlayer"
            if (r.folder.recoveryPending)
                problems += "$scenario/$step#$n recovery still pending notices=${f.notices}"
            val afterRecovery = snapshot(f.game)
            val placedUnowned =
                afterRecovery.keys.filter { it in data && it !in rec && it !in playerFiles }
            if (placedUnowned.isNotEmpty())
                problems +=
                    "$scenario/$step#$n NNOMM-placed but unowned after recovery: $placedUnowned"
            r.folder.removeLoader(f.game.toString())
            val end = snapshot(f.game)
            val lostPlayer = playerFiles.keys.filter { it !in end }
            if (lostPlayer.isNotEmpty())
                problems += "$scenario/$step#$n Uninstall removed player files $lostPlayer"
            if (!player.exists()) problems += "$scenario/$step#$n player.txt lost"
            val leftNnomm = end.keys.filter { it in data && it !in playerFiles }
            if (leftNnomm.isNotEmpty())
                problems += "$scenario/$step#$n NNOMM files left after Uninstall: $leftNnomm"
        }
        println("Crash matrix: $runs interrupted operations")
        assertTrue(runs >= 153, "Crash scenarios exercised: $runs")
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    // E: Uninstall crash after its journal is deleted but before the record is emptied.
    // The player then installs their own loader by hand; a later Uninstall / Remove all mods.
    @Test
    fun uninstallCrashDoesNotClaimPlayerReinstall() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        DiskFaults.step = { if (it == "journal-deleted") throw Crash() }
        assertFailsWith<Crash> { f.folder.removeLoader(f.game.toString()) }
        DiskFaults.step = {}
        val r = restart(f)
        f.game.resolve("BepInEx/core").createDirectories()
        f.game.resolve("BepInEx/core/BepInEx.dll").writeBytes(dll)
        f.game.resolve("winhttp.dll").writeText("player proxy")
        f.game.resolve("doorstop_config.ini").writeText("[General]\ntarget_assembly=player.dll\n")
        r.folder.recoverPending()
        val removal = r.folder.removeLoader(f.game.toString())
        val left =
            listOf("BepInEx/core/BepInEx.dll", "winhttp.dll", "doorstop_config.ini").filter {
                f.game.resolve(it).exists()
            }
        assertEquals(3, left.size)
    }

    @Test
    fun unreadableVerificationStaysPendingUntilReadableWithoutDownload() = runBlocking {
        val f = fixture(false, null)
        DiskFaults.step = { if (it == "journal-deleted") throw Crash() }
        try {
            assertFailsWith<Crash> { f.folder.installLoader(f.game.toString()) }
        } finally {
            DiskFaults.step = {}
        }
        var locked = true
        val restarted =
            RealGameFolder(
                f.home,
                RealModStore(f.home),
                object : Downloader {
                    override suspend fun download(
                        mod: CatalogMod,
                        version: ModVersion,
                        progress: (Int) -> Unit,
                    ): DownloadedFile = error("Recovery must not download")
                },
                running = { false },
                readLoaderHash = {
                    if (locked && it.fileName.toString() == "winhttp.dll")
                        throw AccessDeniedException(it.toString())
                    digest(it)
                },
            )
        restarted.recoverPending()
        assertTrue(restarted.recoveryPending)
        assertFalse("winhttp.dll" in record(f).read().orEmpty())
        restarted.recoverPending()
        assertTrue(restarted.recoveryPending)
        locked = false
        restarted.recoverPending()
        assertFalse(restarted.recoveryPending)
        assertTrue("winhttp.dll" in record(f).read().orEmpty())
        restarted.removeLoader(f.game.toString())
        assertFalse(f.game.resolve("winhttp.dll").exists())
    }

    @Test
    fun crashesDuringRecoveryReplayPromotionEvidence() = runBlocking {
        for (operation in listOf("install", "repair", "uninstall")) for (at in
            1..(if (operation == "repair") 5 else 6)) {
            val f = fixture(false, null)
            if (operation != "install") f.folder.installLoader(f.game.toString())
            if (operation == "repair")
                f.game.resolve("doorstop_config.ini").writeText("[General]\ntarget_assembly=\n")
            DiskFaults.step = { if (it == "ownership-published") throw Crash() }
            try {
                assertFailsWith<Crash> {
                    if (operation == "uninstall") f.folder.removeLoader(f.game.toString())
                    else if (operation == "repair") f.folder.repairLoader(f.game.toString())
                    else f.folder.installLoader(f.game.toString())
                }
            } finally {
                DiskFaults.step = {}
            }
            var hits = 0
            DiskFaults.step = { if (it == "recovery-staged-deleted" && ++hits == at) throw Crash() }
            try {
                assertFailsWith<Crash> { restart(f).folder.recoverPending() }
            } finally {
                DiskFaults.step = {}
            }
            val restarted = restart(f).folder
            restarted.recoverPending()
            restarted.recoverPending()
            assertFalse(restarted.recoveryPending)
            if (operation != "uninstall")
                assertEquals(
                    if (operation == "repair") data.keys - "doorstop_config.ini" else data.keys,
                    record(f).read().orEmpty().toSet(),
                )
            restarted.removeLoader(f.game.toString())
            assertTrue(
                (if (operation == "repair") data.keys - "doorstop_config.ini" else data.keys).none {
                    f.game.resolve(it).exists()
                }
            )
            assertTrue(f.game.resolve("NuclearOption.exe").exists())
        }
    }

    @Test
    fun sameGameJournalBlocksLoaderWithRecoveryGuidance() = runBlocking {
        val f = fixture(false, null)
        val journals = f.home.resolve("transactions").createDirectories()
        journals
            .resolve("stuck.txn")
            .writeText(
                json.encodeToString(
                    TransactionBatch(
                        listOf(
                            DiskTransaction(
                                f.game.resolve("winhttp.dll").toString(),
                                null,
                                f.home.resolve("unavailable/old").toString(),
                                existed = true,
                                phase = "old-moved",
                            )
                        )
                    )
                )
            )
        val failure = assertFails { f.folder.installLoader(f.game.toString()) }
        assertTrue(failure.message.orEmpty().contains("this game folder still need recovery"))
        assertFalse(f.folder.inspect(f.game.toString()).loaderPresent)
        assertTrue(f.folder.recoveryPending)
    }

    @Test
    fun refusedPartialUninstallPublishesOnlyRemovedNamesAndOffersRetry() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        val proxy = f.game.resolve("winhttp.dll")
        DiskFaults.beforeMove = { if (it == proxy) throw AccessDeniedException(it.toString()) }
        try {
            assertFails { f.folder.removeLoader(f.game.toString()) }
        } finally {
            DiskFaults.beforeMove = {}
        }
        assertEquals(listOf("winhttp.dll"), record(f).read())
        f.folder.removeLoader(f.game.toString())
        assertFalse(proxy.exists())
        assertTrue(record(f).read().orEmpty().isEmpty())
    }

    @Test
    fun probeOrphanInstallReportMustNotAuthorizeNextInstall() = runBlocking {
        for (action in listOf("remove-all", "uninstall")) {
            val f = fixture(false, null)
            DiskFaults.step = { if (it == "probe-install-pending-deleted") throw Crash() }
            try {
                assertFailsWith<Crash> { f.folder.installLoader(f.game.toString()) }
            } finally {
                DiskFaults.step = {}
            }
            val r = restart(f, allowDownload = true)
            r.folder.recoverPending()
            r.folder.removeLoader(f.game.toString())
            assertTrue(record(f).read().orEmpty().isEmpty())
            DiskFaults.step = { if (it == "probe-install-pending-written") throw Crash() }
            try {
                assertFailsWith<Crash> { r.folder.installLoader(f.game.toString()) }
            } finally {
                DiskFaults.step = {}
            }
            for ((n, d) in data) f.game
                .resolve(n)
                .also { it.parent.createDirectories() }
                .writeBytes(d)
            val r2 = restart(f)
            r2.folder.recoverPending()
            println("PROBE orphan install claimed=${record(f).read()}")
            if (action == "uninstall") r2.folder.removeLoader(f.game.toString())
            else {
                val app =
                    AppStore(
                        Demo.state()
                            .copy(
                                installed = emptyMap(),
                                game = r2.folder.inspect(f.game.toString()),
                            ),
                        Demo.io().copy(gameFolder = r2.folder, persistence = null),
                    )
                try {
                    app.removeEverything()
                    app.shutdown()
                    println("PROBE remove-all toasts=${app.state.value.toasts.map { it.text }}")
                } finally {
                    app.shutdown()
                }
            }
            val remaining = data.keys.filter { f.game.resolve(it).exists() }
            println("PROBE orphan install remaining=$remaining")
            assertEquals(
                data.keys.toList(),
                remaining,
                "Stale promotion report deleted player's manual installation via $action",
            )
        }
    }

    @Test
    fun probeOrphanRemovalReportMustNotDropNextRemovalOwnership() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        DiskFaults.step = { if (it == "probe-removal-pending-deleted") throw Crash() }
        try {
            assertFailsWith<Crash> { f.folder.removeLoader(f.game.toString()) }
        } finally {
            DiskFaults.step = {}
        }
        val r = restart(f, allowDownload = true)
        r.folder.recoverPending()
        r.folder.installLoader(f.game.toString())
        DiskFaults.step = { if (it == "loader-removal-prepared") throw Crash() }
        try {
            assertFailsWith<Crash> { r.folder.removeLoader(f.game.toString()) }
        } finally {
            DiskFaults.step = {}
        }
        val r2 = restart(f)
        r2.folder.recoverPending()
        println(
            "PROBE orphan removal ownership=${record(f).read()} files=${data.keys.filter { f.game.resolve(it).exists() }}"
        )
        assertEquals(
            data.keys,
            record(f).read().orEmpty().toSet(),
            "Stale removal report abandoned all installed files",
        )
    }

    @Test
    fun probeMissingOwnedFileThenCrashAndManualReinstall() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        val proxy = f.game.resolve("winhttp.dll")
        proxy.deleteExisting()
        DiskFaults.step = { if (it == "prepared") throw Crash() }
        try {
            assertFailsWith<Crash> { f.folder.removeLoader(f.game.toString()) }
        } finally {
            DiskFaults.step = {}
        }
        proxy.writeText("player's new proxy")
        val r = restart(f)
        r.folder.recoverPending()
        r.folder.removeLoader(f.game.toString())
        assertTrue(
            proxy.exists(),
            "Uninstall removed a player file at an originally absent removal target",
        )
    }
}
