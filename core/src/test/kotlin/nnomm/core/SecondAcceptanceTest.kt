package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.decodeRegistry
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.core.settings.*
import nnomm.core.store.AppStore

class SecondAcceptanceTest {
    @Test
    fun realManifestTagsHaveOneSpellingAndKeepSharedRealTags() {
        val mods = decodeRegistry(javaClass.getResource("/manifest.json")!!.readText())
        val cleaned = cleanCatalogTags(mods)
        val tags = cleaned.flatMap { it.tags }
        assertTrue(tags.none { it.equals("mod", true) })
        assertTrue(
            tags
                .groupBy { it.lowercase().replace("weapon", "weapons") }
                .values
                .all { it.distinct().size == 1 }
        )
        for (tag in listOf("vr", "combat", "solar")) {
            if (mods.any { m -> m.tags.any { it.equals(tag, true) } })
                assertTrue(tags.any { it.equals(tag, true) }, tag)
        }
        val author = "OnlyAnAuthor"
        val fixtures =
            mods.take(2).mapIndexed { index, m ->
                m.copy(
                    authors = listOf(if (index == 0) author else "AnotherAuthor"),
                    githubOwner = null,
                    tags = listOf(author, "Combat", "weapon", if (index == 0) "QoL" else "qol"),
                )
            }
        // Author names used by only one author are hidden; a tag shared by distinct authors
        // survives.
        val once =
            fixtures.mapIndexed { i, m -> m.copy(tags = if (i == 1) m.tags - author else m.tags) }
        assertTrue(cleanCatalogTags(once).flatMap { it.tags }.none { it == author })
        assertEquals(
            1,
            cleanCatalogTags(fixtures)
                .flatMap { it.tags }
                .filter { it.equals("qol", true) }
                .distinct()
                .size,
        )
    }

    @Test
    fun preferencesSaveWithoutGameWritesAndDangerActionHasNoUndo() = runBlocking {
        val io = Demo.io()
        var applies = 0
        var consoles = 0
        val folder =
            object : GameFolder by io.gameFolder {
                override suspend fun apply(plan: GamePlan) {
                    applies++
                }

                override suspend fun setConsole(folder: String, enabled: Boolean) {
                    consoles++
                }
            }
        val store = AppStore(Demo.state().copy(profileId = "friday"), io.copy(gameFolder = folder))
        try {
            store.setSetting("showProfiles", JsonPrimitive(false))
            assertEquals("default", store.state.value.profileId)
            store.setSetting("console", JsonPrimitive(true))
            store.resetSettings()
            store.awaitDiskIdle()
            assertEquals(0, applies)
            assertEquals(0, consoles)
            store.removeEverything()
            store.awaitDiskIdle()
            assertTrue(store.state.value.toasts.none { it.action == "Undo" })
        } finally {
            store.shutdown()
        }
    }

    @Test
    fun everyProfilePreferenceResolvesForTheProfileInUseAndPlanUsesOwnership() {
        var state = Demo.state()
        for (definition in SETTINGS.filter { it.ov }) {
            val key = definition.key
            val value =
                when (key) {
                    "launchArgs" -> JsonPrimitive("-windowed")
                    "onLaunch" -> JsonPrimitive("Close NNOMM")
                    "onUpdate" -> JsonPrimitive("Update by itself")
                    else -> JsonPrimitive(true)
                }
            state = ovset(state, key, "friday")
            state = setSetting(state, key, value, "friday")
            assertEquals(value, state.copy(profileId = "friday").setting(key), key)
            if (!definition.profileOnly)
                assertEquals(
                    state.settings.general[key],
                    state.copy(profileId = "default").setting(key),
                    key,
                )
        }
        assertTrue(gamePlan(state.copy(profileId = "friday")).ownSettings)
        assertFalse(gamePlan(state.copy(profileId = "default")).ownSettings)
    }

    @Test
    fun activeProfileControlsConsoleLaunchAndAutomaticUpdates() = runBlocking {
        for (profile in listOf("default", "friday")) {
            val io = Demo.io()
            var console: Boolean? = null
            var launch: AppState? = null
            val downloads = java.util.concurrent.CopyOnWriteArrayList<String>()
            var seed = Demo.state().copy(profileId = profile)
            for ((key, value) in
                mapOf(
                    "console" to JsonPrimitive(true),
                    "launchArgs" to JsonPrimitive("-windowed"),
                    "onLaunch" to JsonPrimitive("Close NNOMM"),
                    "onUpdate" to JsonPrimitive("Update by itself"),
                    "ownSettingsP" to JsonPrimitive(true),
                )) {
                seed = ovset(seed, key, "friday")
                seed = setSetting(seed, key, value, "friday")
            }
            val folder =
                object : GameFolder by io.gameFolder {
                    override suspend fun setConsole(folder: String, enabled: Boolean) {
                        console = enabled
                    }
                }
            val launcher =
                object : GameLauncher {
                    override val running = kotlinx.coroutines.flow.MutableStateFlow(false)

                    override suspend fun start(state: AppState, withoutMods: Boolean) {
                        launch = state
                    }
                }
            val downloader =
                object : Downloader {
                    override suspend fun download(
                        mod: CatalogMod,
                        version: ModVersion,
                        progress: (Int) -> Unit,
                    ): DownloadedFile {
                        downloads += mod.id
                        return DownloadedFile("demo")
                    }
                }
            val store =
                AppStore(
                    seed,
                    io.copy(gameFolder = folder, launcher = launcher, downloader = downloader),
                )
            try {
                store.play(withoutMods = true)
                withTimeout(5000) { while (launch == null) delay(10) }
                assertEquals(profile == "friday", console)
                assertEquals(
                    if (profile == "friday") "-windowed" else "",
                    launch!!.setting("launchArgs")?.content,
                )
                assertEquals(
                    if (profile == "friday") "Close NNOMM" else "Keep NNOMM open",
                    launch!!.setting("onLaunch")?.content,
                )
                assertEquals(profile == "friday", gamePlan(launch!!).ownSettings)
                store.refreshRegistry()
                withTimeout(5000) {
                    while (store.state.value.connection.kind == ConnectionKind.LOADING) delay(10)
                    if (profile == "friday") while (downloads.isEmpty()) delay(10)
                }
                if (profile == "default") assertTrue(downloads.isEmpty())
                else assertTrue(downloads.isNotEmpty())
            } finally {
                store.shutdown()
            }
        }
    }

    @Test
    fun metadataOnlyRequestsNewestVersionsOfRequestedRows() = runBlocking {
        val io = Demo.io()
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        val seed = Demo.state()
        val mod = seed.catalog.first()
        val source =
            object : MetaSource {
                override suspend fun metadata(
                    mod: CatalogMod,
                    version: ModVersion,
                ): VersionMetadata {
                    requested += "${mod.id}/${version.version}"
                    return VersionMetadata(10, 1)
                }
            }
        val store = AppStore(seed, io.copy(meta = source))
        try {
            store.loadMetadata(setOf(mod.id))
            val expected =
                listOfNotNull(mod.newestRelease, mod.newerPreRelease, mod.oldestVersion)
                    .filter { it.downloadUrl != null }
                    .map { "${mod.id}/${it.version}" }
                    .toSet()
            withTimeout(5000) { while (requested.size < expected.size) delay(10) }
            assertEquals(expected, requested.toSet())
        } finally {
            store.shutdown()
        }
    }
}
