package nnomm.core

import java.nio.file.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.store.AppStore

class FollowupSafetyProbeTest {
    private val temporary = mutableListOf<Path>()

    private fun temp() =
        Files.createTempDirectory("nnomm-followup-").also { temporary += listOf(it) }

    @AfterTest
    fun cleanup() {
        temporary.asReversed().forEach(::deleteTree)
    }

    private val noNet =
        object : Downloader {
            override suspend fun download(
                mod: CatalogMod,
                version: ModVersion,
                progress: (Int) -> Unit,
            ): DownloadedFile = error("network forbidden")
        }

    private suspend fun payload(s: RealModStore, h: Path, id: String) {
        val d = Files.createTempDirectory(h, "input-")
        d.resolve("mod.dll").writeText("good")
        s.put(id, "1", DownloadedFile(d.toString()))
    }

    @Test
    fun collidingFolderNamesCannotShareAdoptionAuthority() = runBlocking {
        val h = temp()
        val base = temp()
        val a = base.resolve("Aa").createDirectories()
        val b = base.resolve("BB")
        a.resolve("NuclearOption.exe").writeText("")
        val store = RealModStore(h)
        payload(store, h, "m")
        val folder = RealGameFolder(h, store, noNet, running = { false })
        folder.apply(GamePlan(a.toString(), "default", mapOf("m" to "1"), false))
        copyTree(a, b)
        assertEquals(a.toString().hashCode(), b.toString().hashCode())
        folder.apply(GamePlan(b.toString(), "default", emptyMap(), false))
        assertTrue(b.resolve("BepInEx/plugins/m/mod.dll").exists())
    }

    @Test
    fun recoveryMustPreserveFilesWrittenAfterInterruptedPromotion() {
        val h = temp()
        val target = h.resolve("game/BepInEx").createDirectories()
        target.resolve("old").writeText("old")
        val src = h.resolve("input").createDirectories()
        src.resolve("new").writeText("new")
        DiskFaults.step = { if (it == "promoted") throw DiskCrash() }
        try {
            assertFails { replaceTree(target, src, h.resolve("transactions")) }
        } finally {
            DiskFaults.step = {}
        }
        target.resolve("player-save").writeText("unique-player-data")
        recoverTransactions(h.resolve("transactions"))
        assertTrue(
            Files.walk(h).use {
                it.filter { p -> p.isRegularFile() }
                    .anyMatch { p -> p.readText() == "unique-player-data" }
            },
            "Recovery permanently deleted the player's only copy",
        )
    }

    @Test
    fun inspectWhileRunningMustNotRollBackGameTree() = runBlocking {
        val h = temp()
        val g = temp()
        g.resolve("NuclearOption.exe").writeText("")
        val s = RealModStore(h)
        val target = g.resolve("BepInEx").createDirectories()
        target.resolve("old").writeText("old")
        val src = h.resolve("input").createDirectories()
        src.resolve("new").writeText("new")
        DiskFaults.step = { if (it == "promoted") throw DiskCrash() }
        try {
            assertFails { replaceTree(target, src, h.resolve("transactions")) }
        } finally {
            DiskFaults.step = {}
        }
        RealGameFolder(h, s, noNet, running = { true }).inspect(g.toString())
        assertTrue(target.resolve("new").exists(), "Inspect rolled back files while the game runs")
    }

    @Test
    fun addonPlayerFilesMustReturnWithProfile() = runBlocking {
        val h = temp()
        val g = temp()
        g.resolve("NuclearOption.exe").writeText("")
        val s = RealModStore(h)
        payload(s, h, "parent")
        payload(s, h, "child")
        val cat =
            listOf(
                CatalogMod("parent", "Parent", versions = listOf(ModVersion("1"))),
                CatalogMod(
                    "child",
                    "Child",
                    versions = listOf(ModVersion("1", extends = PackageReference("parent", "1"))),
                ),
            )
        val f =
            RealGameFolder(
                h,
                s,
                noNet,
                catalog = { cat },
                running = { false },
                notice = { println(it) },
            )
        val on =
            GamePlan(
                g.toString(),
                "default",
                mapOf("parent" to "1", "child" to "1"),
                false,
                setOf("parent", "child"),
            )
        f.apply(on)
        val extra = g.resolve("BepInEx/plugins/parent/addons/child/save.dat")
        extra.writeText("player")
        f.apply(on.copy(profileId = "other", versions = emptyMap()))
        f.apply(on)
        assertTrue(
            extra.exists(),
            "Child extras vanished because parent was removed before child capture",
        )
    }

    @Test
    fun staleOwnershipWithUnrelatedSavedStateMustNotAuthorizeRemoval() = runBlocking {
        val h = temp()
        val g = temp()
        g.resolve("NuclearOption.exe").writeText("")
        val s = RealModStore(h)
        payload(s, h, "m")
        RealGameFolder(h, s, noNet, running = { false })
            .apply(GamePlan(g.toString(), "default", mapOf("m" to "1"), false, setOf("m")))
        val fresh = temp()
        val other = RealModStore(fresh)
        payload(other, fresh, "b")
        RealPersistence(fresh)
            .save(
                AppState(
                    installed =
                        mapOf("b" to InstalledMod("b", versions = setOf("1"), defaultVersion = "1"))
                )
            )
        RealGameFolder(fresh, other, noNet, running = { false })
            .apply(GamePlan(g.toString(), "default", mapOf("b" to "1"), false, setOf("b")))
        assertTrue(
            g.resolve("BepInEx/plugins/m/mod.dll").exists(),
            "Unrelated home state authorized deletion of foreign managed mod",
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun pendingVanillaRestoreMustRunAfterAlreadyRunningGameExits() =
        kotlinx.coroutines.test.runTest {
            val h = temp()
            val ini = h.resolve("doorstop_config.ini")
            ini.writeText("enabled = false")
            atomic(
                h.resolve("doorstop-restore.json"),
                """{"path":"$ini","text":"enabled = true"}""",
            )
            var running = true
            RealGameLauncher(h, backgroundScope, detect = { running })
            runCurrent()
            running = false
            advanceTimeBy(3001)
            runCurrent()
            assertEquals(
                "enabled = true",
                ini.readText(),
                "Reopening during vanilla game leaves pending restore ignored after exit",
            )
        }

    @Test
    fun redirectsMustKeepHttpsPolicy() = runBlocking {
        val h = temp()
        val server =
            com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("0.0.0.0", 0), 0)
        server.createContext("/start") { ex ->
            ex.responseHeaders.add("Location", "http://127.0.0.2:${server.address.port}/end")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/end") { ex ->
            ex.sendResponseHeaders(200, 2)
            ex.responseBody.use { it.write(byteArrayOf(77, 90)) }
            ex.close()
        }
        server.start()
        val client = networkClient("test")
        try {
            assertFails { checkedUrl("http://127.0.0.2:${server.address.port}/end") }
            val result = runCatching {
                RealDownloader(client, h).download(
                    CatalogMod("m", "M"),
                    ModVersion(
                        "1",
                        downloadUrl = "http://127.0.0.1:${server.address.port}/start",
                        fileName = "m.dll",
                    ),
                ) {}
            }
            result.getOrNull()?.close()
            assertTrue(result.isFailure, "Redirect bypassed address policy")
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
