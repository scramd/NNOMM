package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import nnomm.core.demo.*
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.core.settings.*
import nnomm.core.store.AppStore

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RegistryTest {
    @Test
    fun tagsMergeAcrossRegistry() {
        val mods =
            cleanCatalogTags(
                listOf(
                    CatalogMod("a", "A", authors = listOf("Alice"), tags = listOf("HUD", "Bob")),
                    CatalogMod(
                        "b",
                        "B",
                        authors = listOf("Bob"),
                        tags = listOf("hud", "mod", "Alice"),
                    ),
                )
            )
        assertEquals(listOf("HUD", "Bob"), mods[0].tags)
        assertEquals(listOf("HUD", "Alice"), mods[1].tags)
    }

    @Test
    fun registryWireFormat() {
        val entry =
            Json.decodeFromString<RegistryEntry>(
                """{
            "id":"test","displayName":"Test","authors":["Someone"],"tags":["mod","Someone","HUD","hud"],
            "urls":[{"name":"Source","url":"https://github.com/owner/repo"}],
            "isClientOrServer":"Both","artifacts":[{"version":"1.2.3","category":"preRelease","type":"addon",
            "gameVersion":"0.34.6","downloadURL":"https://example.invalid/test.zip","hash":"abc",
            "extends":{"id":"base"},"dependencies":[{"id":"base","version":"1.0"}],
            "incompatibilities":[{"id":"other"}]}]}"""
            )
        val mod = entry.toCatalogMod()
        assertEquals(listOf("HUD"), mod.tags)
        assertEquals(Side.BOTH, mod.side)
        assertEquals("owner", mod.githubOwner)
        assertEquals("repo", mod.githubRepository)
        assertEquals("https://example.invalid/test.zip", mod.versions.single().downloadUrl)
        assertEquals(ReleaseKind.PRE_RELEASE, mod.versions.single().category)
        assertEquals("base", mod.versions.single().extends!!.id)
        assertEquals(mod, Json.decodeFromString<CatalogMod>(Json.encodeToString(mod)))
    }

    @Test
    fun derivedVersionsAndDates() {
        val mod = Demo.state().mod("coastal-strike")
        assertEquals("1.2.0", mod.newestRelease!!.version)
        assertEquals("1.3.0-beta2", mod.newerPreRelease!!.version)
        assertEquals(5, daysAgo(Demo.state(), mod.updated))
        assertEquals(150, daysAgo(Demo.state(), mod.added))
    }

    @Test
    fun liveSettingsHelp() {
        val s =
            Demo.state()
                .copy(game = GameInfo(version = "9.8.7"), appVersion = "2.0", storeUsedMb = 123)
        assertEquals(
            "Nuclear Option 9.8.7 found here.",
            settingHelp(SETTINGS.first { it.key == "gamePath" }, s),
        )
        assertTrue(settingHelp(SETTINGS.first { it.key == "store" }, s).contains("123 MB"))
        assertTrue(settingHelp(SETTINGS.first { it.key == "appUpdate" }, s).contains("2.0"))
    }

    @Test
    fun tagsFollowOtherFiltersAndAllMode() {
        val s = Demo.state()
        assertEquals(3, tagCounts(s, BrowseFilter(type = "addon"))["Aircraft"])
        assertEquals(2, tagCounts(s, BrowseFilter(tags = listOf("HUD"), tagMode = "all"))["Map"])
        assertEquals(4, tagCounts(s, BrowseFilter(tags = listOf("HUD"), tagMode = "any"))["Map"])
    }

    @Test
    fun librarySortingDirection() {
        val s = Demo.state()
        val asc = libList(s, LibraryFilter(sort = LibrarySort.SIZE))
        val desc = libList(s, LibraryFilter(sort = LibrarySort.SIZE, direction = -1))
        assertEquals("g-meter", asc.first())
        assertEquals("satellite-map", desc.first())
        assertEquals(17, libList(s, LibraryFilter(filter = "on")).size)
        assertEquals(listOf("minimap"), libList(s, LibraryFilter(query = "minimap")))
    }

    @Test
    fun missingDependencyFix() {
        val s = Demo.state().copy(installed = Demo.state().installed - "blueprinter")
        val p = problems(s, "harrier-kai").first { it.key == "dep" }
        assertEquals("Install Blueprinter", problemFixes(s, "harrier-kai", p).first().label)
    }

    @Test
    fun packDiffLocalFileIsOff() {
        val rows = importRows(Demo.state(), Demo.PACK_IMPORT, ImportMode.ADD)
        assertEquals(PackKind.UP, rows.first { it.id == "harrier-kai" }.kind)
        assertEquals(PackKind.CONFLICT, rows.first { it.id == "map-markings" }.kind)
        assertFalse(rows.last().defaultOn)
        assertTrue(rows.last().local)
        assertEquals(
            "turned off, not deleted",
            packUpdateRows(Demo.state(), Demo.PACK_UPDATE).first { it.kind == PackKind.REM }.text,
        )
    }

    @Test
    fun repairActuallyDownloads() = runTest {
        val a = AppStore(Demo.state(), Demo.io(), backgroundScope)
        a.repair("tracers")
        assertTrue(a.state.value.installed.getValue("tracers").fileMissing)
        advanceTimeBy(6000)
        runCurrent()
        assertFalse(a.state.value.installed.getValue("tracers").fileMissing)
        assertEquals("Downloaded Tracer Colors again", a.state.value.toasts.last().text)
    }

    @Test
    fun updateAllKeepsOldVersions() = runTest {
        val a = AppStore(Demo.state(), Demo.io(), backgroundScope)
        a.updateAll()
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(0, updates(a.state.value).size)
        assertEquals(setOf("0.5.1", "0.5.2"), a.state.value.installed.getValue("minimap").versions)
    }

    @Test
    fun downloadButtonProgress() = runTest {
        val a = AppStore(Demo.state(), Demo.io(), backgroundScope)
        a.install("wingman")
        advanceTimeBy(261)
        runCurrent()
        assertEquals(
            (6 + hash("wingman") % 14).toInt(),
            modAction(a.state.value, "wingman").percent,
        )
    }

    @Test
    fun demoNotes() {
        assertEquals("3 days ago", Demo.notes("minimap").first().date)
        assertEquals(3, Demo.notes("minimap").size)
        assertEquals("1.3.0", Demo.notes("fuel-planner").first().version)
    }
}
