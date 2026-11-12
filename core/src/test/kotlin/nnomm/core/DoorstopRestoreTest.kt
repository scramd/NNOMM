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
        JsonFile(
            home.resolve("doorstop-restore.json"),
            MapSerializer(String.serializer(), String.serializer()),
        )

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
            launcher.start(AppState(game = GameInfo(game.toString())), false)
            withTimeout(5000) {
                launcher.running.first { !it }
                Unit
            }
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
    fun editFailureKeepsOriginalExceptionAndSuppressesRestoreFailure() = runBlocking {
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
                    writeDoorstop = { _, _ ->
                        throw if (++writes == 1) editFailure else restoreFailure
                    },
                )
            val failure =
                assertFailsWith<DoorstopSettingsFailure> {
                    launcher.start(AppState(game = GameInfo(game.toString())), true)
                }
            assertSame(editFailure, failure.cause)
            assertContentEquals(arrayOf(restoreFailure), failure.suppressed)
            assertContentEquals(original, ini.readBytes())
            assertFalse(record(home).read().isNullOrEmpty())
            assertFalse(launcher.running.value)
            assertTrue(loaderFailureHelp(failure).contains("close programs", true))
            assertFalse(loaderFailureHelp(failure).contains("connection"))
        } finally {
            scope.cancelAndJoin()
        }
    }

    @Test
    fun deletedFolderIsNotRecreatedAndRecordIsDropped() = runBlocking {
        val home = temp()
        val gone = temp().resolve("gone")
        save(home, gone.resolve("doorstop_config.ini"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            RealGameLauncher(home, scope, detect = { false })
            assertFalse(gone.exists())
            assertTrue(record(home).read().isNullOrEmpty())
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

    private suspend fun CoroutineScope.cancelAndJoin() {
        val job = coroutineContext[Job]!!
        job.cancelAndJoin()
    }
}
