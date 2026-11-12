package nnomm.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.*
import java.util.zip.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*

class RealIoTest {
    private fun peFixture() =
        ByteArray(128).also {
            it[0] = 77
            it[1] = 90
            it[60] = 64
            it[64] = 80
            it[65] = 69
        }

    @Test
    fun dllExtensionAndMzAloneDoNotAuthorizeImport() = runBlocking {
        val home = temp()
        val file = home.resolve("fake.dll")
        file.writeText("MZnot-a-PE-file")
        assertFails { RealModStore(home).put("bad", "1", DownloadedFile(file.toString())) }
        Unit
    }

    private val temporary = mutableListOf<java.nio.file.Path>()

    @kotlin.test.AfterTest
    fun cleanupTemporaryFiles() {
        temporary.asReversed().forEach { nnomm.core.io.real.deleteTree(it) }
    }

    private fun temp() = Files.createTempDirectory("nnomm-test-").also { temporary.add(it) }

    @Test
    fun pathsArePrivate() {
        assertEquals(
            Path.of("/test/NNOMM"),
            settingsHome(mapOf("XDG_CONFIG_HOME" to "/test"), "Linux", "/user"),
        )
        assertEquals(
            Path.of("/override"),
            settingsHome(
                mapOf("NNOMM_HOME" to "/override", "APPDATA" to "/wrong"),
                "Windows 11",
                "/user",
            ),
        )
    }

    @Test
    fun persistenceRecoversBackupAndKeepsBadPrimary() = runBlocking {
        val home = temp()
        val warnings = mutableListOf<String>()
        val disk = RealPersistence(home) { warnings += it }
        val a =
            AppState(
                profiles = listOf(Profile("default", "Default"), Profile("p1", "Flying")),
                profileId = "p1",
            )
        disk.save(a)
        disk.save(a.copy(profileId = "default"))
        home.resolve("state.json").writeText("broken")
        assertEquals("p1", disk.load()!!.profileId)
        assertEquals("broken", home.resolve("state.json").readText())
        assertTrue(warnings.isNotEmpty())
        disk.save(a)
        assertTrue(Files.list(home).use { it.anyMatch { p -> p.name.contains(".broken-") } })
    }

    @Test
    fun localServerRegistryRedirectHeadDownloadAndOffline() = runBlocking {
        val home = temp()
        val bytes = "fake dll payload".toByteArray()
        var heads = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file.dll") { exchange ->
            exchange.responseHeaders.add("Last-Modified", "Wed, 11 Nov 2026 12:00:00 GMT")
            exchange.responseHeaders.add("Content-Length", bytes.size.toString())
            if (exchange.requestMethod == "HEAD") {
                heads++
                exchange.sendResponseHeaders(200, -1)
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.createContext("/redirect") {
            it.responseHeaders.add("Location", "/file.dll")
            it.sendResponseHeaders(302, -1)
            it.close()
        }
        server.createContext("/registry") { exchange ->
            val body =
                """[{"id":"test","artifacts":[{"version":"1","downloadUrl":"http://127.0.0.1:${server.address.port}/redirect"}],"isAiAssisted":"True","isClientOrServer":"Both"}]"""
                    .toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        val client = networkClient("test")
        try {
            val registry =
                RealRegistry(
                    client,
                    home,
                    { listOf("http://127.0.0.1:${server.address.port}/registry") },
                )
            val mods = registry.read()
            assertEquals(Side.BOTH, mods.single().side)
            registry.save(mods, 123)
            val meta = RealMetaSource(client, home)
            val m = mods.single()
            val v = m.versions.single()
            assertEquals(1, meta.metadata(m, v).sizeKb)
            assertNotNull(meta.metadata(m, v).dateMillis)
            assertEquals(1, heads)
            assertFalse(home.resolve("head.json").exists())
            meta.shutdown()
            assertTrue(home.resolve("head.json").exists())
            val cached = RealMetaSource(client, home)
            assertEquals(1, cached.metadata(m, v).sizeKb)
            assertEquals(1, heads)
            cached.shutdown()
            // Redirect target controls transfer metadata, not its source response.
            val downloader = RealDownloader(client, home)
            val payload =
                downloader.download(
                    m,
                    v.copy(downloadUrl = "http://127.0.0.1:${server.address.port}/file.dll"),
                ) {}
            assertContentEquals(bytes, Path.of(payload.path).readBytes())
            assertFailsWith<IllegalArgumentException> {
                downloader.download(m, v.copy(hash = "0".repeat(64))) {}
            }
            server.stop(0)
            assertEquals(123, registry.saved()!!.second)
        } finally {
            server.stop(0)
            client.close()
        }
    }

    @Test
    fun archiveTraversalCannotEscapeStore() = runBlocking {
        val home = temp()
        val file = home.resolve("evil.zip")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("../../escape.dll"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> {
            RealModStore(home).put("test", "1", DownloadedFile(file.toString()))
        }
        assertFalse(home.resolve("escape.dll").exists())
    }

    @Test
    fun takeoverApplyUndoAndUnmanagedFiles() = runBlocking {
        val home = temp()
        val game = temp()
        game.resolve("NuclearOption.exe").writeText("")
        val on = game.resolve("BepInEx/plugins/Existing")
        on.createDirectories()
        on.resolve("mod.dll").writeText("original")
        val off = game.resolve("BepInEx/disabledPlugins/Off")
        off.createDirectories()
        off.resolve("mod.dll").writeText("off")
        val mods = RealModStore(home)
        val downloader =
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ): DownloadedFile = error("No network")
            }
        var running = false
        val folder = RealGameFolder(home, mods, downloader, running = { running })
        val found = folder.takeOver(game.toString())
        assertEquals(2, found.size)
        assertEquals("original", on.resolve("mod.dll").readText())
        assertEquals("off", off.resolve("mod.dll").readText())
        val state =
            AppState(
                game = GameInfo(game.toString()),
                catalog = found.map { it.first },
                installed = found.associate { it.second.id to it.second },
            )
        val unknown = game.resolve("BepInEx/plugins/unknown.dll")
        unknown.writeText("private")
        running = true
        folder.apply(state.copy(installed = emptyMap()), "default")
        assertTrue(on.exists())
        running = false
        folder.apply(state.copy(installed = emptyMap()), "default")
        assertFalse(on.exists())
        assertTrue(unknown.exists())
        assertTrue(off.exists())
        folder.apply(state, "default")
        val id = found.first { it.second.profiles["default"]!!.on }.first.id
        assertEquals("original", game.resolve("BepInEx/plugins/Existing/mod.dll").readText())
        mods.remove(listOf(id))
        mods.restore(listOf(id))
        assertTrue(
            mods.version(id, found.first { it.first.id == id }.second.defaultVersion).exists()
        )
    }

    @Test
    fun cancellationDiscardsPartialDownload() = runBlocking {
        val home = temp()
        val started = CompletableDeferred<Unit>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow.dll") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 1024L * 1024)
                exchange.responseBody.use { out ->
                    repeat(1024) {
                        out.write(ByteArray(1024))
                        out.flush()
                        Thread.sleep(5)
                    }
                }
            } catch (_: Exception) {} finally {
                exchange.close()
            }
        }
        server.start()
        val client = networkClient("test")
        try {
            val job =
                launch(Dispatchers.IO) {
                    RealDownloader(client, home).download(
                        CatalogMod("test", "Test"),
                        ModVersion(
                            "1",
                            downloadUrl = "http://127.0.0.1:${server.address.port}/slow.dll",
                        ),
                    ) {
                        started.complete(Unit)
                    }
                }
            withTimeout(5000) { started.await() }
            job.cancelAndJoin()
            assertEquals(0, Files.list(home.resolve("staging")).use { it.count() })
        } finally {
            server.stop(0)
            client.close()
        }
    }

    @Test
    fun localHashRecognitionAndRetentionRespectPinnedProfiles() = runBlocking {
        val home = temp()
        val file = home.resolve("known.dll")
        file.writeBytes(peFixture())
        val hash =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        val mod =
            CatalogMod("known", "Known", versions = listOf(ModVersion("1", hash = "sha256:$hash")))
        val store = RealModStore(home) { listOf(mod) }
        val imported = store.addLocal(file.toString())
        assertEquals(ModSource.REGISTRY, imported.second.source)
        assertEquals("known", imported.first.id)
        store.put("known", "2", DownloadedFile(file.toString()))
        store.put("known", "3", DownloadedFile(file.toString()))
        val state =
            AppState(
                installed =
                    mapOf(
                        "known" to
                            InstalledMod(
                                "known",
                                versions = setOf("1", "2", "3"),
                                defaultVersion = "3",
                                profiles = mapOf("default" to ProfileMod(false, "1")),
                            )
                    ),
                settings = SettingsValues(mapOf("keepOld" to JsonPrimitive("None"))),
            )
        assertTrue(store.unused(state).isEmpty())
        store.clearUnused(state)
        assertTrue(store.version("known", "1").exists())
        assertTrue(store.version("known", "2").exists())
        assertTrue(store.version("known", "3").exists())
    }

    @Test
    fun sevenZipExtractsAndKeepsImportFile() = runBlocking {
        val home = temp()
        val input = home.resolve("input.dll")
        input.writeText("7z mod")
        val archive = home.resolve("input.7z")
        org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(archive.toFile()).use { out ->
            val entry = out.createArchiveEntry(input.toFile(), "nested/mod.dll")
            out.putArchiveEntry(entry)
            out.write(input.readBytes())
            out.closeArchiveEntry()
        }
        val store = RealModStore(home)
        store.put("seven", "1", DownloadedFile(archive.toString()))
        assertEquals("7z mod", store.version("seven", "1").resolve("nested/mod.dll").readText())
        assertTrue(archive.exists())
    }

    @Test
    fun fakeGameRestoresVanillaIni() = runBlocking {
        val home = temp()
        val game = temp()
        game.resolve("NuclearOption.exe").writeText("")
        val ini = game.resolve("doorstop_config.ini")
        val original = "[General]\nenabled = true\n"
        ini.writeText(original)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val launcher =
                RealGameLauncher(home, scope, listOf("sh", "-c", "sleep 1"), detect = { false })
            launcher.start(AppState(game = GameInfo(game.toString())), true)
            assertTrue(ini.readText().contains("false"))
            withTimeout(5000) { launcher.running.first { !it } }
            assertEquals(original, ini.readText())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun atomMapsFullReleaseTagAndEscapedHtml() {
        val mod =
            CatalogMod(
                "test",
                "Test",
                versions =
                    listOf(
                        ModVersion(
                            "1.2",
                            downloadUrl =
                                "https://github.com/o/r/releases/download/Prefix-v1.2/mod.dll",
                        )
                    ),
            )
        val notes =
            parseAtom(
                """<feed><entry><link href="https://github.com/o/r/releases/tag/Prefix-v1.2"/><updated>2026-11-11T00:00:00Z</updated><content type="html">&lt;ul&gt;&lt;li&gt;Fixed &amp;amp; improved&lt;/li&gt;&lt;/ul&gt;</content></entry></feed>""",
                mod,
            )
        assertEquals("1.2", notes.single().version)
        assertTrue(notes.single().lines.contains("Fixed & improved"))
    }

    @Test
    fun packRoundTripAndLegacyPreview() = runBlocking {
        val home = temp()
        val client = networkClient("test")
        try {
            val packs = RealPacks(home, RealModStore(home), client)
            val pack = Pack("Friends", "A", "1", listOf(PackItem(PackKind.VER, "test", "1")))
            val path = home.resolve("friends.nommpack")
            packs.write(path, pack, AppState())
            packs.read(path).use { assertEquals(pack, it.pack) }
            val legacy = home.resolve("old.nomm.json")
            legacy.writeText("""[{"id":"test","version":"1"}]""")
            packs.read(legacy).use { assertEquals("test", it.pack.items.single().id) }
        } finally {
            client.close()
        }
    }

    @Test
    fun movingStoreKeepsOriginalAndUsesDestination() = runBlocking {
        val home = temp()
        val store = RealModStore(home)
        val input = home.resolve("payload.dll")
        input.writeBytes(peFixture())
        store.put("m", "1", DownloadedFile(input.toString()))
        val original = store.version("m", "1")
        val target = home.resolve("moved")
        store.moveTo(target.toString())
        assertTrue(original.resolve("payload.dll").exists())
        assertEquals(target.resolve("m/1"), store.version("m", "1"))
        assertContentEquals(
            input.readBytes(),
            store.version("m", "1").resolve("payload.dll").readBytes(),
        )
        assertTrue(input.exists())
    }

    @Test
    fun loaderRemovalPreservesUnownedFilesAndRecovery() = runBlocking {
        val home = temp()
        Files.createDirectories(home.resolve("staging"))
        val game = home.resolve("game")
        Files.createDirectories(game)
        game.resolve("NuclearOption.exe").writeText("")
        game.resolve("doorstop_config.ini").writeText("player settings")
        val archive = home.resolve("staging/download/loader.zip")
        Files.createDirectories(archive.parent)
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            for ((name, text) in
                mapOf(
                    "BepInEx/core/BepInEx.dll" to "loader",
                    "doorstop_config.ini" to "upstream defaults",
                    "winhttp.dll" to "proxy",
                )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        val dl =
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ) = DownloadedFile(archive.toString())
            }
        val folder = RealGameFolder(home, RealModStore(home), dl, running = { false })
        folder.installLoader(game.toString())
        assertTrue(game.resolve("BepInEx/core/BepInEx.dll").exists())
        folder.removeLoader(game.toString())
        assertFalse(game.resolve("BepInEx/core/BepInEx.dll").exists())
        assertFalse(game.resolve("winhttp.dll").exists())
        assertEquals("player settings", game.resolve("doorstop_config.ini").readText())
        assertTrue(
            Files.walk(game.resolve(".nnomm/recovery")).use { paths ->
                paths.anyMatch { it.fileName.toString() == "BepInEx.dll" }
            }
        )
    }

    @Test
    fun repairRestoresDoorstopKeyWithoutOverwritingUnownedLoaderFiles() = runBlocking {
        for (adopted in listOf(false, true)) for (arbitraryBytes in listOf(false, true)) {
            val home = temp()
            Files.createDirectories(home.resolve("staging"))
            val game = home.resolve("game")
            Files.createDirectories(game)
            game.resolve("NuclearOption.exe").writeText("")
            val archive = home.resolve("loader.zip")
            val dll = "5.4.23.4".toByteArray(Charsets.UTF_16LE)
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                for ((name, bytes) in
                    mapOf(
                        "BepInEx/core/BepInEx.dll" to dll,
                        "doorstop_config.ini" to
                            "[General]\nenabled=true\ntarget_assembly=shipped.dll\n".toByteArray(),
                        "winhttp.dll" to "proxy".toByteArray(),
                    )) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val dl =
                object : Downloader {
                    override suspend fun download(
                        mod: CatalogMod,
                        version: ModVersion,
                        progress: (Int) -> Unit,
                    ) = DownloadedFile(archive.toString())
                }
            val folder = RealGameFolder(home, RealModStore(home), dl, running = { false })
            if (adopted) {
                Files.createDirectories(game.resolve("BepInEx/core"))
                game.resolve("BepInEx/core/BepInEx.dll").writeBytes(dll + byteArrayOf(0, 0))
                game.resolve("winhttp.dll").writeText("player proxy")
            } else folder.installLoader(game.toString())
            val old =
                "[UnityDoorstop]\n# player settings\ntargetAssembly=custom.dll\n[Other]\nplayer=true\nenabled=true\n"
            val oldBytes =
                if (arbitraryBytes) byteArrayOf(0xC3.toByte(), 0x28) else old.toByteArray()
            game.resolve("doorstop_config.ini").writeBytes(oldBytes)
            game.resolve("BepInEx/core/player.txt").writeText("player file")
            val beforeDll = game.resolve("BepInEx/core/BepInEx.dll").readBytes()
            folder.repairLoader(game.toString())
            // Neither a legacy-only file nor arbitrary bytes supplies Doorstop 4's target.
            // Repair replaces these with the shipped defaults and retains the original below.
            assertContentEquals(
                "[General]\nenabled=true\ntarget_assembly=shipped.dll\n".toByteArray(),
                game.resolve("doorstop_config.ini").readBytes(),
            )
            assertEquals("player file", game.resolve("BepInEx/core/player.txt").readText())
            assertContentEquals(beforeDll, game.resolve("BepInEx/core/BepInEx.dll").readBytes())
            if (adopted) assertEquals("player proxy", game.resolve("winhttp.dll").readText())
            assertTrue(
                Files.walk(game.resolve(".nnomm/recovery")).use { paths ->
                    paths.anyMatch {
                        it.isRegularFile() &&
                            runCatching { it.readBytes().contentEquals(oldBytes) }
                                .getOrDefault(false)
                    }
                }
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val launcher =
                    RealGameLauncher(home, scope, fakeCommand = listOf("true"), detect = { false })
                launcher.start(
                    nnomm.core.demo.Demo.state().copy(game = folder.inspect(game.toString())),
                    true,
                )
                withTimeout(5000) {
                    while (
                        game.resolve("doorstop_config.ini").readText().contains("enabled = false")
                    ) delay(10)
                }
            } finally {
                scope.cancel()
            }
            val result = folder.removeLoader(game.toString())
            assertTrue(result.kept)
            assertTrue(if (adopted) result.removed == 0 else result.removed > 0)
            assertEquals("player file", game.resolve("BepInEx/core/player.txt").readText())
        }
    }
}
