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
class LoaderRecoveryMatrixTest {
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

    // B. Crash during an operation, a second crash during recovery, THEN the player changes
    // loader files by hand, then restart, then Uninstall.
    @Test
    fun probeB_handChangeAfterRecoveryCrash() = runBlocking {
        val steps =
            listOf(
                "prepared",
                "old-rename-before-journal",
                "old-moved",
                "new-rename-before-journal",
                "promoted",
                "ownership-published",
            )
        val scenarios =
            listOf(
                "install-fresh",
                "repair-owned",
                "repair-adopted-disabled",
                "uninstall-owned",
                "remove-all",
            )
        val hands = listOf("none", "install-official", "remove-all", "replace-own")
        val cats = sortedMapOf<String, MutableList<String>>()
        var runs = 0
        val maxN = mutableMapOf<Pair<String, String>, Int>()
        val maxM = mutableMapOf<String, Int>()
        for (scenario in scenarios) for (step in steps) for (n in 1..7) for (m in 1..8) for (hand in
            hands) {
            if (n > maxN.getOrDefault(scenario to step, 7)) continue
            if (m > maxM.getOrDefault("$scenario/$step/$n", 8)) continue
            val f =
                when (scenario) {
                    "install-fresh" -> fixture(false, null)
                    "repair-adopted-disabled" ->
                        fixture(
                            true,
                            "[General]\nenabled=false\ntarget_assembly=player.dll\n".toByteArray(),
                        )
                    else ->
                        fixture(false, null).also { it.folder.installLoader(it.game.toString()) }
                }
            f.game.resolve("player.txt").writeText("keep me")
            val ownedBefore = record(f).read().orEmpty().toSet()
            val playerBefore =
                snapshot(f.game).filter { (k, v) -> itIsPlayer(f, k, v, ownedBefore) }
            var hits = 0
            DiskFaults.step = { if (it == step && ++hits == n) throw Crash() }
            val crashed =
                try {
                    when (scenario) {
                        "install-fresh" -> f.folder.installLoader(f.game.toString())
                        "repair-owned",
                        "repair-adopted-disabled" -> f.folder.repairLoader(f.game.toString())
                        "remove-all" -> removeEverythingForMatrix(f)
                        else -> f.folder.removeLoader(f.game.toString()).let {}
                    }
                    false
                } catch (_: Crash) {
                    true
                } catch (e: Exception) {
                    true
                } finally {
                    DiskFaults.step = {}
                }
            if (!crashed) {
                maxN[scenario to step] = hits
                f.home.toFile().deleteRecursively()
                roots.remove(f.home)
                continue
            }
            var h = 0
            DiskFaults.step = { if (it == "recovery-staged-deleted" && ++h == m) throw Crash() }
            val rcrashed =
                try {
                    restart(f).folder.recoverPending()
                    false
                } catch (_: Crash) {
                    true
                } finally {
                    DiskFaults.step = {}
                }
            if (!rcrashed) {
                maxM["$scenario/$step/$n"] = h
                f.home.toFile().deleteRecursively()
                roots.remove(f.home)
                continue
            }
            runs++
            val written = mutableMapOf<String, ByteArray>()
            when (hand) {
                "install-official" ->
                    data.forEach { (k, v) ->
                        if (!f.game.resolve(k).exists()) {
                            write(f, k, v)
                            written[k] = v
                        }
                    }
                "remove-all" -> {
                    data.keys.forEach { f.game.resolve(it).deleteIfExists() }
                    f.game.resolve("BepInEx").toFile().deleteRecursively()
                }
                "replace-own" ->
                    data.keys.forEach {
                        f.game.resolve(it).deleteIfExists()
                        val b = "player own $it".toByteArray()
                        write(f, it, b)
                        written[it] = b
                    }
            }
            val noticeStart = f.notices.size
            val r = restart(f)
            r.folder.recoverPending()
            val newNotices = f.notices.drop(noticeStart)
            fun add(cat: String, detail: String) {
                cats.getOrPut("$scenario | $hand | $cat") { mutableListOf() } +=
                    "$step#$n rc#$m $detail"
            }
            val stillPending =
                pendingDir(f, "loader-pending").any { it.name.endsWith(".json") } ||
                    pendingDir(f, "loader-removals").any { it.name.endsWith(".json") }
            if (
                newNotices.any { it.contains("ownership") || it.contains("removal recovery") } &&
                    !stillPending
            )
                add("startup notice without pending record", "${newNotices.distinct()}")
            val mid = snapshot(f.game)
            written.forEach { (k, v) ->
                if (mid[k] != digestBytes(v)) add("hand-written file displaced by recovery", k)
            }
            if (r.folder.recoveryPending) add("recovery stuck", "${newNotices.distinct()}")
            val rec = record(f).read().orEmpty().toSet()
            val claimedWritten = (rec - ownedBefore).filter { it in written }
            if (claimedWritten.isNotEmpty()) add("CLAIMED hand-written", "$claimedWritten")
            val placedUnowned =
                mid.keys.filter {
                    it in data && it !in rec && it !in playerBefore && it !in written
                }
            if (placedUnowned.isNotEmpty())
                add("NNOMM-placed unowned after recovery", "$placedUnowned")
            val un = runCatching { r.folder.removeLoader(f.game.toString()) }
            un.onFailure { add("uninstall failed", "${it.javaClass.simpleName}: ${it.message}") }
            val end = snapshot(f.game)
            val touched =
                if (hand == "none") emptySet()
                else if (hand == "install-official") written.keys else data.keys
            playerBefore.forEach { (k, v) ->
                if (k in touched) return@forEach
                if (scenario.startsWith("repair") && k == "doorstop_config.ini") return@forEach
                if (end[k] != v) add("pre-existing player file changed/removed", k)
            }
            written.forEach { (k, v) ->
                if (end[k] != digestBytes(v)) add("hand-written file gone after Uninstall", k)
            }
            val leftNnomm = end.keys.filter { it in data && it !in playerBefore && it !in written }
            if (leftNnomm.isNotEmpty()) add("NNOMM files left after Uninstall", "$leftNnomm")
            val core = f.game.resolve("BepInEx/core")
            if (hand == "remove-all" && core.isDirectory() && core.listDirectoryEntries().isEmpty())
                add("empty BepInEx/core recreated", "")
            f.home.toFile().deleteRecursively()
            roots.remove(f.home)
        }
        println("PROBEB runs=$runs")
        assertEquals(2600, runs, "Recovery interruption matrix size")
        assertTrue(
            cats.isEmpty(),
            cats.entries.joinToString("\n") { "${it.key}: ${it.value.take(4)}" },
        )
    }
}
