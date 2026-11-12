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
import nnomm.core.store.loaderFailureHelp

class LoaderPartialPlacementTest {
    private val shipped =
        "[General]\nenabled=true\ntarget_assembly=BepInEx\\core\\BepInEx.Preloader.dll\n"
            .toByteArray()
    private val dll = "5.4.23.4".toByteArray(Charsets.UTF_16LE)
    private val roots = mutableListOf<Path>()

    private fun temp() = Files.createTempDirectory("loader-partial-").also { roots += listOf(it) }

    @AfterTest
    fun clean() {
        DiskFaults.beforeMove = {}
        DiskFaults.step = {}
        roots.forEach { it.toFile().deleteRecursively() }
    }

    private fun zip(archive: Path, entries: List<Pair<String, ByteArray>>) =
        ZipOutputStream(Files.newOutputStream(archive)).use { z ->
            for ((n, d) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(d)
                z.closeEntry()
            }
        }

    private class F(
        val home: Path,
        val game: Path,
        val folder: RealGameFolder,
        val notices: MutableList<String>,
    )

    private fun fixture(withLoader: Boolean, ini: ByteArray?, order: List<String>): F {
        val home = temp()
        home.resolve("staging").createDirectories()
        val game = home.resolve("game").createDirectories()
        game.resolve("NuclearOption.exe").writeText("")
        if (withLoader) {
            game.resolve("BepInEx/core").createDirectories()
            game.resolve("BepInEx/core/BepInEx.dll").writeBytes(dll)
            game.resolve("winhttp.dll").writeText("player proxy")
        }
        if (ini != null) game.resolve("doorstop_config.ini").writeBytes(ini)
        val data =
            mapOf(
                "BepInEx/core/BepInEx.dll" to dll,
                "winhttp.dll" to "shipped proxy".toByteArray(),
                "doorstop_config.ini" to shipped,
                "BepInEx/core/Extra.dll" to "extra".toByteArray(),
            )
        val archive = home.resolve("loader.zip")
        zip(archive, order.map { it to data.getValue(it) })
        val dl =
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ) = DownloadedFile(archive.toString())
            }
        val notices = mutableListOf<String>()
        return F(
            home,
            game,
            RealGameFolder(
                home,
                RealModStore(home),
                dl,
                running = { false },
                notice = { notices += it },
            ),
            notices,
        )
    }

    private fun record(f: F) =
        JsonFile(
            f.home.resolve("loader-${Integer.toHexString(f.game.toString().hashCode())}.json"),
            ListSerializer(String.serializer()),
        )

    private val orders =
        listOf(
            listOf(
                "BepInEx/core/BepInEx.dll",
                "winhttp.dll",
                "BepInEx/core/Extra.dll",
                "doorstop_config.ini",
            ),
            listOf(
                "doorstop_config.ini",
                "BepInEx/core/BepInEx.dll",
                "winhttp.dll",
                "BepInEx/core/Extra.dll",
            ),
            listOf(
                "winhttp.dll",
                "doorstop_config.ini",
                "BepInEx/core/Extra.dll",
                "BepInEx/core/BepInEx.dll",
            ),
        )

    private fun assertRecordedPlacements(f: F, previous: List<String> = emptyList()) {
        val placed =
            listOf(
                    "doorstop_config.ini",
                    "BepInEx/core/BepInEx.dll",
                    "winhttp.dll",
                    "BepInEx/core/Extra.dll",
                )
                .filter { relative ->
                    val path = f.game.resolve(relative)
                    val expected =
                        when (relative) {
                            "doorstop_config.ini" -> shipped
                            "BepInEx/core/BepInEx.dll" -> dll
                            "winhttp.dll" -> "shipped proxy".toByteArray()
                            else -> "extra".toByteArray()
                        }
                    path.isRegularFile() && path.readBytes().contentEquals(expected)
                }
        assertEquals((previous + placed).toSet(), record(f).read()!!.toSet())
    }

    // Assertion ports of VerifyTwoProbeTest's laterOwnedMoveFailsAfterIni and
    // installLaterMoveFails, with Retry and Uninstall checks for partial ownership.
    @Test
    fun laterOwnedMoveFailsAfterIni() = runBlocking {
        for (order in orders) for (missing in listOf(true, false)) {
            val f =
                fixture(
                    true,
                    if (missing) null else "[General]\ntarget_assembly=\n".toByteArray(),
                    order,
                )
            val previous = listOf("BepInEx/core/BepInEx.dll", "winhttp.dll", "doorstop_config.ini")
            record(f).write(previous)
            val proxy = f.game.resolve("winhttp.dll")
            DiskFaults.beforeMove = { if (it == proxy) throw AccessDeniedException(it.toString()) }
            val failure = assertFails { f.folder.repairLoader(f.game.toString()) }
            DiskFaults.beforeMove = {}
            assertTrue(loaderFailureHelp(failure).contains("close programs"))
            assertFalse(loaderFailureHelp(failure).contains("connection"))
            assertContentEquals(shipped, f.game.resolve("doorstop_config.ini").readBytes())
            assertEquals("player proxy", proxy.readText())
            assertTrue(f.notices.isEmpty(), f.notices.toString())
            assertRecordedPlacements(f, previous)
            f.folder.repairLoader(f.game.toString())
            assertEquals("shipped proxy", proxy.readText())
            assertRecordedPlacements(f, previous)
        }
    }

    @Test
    fun installLaterMoveFailsAndRetryKeepsOwnership() = runBlocking {
        for (order in orders) {
            val f = fixture(false, null, order)
            val target = f.game.resolve("BepInEx/core/BepInEx.dll")
            DiskFaults.beforeMove = { if (it == target) throw AccessDeniedException(it.toString()) }
            val failure = assertFails { f.folder.installLoader(f.game.toString()) }
            DiskFaults.beforeMove = {}
            assertTrue(loaderFailureHelp(failure).contains("close programs"))
            assertFalse(f.folder.inspect(f.game.toString()).loaderPresent)
            assertTrue(f.notices.isEmpty())
            assertRecordedPlacements(f)
            f.folder.installLoader(f.game.toString())
            assertTrue(f.folder.inspect(f.game.toString()).loaderPresent)
            assertRecordedPlacements(f)
            assertEquals(4, record(f).read()!!.size)
        }
    }

    @Test
    fun uninstallRemovesPartialInstallButKeepsPlayerFiles() = runBlocking {
        for (order in orders) {
            val f = fixture(false, null, order)
            val target = f.game.resolve("BepInEx/core/BepInEx.dll")
            val player = f.game.resolve("player.txt").apply { writeText("keep me") }
            DiskFaults.beforeMove = { if (it == target) throw AccessDeniedException(it.toString()) }
            assertFails { f.folder.installLoader(f.game.toString()) }
            DiskFaults.beforeMove = {}
            assertRecordedPlacements(f)
            val placed = record(f).read()!!
            assertTrue(placed.isNotEmpty())
            f.folder.removeLoader(f.game.toString())
            placed.forEach { assertFalse(f.game.resolve(it).exists(), it) }
            assertEquals("keep me", player.readText())
            assertTrue(f.game.resolve("NuclearOption.exe").exists())
            assertTrue(record(f).read()!!.isEmpty())
        }
    }

    @Test
    fun partialLoaderOperationsOfferRetryWithoutSuccessToasts() = runBlocking {
        for (repair in listOf(false, true)) {
            val f =
                fixture(
                    repair,
                    if (repair) "[General]\ntarget_assembly=\n".toByteArray() else null,
                    orders[0],
                )
            if (repair) record(f).write(listOf("BepInEx/core/BepInEx.dll", "winhttp.dll"))
            val target = f.game.resolve(if (repair) "winhttp.dll" else "BepInEx/core/BepInEx.dll")
            val io = Demo.io()
            val app =
                AppStore(
                    Demo.state().copy(game = f.folder.inspect(f.game.toString())),
                    io.copy(gameFolder = f.folder, persistence = null),
                )
            DiskFaults.beforeMove = { if (it == target) throw AccessDeniedException(it.toString()) }
            try {
                if (repair) app.repairLoader() else app.play()
                withTimeout(5000) {
                    while (
                        app.state.value.toasts.none {
                            it.action == "Retry" && it.text.contains("mod loader")
                        }
                    ) delay(10)
                }
                val toasts = app.state.value.toasts
                val failure = toasts.last { it.action == "Retry" }
                assertTrue(failure.text.contains("close programs"), failure.text)
                assertTrue(
                    toasts.none {
                        it.text.contains("Repair finished") ||
                            it.text.contains("Installed the mod loader") ||
                            it.text.contains("Replaced the damaged")
                    }
                )
                assertTrue(f.notices.isEmpty())
                assertTrue("doorstop_config.ini" in record(f).read()!!)
            } finally {
                app.shutdown()
                DiskFaults.beforeMove = {}
            }
        }
    }

    @Test
    fun verifiesBinaryBytesBeforePublishingOwnershipAndSuccess() = runBlocking {
        val f = fixture(false, null, orders[0])
        val proxy = f.game.resolve("winhttp.dll")
        DiskFaults.step = { if (it == "ownership-published") proxy.writeText("different bytes") }
        val failure = assertFails { f.folder.installLoader(f.game.toString()) }
        DiskFaults.step = {}
        assertTrue(loaderFailureHelp(failure).contains("close programs"))
        assertTrue(f.notices.isEmpty())
        assertRecordedPlacements(f)
        assertFalse("winhttp.dll" in record(f).read()!!)
        f.folder.removeLoader(f.game.toString())
        assertEquals("different bytes", proxy.readText())
    }
}
