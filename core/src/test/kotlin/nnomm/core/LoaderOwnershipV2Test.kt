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
class LoaderOwnershipV2Test {
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

    // 1. Crash matrix with hand changes between crash and restart, also after Retry and with a
    // crash during the first recovery attempt.
    @Test
    fun crashMatrixWithHandChanges() = runBlocking {
        val steps =
            listOf(
                "prepared",
                "old-rename-before-journal",
                "old-moved",
                "new-rename-before-journal",
                "promoted",
                "ownership-published",
                "journal-deleted",
                "loader-removal-prepared",
                "loader-removal-published",
                "probe-install-pending-written",
                "loader-ownership-report-deleted",
                "probe-install-pending-deleted",
                "loader-removal-report-deleted",
                "probe-removal-pending-deleted",
            )
        val scenarios =
            listOf(
                "install-fresh",
                "install-playerproxy",
                "repair-adopted-disabled",
                "repair-adopted-missing-ini",
                "repair-owned",
                "uninstall-owned",
                "uninstall-adopted-repaired",
                "retry-install",
                "retry-uninstall",
                "remove-all",
            )
        val hands = listOf("none", "install-official", "remove-all", "replace-own")
        val newProblems = mutableListOf<String>()
        val cats = mutableMapOf<String, MutableList<String>>()
        var runs = 0
        val maxHits = mutableMapOf<Pair<String, String>, Int>()
        for (scenario in scenarios) for (step in steps) for (n in 1..7) for (hand in
            hands) for (recoveryCrash in listOf(false, true)) {
            if (n > maxHits.getOrDefault(scenario to step, 7)) continue
            val f =
                when (scenario) {
                    "install-fresh",
                    "retry-install" -> fixture(false, null)
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
                    "uninstall-owned",
                    "retry-uninstall",
                    "remove-all" ->
                        fixture(false, null).also { it.folder.installLoader(it.game.toString()) }
                    else ->
                        fixture(
                                true,
                                "[General]\nenabled=false\ntarget_assembly=player.dll\n"
                                    .toByteArray(),
                            )
                            .also { it.folder.repairLoader(it.game.toString()) }
                }
            if (scenario.startsWith("retry")) {
                val proxy = f.game.resolve("winhttp.dll")
                DiskFaults.beforeMove = {
                    if (it == proxy) throw AccessDeniedException(it.toString())
                }
                runCatching {
                    if (scenario == "retry-install") f.folder.installLoader(f.game.toString())
                    else f.folder.removeLoader(f.game.toString())
                }
                DiskFaults.beforeMove = {}
            }
            f.game.resolve("player.txt").writeText("keep me")
            val ownedBefore = record(f).read().orEmpty().toSet()
            val playerBefore =
                snapshot(f.game).filter { (k, v) -> itIsPlayer(f, k, v, ownedBefore) }
            var hits = 0
            DiskFaults.step = { if (it == step && ++hits == n) throw Crash() }
            val crashed =
                try {
                    when {
                        scenario.contains("install") && !scenario.contains("uninstall") ->
                            f.folder.installLoader(f.game.toString())
                        scenario.startsWith("repair") -> f.folder.repairLoader(f.game.toString())
                        scenario == "remove-all" -> removeEverythingForMatrix(f)
                        else -> f.folder.removeLoader(f.game.toString())
                    }
                    false
                } catch (_: Crash) {
                    true
                } catch (e: Exception) {
                    cats.getOrPut("$scenario | op-exception") { mutableListOf() } += "$step#$n $e"
                    true
                } finally {
                    DiskFaults.step = {}
                }
            if (!crashed) {
                maxHits[scenario to step] = hits
                f.home.toFile().deleteRecursively()
                roots.remove(f.home)
                continue
            }
            runs++
            // Hand changes by the player while NNOMM is closed.
            val written = mutableMapOf<String, ByteArray>()
            when (hand) {
                "install-official" ->
                    data.forEach { (k, v) ->
                        if (!f.game.resolve(k).exists()) {
                            write(f, k, v)
                            written[k] = v
                        }
                    }
                "remove-all" -> data.keys.forEach { f.game.resolve(it).deleteIfExists() }
                "replace-own" ->
                    data.keys.forEach {
                        f.game.resolve(it).deleteIfExists()
                        val b = "player own $it".toByteArray()
                        write(f, it, b)
                        written[it] = b
                    }
            }
            if (recoveryCrash) {
                var h = 0
                DiskFaults.step = { if (it == "recovery-staged-deleted" && ++h == 1) throw Crash() }
                try {
                    restart(f).folder.recoverPending()
                } catch (_: Crash) {} finally {
                    DiskFaults.step = {}
                }
            }
            val noticeStart = f.notices.size
            val r = restart(f)
            r.folder.recoverPending()
            val newNotices = f.notices.drop(noticeStart)
            val touched =
                if (hand == "none") emptySet()
                else if (hand == "install-official") written.keys else data.keys
            fun add(cat: String, detail: String) {
                val key = "$scenario | $hand | $cat"
                val e = cats.getOrPut(key) { mutableListOf() }
                e += "$step#$n rc=$recoveryCrash $detail"
            }
            val rec = record(f).read().orEmpty().toSet()
            val claimedNew = rec - ownedBefore
            val claimedWritten = claimedNew.filter { it in written }
            if (claimedWritten.isNotEmpty()) add("CLAIMED hand-written", "$claimedWritten")
            val claimedPlayer =
                claimedNew.filter {
                    it in playerBefore && it !in touched && snapshot(f.game)[it] == playerBefore[it]
                }
            if (claimedPlayer.isNotEmpty()) add("CLAIMED pre-existing player", "$claimedPlayer")
            if (r.folder.recoveryPending) add("recovery stuck", "${newNotices.distinct()}")
            if (
                newNotices.any { it.contains("pending record is kept") } &&
                    pendingDir(f, "loader-pending").isEmpty()
            )
                add(
                    "misleading notice",
                    "${newNotices.filter { it.contains("pending record is kept") }}",
                )
            val after = snapshot(f.game)
            val placedUnowned =
                after.keys.filter {
                    it in data && it !in rec && it !in playerBefore && it !in written
                }
            if (placedUnowned.isNotEmpty())
                add("NNOMM-placed unowned after recovery", "$placedUnowned")
            val un = runCatching { r.folder.removeLoader(f.game.toString()) }
            un.onFailure { add("uninstall failed", "${it.message}") }
            val end = snapshot(f.game)
            playerBefore.forEach { (k, v) ->
                if (k in touched) return@forEach
                if (scenario.startsWith("repair") && k == "doorstop_config.ini") return@forEach
                if (end[k] != v) add("pre-existing player file changed/removed", k)
            }
            written.forEach { (k, v) ->
                if (end[k] != digestBytes(v)) {
                    val where =
                        if (k in ownedBefore) "name owned before op" else "name NOT owned before op"
                    add("hand-written file removed ($where)", k)
                }
            }
            val leftNnomm = end.keys.filter { it in data && it !in playerBefore && it !in written }
            if (leftNnomm.isNotEmpty()) add("NNOMM files left after Uninstall", "$leftNnomm")
            f.home.toFile().deleteRecursively()
            roots.remove(f.home)
        }
        println("PROBE1 runs=$runs")
        assertTrue(runs >= 1384, "matrix runs=$runs")
        assertTrue(
            cats.isEmpty(),
            cats.map { (k, v) -> "$k x${v.size}: ${v.take(2)}" }.joinToString("\n"),
        )
        cats.toSortedMap().forEach { (k, v) -> println("PROBE1 [$k] x${v.size} e.g. ${v.take(3)}") }
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

    @Test
    fun editedIniStaysUntouchedAcrossRepairRetries() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        val ini = f.game.resolve("doorstop_config.ini")
        val player = "[General]\ntarget_assembly=player.dll\nenabled=false\n".toByteArray()
        ini.writeBytes(player)
        repeat(2) { f.folder.repairLoader(f.game.toString()) }
        assertContentEquals(player, ini.readBytes())
        assertFalse("doorstop_config.ini" in record(f).read().orEmpty())
        f.folder.removeLoader(f.game.toString())
        assertContentEquals(player, ini.readBytes())
    }

    @Test
    fun changedPinnedLoaderDoesNotResume() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        f.game.resolve("BepInEx/core/Extra.dll").deleteExisting()
        f.game.resolve("winhttp.dll").writeText("player proxy")
        val before = snapshot(f.game)
        assertFalse(f.folder.inspect(f.game.toString()).loaderResumeAllowed)
        f.folder.installLoader(f.game.toString())
        assertEquals(before, snapshot(f.game))
    }

    @Test
    fun unmovedMissingTargetsRecoverForAllJournalCallers() {
        for (phase in listOf("prepared", "copying")) {
            val f = fixture(false, null)
            val journals = f.home.resolve("transactions").createDirectories()
            val target = f.home.resolve("store/missing")
            journals
                .resolve("unmoved.txn")
                .writeText(
                    json.encodeToString(
                        TransactionBatch.serializer(),
                        TransactionBatch(
                            listOf(
                                DiskTransaction(
                                    target.toString(),
                                    null,
                                    f.home.resolve("old").toString(),
                                    existed = true,
                                    phase = phase,
                                )
                            )
                        ),
                    )
                )
            recoverTransactions(journals)
            assertFalse(pendingTransactions(journals))
            assertFalse(target.exists())
        }
    }

    @Test
    fun handChangesDuringStagingAreKept() = runBlocking {
        for (operation in listOf("install", "repair", "uninstall")) {
            val f = fixture(false, null)
            if (operation != "install") f.folder.installLoader(f.game.toString())
            val proxy = f.game.resolve("winhttp.dll")
            DiskFaults.step = { if (it == "prepared") proxy.writeText("player replacement") }
            try {
                assertFails {
                    when (operation) {
                        "install" -> f.folder.installLoader(f.game.toString())
                        "repair" -> f.folder.repairLoader(f.game.toString())
                        else -> f.folder.removeLoader(f.game.toString())
                    }
                }
            } finally {
                DiskFaults.step = {}
            }
            restart(f).folder.recoverPending()
            f.folder.removeLoader(f.game.toString())
            assertEquals("player replacement", proxy.readText())
        }
    }

    private fun digestBytes(b: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") {
            "%02x".format(it)
        }

    // 2. Owned install, player replaces the loader by hand with their own copy, then Uninstall.
    @Test
    fun handReplacedOwnedLoaderThenUninstall() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        f.game.resolve("winhttp.dll").writeText("player own proxy")
        f.game.resolve("doorstop_config.ini").writeText("[General]\ntarget_assembly=player.dll\n")
        val removal = f.folder.removeLoader(f.game.toString())
        assertEquals("player own proxy", f.game.resolve("winhttp.dll").readText())
        assertTrue(f.game.resolve("doorstop_config.ini").exists())
        println(
            "PROBE2 after Uninstall: winhttp=${f.game.resolve("winhttp.dll").exists()} ini=${f.game.resolve("doorstop_config.ini").exists()} removed=${removal.removed} kept=${removal.kept}"
        )
        println(
            "PROBE2 recovery copies: " +
                Files.walk(f.game.resolve(".nnomm")).use { s ->
                    s.filter { it.isRegularFile() }
                        .map { f.game.relativize(it).toString() }
                        .toList()
                }
        )
    }

    // 3. Player removes the owned loader and installs a different BepInEx by hand.
    // Play with mods sees loaderComplete=false and calls installLoader.
    @Test
    fun handInstalledOtherVersionIsNotMixedByPlay() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        data.keys.forEach { f.game.resolve(it).deleteIfExists() }
        write(f, "BepInEx/core/BepInEx.dll", "5.4.22.0".toByteArray(Charsets.UTF_16LE))
        write(f, "winhttp.dll", "player 5.4.22 proxy".toByteArray())
        write(
            f,
            "doorstop_config.ini",
            "[General]\nenabled=true\ntarget_assembly=player.dll\n".toByteArray(),
        )
        val info = f.folder.inspect(f.game.toString())
        println(
            "PROBE3 inspect present=${info.loaderPresent} version=${info.loaderVersion} complete=${info.loaderComplete}"
        )
        assertFalse(info.loaderComplete)
        assertFalse(info.loaderResumeAllowed)
        val before = snapshot(f.game)
        var installCalls = 0
        val counted =
            object : GameFolder by f.folder {
                override suspend fun installLoader(folder: String) {
                    installCalls++
                    f.folder.installLoader(folder)
                }

                override suspend fun apply(state: AppState, profileId: String) {}

                override suspend fun apply(plan: GamePlan) {}

                override val applySucceeded = true
            }
        val io = Demo.io()
        val app =
            AppStore(
                Demo.state().copy(game = info),
                io.copy(gameFolder = counted, persistence = null),
            )
        try {
            app.play()
            withTimeout(10000) {
                while (
                    !app.state.value.gameRunning &&
                        app.state.value.toasts.none { it.action == "Retry" }
                ) delay(10)
            }
            delay(300)
            println(
                "PROBE3 installLoader calls=$installCalls toasts=${app.state.value.toasts.map { it.text }}"
            )
        } finally {
            app.shutdown()
        }
        assertEquals(0, installCalls)
        assertEquals(before, snapshot(f.game).filterKeys { it in before })
        println("PROBE3 files now: " + snapshot(f.game).keys.sorted())
        println("PROBE3 owned: " + record(f).read())
        f.folder.removeLoader(f.game.toString())
        println("PROBE3 after Uninstall: " + snapshot(f.game).keys.sorted())
    }

    // 4. Stale removal report: a crash between deleting the removal record and its report.
    @Test
    fun staleRemovalReport() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        var saved: Pair<Path, ByteArray>? = null
        DiskFaults.step = {
            if (it == "journal-deleted") {
                val d = f.home.resolve("loader-removals")
                d.listDirectoryEntries("*.promoted").firstOrNull()?.let { p ->
                    saved = p to p.readBytes()
                }
            }
        }
        f.folder.removeLoader(f.game.toString())
        DiskFaults.step = {}
        val (path, bytes) = saved!!
        path.writeBytes(
            bytes
        ) // state left by a crash between Files.delete(path) and deleteIfExists(report)
        val r = restart(f)
        r.folder.recoverPending()
        r.folder.installLoader(f.game.toString())
        println("PROBE4 owned after reinstall: ${record(f).read()}")
        DiskFaults.step = { if (it == "loader-removal-prepared") throw Crash() }
        assertFailsWith<Crash> { r.folder.removeLoader(f.game.toString()) }
        DiskFaults.step = {}
        val r2 = restart(f)
        r2.folder.recoverPending()
        assertEquals(data.keys, record(f).read().orEmpty().toSet())
        println("PROBE4 owned after crash+restart: ${record(f).read()}")
        r2.folder.removeLoader(f.game.toString())
        println(
            "PROBE4 NNOMM files left after Uninstall: " +
                snapshot(f.game).keys.filter { it in data }
        )
    }

    // 5. Stale install report + pending record without journal (crash right after the pending
    // write), then the player installs the official loader by hand.
    @Test
    fun staleInstallReport() = runBlocking {
        val f = fixture(false, null)
        var report: Pair<Path, ByteArray>? = null
        var pendingRecord: Pair<Path, ByteArray>? = null
        DiskFaults.step = {
            if (it == "prepared") {
                f.home
                    .resolve("loader-pending")
                    .listDirectoryEntries("*.json")
                    .firstOrNull()
                    ?.let { p -> pendingRecord = p to p.readBytes() }
            }
            if (it == "ownership-published") {
                f.home
                    .resolve("loader-pending")
                    .listDirectoryEntries("*.promoted")
                    .firstOrNull()
                    ?.let { p -> report = p to p.readBytes() }
            }
        }
        f.folder.installLoader(f.game.toString())
        DiskFaults.step = {}
        f.folder.removeLoader(f.game.toString())
        // Crash 1 left the report; crash 2 happened right after the next Install wrote its pending
        // record.
        report!!.first.writeBytes(report!!.second)
        val oldPending =
            json.decodeFromString<PendingLoaderOwnership>(String(pendingRecord!!.second))
        pendingRecord!!
            .first
            .writeText(
                json.encodeToString(
                    PendingLoaderOwnership.serializer(),
                    oldPending.copy(operationId = "different-operation"),
                )
            )
        data.forEach { (k, v) -> write(f, k, v) }
        val r = restart(f)
        r.folder.recoverPending()
        println("PROBE5 owned after recovery: ${record(f).read()}")
        r.folder.removeLoader(f.game.toString())
        assertTrue(data.keys.all { f.game.resolve(it).exists() })
        assertTrue(record(f).read().orEmpty().isEmpty())
        println(
            "PROBE5 player files left after Uninstall: " +
                snapshot(f.game).keys.filter { it in data }
        )
    }

    // 6. Player deleted the loader folder by hand; Uninstall then runs.
    @Test
    fun uninstallAfterHandRemoval() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        f.game.resolve("BepInEx").toFile().deleteRecursively()
        data.keys.forEach { f.game.resolve(it).deleteIfExists() }
        val res = f.folder.removeLoader(f.game.toString())
        assertFalse(f.game.resolve("BepInEx").exists())
        assertEquals(0, res.removed)
        println(
            "PROBE6 removed=${res.removed} kept=${res.kept} entries now: " +
                Files.walk(f.game).use { s -> s.map { f.game.relativize(it).toString() }.toList() }
        )
    }

    // 7. Notice after hand removal between crash and restart.
    @Test
    fun noticeAfterHandRemoval() = runBlocking {
        val f = fixture(false, null)
        DiskFaults.step = { if (it == "journal-deleted") throw Crash() }
        assertFailsWith<Crash> { f.folder.installLoader(f.game.toString()) }
        DiskFaults.step = {}
        f.game.resolve("winhttp.dll").deleteIfExists()
        val r = restart(f)
        r.folder.recoverPending()
        assertFalse(r.folder.recoveryPending)
        assertTrue(
            f.notices.none {
                it.contains("pending record is kept") || it.contains(f.game.toString())
            }
        )
        println(
            "PROBE7 notices=${f.notices} pendingFiles=${pendingDir(f, "loader-pending")} recoveryPending=${r.folder.recoveryPending}"
        )
    }

    private suspend fun removeEverythingForMatrix(f: F) {
        val completed = CompletableDeferred<Result<LoaderRemoval>>()
        val port =
            object : GameFolder by f.folder {
                override suspend fun apply(plan: GamePlan) {}

                override suspend fun apply(state: AppState, profileId: String) {}

                override val applySucceeded = true

                override suspend fun removeLoader(folder: String): LoaderRemoval {
                    val result = runCatching { f.folder.removeLoader(folder) }
                    completed.complete(result)
                    // Stop simulated process death from cancelling AppStore's disk worker.
                    // No further game mutations follow loader removal; rethrow after draining it.
                    return result.getOrNull() ?: LoaderRemoval()
                }
            }
        val app =
            AppStore(
                Demo.state()
                    .copy(installed = emptyMap(), game = f.folder.inspect(f.game.toString())),
                Demo.io().copy(gameFolder = port, persistence = null),
            )
        val result =
            try {
                app.removeEverything()
                withTimeout(5000) { completed.await() }
            } finally {
                app.shutdown()
            }
        result.getOrThrow()
    }

    private suspend fun removeEverything(f: F) {
        val app =
            AppStore(
                Demo.state()
                    .copy(installed = emptyMap(), game = f.folder.inspect(f.game.toString())),
                Demo.io().copy(gameFolder = f.folder, persistence = null),
            )
        try {
            app.removeEverything()
            withTimeout(5000) {
                while (
                    app.state.value.toasts.none {
                        it.text.contains("mod loader files were removed") ||
                            it.text.contains("Existing mod loader files were kept") ||
                            it.text.contains("Could not remove")
                    }
                ) delay(10)
            }
            println("PROBE toasts: " + app.state.value.toasts.map { it.text })
        } finally {
            app.shutdown()
        }
    }

    private val own =
        mapOf(
            "BepInEx/core/BepInEx.dll" to "5.4.23.4 player build".toByteArray(Charsets.UTF_16LE),
            "winhttp.dll" to "player proxy".toByteArray(),
            "doorstop_config.ini" to
                "[General]\nenabled=true\ntarget_assembly=player.dll\n".toByteArray(),
        )

    // 8. Opus E at other Uninstall crash steps: crash, player removes the loader and installs
    // their own by hand, restart, Remove all mods.
    @Test
    fun uninstallCrashThenOwnLoaderThenRemoveAll() = runBlocking {
        for ((step, n) in
            listOf(
                "loader-removal-prepared" to 1,
                "prepared" to 1,
                "old-moved" to 2,
                "promoted" to 3,
                "journal-deleted" to 1,
            )) {
            val f = fixture(false, null)
            f.folder.installLoader(f.game.toString())
            var hits = 0
            DiskFaults.step = { if (it == step && ++hits == n) throw Crash() }
            assertFailsWith<Crash> { f.folder.removeLoader(f.game.toString()) }
            DiskFaults.step = {}
            data.keys.forEach { f.game.resolve(it).deleteIfExists() }
            own.forEach { (k, v) -> write(f, k, v) }
            val r = restart(f)
            r.folder.recoverPending()
            println("PROBE8 $step#$n owned after restart: ${record(f).read()}")
            removeEverything(r)
            own.forEach { (k, v) -> assertContentEquals(v, f.game.resolve(k).readBytes()) }
            println(
                "PROBE8 $step#$n player files left: ${own.keys.filter { f.game.resolve(it).exists() }}"
            )
        }
    }

    // 9. Uninstall crash before any move, player deletes the loader by hand.
    @Test
    fun uninstallCrashThenHandRemovalDoesNotBlock() = runBlocking {
        val f = fixture(false, null)
        f.folder.installLoader(f.game.toString())
        DiskFaults.step = { if (it == "prepared") throw Crash() }
        assertFailsWith<Crash> { f.folder.removeLoader(f.game.toString()) }
        DiskFaults.step = {}
        data.keys.forEach { f.game.resolve(it).deleteIfExists() }
        repeat(3) { restart(f).folder.recoverPending() }
        val r = restart(f)
        assertFalse(r.folder.recoveryPending)
        println(
            "PROBE9 recoveryPending=${r.folder.recoveryPending} journals=${pendingDir(f, "transactions").map { it.fileName }}"
        )
        for (op in listOf("install", "repair", "uninstall")) {
            val res = runCatching {
                when (op) {
                    "install" -> r.folder.installLoader(f.game.toString())
                    "repair" -> r.folder.repairLoader(f.game.toString())
                    else -> r.folder.removeLoader(f.game.toString())
                }
            }
            println(
                "PROBE9 $op -> ${res.exceptionOrNull()?.message ?: "ok"} help=${res.exceptionOrNull()?.let { nnomm.core.store.loaderFailureHelp(it) }}"
            )
        }
        println("PROBE9 notices=${f.notices.distinct()}")
    }

    // 10. Unreadable at Install verification (no crash): what Retry, Uninstall and a later
    // replacement by the player do.
    @Test
    fun unreadableAtInstallThenActions() = runBlocking {
        val f = fixture(false, null)
        var locked = true
        val g =
            RealGameFolder(
                f.home,
                RealModStore(f.home),
                object : Downloader {
                    override suspend fun download(
                        mod: CatalogMod,
                        version: ModVersion,
                        progress: (Int) -> Unit,
                    ) = DownloadedFile(f.home.resolve("loader.zip").toString())
                },
                running = { false },
                notice = { f.notices += it },
                readLoaderHash = {
                    if (locked && it.fileName.toString() == "winhttp.dll")
                        throw AccessDeniedException(it.toString())
                    digest(it)
                },
            )
        val e = runCatching { g.installLoader(f.game.toString()) }.exceptionOrNull()
        val info = g.inspect(f.game.toString())
        println(
            "PROBE10 install -> $e pending=${g.recoveryPending} owned=${record(f).read()} present=${info.loaderPresent} complete=${info.loaderComplete}"
        )
        assertTrue(g.recoveryPending)
        val u = runCatching { g.removeLoader(f.game.toString()) }.exceptionOrNull()
        println(
            "PROBE10 uninstall while locked -> ${u?.message} help=${u?.let { nnomm.core.store.loaderFailureHelp(it) }} files=${data.keys.filter { f.game.resolve(it).exists() }}"
        )
        locked = false
        g.removeLoader(f.game.toString())
        assertFalse(g.recoveryPending)
        assertTrue(data.keys.none { f.game.resolve(it).exists() })
        println(
            "PROBE10 uninstall after unlock -> files=${data.keys.filter { f.game.resolve(it).exists() }} pending=${g.recoveryPending}"
        )
    }
}
