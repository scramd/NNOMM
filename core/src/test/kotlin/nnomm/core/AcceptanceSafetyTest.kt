package nnomm.core

import java.nio.file.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.core.settings.defaultSettings
import nnomm.core.store.AppStore

class AcceptanceSafetyTest {
    private val root = Files.createTempDirectory("safety-acceptance-")
    private val noNet =
        object : Downloader {
            override suspend fun download(
                mod: CatalogMod,
                version: ModVersion,
                progress: (Int) -> Unit,
            ): DownloadedFile = error("no network")
        }

    @AfterTest
    fun cleanup() {
        DiskFaults.step = {}
        deleteTree(root)
    }

    private fun game() =
        root.resolve("game").createDirectories().also {
            it.resolve("NuclearOption.exe").writeText("")
        }

    private fun settings() =
        defaultSettings().let {
            it.copy(
                general =
                    it.general +
                        mapOf(
                            "sources" to JsonPrimitive(""),
                            "checkUpdates" to JsonPrimitive("Only when I ask"),
                        )
            )
        }

    @Test
    fun editedShippedFileReturnsAfterVersionRoundTrip() = runBlocking {
        val g = game()
        val home = root.resolve("home")
        val store = RealModStore(home)
        val payload = root.resolve("payload").createDirectories()
        payload.resolve("a.dll").writeText("version-one")
        store.put("a", "1", DownloadedFile(payload.toString()))
        payload.resolve("a.dll").writeText("version-two")
        store.put("a", "2", DownloadedFile(payload.toString()))
        val f = RealGameFolder(home, store, noNet, running = { false })
        val one = GamePlan(g.toString(), "default", mapOf("a" to "1"), false)
        f.apply(one)
        val file = g.resolve("BepInEx/plugins/a/a.dll")
        file.writeText("PLAYER EDIT OF ONE")
        f.apply(one.copy(versions = mapOf("a" to "2")))
        assertEquals("version-two", file.readText())
        f.apply(one)
        assertEquals("PLAYER EDIT OF ONE", file.readText())
    }

    @Test
    fun clearKeepsVersionsTheRegistryCannotDeliverAgain() = runBlocking {
        val home = root.resolve("home")
        val catalog =
            listOf(
                CatalogMod(
                    "reg",
                    "Registry mod",
                    versions = listOf(ModVersion("1", downloadUrl = "https://example.org/reg.zip")),
                )
            )
        val store = RealModStore(home, null) { catalog }
        for ((id, version) in
            listOf("reg" to "1", "local-file-abc" to "abc", "taken" to "takeover")) {
            val source = root.resolve("payload-$id").createDirectories()
            source.resolve("$id.dll").writeText("bytes of $id")
            store.put(id, version, DownloadedFile(source.toString()))
        }
        // None of the three is installed any more; wait out the Undo window.
        Thread.sleep(9500)
        store.clearUnused(AppState())
        val mods = home.resolve("mods")
        assertTrue(
            mods.resolve("reg/1").exists(),
            "a registry identity without verified provenance must stay",
        )
        assertTrue(mods.resolve("local-file-abc/abc").exists(), "a local file is the only copy")
        assertTrue(mods.resolve("taken/takeover").exists(), "a taken-over copy is the only copy")
    }

    @Test
    fun externalStoreRecoveryCannotCountItselfAsDuplicate() = runBlocking {
        val home = root.resolve("home")
        val external = root.resolve("external-store")
        val copy = external.resolve("mod/.nnomm/recovery/copy").createDirectories()
        copy.resolve("save.dat").writeText("only copy")
        Files.setLastModifiedTime(copy, java.nio.file.attribute.FileTime.fromMillis(1000))
        atomic(
            home.resolve("recovery/copy.json"),
            kotlinx.serialization.json
                .buildJsonObject { put("copies", JsonPrimitive(copy.toString())) }
                .toString(),
        )
        val store = RealModStore(home, external.toString())
        store.clearUnused(AppState())
        assertEquals("only copy", copy.resolve("save.dat").readText())
        assertEquals(9L, store.usedBytes())
    }

    @Test
    fun firstApplyOwnershipSurvivesCrashBeforeAdoptionPublication() = runBlocking {
        val g = game()
        val home = root.resolve("home")
        val store = RealModStore(home)
        val source = root.resolve("payload").createDirectories()
        source.resolve("a.dll").writeText("payload")
        store.put("a", "1", DownloadedFile(source.toString()))
        val f = RealGameFolder(home, store, noNet, running = { false })
        DiskFaults.step = {
            if (it == "promoted" && pendingTransactions(home.resolve("game-transactions")))
                throw DiskCrash()
        }
        assertFails { f.apply(GamePlan(g.toString(), "default", mapOf("a" to "1"), false)) }
        DiskFaults.step = {}
        f.recoverPending()
        f.apply(GamePlan(g.toString(), "default", emptyMap(), false))
        assertTrue(f.applySucceeded)
        assertFalse(g.resolve("BepInEx/plugins/a").exists())
    }

    @Test
    fun explicitForeignTakeoverSupersedesForeignIdentities() = runBlocking {
        val g = game()
        val a = root.resolve("home-a")
        val b = root.resolve("home-b")
        val store = RealModStore(a)
        val payload = root.resolve("payload").createDirectories()
        payload.resolve("mod.dll").writeText("original")
        store.put("registry-id", "1", DownloadedFile(payload.toString()))
        RealGameFolder(a, store, noNet, running = { false })
            .apply(GamePlan(g.toString(), "default", mapOf("registry-id" to "1"), false))
        val fresh = RealGameFolder(b, RealModStore(b), noNet, running = { false })
        fresh.apply(GamePlan(g.toString(), "default", emptyMap(), false))
        assertFalse(fresh.applySucceeded)
        val adopted = fresh.takeOver(g.toString()).single()
        assertTrue(adopted.first.id.startsWith("local-"))
        fresh.apply(GamePlan(g.toString(), "default", emptyMap(), false))
        assertTrue(fresh.applySucceeded)
        assertFalse(g.resolve("BepInEx/plugins/registry-id").exists())
    }

    @Test
    fun guidedTestSessionSurvivesNormalClose() = runBlocking {
        val p = RealPersistence(root)
        val original = Demo.state().copy(settings = settings())
        val app = AppStore(original, Demo.io().copy(persistence = p, settingsFile = p))
        app.startBrokenModTest()
        app.shutdown()
        val loaded = p.load()!!
        assertNotNull(loaded.trouble)
        assertEquals(
            original.installed.keys.filter { isOn(original, it) }.toSet(),
            loaded.trouble!!.saved.toSet(),
        )
        val resumed = AppStore(loaded, Demo.io().copy(persistence = p, settingsFile = p))
        resumed.stopBrokenModTest()
        resumed.shutdown()
        assertEquals(
            original.installed.mapValues { it.value.profiles },
            p.load()!!.installed.mapValues { it.value.profiles },
        )
    }

    @Test
    fun persistedGuidedTestSurvivesProcessHalt() = runBlocking {
        val process =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    "nnomm.core.GuidedCrashWorker",
                    root.toString(),
                )
                .redirectErrorStream(true)
                .redirectOutput(root.resolve("worker.log").toFile())
                .start()
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(23, process.exitValue())
        val loaded = RealPersistence(root).load()!!
        assertNotNull(loaded.trouble)
        assertEquals(
            loaded.trouble!!.saved.toSet(),
            restoreTrouble(loaded)
                .installed
                .keys
                .filter { isOn(restoreTrouble(loaded), it) }
                .toSet(),
        )
    }

    @Test
    fun runtimeHomeLockRejectsSecondOpenAndReleasesAfterShutdown() = runBlocking {
        RealPersistence(root).save(AppState(settings = settings()))
        val first = RealRuntime.open(root)
        try {
            assertFails { RealRuntime.open(root) }
        } finally {
            first.shutdown()
        }
        RealRuntime.open(root).shutdown()
    }

    @Test
    fun brokenHomeJournalIsSetAsideWithoutBlockingOtherRecoveryOrStartup() = runBlocking {
        val journals = root.resolve("transactions").createDirectories()
        journals.resolve("broken.txn").writeText("not json")
        val target = root.resolve("target").createDirectories()
        target.resolve("old").writeText("old")
        val source = root.resolve("source").createDirectories()
        source.resolve("new").writeText("new")
        DiskFaults.step = { if (it == "promoted") throw DiskCrash() }
        assertFails { replaceTree(target, source, journals) }
        DiskFaults.step = {}
        val notes = mutableListOf<String>()
        recoverTransactions(journals, notice = { notes += it })
        assertEquals("new", target.resolve("new").readText())
        assertTrue(notes.isNotEmpty())
        RealPersistence(root).save(AppState(settings = settings()))
        val app = RealRuntime.open(root, notice = { notes += it })
        try {
            assertFalse(app.store.state.value.gameIoPending)
            assertFalse(journals.resolve("broken.txn").exists())
            assertTrue(journals.resolve("damaged-records").isDirectory())
        } finally {
            app.shutdown()
        }
    }
}

object GuidedCrashWorker {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val p = RealPersistence(Path.of(args[0]))
        val next = startTrouble(Demo.state())
        p.save(next)
        Runtime.getRuntime().halt(23)
    }
}
