package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.core.store.AppStore

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateStateRegressionTest {
    private fun fixture(cached: Boolean = false): AppState {
        val mod =
            CatalogMod("test", "Test mod", versions = listOf(ModVersion("1"), ModVersion("2")))
        return Demo.state()
            .copy(
                catalog = listOf(mod),
                installed =
                    mapOf(
                        "test" to
                            InstalledMod(
                                "test",
                                versions = if (cached) setOf("1", "2") else setOf("1"),
                                defaultVersion = "1",
                                profiles =
                                    mapOf(
                                        "off" to ProfileMod(false, "1"),
                                        "on" to ProfileMod(true, "1"),
                                    ),
                            )
                    ),
                profiles = listOf(Profile("off", "Off"), Profile("on", "On")),
                profileId = "off",
                game = GameInfo(),
                downloads = emptyList(),
                toasts = emptyList(),
            )
    }

    private fun TestScope.checkPath(
        cached: Boolean = false,
        pack: Boolean = false,
        action: (AppStore) -> Unit,
    ) {
        for (profile in listOf("off", "on")) {
            val base = fixture(cached)
            val initial =
                base.copy(
                    profileId = profile,
                    profiles =
                        base.profiles.map {
                            if (pack && it.id == profile)
                                it.copy(
                                    pack =
                                        PackInfo(
                                            "Author",
                                            "1",
                                            mods = listOf("test"),
                                            memberVersions = mapOf("test" to "1"),
                                        )
                                )
                            else it
                        },
                )
            val original = Demo.io()
            val io =
                original.copy(
                    registry =
                        object : Registry by original.registry {
                            override suspend fun read() = initial.catalog
                        },
                    downloader =
                        object : Downloader {
                            override suspend fun download(
                                mod: CatalogMod,
                                version: ModVersion,
                                progress: (Int) -> Unit,
                            ): DownloadedFile {
                                if (cached) error("Cached versions must not download")
                                progress(30)
                                delay(20)
                                progress(90)
                                return DownloadedFile("fixture")
                            }
                        },
                )
            val store = AppStore(initial, io, backgroundScope)
            action(store)
            advanceTimeBy(100)
            runCurrent()
            val installed = store.state.value.installed.getValue("test")
            assertEquals(false, installed.profiles.getValue("off").on)
            assertEquals(true, installed.profiles.getValue("on").on)
            assertEquals("2", installed.profiles.getValue(profile).version)
            assertEquals(
                "1",
                installed.profiles.getValue(if (profile == "off") "on" else "off").version,
            )
            assertTrue(store.state.value.downloads.all { it.status == DownloadStatus.COMPLETE })
            store.close()
            runCurrent()
        }
    }

    @Test
    fun singleUpdatePreservesEveryProfileSwitch() = runTest { checkPath { it.update("test") } }

    @Test fun updateAllPreservesEveryProfileSwitch() = runTest { checkPath { it.updateAll() } }

    @Test
    fun modPanelUpdatePreservesEveryProfileSwitch() = runTest {
        checkPath {
            // ModPanel calls AppStore.update; AppModel also routes UPDATE to it.
            assertEquals(ActionKind.UPDATE, modAction(it.state.value, "test").action?.kind)
            it.update("test")
        }
    }

    @Test
    fun automaticUpdatePreservesEveryProfileSwitch() = runTest {
        checkPath {
            it.setSetting("onUpdate", JsonPrimitive("Update by itself"))
            it.refreshRegistry()
        }
    }

    @Test
    fun downloadedVersionChangePreservesEveryProfileSwitch() = runTest {
        checkPath { it.changeVersion("test", "2") }
    }

    @Test
    fun cachedVersionChangePreservesEveryProfileSwitch() = runTest {
        checkPath(cached = true) { it.changeVersion("test", "2") }
    }

    @Test
    fun packVersionUpdatePreservesEveryProfileSwitch() = runTest {
        checkPath(pack = true) {
            it.updatePack(Pack("Pack", "Author", "2", listOf(PackItem(PackKind.VER, "test", "2"))))
        }
    }

    @Test
    fun packOnlySwitchesItsExplicitAdditionsAndRemovals() = runTest {
        val base = fixture()
        val state =
            base.copy(
                catalog =
                    base.catalog +
                        listOf("added", "removed", "unrelated").map {
                            base.catalog.single().copy(id = it)
                        },
                installed =
                    base.installed +
                        listOf("added", "removed", "unrelated").associateWith {
                            base.installed
                                .getValue("test")
                                .copy(
                                    id = it,
                                    profiles =
                                        if (it == "removed")
                                            mapOf(
                                                "off" to ProfileMod(true, "1"),
                                                "on" to ProfileMod(true, "1"),
                                            )
                                        else base.installed.getValue("test").profiles,
                                )
                        },
                profiles =
                    base.profiles.map {
                        if (it.id == "off")
                            it.copy(
                                pack =
                                    PackInfo(
                                        "Author",
                                        "1",
                                        mods = listOf("test", "removed"),
                                        memberVersions = mapOf("test" to "1", "removed" to "1"),
                                    )
                            )
                        else it
                    },
            )
        val store = AppStore(state, Demo.io(), backgroundScope)
        store.updatePack(
            Pack(
                "Pack",
                "Author",
                "2",
                listOf(PackItem(PackKind.VER, "test", "2"), PackItem(PackKind.VER, "added", "1")),
            )
        )
        advanceTimeBy(6000)
        runCurrent()
        assertEquals("2", verOf(store.state.value, "test", "off"))
        for (id in state.installed.keys) {
            assertEquals(id == "added", isOn(store.state.value, id, "off"), id)
            assertTrue(isOn(store.state.value, id, "on"), id)
        }
        store.close()
    }
}
