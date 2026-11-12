package nnomm.core

import java.nio.file.*
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.builtins.*
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.store.loaderFailureHelp

/** Assertion-based ports of doorstop2/ZExitProbeTest and the restore/edit-failure probes. */
class DoorstopRestoreTest {
    private val roots = mutableListOf<Path>()

    private fun temp() = Files.createTempDirectory("doorstop-restore-").also { roots.add(it) }

    private val original = "[General]\nenabled=true\n".toByteArray()

    private fun record(home: Path) =
        object {
            fun read() = DoorstopRestores(home).records().firstOrNull()

            fun write(value: Map<String, String>) =
                JsonFile(
                        home.resolve("doorstop-restore.json"),
                        MapSerializer(String.serializer(), String.serializer()),
                    )
                    .write(value)
        }

    private fun save(home: Path, ini: Path) =
        record(home)
            .write(
                mapOf(
                    "path" to ini.toString(),
                    "bytes" to Base64.getEncoder().encodeToString(original),
                )
            )

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(10000) { while (!predicate()) delay(25) }
    }

    @AfterTest
    fun clean() {
        roots.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun exitFailureClearsRunningAndRetriesWithoutAnotherTransition() = runBlocking {
        val home = temp()
        val game = temp()
        game.resolve("NuclearOption.exe").writeText("")
        val ini = game.resolve("doorstop_config.ini").apply { writeBytes(original) }
        val blocked = AtomicBoolean(false)
        val notices = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val launcher =
                RealGameLauncher(
                    home,
                    scope,
                    listOf("sh", "-c", "sleep 0.4"),
                    detect = { false },
                    notice = { notices.add(it) },
                    writeDoorstop = { path, bytes ->
                        if (blocked.get()) throw AccessDeniedException(path.toString())
                        Files.write(path, bytes)
                    },
                )
            launcher.start(AppState(game = GameInfo(game.toString())), true)
            blocked.set(true)
            withTimeout(5000) {
                launcher.running.first { !it }
                Unit
            }
            assertFalse(ini.readBytes().contentEquals(original))
            assertFalse(record(home).read().isNullOrEmpty())
            assertEquals(1, notices.size)
            delay(6500) // Multiple automatic failures still produce one notice.
            assertEquals(1, notices.size)
            val failure =
                assertFailsWith<DoorstopSettingsFailure> {
                    launcher.start(AppState(game = GameInfo(game.toString())), false)
                }
            assertIs<AccessDeniedException>(failure.cause)
            assertFalse(launcher.running.value)
            assertEquals(1, notices.size) // Retry does not flood notices.
            blocked.set(false)
            awaitCondition { record(home).read().isNullOrEmpty() }
            assertContentEquals(original, ini.readBytes())
            launcher.start(AppState(game = GameInfo(game.toString())), true)
            blocked.set(true)
            withTimeout(5000) {
                launcher.running.first { !it }
                Unit
            }
            assertEquals(2, notices.size) // A resolved failure permits a new notice.
        } finally {
            scope.cancelAndJoin()
        }
    }

    @Test
    fun startupFailureDoesNotThrowAndPlayRetries() = runBlocking {
        val home = temp()
        val game = temp()
        game.resolve("NuclearOption.exe").writeText("")
        val ini = game.resolve("doorstop_config.ini").apply { writeText("disabled") }
        save(home, ini)
        val blocked = AtomicBoolean(true)
        val notices = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val launcher =
                RealGameLauncher(
                    home,
                    scope,
                    listOf("sh", "-c", "sleep 0.2"),
                    detect = { false },
                    notice = { notices.add(it) },
                    writeDoorstop = { path, bytes ->
                        if (blocked.get()) throw AccessDeniedException(path.toString())
                        Files.write(path, bytes)
                    },
                )
            assertFalse(launcher.running.value)
            assertFalse(record(home).read().isNullOrEmpty())
            assertEquals(1, notices.size)
            blocked.set(false)
            launcher.start(AppState(game = GameInfo(game.toString())), false)
            assertContentEquals(original, ini.readBytes())
            assertTrue(record(home).read().isNullOrEmpty())
            withTimeout(5000) {
                launcher.running.first { !it }
                Unit
            }
        } finally {
            scope.cancelAndJoin()
        }
    }

    @Test
    fun partialEditFailureKeepsOriginalExceptionAndSuppressesRestoreFailure() = runBlocking {
        val home = temp()
        val game = temp()
        game.resolve("NuclearOption.exe").writeText("")
        val ini = game.resolve("doorstop_config.ini").apply { writeBytes(original) }
        val editFailure = AccessDeniedException("edit")
        val restoreFailure = AccessDeniedException("restore")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var writes = 0
        try {
            val launcher =
                RealGameLauncher(
                    home,
                    scope,
                    detect = { false },
                    writeDoorstop = { path, bytes ->
                        if (++writes == 1) {
                            Files.write(path, bytes)
                            throw editFailure
                        }
                        throw restoreFailure
                    },
                )
            val failure =
                assertFailsWith<DoorstopSettingsFailure> {
                    launcher.start(AppState(game = GameInfo(game.toString())), true)
                }
            assertSame(editFailure, failure.cause)
            assertContentEquals(arrayOf(restoreFailure), failure.suppressed)
            assertFalse(ini.readBytes().contentEquals(original))
            assertFalse(record(home).read().isNullOrEmpty())
            assertFalse(launcher.running.value)
            assertTrue(loaderFailureHelp(failure).contains("close programs", true))
            assertFalse(loaderFailureHelp(failure).contains("connection"))
        } finally {
            scope.cancelAndJoin()
        }
    }

    @Test
    fun missingFolderIsNotRecreatedAndRecordIsRetained() = runBlocking {
        val home = temp()
        val gone = temp().resolve("gone")
        save(home, gone.resolve("doorstop_config.ini"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            RealGameLauncher(home, scope, detect = { false })
            assertFalse(gone.exists())
            assertFalse(record(home).read().isNullOrEmpty())
        } finally {
            scope.cancelAndJoin()
        }
    }

    // Assertion port of review5 legacyOrphan: migration starts with a broken alias.
    @Test
    fun absentLegacyAliasIsFoundWhenLibraryReturns() =
        runBlocking<Unit> {
            val home = temp()
            val root = temp()
            val library = root.resolve("library").createDirectories()
            val game = library.resolve("game").createDirectories()
            game.resolve("NuclearOption.exe").writeText("")
            val ini = game.resolve("doorstop_config.ini").apply { writeText("disabled") }
            val alias = Files.createSymbolicLink(root.resolve("alias"), library)
            save(home, alias.resolve("game/doorstop_config.ini"))
            val away = root.resolve("away")
            Files.move(library, away)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                RealGameLauncher(home, scope, detect = { false })
                scope.cancelAndJoin() // Keep the return/lookup assertion independent of retry
                // timing.
                assertFalse(library.exists())
                assertTrue(home.resolve("doorstop-restore.json").readText().contains("alias"))
                assertEquals(1, DoorstopRestores(home).records().size)
                Files.move(away, library)
                // This is also the lookup Repair uses, before restoration has run.
                assertTrue(DoorstopRestores(home).read(game).isNotEmpty())
                val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    RealGameLauncher(home, retryScope, detect = { false })
                    assertContentEquals(original, ini.readBytes())
                    assertTrue(DoorstopRestores(home).records().isEmpty())
                } finally {
                    retryScope.cancelAndJoin()
                }
            } finally {
                scope.cancelAndJoin()
            }
        }

    // Assertion port of review5 lockedOtherFolder, for both Play modes.
    @Test
    fun lockedRestoreDoesNotBlockPlayInAnotherFolder() =
        runBlocking<Unit> {
            val home = temp()
            val a = temp()
            val b = temp()
            for (game in listOf(a, b)) game.resolve("NuclearOption.exe").writeText("")
            val aIni = a.resolve("doorstop_config.ini").apply { writeText("disabled") }
            val bIni = b.resolve("doorstop_config.ini").apply { writeBytes(original) }
            save(home, aIni)
            val notices = CopyOnWriteArrayList<String>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val launcher =
                    RealGameLauncher(
                        home,
                        scope,
                        listOf("sh", "-c", "sleep 0.2"),
                        detect = { false },
                        notice = { notices.add(it) },
                        writeDoorstop = { path, bytes ->
                            if (path.parent == a) throw AccessDeniedException(path.toString())
                            Files.write(path, bytes)
                        },
                    )
                for (withoutMods in listOf(false, true)) {
                    launcher.start(AppState(game = GameInfo(b.toString())), withoutMods)
                    withTimeout(5000) { launcher.running.first { !it } }
                    assertContentEquals(original, bIni.readBytes())
                    assertEquals("disabled", aIni.readText())
                    assertTrue(DoorstopRestores(home).read(a).isNotEmpty())
                    assertTrue(DoorstopRestores(home).read(b).isEmpty())
                }
                assertEquals(1, notices.size)
                assertTrue(notices.single().contains(a.toString()))
                assertFailsWith<DoorstopSettingsFailure> {
                    launcher.start(AppState(game = GameInfo(a.toString())), false)
                }
            } finally {
                scope.cancelAndJoin()
            }
        }

    @Test
    fun runtimeOpensWithUnrestorableRecordWithoutNetwork() = runBlocking {
        val home = temp()
        // Invalid base64 cannot restore, independent of OS permissions.
        record(home)
            .write(mapOf("path" to home.resolve("doorstop_config.ini").toString(), "bytes" to "!"))
        val notices = CopyOnWriteArrayList<String>()
        val settings = nnomm.core.settings.defaultSettings()
        RealPersistence(home)
            .save(
                AppState(
                    settings =
                        settings.copy(
                            general =
                                settings.general +
                                    ("checkUpdates" to
                                        kotlinx.serialization.json.JsonPrimitive("Only when I ask"))
                        )
                )
            )
        val runtime = RealRuntime.open(home, notice = { notices.add(it) })
        try {
            assertFalse(runtime.store.state.value.gameRunning)
            assertFalse(record(home).read().isNullOrEmpty())
            assertEquals(
                1,
                notices.count { it.startsWith("NNOMM could not turn the mods back on") },
            )
        } finally {
            runtime.shutdown()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun playShowsDoorstopAccessGuidanceWithRetry() =
        kotlinx.coroutines.test.runTest {
            val launcher =
                object : GameLauncher {
                    override val running = kotlinx.coroutines.flow.MutableStateFlow(false)

                    override suspend fun start(state: AppState, withoutMods: Boolean) {
                        throw DoorstopSettingsFailure(AccessDeniedException("doorstop_config.ini"))
                    }
                }
            val app =
                nnomm.core.store.AppStore(
                    AppState(
                        firstStart = FirstStart(true),
                        game = GameInfo("sandbox", loaderPresent = true),
                    ),
                    nnomm.core.demo.Demo.io().copy(launcher = launcher),
                    backgroundScope,
                )
            try {
                app.play(withoutMods = true)
                runCurrent()
                val toast =
                    app.state.value.toasts.single {
                        it.text.startsWith("Nuclear Option did not start.")
                    }
                assertTrue(toast.text.contains("close programs", true))
                assertFalse(toast.text.contains("Check that the game is installed"))
                assertFalse(toast.text.contains("connection"))
                assertEquals("Retry", toast.action)
                assertFalse(app.state.value.gameStarting)
            } finally {
                app.shutdown()
            }
        }

    // Assertion ports of doorstop3/ZRound3ProbeTest A, C and F (B extends exitFailure above).
    @Test
    fun unchangedReadOnlyFileDoesNotBlockNormalPlay() = runBlocking {
        val home = temp()
        val game = temp()
        game.resolve("NuclearOption.exe").writeText("")
        val ini = game.resolve("doorstop_config.ini").apply { writeBytes(original) }
        val notices = CopyOnWriteArrayList<String>()
        var writes = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val launcher =
                RealGameLauncher(
                    home,
                    scope,
                    listOf("sh", "-c", "true"),
                    detect = { false },
                    notice = { notices.add(it) },
                    writeDoorstop = { path, _ ->
                        writes++
                        throw AccessDeniedException(path.toString())
                    },
                )
            assertFailsWith<DoorstopSettingsFailure> {
                launcher.start(AppState(game = GameInfo(game.toString())), true)
            }
            assertContentEquals(original, ini.readBytes())
            assertTrue(record(home).read().isNullOrEmpty())
            delay(3200) // Periodic retries must not rewrite an unchanged read-only INI.
            launcher.start(AppState(game = GameInfo(game.toString())), false)
            assertEquals(1, writes)
            assertTrue(notices.isEmpty())
        } finally {
            scope.cancelAndJoin()
        }
    }

    @Test
    fun missingLibraryRestoresWhenItReturns() = runBlocking {
        val home = temp()
        val root = temp()
        val game = root.resolve("game").createDirectories()
        val ini = game.resolve("doorstop_config.ini").apply { writeText("disabled") }
        save(home, ini)
        val away = root.resolve("away")
        Files.move(game, away)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            RealGameLauncher(home, scope, detect = { false })
            assertFalse(game.exists())
            assertFalse(record(home).read().isNullOrEmpty())
            Files.move(away, game)
            awaitCondition { record(home).read().isNullOrEmpty() }
            assertContentEquals(original, ini.readBytes())
        } finally {
            scope.cancelAndJoin()
        }
    }

    @Test
    fun malformedUtf16DoesNotWriteOrSaveRestoreRecord() = runBlocking {
        val home = temp()
        val game = temp()
        game.resolve("NuclearOption.exe").writeText("")
        val malformed = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x41)
        val ini = game.resolve("doorstop_config.ini").apply { writeBytes(malformed) }
        var writes = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val launcher =
                RealGameLauncher(
                    home,
                    scope,
                    detect = { false },
                    writeDoorstop = { _, _ -> writes++ },
                )
            val failure =
                assertFailsWith<DoorstopSettingsFailure> {
                    launcher.start(AppState(game = GameInfo(game.toString())), true)
                }
            assertTrue(loaderFailureHelp(failure).contains("invalid UTF-16"))
            assertEquals(0, writes)
            assertContentEquals(malformed, ini.readBytes())
            assertTrue(record(home).read().isNullOrEmpty())
        } finally {
            scope.cancelAndJoin()
        }
    }

    @Test
    fun matchingLegacyByteAndTextRecordsClearWithoutAnyIniWrite() = runBlocking {
        for (legacy in listOf(false, true)) {
            val home = temp()
            val game = temp()
            game.resolve("doorstop_config.ini").writeBytes(original)
            record(home)
                .write(
                    mapOf("path" to game.resolve("doorstop_config.ini").toString()) +
                        if (legacy) mapOf("text" to String(original, DoorstopIni.charset))
                        else mapOf("bytes" to Base64.getEncoder().encodeToString(original))
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val notices = CopyOnWriteArrayList<String>()
            try {
                RealGameLauncher(
                    home,
                    scope,
                    detect = { false },
                    notice = { notices.add(it) },
                    writeDoorstop = { _, _ -> fail("Matching original must not be rewritten") },
                )
                assertTrue(DoorstopRestores(home).records().isEmpty())
                assertTrue(notices.isEmpty())
            } finally {
                scope.cancelAndJoin()
            }
        }
    }

    // Round 4 S1d: model an exclusive lock without depending on chmod/root semantics.
    @Test
    fun refusedEditAndUnreadableRollbackDoNotClaimModsWereTurnedOff() = runBlocking {
        val home = temp()
        val game = temp()
        game.resolve("NuclearOption.exe").writeText("")
        val ini = game.resolve("doorstop_config.ini").apply { writeBytes(original) }
        val locked = AtomicBoolean(false)
        val notices = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val launcher =
                RealGameLauncher(
                    home,
                    scope,
                    listOf("sh", "-c", "true"),
                    detect = { false },
                    notice = { notices.add(it) },
                    readDoorstop = { path ->
                        if (locked.get()) throw AccessDeniedException(path.toString())
                        Files.readAllBytes(path)
                    },
                    writeDoorstop = { path, _ ->
                        locked.set(true)
                        throw AccessDeniedException(path.toString())
                    },
                )
            val failure =
                assertFailsWith<DoorstopSettingsFailure> {
                    launcher.start(AppState(game = GameInfo(game.toString())), true)
                }
            assertIs<AccessDeniedException>(failure.cause)
            assertEquals(1, failure.suppressed.size)
            assertTrue(notices.isEmpty())
            assertTrue(DoorstopRestores(home).read(game).isNotEmpty())
            assertContentEquals(original, ini.readBytes())
            locked.set(false)
            launcher.start(AppState(game = GameInfo(game.toString())), false)
            assertTrue(DoorstopRestores(home).read(game).isEmpty())
            assertTrue(notices.isEmpty())
        } finally {
            scope.cancelAndJoin()
        }
    }

    @Test
    fun missingLibraryRecordSurvivesVanillaPlayInAnotherFolder() = runBlocking {
        val home = temp()
        val root = temp()
        val a = root.resolve("A").createDirectories()
        val b = root.resolve("B").createDirectories()
        val bOriginal = "[General]\nenabled=true\n# folder B\n".toByteArray()
        val aIni = a.resolve("doorstop_config.ini").apply { writeText("disabled A") }
        val bIni = b.resolve("doorstop_config.ini").apply { writeBytes(bOriginal) }
        b.resolve("NuclearOption.exe").writeText("")
        save(home, aIni) // Also exercises migration of the legacy single record.
        val away = root.resolve("A-away")
        Files.move(a, away)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val launcher =
                RealGameLauncher(home, scope, listOf("sh", "-c", "sleep 0.3"), detect = { false })
            launcher.start(AppState(game = GameInfo(b.toString())), true)
            assertEquals(2, DoorstopRestores(home).records().size)
            assertFalse(a.exists())
            withTimeout(5000) { launcher.running.first { !it } }
            assertContentEquals(bOriginal, bIni.readBytes())
            assertEquals(1, DoorstopRestores(home).records().size)
            Files.move(away, a)
            awaitCondition { DoorstopRestores(home).records().isEmpty() }
            assertContentEquals(original, aIni.readBytes())
            assertContentEquals(bOriginal, bIni.readBytes())
        } finally {
            scope.cancelAndJoin()
        }
    }

    private suspend fun CoroutineScope.cancelAndJoin() {
        val job = coroutineContext[Job]!!
        job.cancelAndJoin()
    }
}
