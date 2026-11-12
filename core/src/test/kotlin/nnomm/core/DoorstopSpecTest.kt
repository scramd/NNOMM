package nnomm.core

import java.nio.file.*
import java.util.Base64
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

class DoorstopSpecTest {
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

    private val launchCases
        get() =
            linkedMapOf(
                "crlf+bom+allHighBytes" to
                    (bom +
                        bytes("# c\r\n[General]\r\nenabled = true\r\ntarget_assembly=x.dll\r\n# ") +
                        ByteArray(128) { (128 + it).toByte() } +
                        bytes("\r\n[UnityMono]\r\ndebug_enabled=false\r\n")),
                "allByteValuesInComment" to
                    (bytes("[General]\nenabled=true\n#") +
                        ByteArray(256) { it.toByte() } +
                        bytes("\n")),
                "unityDoorstopThenGeneral" to
                    bytes("[UnityDoorstop]\nenabled=true\n[General]\nenabled=true\n"),
                "sectionless" to bytes("enabled=true\ntarget_assembly=x.dll\n"),
                "generalWithTrailingComment" to bytes("[General] ; doorstop 4\nenabled=true\n"),
                "generalWithInnerSpaces" to bytes("[ General ]\nenabled=true\n"),
                "utf16le" to utf16("[General]\r\nenabled = true\r\ntarget_assembly=x.dll\r\n"),
                "utf16be" to
                    utf16("[General]\r\nenabled = true\r\ntarget_assembly=x.dll\r\n", false),
                "missingKeyGeneral" to bytes("[General]\ntarget_assembly=x.dll\n"),
                "nelByte" to bytes("[General]\n# caf\u0085enabled = x\ntarget=y\n"),
                "loneCR" to bytes("[General]\renabled = true\r[UnityMono]\renabled = true\r"),
            )

    @Test
    fun launchProbesRestoreExactBytes() = runBlocking {
        for ((name, original) in launchCases) {
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
                        listOf("sh", "-c", "sleep 0.3"),
                        detect = { false },
                    )
                launcher.start(AppState(game = GameInfo(game.toString())), true)
                assertFalse(ini.readBytes().contentEquals(original), name)
                val record = DoorstopRestores(home).read(game)
                assertContentEquals(
                    original,
                    Base64.getDecoder().decode(record.getValue("bytes")),
                    name,
                )
                withTimeout(8000) { launcher.running.first { !it } }
                assertContentEquals(original, ini.readBytes(), name)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun editorChangesOnlyEffectiveLines() {
        val cases =
            mapOf(
                "[UnityDoorstop]\renabled=true\r[General]\renabled=true\r[Other]\renabled=true\r" to
                    "[UnityDoorstop]\renabled= false\r[General]\renabled= false\r[Other]\renabled=true\r",
                "enabled=true\rtarget_assembly=x\r" to
                    "[General]\renabled = false\renabled=true\rtarget_assembly=x\r",
                "[General] ; comment\nenabled=true\n" to "[General] ; comment\nenabled= false\n",
                "[ General ]\nenabled=true\n" to "[ General ]\nenabled= false\n",
                "[General]\n# caf\u0085enabled=x\ntarget=y\n" to
                    "[General]\nenabled = false\n# caf\u0085enabled=x\ntarget=y\n",
                "[GENERAL]\nenabled=true\n[General]\nenabled=true\n" to
                    "[GENERAL]\nenabled= false\n[General]\nenabled=true\n",
                "[General]" to "[General]\nenabled = false\n",
                "[General]\ntarget_assembly=x\n[UnityDoorstop]\nx=y\n" to
                    "[General]\nenabled = false\ntarget_assembly=x\n[UnityDoorstop]\nenabled = false\nx=y\n",
            )
        for ((before, after) in cases) assertContentEquals(
            bytes(after),
            DoorstopIni.read(bytes(before)).setEnabled(false, true),
        )
        assertContentEquals(
            bom + bytes("[General]\r\nenabled = false\r\nx=y\r\n"),
            DoorstopIni.read(bom + bytes("x=y\r\n")).setEnabled(false, true),
        )
        for (le in listOf(true, false)) assertContentEquals(
            utf16("[General]\renabled= false\r", le),
            DoorstopIni.read(utf16("[General]\renabled=true\r", le)).setEnabled(false, true),
        )
        assertFalse(DoorstopIni.read(bytes("enabled=true\n[Other]\nx=y")).hasEnabled())
    }

    @Test
    fun startupAndLaunchFailureRestoreBytesAndLegacyText() = runBlocking {
        for (legacy in listOf(false, true)) {
            val home = temp()
            val game = temp()
            val ini = game.resolve("doorstop_config.ini")
            val original =
                bom +
                    bytes("[General]\r\nenabled=true\r\n# ") +
                    ByteArray(128) { (128 + it).toByte() }
            ini.writeText("disabled")
            val record =
                mapOf("path" to ini.toString()) +
                    if (legacy) mapOf("text" to String(original, DoorstopIni.charset))
                    else mapOf("bytes" to Base64.getEncoder().encodeToString(original))
            val saved =
                JsonFile(
                    home.resolve("doorstop-restore.json"),
                    MapSerializer(String.serializer(), String.serializer()),
                )
            saved.write(record)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                RealGameLauncher(home, scope, detect = { true })
                assertEquals("disabled", ini.readText()) // startup while running must wait
                RealGameLauncher(home, scope, detect = { false })
                assertContentEquals(original, ini.readBytes())
                game.resolve("NuclearOption.exe").writeText("")
                val launcher =
                    RealGameLauncher(
                        home,
                        scope,
                        listOf(game.resolve("absent-command").toString()),
                        detect = { false },
                    )
                assertFailsWith<java.io.IOException> {
                    launcher.start(AppState(game = GameInfo(game.toString())), true)
                }
                assertContentEquals(original, ini.readBytes())
            } finally {
                scope.cancel()
            }
        }
    }

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
    fun repairProbesPreserveHealthyFilesAndRecoverDamagedFiles() = runBlocking {
        val cases =
            linkedMapOf(
                "utf16leWorking" to
                    (utf16("[General]\r\nenabled=true\r\ntarget_assembly=x.dll\r\n") to true),
                "generalWithTrailingComment" to
                    (bytes("[General] ; doorstop 4\nenabled=true\ntarget_assembly=x.dll\n") to
                        true),
                "sectionlessWithKey" to (bytes("enabled=true\ntarget_assembly=x.dll\n") to false),
                "sectionlessNoKey" to (bytes("target_assembly=x.dll\n") to false),
                "bomNoKey" to ((bom + bytes("[General]\r\ntarget_assembly=x.dll\r\n")) to true),
                "bomSectionless" to ((bom + bytes("target_assembly=x.dll\r\n")) to false),
                "unityDoorstopOnly" to
                    (bytes("[UnityDoorstop]\nenabled=true\ntargetAssembly=x.dll\n") to false),
                "otherSectionOnlyHasKey" to
                    (bytes("[General]\ntarget_assembly=x.dll\n[UnityMono]\nenabled=true\n") to
                        true),
                "cp1252" to
                    ("[General]\r\n# café …\r\ntarget_assembly=custom.dll\r\n"
                        .toByteArray(charset("windows-1252")) to true),
                "firstGeneralWins" to
                    (bytes("[General]\nenabled=true\n[General]\ntarget_assembly=x.dll\n") to false),
                "targetOnlyInOtherSection" to
                    (bytes("[General]\nenabled=true\n[Other]\ntarget_assembly=x.dll\n") to false),
            )
        for ((name, case) in cases) {
            val (original, healthy) = case
            val f = fixture(original)
            f.folder.repairLoader(f.game.toString())
            assertContentEquals(
                if (healthy) original else shipped,
                f.game.resolve("doorstop_config.ini").readBytes(),
                name,
            )
            assertEquals("player proxy", f.game.resolve("winhttp.dll").readText(), name)
            if (!healthy) {
                assertTrue(f.notices.any { "shipped defaults" in it && "recovery" in it }, name)
                assertTrue(
                    Files.walk(f.game.resolve(".nnomm/recovery")).use { paths ->
                        paths.anyMatch {
                            it.isRegularFile() && it.readBytes().contentEquals(original)
                        }
                    },
                    name,
                )
            }
        }
    }

    @Test
    fun repairUtf16PreservesEncodingAndBomAndRespectsPendingRestore() = runBlocking {
        for (le in listOf(true, false)) for (pending in listOf(false, true)) {
            val original =
                utf16(
                    "[ General ] ; comment\r\nenabled=false\r\n# café\r\ntarget_assembly=custom.dll\r\n",
                    le,
                )
            val f = fixture(original)
            if (pending)
                JsonFile(
                        f.home.resolve("doorstop-restore.json"),
                        MapSerializer(String.serializer(), String.serializer()),
                    )
                    .write(
                        mapOf(
                            "path" to f.game.resolve("doorstop_config.ini").toString(),
                            "bytes" to Base64.getEncoder().encodeToString(original),
                        )
                    )
            f.folder.repairLoader(f.game.toString())
            assertContentEquals(
                if (pending) original
                else
                    utf16(
                        "[ General ] ; comment\r\nenabled= true\r\n# café\r\ntarget_assembly=custom.dll\r\n",
                        le,
                    ),
                f.game.resolve("doorstop_config.ini").readBytes(),
            )
        }
        // Missing enabled is healthy: adapt the lead-review probe to leave its bytes alone.
        val original = utf16("[General]\r\n# café\r\ntarget_assembly=custom.dll\r\n")
        val f = fixture(original)
        f.folder.repairLoader(f.game.toString())
        assertContentEquals(original, f.game.resolve("doorstop_config.ini").readBytes())
    }

    @Test
    fun lockedFileIsNotReplaced() = runBlocking {
        val original = bytes("damaged but locked")
        val f = fixture(original, locked = true)
        val failure =
            assertFailsWith<DoorstopSettingsFailure> { f.folder.repairLoader(f.game.toString()) }
        assertTrue(loaderFailureHelp(failure).contains("close programs", true))
        assertFalse(loaderFailureHelp(failure).contains("Repair"))
        assertContentEquals(original, f.game.resolve("doorstop_config.ini").readBytes())
        assertEquals("player proxy", f.game.resolve("winhttp.dll").readText())
        assertTrue(f.notices.none { "shipped defaults" in it })
    }

    // Assertions ported from doorstop2/ZReviewProbeTest; no print-only probes.
    @Test
    fun repairAllDisabledValuesAndEmptyTargets() = runBlocking {
        for (value in
            listOf(
                "false",
                "FALSE",
                " false ",
                "\"false\"",
                "'false'",
                "0",
                "1",
                "no",
                "yes",
                "off",
                "",
                "true ;x",
                "false ; comment",
                "'true\"",
            )) {
            val f = fixture(bytes("[General]\nenabled=$value\ntarget_assembly=x\n"))
            f.folder.repairLoader(f.game.toString())
            assertContentEquals(
                bytes("[General]\nenabled= true\ntarget_assembly=x\n"),
                f.game.resolve("doorstop_config.ini").readBytes(),
                value,
            )
        }
        for (value in listOf("", " \t", "\"\"", "''", "\u001a", "\u000b", "\u000c")) {
            val original = bytes("[General]\nenabled=true\ntarget_assembly=$value\n")
            val f = fixture(original)
            f.folder.repairLoader(f.game.toString())
            assertContentEquals(shipped, f.game.resolve("doorstop_config.ini").readBytes())
            assertTrue(
                Files.walk(f.game.resolve(".nnomm/recovery")).use { paths ->
                    paths.anyMatch { it.isRegularFile() && it.readBytes().contentEquals(original) }
                }
            )
        }
        for (line in
            listOf(
                "enabled",
                "enabled=true",
                "enabled=TRUE",
                "enabled='true'",
                "enabled=\"true\"",
            )) {
            val original = bytes("[General]\n$line\ntarget_assembly=x\n")
            val f = fixture(original)
            f.folder.repairLoader(f.game.toString())
            assertContentEquals(original, f.game.resolve("doorstop_config.ini").readBytes())
        }
    }

    @Test
    fun wineWhitespaceAndUnmatchedBracketProbes() {
        val original = bytes("[General]\n[oops\nenabled=true\ntarget_assembly=x\n")
        assertTrue(DoorstopIni.read(original).healthy)
        assertContentEquals(
            bytes("[General]\n[oops\nenabled= false\ntarget_assembly=x\n"),
            DoorstopIni.read(original).setEnabled(false),
        )
        for (space in listOf('\u00a0', '\u001c', '\u001d', '\u001e', '\u001f')) {
            assertFalse(DoorstopIni.read(bytes("[$space" + "General]\ntarget_assembly=x")).healthy)
            assertFalse(
                DoorstopIni.read(bytes("[General]\n$space" + "enabled=false")).explicitlyDisabled
            )
            assertTrue(DoorstopIni.read(bytes("[General]\nenabled=true$space")).explicitlyDisabled)
        }
    }

    @Test
    fun malformedUtf16RefusesRepairAndLaunchWithoutChangingBytes() = runBlocking {
        for (le in listOf(true, false)) {
            val prefix = utf16("[General]\nenabled=false\ntarget_assembly=x\n# ", le)
            val malformed =
                listOf(
                    prefix + byteArrayOf(0x41),
                    prefix +
                        if (le) byteArrayOf(0, 0xd8.toByte()) else byteArrayOf(0xd8.toByte(), 0),
                )
            for (original in malformed) {
                val f = fixture(original)
                val failure =
                    assertFailsWith<DoorstopSettingsFailure> {
                        f.folder.repairLoader(f.game.toString())
                    }
                assertTrue(loaderFailureHelp(failure).contains("invalid UTF-16"))
                assertContentEquals(original, f.game.resolve("doorstop_config.ini").readBytes())
                assertEquals("player proxy", f.game.resolve("winhttp.dll").readText())
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val launcher = RealGameLauncher(f.home, scope, detect = { false })
                    assertFailsWith<DoorstopSettingsFailure> {
                        launcher.start(AppState(game = GameInfo(f.game.toString())), true)
                    }
                    assertContentEquals(original, f.game.resolve("doorstop_config.ini").readBytes())
                    assertFalse(launcher.running.value)
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun bareEnabledBeforeDuplicateIsTheEffectiveKey() {
        for (duplicate in listOf("", "enabled=true\n")) {
            val input = bytes("[General]\nenabled\n" + duplicate + "target_assembly=x\n")
            assertFalse(DoorstopIni.read(input).explicitlyDisabled)
            val disabled = DoorstopIni.read(input).setEnabled(false, true)
            assertContentEquals(
                bytes("[General]\nenabled = false\n" + duplicate + "target_assembly=x\n"),
                disabled,
            )
            assertTrue(DoorstopIni.read(disabled).explicitlyDisabled)
        }
    }

    @Test
    fun profileWhitespaceMatchesWine() {
        for (space in listOf('\t', '\u000b', '\u000c', '\u001a', ' ')) {
            val text =
                "$space[$space" +
                    "General$space]$space\n" +
                    "$space" +
                    "enabled$space=$space" +
                    "true$space\n" +
                    "$space" +
                    "target_assembly$space=$space" +
                    "x$space\n"
            val doc = DoorstopIni.read(bytes(text))
            assertTrue(doc.healthy)
            assertFalse(doc.explicitlyDisabled)
            assertFalse(DoorstopIni.read(bytes("[General]\ntarget_assembly=$space\n")).healthy)
        }
    }

    @Test
    fun repairDefersPendingUnhealthyRestoreForBytesAndLegacyText() = runBlocking {
        for (legacy in listOf(false, true)) {
            val original = bytes("[General]\nenabled=false\ntarget_assembly=\n")
            val f = fixture(original)
            val ini = f.game.resolve("doorstop_config.ini")
            JsonFile(
                    f.home.resolve("doorstop-restore.json"),
                    MapSerializer(String.serializer(), String.serializer()),
                )
                .write(
                    mapOf("path" to ini.toString()) +
                        if (legacy) mapOf("text" to String(original, DoorstopIni.charset))
                        else mapOf("bytes" to Base64.getEncoder().encodeToString(original))
                )
            f.folder.repairLoader(f.game.toString())
            assertContentEquals(original, ini.readBytes())
            assertTrue(DoorstopRestores(f.home).read(f.game).isNotEmpty())
            assertTrue(f.notices.any { "use Repair again" in it })
            assertFalse(f.notices.any { "shipped defaults" in it })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                RealGameLauncher(f.home, scope, detect = { false })
                assertContentEquals(original, ini.readBytes())
                assertTrue(DoorstopRestores(f.home).read(f.game).isEmpty())
                f.folder.repairLoader(f.game.toString())
                assertContentEquals(shipped, ini.readBytes())
                assertTrue(
                    Files.walk(f.game.resolve(".nnomm/recovery")).use { paths ->
                        paths.anyMatch {
                            it.isRegularFile() && it.readBytes().contentEquals(original)
                        }
                    }
                )
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }
    }

    // Assertion ports of review5 mixedCommit and Sol's owned-loader locked move.
    @Test
    fun ownedLoaderLockedIniFailsBeforeAnyPromotion() = runBlocking {
        for (target in listOf("", "valid.dll")) {
            val original = bytes("[General]\nenabled=false\ntarget_assembly=$target\n")
            val f = fixture(original)
            JsonFile(
                    f.home.resolve(
                        "loader-${Integer.toHexString(f.game.toString().hashCode())}.json"
                    ),
                    ListSerializer(String.serializer()),
                )
                .write(listOf("BepInEx/core/BepInEx.dll", "winhttp.dll", "doorstop_config.ini"))
            val ini = f.game.resolve("doorstop_config.ini")
            val moves = mutableListOf<Path>()
            DiskFaults.beforeMove = {
                moves.add(it)
                if (it == ini) throw AccessDeniedException(it.toString())
            }
            try {
                val failure =
                    assertFailsWith<AccessDeniedException> {
                        f.folder.repairLoader(f.game.toString())
                    }
                assertTrue(loaderFailureHelp(failure).contains("close programs", true))
            } finally {
                DiskFaults.beforeMove = {}
            }
            assertEquals(listOf(ini), moves)
            assertContentEquals(original, ini.readBytes())
            assertEquals("player proxy", f.game.resolve("winhttp.dll").readText())
            assertTrue(f.notices.isEmpty())
        }
    }

    @Test
    fun repairVerifiesIniBeforeSuccessNotice() = runBlocking {
        val original = bytes("[General]\ntarget_assembly=\n")
        val f = fixture(original)
        val ini = f.game.resolve("doorstop_config.ini")
        DiskFaults.step = { if (it == "ownership-published") ini.writeBytes(original) }
        try {
            val failure =
                assertFailsWith<DoorstopSettingsFailure> {
                    f.folder.repairLoader(f.game.toString())
                }
            assertTrue(loaderFailureHelp(failure).contains("close programs", true))
            assertTrue(f.notices.isEmpty())
        } finally {
            DiskFaults.step = {}
        }
    }

    @Test
    fun repairLockedMoveLeavesPendingIniAndRecordUntouched() = runBlocking {
        for (pending in listOf(false, true)) {
            val original = bytes("[General]\nenabled=false\ntarget_assembly=\n")
            val f = fixture(original)
            val ini = f.game.resolve("doorstop_config.ini")
            val record =
                mapOf(
                    "path" to ini.toString(),
                    "bytes" to Base64.getEncoder().encodeToString(original),
                )
            if (pending) {
                DoorstopRestores(f.home).write(f.game, record)
                JsonFile(
                        f.home.resolve(
                            "loader-${Integer.toHexString(f.game.toString().hashCode())}.json"
                        ),
                        ListSerializer(String.serializer()),
                    )
                    .write(listOf("winhttp.dll"))
            }
            var iniMoveAttempts = 0
            DiskFaults.beforeMove = {
                if (it == ini) {
                    iniMoveAttempts++
                    throw AccessDeniedException(it.toString())
                }
            }
            try {
                if (pending) f.folder.repairLoader(f.game.toString())
                else
                    assertFailsWith<AccessDeniedException> {
                        f.folder.repairLoader(f.game.toString())
                    }
            } finally {
                DiskFaults.beforeMove = {}
            }
            assertEquals(if (pending) 0 else 1, iniMoveAttempts)
            assertContentEquals(original, ini.readBytes())
            assertFalse(f.notices.any { "shipped defaults" in it })
            if (pending) {
                assertEquals("shipped proxy", f.game.resolve("winhttp.dll").readText())
                assertEquals(record, DoorstopRestores(f.home).read(f.game))
                assertTrue(f.notices.any { "use Repair again" in it })
            }
        }
    }

    @Test
    fun pendingRestoreSkipsMissingUnreadableAndMalformedIni() = runBlocking {
        for (kind in listOf("missing", "locked", "malformed")) {
            val original =
                if (kind == "malformed") byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x41)
                else bytes("player original")
            val f = fixture(original, locked = kind == "locked")
            val ini = f.game.resolve("doorstop_config.ini")
            if (kind == "missing") Files.delete(ini)
            val record =
                mapOf(
                    "path" to ini.toString(),
                    "bytes" to Base64.getEncoder().encodeToString(original),
                )
            DoorstopRestores(f.home).write(f.game, record)
            f.folder.repairLoader(f.game.toString())
            assertEquals(record, DoorstopRestores(f.home).read(f.game))
            if (kind == "missing") assertFalse(ini.exists())
            else assertContentEquals(original, ini.readBytes())
            assertTrue(f.notices.any { "use Repair again" in it })
            assertFalse(f.notices.any { "shipped defaults" in it })
        }
    }

    @Test
    fun inFlightRestoreAndRepairShareWholeSequenceLock() = runBlocking {
        val original = bytes("[General]\nenabled=false\ntarget_assembly=\n")
        val restored = bytes("[General]\nenabled=true\ntarget_assembly=\n")
        val f = fixture(original)
        val ini = f.game.resolve("doorstop_config.ini")
        DoorstopRestores(f.home)
            .write(
                f.game,
                mapOf(
                    "path" to ini.toString(),
                    "bytes" to Base64.getEncoder().encodeToString(restored),
                ),
            )
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val contended = java.util.concurrent.CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        DoorstopLocks.contended = { contended.countDown() }
        try {
            val startup =
                async(Dispatchers.IO) {
                    RealGameLauncher(
                        f.home,
                        scope,
                        detect = { false },
                        writeDoorstop = { path, data ->
                            entered.countDown()
                            check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
                            Files.write(path, data)
                        },
                    )
                }
            assertTrue(
                withContext(Dispatchers.IO) {
                    entered.await(10, java.util.concurrent.TimeUnit.SECONDS)
                }
            )
            val repair = async(Dispatchers.IO) { f.folder.repairLoader(f.game.toString()) }
            assertTrue(
                withContext(Dispatchers.IO) {
                    contended.await(10, java.util.concurrent.TimeUnit.SECONDS)
                }
            )
            assertFalse(repair.isCompleted)
            assertContentEquals(original, ini.readBytes())
            release.countDown()
            startup.await()
            repair.await()
            assertContentEquals(shipped, ini.readBytes())
            assertTrue(DoorstopRestores(f.home).read(f.game).isEmpty())
            assertTrue(
                Files.walk(f.game.resolve(".nnomm/recovery")).use { paths ->
                    paths.anyMatch { it.isRegularFile() && it.readBytes().contentEquals(restored) }
                }
            )
        } finally {
            release.countDown()
            DoorstopLocks.contended = {}
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun messageProbes() {
        val storage =
            listOf(
                FileSystemException("loader.zip", null, "used by another process"),
                FileSystemException("loader.zip", null, "Read-only file system"),
                java.io.FileNotFoundException("loader.zip (Permission denied)"),
                AccessDeniedException("loader.zip"),
            )
        for (error in storage) for (wrapped in listOf(error, LoaderDownloadFailure(error))) {
            assertTrue(loaderFailureHelp(wrapped).contains("storage folder"))
            assertFalse(loaderFailureHelp(wrapped).contains("connection"))
        }
        for (message in
            listOf("No space left on device", "There is not enough space on the disk")) assertTrue(
            loaderFailureHelp(LoaderDownloadFailure(java.io.IOException(message)))
                .contains("Free some space")
        )
        for (message in listOf("Disk quota exceeded", "Space in file name: My Games")) assertFalse(
            loaderFailureHelp(java.io.IOException(message)).contains("Free some space")
        )
        assertTrue(
            loaderFailureHelp(LoaderDownloadFailure(java.io.IOException("HTTP 404")))
                .contains("connection")
        )
        assertTrue(
            loaderFailureHelp(LoaderDownloadFailure(java.net.UnknownHostException()))
                .contains("connection")
        )
        assertTrue(loaderFailureHelp(ZipException()).contains("could not be checked"))
        assertFalse(loaderFailureHelp(NoSuchFileException("loader.dll")).contains("connection"))
    }
}
