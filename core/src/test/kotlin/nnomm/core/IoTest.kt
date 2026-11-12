package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import nnomm.core.demo.*
import nnomm.core.io.*
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.core.store.AppStore

@OptIn(ExperimentalCoroutinesApi::class)
class IoTest {
    @Test
    fun offlineRegistryUsesSavedTimestamp() = runTest {
        val registry =
            object : Registry {
                override suspend fun read(): List<CatalogMod> = error("Offline")

                override suspend fun saved() =
                    Demo.state().catalog.filter { it.localFile == null } to 123L

                override suspend fun save(mods: List<CatalogMod>, readAtMillis: Long) {}
            }
        val a = AppStore(Demo.state(), Demo.io().copy(registry = registry), backgroundScope)
        a.refreshRegistry()
        runCurrent()
        assertEquals(ConnectionKind.OFFLINE, a.state.value.connection.kind)
        assertEquals(123, a.state.value.connection.readAtMillis)
        assertEquals(2, a.state.value.catalog.count { it.localFile != null })
    }

    @Test
    fun transferFailureDoesNotInstall() = runTest {
        val downloader =
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ): DownloadedFile = error("No connection")
            }
        val a = AppStore(Demo.state(), Demo.io().copy(downloader = downloader), backgroundScope)
        a.install("wingman")
        runCurrent()
        assertFalse("wingman" in a.state.value.installed)
        assertEquals(
            "Download failed. Retry when you have a connection.",
            a.state.value.downloads.single().error,
        )
        assertEquals("Could not download Wingman Commands.", a.state.value.toasts.last().text)
    }

    @Test
    fun repairFailureKeepsMissingFlag() = runTest {
        val downloader =
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ): DownloadedFile = error("No connection")
            }
        val a = AppStore(Demo.state(), Demo.io().copy(downloader = downloader), backgroundScope)
        a.repair("tracers")
        runCurrent()
        assertTrue(a.state.value.installed.getValue("tracers").fileMissing)
    }

    @Test
    fun removeThenUndoOrdersDiskOperations() = runTest {
        val original = Demo.io()
        val operations = mutableListOf<String>()
        val storage =
            object : ModStore by original.modStore {
                override suspend fun remove(ids: List<String>) {
                    delay(100)
                    operations += "remove"
                }

                override suspend fun restore(ids: List<String>) {
                    operations += "restore"
                }
            }
        val a = AppStore(Demo.state(), original.copy(modStore = storage), backgroundScope)
        a.uninstall(listOf("minimap"))
        a.undo()
        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf("remove", "restore"), operations)
        assertTrue("minimap" in a.state.value.installed)
    }

    @Test
    fun runningGameDefersProfileApplication() = runTest {
        val original = Demo.io()
        val profiles = mutableListOf<String>()
        val folder =
            object : GameFolder by original.gameFolder {
                override suspend fun apply(plan: GamePlan) {
                    profiles += plan.profileId
                }
            }
        val a =
            AppStore(
                Demo.state().copy(gameRunning = true),
                original.copy(
                    gameFolder = folder,
                    launcher =
                        object : GameLauncher {
                            override val running = kotlinx.coroutines.flow.MutableStateFlow(true)

                            override suspend fun start(state: AppState, withoutMods: Boolean) {}
                        },
                ),
                backgroundScope,
            )
        a.useProfile("hardcore")
        a.toggle("minimap")
        runCurrent()
        assertTrue(profiles.isEmpty())
        (a.io.launcher.running as kotlinx.coroutines.flow.MutableStateFlow).value = false
        runCurrent()
        assertEquals(listOf("hardcore"), profiles)
    }

    @Test
    fun observesLauncherExit() = runTest {
        val io = Demo.io()
        val a = AppStore(Demo.state(), io, backgroundScope)
        runCurrent()
        a.play()
        runCurrent()
        assertTrue(a.state.value.gameRunning)
        (io.launcher as DemoLauncher).stop()
        runCurrent()
        assertFalse(a.state.value.gameRunning)
    }

    @Test
    fun changeVersionToastAndTarget() = runTest {
        val a = AppStore(Demo.state(), Demo.io(), backgroundScope)
        a.changeVersion("minimap", "0.5.0")
        assertEquals("Version 0.5.0 is not available", a.state.value.toasts.last().text)
        advanceTimeBy(6000)
        runCurrent()
        assertEquals("0.5.1", verOf(a.state.value, "minimap"))
    }

    @Test
    fun updateToastReturnsUiAction() = runTest {
        val a = AppStore(Demo.state(), Demo.io(), backgroundScope)
        a.update("minimap")
        advanceTimeBy(6000)
        runCurrent()
        val t = a.state.value.toasts.last()
        assertEquals("Updated Nuclear Minimap to 0.5.2", t.text)
        assertEquals("What's new", t.action)
        assertEquals(UiAction("openMod", "minimap", "new"), a.runToast(t.id))
    }

    @Test
    fun paletteToggleUsesToast() = runTest {
        val a = AppStore(Demo.state(), Demo.io(), backgroundScope)
        a.paletteToggle("minimap")
        assertEquals("Nuclear Minimap is off in Default", a.state.value.toasts.last().text)
    }

    @Test
    fun firstStartTakesOverWithoutApplying() = runTest {
        val io = Demo.io()
        var applies = 0
        val folder =
            object : GameFolder by io.gameFolder {
                override suspend fun apply(plan: GamePlan) {
                    applies++
                }
            }
        val a =
            AppStore(
                Demo.state().copy(installed = emptyMap()),
                io.copy(gameFolder = folder),
                backgroundScope,
            )
        a.takeOverExistingMods("sandbox/game")
        runCurrent()
        assertEquals(25, a.state.value.installed.size)
        assertEquals(0, applies)
        assertFalse(a.state.value.firstStart.complete)
        a.completeFirstStart()
        assertTrue(a.state.value.firstStart.complete)
    }
}
