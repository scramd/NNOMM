package nnomm.core

import java.nio.file.*
import java.util.zip.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.*
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.store.loaderFailureHelp

class DoorstopRepairOrderTest {
    private val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    private val shipped =
        "[General]\nenabled=true\ntarget_assembly=BepInEx\\core\\BepInEx.Preloader.dll\n"
            .toByteArray()
    private val roots = mutableListOf<Path>()

    private fun temp() = Files.createTempDirectory("doorstop-spec-").also { roots += listOf(it) }

    @AfterTest
    fun clean() {
        roots.forEach { it.toFile().deleteRecursively() }
    }

    private fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun utf16(text: String, le: Boolean = true) =
        (if (le) byteArrayOf(0xff.toByte(), 0xfe.toByte())
        else byteArrayOf(0xfe.toByte(), 0xff.toByte())) +
            text.toByteArray(if (le) Charsets.UTF_16LE else Charsets.UTF_16BE)

    private data class Fixture(
        val home: Path,
        val game: Path,
        val folder: RealGameFolder,
        val notices: List<String>,
    )

    private fun fixture(original: ByteArray, locked: Boolean = false): Fixture {
        val home = temp()
        home.resolve("staging").createDirectories()
        val game = home.resolve("game").createDirectories()
        game.resolve("NuclearOption.exe").writeText("")
        game.resolve("BepInEx/core").createDirectories()
        val dll = "5.4.23.4".toByteArray(Charsets.UTF_16LE)
        game.resolve("BepInEx/core/BepInEx.dll").writeBytes(dll)
        game.resolve("winhttp.dll").writeText("player proxy")
        game.resolve("doorstop_config.ini").writeBytes(original)
        val archive = home.resolve("loader.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            for ((name, data) in
                mapOf(
                    "BepInEx/core/BepInEx.dll" to dll,
                    "winhttp.dll" to bytes("shipped proxy"),
                    "doorstop_config.ini" to shipped,
                )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        val downloader =
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ) = DownloadedFile(archive.toString())
            }
        val notices = mutableListOf<String>()
        return Fixture(
            home,
            game,
            RealGameFolder(
                home,
                RealModStore(home),
                downloader,
                running = { false },
                notice = { notices += it },
                readDoorstop = {
                    if (locked)
                        throw FileSystemException(
                            it.fileName.toString(),
                            null,
                            "used by another process",
                        )
                    Files.readAllBytes(it)
                },
            ),
            notices,
        )
    }

    @Test
    fun missingIniMustCommitBeforeOwnedFiles() = runBlocking {
        val f = fixture(bytes("[General]\nenabled=false\ntarget_assembly=x\n"))
        val ini = f.game.resolve("doorstop_config.ini")
        Files.delete(ini)
        // Put INI first in the ZIP; tmpfs enumerates the later-created binaries first.
        ZipOutputStream(Files.newOutputStream(f.home.resolve("loader.zip"))).use { zip ->
            for ((name, data) in
                linkedMapOf(
                    "doorstop_config.ini" to shipped,
                    "BepInEx/core/BepInEx.dll" to "5.4.23.4".toByteArray(Charsets.UTF_16LE),
                    "winhttp.dll" to bytes("shipped proxy"),
                )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        JsonFile(
                f.home.resolve("loader-${Integer.toHexString(f.game.toString().hashCode())}.json"),
                ListSerializer(String.serializer()),
            )
            .write(listOf("BepInEx/core/BepInEx.dll", "winhttp.dll", "doorstop_config.ini"))
        val moves = mutableListOf<Path>()
        DiskFaults.beforeMove = {
            moves.add(it)
            if (it == ini) throw AccessDeniedException(it.toString())
        }
        val error =
            try {
                runCatching { f.folder.repairLoader(f.game.toString()) }.exceptionOrNull()
            } finally {
                DiskFaults.beforeMove = {}
            }
        println(
            "MISSING: moveOrder=${moves.map { it.fileName }} error=${error?.javaClass?.simpleName} proxy=${f.game.resolve("winhttp.dll").readText()} iniExists=${ini.exists()} notices=${f.notices}"
        )
        assertNotNull(error)
        assertEquals(
            "player proxy",
            f.game.resolve("winhttp.dll").readText(),
            "Failed INI placement must precede promotion of owned loader files",
        )
        assertEquals(listOf(ini), moves)
    }

    @Test
    fun postCommitUnreadableIniHasNoSuccessNotice() = runBlocking {
        val f = fixture(bytes("[General]\ntarget_assembly=\n"))
        val ini = f.game.resolve("doorstop_config.ini")
        val dl =
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ) = DownloadedFile(f.home.resolve("loader.zip").toString())
            }
        var reads = 0
        val notices = mutableListOf<String>()
        val folder =
            RealGameFolder(
                f.home,
                RealModStore(f.home),
                dl,
                running = { false },
                notice = { notices.add(it) },
                readDoorstop = {
                    if (++reads > 1) throw AccessDeniedException(it.toString())
                    Files.readAllBytes(it)
                },
            )
        val error =
            assertFailsWith<DoorstopSettingsFailure> { folder.repairLoader(f.game.toString()) }
        assertTrue(loaderFailureHelp(error).contains("Close programs"))
        assertTrue(notices.isEmpty())
    }

    @Test
    fun independentEditorMatrix() = runBlocking {
        for (nl in listOf("\r\n", "\n", "\r")) {
            val text =
                listOf(
                        "# café …",
                        "[UnityDoorstop]",
                        "enabled=true",
                        "[ General ] ; c",
                        "# comment\u0085enabled=true",
                        "target_assembly=x",
                        "[General]",
                        "enabled=true",
                        "",
                    )
                    .joinToString(nl)
            val expected =
                text
                    .replace(
                        "[UnityDoorstop]${nl}enabled=true",
                        "[UnityDoorstop]${nl}enabled= false",
                    )
                    .replace("[ General ] ; c$nl", "[ General ] ; c${nl}enabled = false$nl")
            val originalBytes =
                listOf(
                    text.toByteArray(charset("windows-1252")),
                    bom + text.toByteArray(),
                    utf16(text),
                    utf16(text, false),
                )
            val expectedBytes =
                listOf(
                    expected.toByteArray(charset("windows-1252")),
                    bom + expected.toByteArray(),
                    utf16(expected),
                    utf16(expected, false),
                )
            for ((index, original) in originalBytes.withIndex()) {
                assertContentEquals(
                    expectedBytes[index],
                    DoorstopIni.read(original).setEnabled(false, true),
                )
                val home = temp()
                val game = temp()
                game.resolve("NuclearOption.exe").writeText("")
                val ini = game.resolve("doorstop_config.ini").apply { writeBytes(original) }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val launcher =
                        RealGameLauncher(
                            home,
                            scope,
                            listOf("sh", "-c", "sleep 0.1"),
                            detect = { false },
                        )
                    launcher.start(AppState(game = GameInfo(game.toString())), true)
                    assertContentEquals(expectedBytes[index], ini.readBytes())
                    withTimeout(5000) { launcher.running.first { !it } }
                    assertContentEquals(original, ini.readBytes())
                } finally {
                    scope.coroutineContext[Job]!!.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun oldNoncanonicalRecordKeyMatchesAfterReturn() {
        val home = temp()
        val root = temp()
        val library = root.resolve("library").createDirectories()
        val game = library.resolve("game").createDirectories()
        val alias = Files.createSymbolicLink(root.resolve("alias"), library)
        Files.move(library, root.resolve("away"))
        val record =
            mapOf(
                "path" to alias.resolve("game/doorstop_config.ini").toString(),
                "text" to "original",
            )
        DoorstopRestores(home).write(alias.resolve("game"), record)
        Files.move(root.resolve("away"), library)
        assertEquals(record, DoorstopRestores(home).read(game))
        DoorstopRestores(home).clear(game)
        assertTrue(DoorstopRestores(home).records().isEmpty())
    }
}
