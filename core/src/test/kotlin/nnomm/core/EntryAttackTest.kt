package nnomm.core

import java.nio.file.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*

class EntryAttackTest {
    private val base = Files.createTempDirectory("entry-attacks-")

    private fun tmp() = Files.createTempDirectory(base, "attack-")

    private val noNet =
        object : Downloader {
            override suspend fun download(
                mod: CatalogMod,
                version: ModVersion,
                progress: (Int) -> Unit,
            ): DownloadedFile = error("no network")
        }

    private fun game() = tmp().also { it.resolve("NuclearOption.exe").writeText("") }

    private suspend fun put(
        s: RealModStore,
        id: String,
        v: String = "1",
        file: String = "mod.dll",
        text: String = "shipped",
    ) {
        val p = tmp()
        p.resolve(file).also { it.parent.createDirectories() }.writeText(text)
        s.put(id, v, DownloadedFile(p.toString()))
    }

    private fun plan(g: Path, vararg ids: String) =
        GamePlan(g.toString(), "default", ids.associateWith { "1" }, false)

    private fun contains(root: Path, text: String) =
        root.exists() &&
            Files.walk(root).use { s ->
                s.filter { it.isRegularFile() }
                    .anyMatch { runCatching { it.readText() == text }.getOrDefault(false) }
            }

    @AfterTest
    fun reset() {
        DiskFaults.step = {}
        deleteTree(base)
    }

    @Test
    fun editedLooseFileSurvivesRemoval() = runBlocking {
        val h = tmp()
        val g = game()
        g.resolve("BepInEx/plugins").createDirectories()
        val file = g.resolve("BepInEx/plugins/Mine.dll")
        file.writeText("original")
        val s = RealModStore(h)
        val f = RealGameFolder(h, s, noNet, running = { false })
        f.takeOver(g.toString())
        file.writeText("UNIQUE LOOSE EDIT")
        f.apply(plan(g))
        assertTrue(
            contains(h, "UNIQUE LOOSE EDIT") || contains(g, "UNIQUE LOOSE EDIT"),
            "Loose edit lost everywhere",
        )
    }

    @Test
    fun missingChildPayloadMustNotBeDeletedByParentUpdate() = runBlocking {
        val h = tmp()
        val g = game()
        val s = RealModStore(h)
        put(s, "parent")
        put(s, "parent", "2")
        put(s, "child")
        val cat =
            listOf(
                CatalogMod(
                    "child",
                    "Child",
                    versions = listOf(ModVersion("1", extends = PackageReference("parent", "1"))),
                )
            )
        val f = RealGameFolder(h, s, noNet, catalog = { cat }, running = { false })
        val p = plan(g, "parent", "child")
        f.apply(p)
        deleteTree(s.version("child", "1"))
        f.apply(p.copy(versions = mapOf("parent" to "2", "child" to "1")))
        assertTrue(
            g.resolve("BepInEx/plugins/parent/addons/child/mod.dll").exists(),
            "Parent update deleted missing-store child",
        )
    }

    @Test
    fun emptyProfileConfigDoesNotInheritOtherProfile() = runBlocking {
        val h = tmp()
        val g = game()
        val s = RealModStore(h)
        val f = RealGameFolder(h, s, noNet, running = { false })
        val cfg = g.resolve("BepInEx/config").createDirectories().resolve("mine.cfg")
        cfg.writeText("shared")
        f.apply(plan(g).copy(ownSettings = true))
        cfg.writeText("profile default")
        f.apply(plan(g).copy(profileId = "other", ownSettings = true))
        assertFalse(cfg.exists(), "New profile inherited previous profile config")
    }

    @Test
    fun configPromotionCrashDoesNotDestroyPriorSnapshotOnRetry() = runBlocking {
        val h = tmp()
        val g = game()
        val s = RealModStore(h)
        val f = RealGameFolder(h, s, noNet, running = { false })
        val cfg = g.resolve("BepInEx/config").createDirectories().resolve("mine.cfg")
        cfg.writeText("DEFAULT UNIQUE")
        f.apply(plan(g).copy(ownSettings = true))
        h.resolve("profiles/other/config")
            .createDirectories()
            .resolve("mine.cfg")
            .writeText("OTHER")
        DiskFaults.step = {
            if (it == "promoted" && cfg.exists() && cfg.readText() == "OTHER") throw DiskCrash()
        }
        assertFails { f.apply(plan(g).copy(profileId = "other", ownSettings = true)) }
        DiskFaults.step = {}
        f.recoverPending()
        f.apply(plan(g).copy(profileId = "other", ownSettings = true))
        assertEquals(
            "DEFAULT UNIQUE",
            h.resolve("profiles/default/config/mine.cfg").readText(),
            "Retry overwrote old profile snapshot",
        )
    }

    @Test
    fun removalRecoveryCanResumeAfterCommitWriteFailure() {
        val h = tmp()
        val g = game()
        val t = g.resolve("BepInEx/plugins/m").createDirectories()
        t.resolve("save").writeText("unique")
        val bad = h.resolve("commit").createDirectories()
        DiskFaults.step = { if (it == "promoted") throw DiskCrash() }
        assertFails {
            replaceTree(t, null, h.resolve("game-transactions"), afterCommit = bad to "{}")
        }
        DiskFaults.step = {}
        assertFails { recoverTransactions(h.resolve("game-transactions")) }
        deleteTree(bad)
        recoverTransactions(h.resolve("game-transactions"))
        assertEquals("{}", bad.readText())
    }

    @Test
    fun crashMatrix() {
        for (remove in listOf(false, true)) for (phase in
            listOf("prepared", "old-moved", "promoted")) {
            val h = tmp()
            val t = h.resolve("game/BepInEx/plugins/m").createDirectories()
            t.resolve("old").writeText("old")
            val src = h.resolve("src").createDirectories()
            src.resolve("new").writeText("new")
            DiskFaults.step = { if (it == phase) throw DiskCrash() }
            assertFails {
                replaceTree(
                    t,
                    if (remove) null else src,
                    h.resolve("game-transactions"),
                    afterCommit = h.resolve("ownership") to "committed",
                )
            }
            DiskFaults.step = {}
            recoverTransactions(h.resolve("game-transactions"))
            recoverTransactions(h.resolve("game-transactions"))
            if (phase == "promoted") {
                assertEquals(!remove, t.exists())
                assertEquals("committed", h.resolve("ownership").readText())
            } else assertEquals("old", t.resolve("old").readText())
        }
    }

    @Test
    fun ioFailureAtSwapSteps() {
        for (phase in listOf("prepared", "old-moved", "promoted")) {
            val h = tmp()
            val t = h.resolve("game/BepInEx/plugins/m").createDirectories()
            t.resolve("old").writeText("old")
            val src = h.resolve("src").createDirectories()
            src.resolve("new").writeText("new")
            DiskFaults.step = {
                if (it == phase) throw java.io.IOException("No space left on device (injected)")
            }
            runCatching {
                replaceTree(
                    t,
                    src,
                    h.resolve("game-transactions"),
                    afterCommit = h.resolve("ownership") to "committed",
                )
            }
            DiskFaults.step = {}
            recoverTransactions(h.resolve("game-transactions"))
            assertTrue(contains(h, "old"))
            assertTrue(t.exists())
        }
    }

    @Test
    fun symlinkInsideChangedEntryMustRemainUnchanged() = runBlocking {
        val h = tmp()
        val g = game()
        val s = RealModStore(h)
        put(s, "m")
        val f = RealGameFolder(h, s, noNet, running = { false })
        f.apply(plan(g, "m"))
        val outside = tmp().resolve("save")
        outside.writeText("UNIQUE LINK")
        val link = g.resolve("BepInEx/plugins/m/player-link")
        Files.createSymbolicLink(link, outside)
        f.apply(plan(g))
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("UNIQUE LINK", outside.readText())
        assertTrue(f.applyProblems.value.containsKey("m"))
    }

    @Test
    fun takeoverTwiceAcrossFoldersKeepsDifferentLocalBytes() = runBlocking {
        val h = tmp()
        val a = game()
        val b = game()
        for ((g, text) in listOf(a to "A", b to "B")) {
            g.resolve("BepInEx/plugins").createDirectories().resolve("Mine.dll").writeText(text)
        }
        val s = RealModStore(h)
        val f = RealGameFolder(h, s, noNet, running = { false })
        val first = f.takeOver(a.toString()).single()
        val twice = f.takeOver(a.toString()).single()
        val second = f.takeOver(b.toString()).single()
        assertEquals(first.first.id, twice.first.id)
        assertNotEquals(first.first.id, second.first.id)
        assertEquals("A", a.resolve("BepInEx/plugins/Mine.dll").readText())
        assertEquals("B", b.resolve("BepInEx/plugins/Mine.dll").readText())
    }

    @Test
    fun actualDestinationLongPathIsIsolated() = runBlocking {
        val h = tmp()
        val g = game()
        val s = RealModStore(h)
        put(s, "bad", file = "x".repeat(200) + ".dll")
        put(s, "good")
        val f = RealGameFolder(h, s, noNet, running = { false })
        f.apply(plan(g, "bad", "good"))
        assertTrue(g.resolve("BepInEx/plugins/good/mod.dll").exists())
        assertTrue(f.applyProblems.value.containsKey("bad"))
    }

    @Test
    fun pruningMustNotDeleteUniquePlayerRecovery() = runBlocking {
        val h = tmp()
        val g = game()
        val s = RealModStore(h)
        val f = RealGameFolder(h, s, noNet, running = { false })
        for (i in 0..100) {
            val p = h.resolve("recovery/copy-$i").createDirectories()
            p.resolve("save").writeText("unique-$i")
            Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(i.toLong()))
        }
        f.apply(plan(g))
        assertTrue(contains(h, "unique-0"), "Unique recovery pruned permanently")
    }

    @Test
    fun deletingWholeEntryDoesNotResurrectSavedExtras() = runBlocking {
        val h = tmp()
        val g = game()
        val s = RealModStore(h)
        put(s, "m")
        val f = RealGameFolder(h, s, noNet, running = { false })
        val on = plan(g, "m")
        f.apply(on)
        val entry = g.resolve("BepInEx/plugins/m")
        entry.resolve("save").writeText("extra")
        f.apply(plan(g))
        f.apply(on)
        deleteTree(entry)
        f.apply(plan(g))
        f.apply(on)
        assertFalse(entry.resolve("save").exists(), "Deleted whole folder resurrected saved extra")
    }

    @Test
    fun directorySymlinkMustBlockRemovalBeforeMovingEntry() = runBlocking {
        val h = tmp()
        val g = game()
        val s = RealModStore(h)
        put(s, "m")
        val f = RealGameFolder(h, s, noNet, running = { false })
        f.apply(plan(g, "m"))
        val link = g.resolve("BepInEx/plugins/m/link")
        Files.createSymbolicLink(link, tmp())
        f.apply(plan(g))
        assertTrue(
            Files.isSymbolicLink(link),
            "Directory symlink entry was moved despite unsupported contents",
        )
    }

    @Test
    fun secondProcessMustNotRecoverActiveTransaction() = runBlocking {
        val h = tmp()
        val g = game()
        val s = RealModStore(h)
        put(s, "m")
        val f = RealGameFolder(h, s, noNet, running = { false })
        var used = false
        DiskFaults.step = { phase ->
            if (phase == "promoted" && !used && g.resolve("BepInEx/plugins/m/mod.dll").exists()) {
                used = true
                val cmd =
                    listOf(
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        "nnomm.core.SecondInstanceWorker",
                        h.toString(),
                        g.toString(),
                    )
                val proc =
                    ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .redirectOutput(h.resolve("worker.log").toFile())
                        .start()
                check(proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { "worker timeout" }
                check(proc.exitValue() == 0) { h.resolve("worker.log").readText() }
            }
        }
        f.apply(plan(g, "m"))
        DiskFaults.step = {}
        val placement =
            json.decodeFromString<Placement>(
                g.resolve("BepInEx/plugins/.nnomm-state.json").readText()
            )
        println(
            "two-process used=$used exists=${g.resolve("BepInEx/plugins/m/mod.dll").exists()} placement=$placement log=${h.resolve("worker.log").readText()}"
        )
        assertTrue(used)
        assertEquals(
            g.resolve("BepInEx/plugins/m/mod.dll").exists(),
            placement.entries.values.any { it.id == "m" },
            "Concurrent recovery caused phantom ownership",
        )
    }

    @Test
    fun promotedIoFailureMustKeepOwnershipConsistent() = runBlocking {
        val h = tmp()
        val g = game()
        val s = RealModStore(h)
        put(s, "m")
        val f = RealGameFolder(h, s, noNet, running = { false })
        var fired = false
        DiskFaults.step = {
            if (it == "promoted" && !fired && g.resolve("BepInEx/plugins/m/mod.dll").exists()) {
                fired = true
                throw java.io.IOException("No space left on device (injected)")
            }
        }
        f.apply(plan(g, "m"))
        DiskFaults.step = {}
        val placement =
            json.decodeFromString<Placement>(
                g.resolve("BepInEx/plugins/.nnomm-state.json").readText()
            )
        assertTrue(
            placement.entries.values.any { it.id == "m" },
            "Promoted target exists but final ownership omits it",
        )
    }

    @Test
    fun renameWindowIoFailureMustKeepOwnershipConsistent() = runBlocking {
        val h = tmp()
        val g = game()
        val s = RealModStore(h)
        put(s, "m")
        val f = RealGameFolder(h, s, noNet, running = { false })
        var fired = false
        DiskFaults.step = {
            if (
                it == "new-rename-before-journal" &&
                    !fired &&
                    g.resolve("BepInEx/plugins/m/mod.dll").exists()
            ) {
                fired = true
                throw java.io.IOException("No space left on device (injected)")
            }
        }
        f.apply(plan(g, "m"))
        DiskFaults.step = {}
        val placement =
            json.decodeFromString<Placement>(
                g.resolve("BepInEx/plugins/.nnomm-state.json").readText()
            )
        assertTrue(
            placement.entries.values.any { it.id == "m" },
            "Promoted target exists but final ownership omits it",
        )
    }

    @Test
    fun interruptedRollbackMustNotBeInferredAsPromotion() {
        val h = tmp()
        val t = h.resolve("game/BepInEx/plugins/m").createDirectories()
        t.resolve("old").writeText("old")
        val src = h.resolve("input").createDirectories()
        src.resolve("new").writeText("new")
        DiskFaults.step = { if (it == "old-moved") throw DiskCrash() }
        assertFails {
            replaceTree(
                t,
                src,
                h.resolve("game-transactions"),
                afterCommit = h.resolve("ownership") to "new",
            )
        }
        DiskFaults.step = { if (it == "recovery-staged-deleted") throw DiskCrash() }
        assertFails { recoverTransactions(h.resolve("game-transactions")) }
        DiskFaults.step = {}
        recoverTransactions(h.resolve("game-transactions"))
        assertEquals("old", t.resolve("old").readText())
        assertFalse(
            h.resolve("ownership").exists(),
            "Rolled-back payload was published as the new version",
        )
    }

    @Test
    fun supportedLoaderRepairCrashMatrixKeepsPlayerBytes() = runBlocking {
        for (phase in listOf("prepared", "old-moved", "promoted")) for (hit in 1..3) {
            val h = tmp()
            val g = game()
            h.resolve("staging").createDirectories()
            val core = g.resolve("BepInEx/core").createDirectories()
            core.resolve("BepInEx.dll").writeBytes("5.4.23.4".toByteArray(Charsets.UTF_16LE))
            core.resolve("player.data").writeText("UNIQUE CORE")
            g.resolve("doorstop_config.ini").writeText("PLAYER INI")
            g.resolve("winhttp.dll").writeText("OLD PROXY")
            val zip = h.resolve("loader.zip")
            java.util.zip.ZipOutputStream(Files.newOutputStream(zip)).use { z ->
                for ((n, t) in
                    mapOf(
                        "BepInEx/core/BepInEx.dll" to "new loader",
                        "winhttp.dll" to "new proxy",
                        "doorstop_config.ini" to "default",
                    )) {
                    z.putNextEntry(java.util.zip.ZipEntry(n))
                    z.write(t.toByteArray())
                    z.closeEntry()
                }
            }
            val dl =
                object : Downloader {
                    override suspend fun download(
                        mod: CatalogMod,
                        version: ModVersion,
                        progress: (Int) -> Unit,
                    ) = DownloadedFile(zip.toString())
                }
            val f = RealGameFolder(h, RealModStore(h), dl, running = { false })
            var n = 0
            DiskFaults.step = { if (it == phase && ++n == hit) throw DiskCrash() }
            runCatching { f.repairLoader(g.toString()) }
            DiskFaults.step = {}
            f.recoverPending()
            assertTrue(contains(h, "UNIQUE CORE") || contains(g, "UNIQUE CORE"))
            assertEquals("PLAYER INI", g.resolve("doorstop_config.ini").readText())
        }
    }

    @Test
    fun gameStartsAfterPreparationMustDeferSwap() = runBlocking {
        val h = tmp()
        val g = game()
        val s = RealModStore(h)
        put(s, "m")
        val f0 = RealGameFolder(h, s, noNet, running = { false })
        f0.apply(plan(g))
        var running = false
        val f = RealGameFolder(h, s, noNet, running = { running })
        DiskFaults.step = { if (it == "prepared") running = true }
        f.apply(plan(g, "m"))
        DiskFaults.step = {}
        assertFalse(
            g.resolve("BepInEx/plugins/m/mod.dll").exists(),
            "Game started while staging but mod was promoted anyway",
        )
    }

    @Test
    fun fineGrainedSwapCrashMatrix() {
        for (exists in listOf(false, true)) for (remove in listOf(false, true)) for (phase in
            listOf(
                "prepared",
                "old-rename-before-journal",
                "old-moved",
                "new-rename-before-journal",
                "promoted",
                "ownership-published",
                "journal-deleted",
            )) {
            val h = tmp()
            val t = h.resolve("game/BepInEx/plugins/m")
            if (exists) t.createDirectories().resolve("old").writeText("old")
            val src = h.resolve("source").createDirectories()
            src.resolve("new").writeText("new")
            DiskFaults.step = { if (it == phase) throw DiskCrash() }
            assertFails {
                replaceTree(
                    t,
                    if (remove) null else src,
                    h.resolve("game-transactions"),
                    keepPrevious = false,
                    afterCommit = h.resolve("ownership") to "new",
                )
            }
            DiskFaults.step = {}
            recoverTransactions(h.resolve("game-transactions"))
            recoverTransactions(h.resolve("game-transactions"))
            val committed =
                phase in listOf("promoted", "ownership-published", "journal-deleted") ||
                    (!remove && phase == "new-rename-before-journal")
            if (committed) {
                assertEquals(!remove, t.exists())
                assertEquals("new", h.resolve("ownership").readText())
            } else {
                assertEquals(exists, t.exists())
                assertFalse(h.resolve("ownership").exists())
            }
        }
    }
}

object SecondInstanceWorker {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val h = Path.of(args[0])
        val s = RealModStore(h)
        val d =
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ): DownloadedFile = error("no net")
            }
        try {
            RealGameFolder(h, s, d, running = { false })
                .apply(GamePlan(args[1], "default", emptyMap(), false))
            error("Second process was allowed to enter the home")
        } catch (e: IllegalStateException) {
            check(e.message.orEmpty().contains("already open"))
            println("Second process refused by home lock")
        }
    }
}
