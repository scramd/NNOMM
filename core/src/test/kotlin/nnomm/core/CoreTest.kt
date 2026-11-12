package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import nnomm.core.demo.*
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.core.settings.*
import nnomm.core.store.AppStore

@OptIn(ExperimentalCoroutinesApi::class)
class CoreTest {
    private fun TestScope.store() = AppStore(Demo.state(), Demo.io(), backgroundScope)

    private fun TestScope.drain() {
        advanceTimeBy(15000)
        runCurrent()
    }

    @Test
    fun browsePictureCounts() {
        val s = Demo.state()
        assertEquals(39, browseList(s).size)
        assertEquals(16, browseList(s, BrowseFilter(have = "new")).size)
        assertEquals(23, browseList(s, BrowseFilter(have = "inst")).size)
        assertEquals(3, browseList(s, BrowseFilter(have = "upd")).size)
        assertEquals(35, browseList(s, BrowseFilter(type = "plugin")).size)
        assertEquals(4, browseList(s, BrowseFilter(type = "addon")).size)
    }

    @Test
    fun libraryPictureCounts() {
        val s = Demo.state()
        assertEquals(
            mapOf("all" to 25, "on" to 17, "off" to 8, "upd" to 3, "local" to 2, "prob" to 3),
            libCounts(s),
        )
    }

    @Test
    fun tagPictureCounts() {
        val c = tagCounts(Demo.state())
        assertEquals(
            mapOf(
                "HUD" to 9,
                "Visual" to 7,
                "Map" to 4,
                "Sound" to 5,
                "QoL" to 4,
                "Aircraft" to 4,
                "Immersion" to 4,
                "Camera" to 3,
                "Multiplayer" to 3,
                "Weapons" to 3,
                "Controls" to 3,
                "Gameplay" to 3,
                "Utility" to 2,
                "Framework" to 1,
                "Dev tools" to 1,
                "Voice pack" to 1,
                "Localization" to 1,
                "Server" to 0,
                "Terrain" to 1,
                "VR" to 1,
            ),
            c,
        )
    }

    @Test
    fun topRows() {
        val rows = browseSections(Demo.state())
        assertEquals(
            listOf(
                "wingman",
                "harrier-kai",
                "minimap",
                "coastal-strike",
                "awacs",
                "spectator",
                "loadout-presets",
            ),
            rows[0].mods.map { it.id },
        )
        assertEquals(7, rows[0].mods.size)
        assertEquals("7 mods", rows[0].subtitle)
        assertEquals(
            listOf(
                "night-vision",
                "spectator",
                "map-markings",
                "satellite-map",
                "map-trails",
                "sounds",
                "minimap",
                "delivery",
                "awacs",
                "kill-feed",
            ),
            rows[1].mods.map { it.id },
        )
        assertEquals(listOf("coastal-strike", "carrier-ops"), rows[2].mods.map { it.id })
        assertEquals("they need Blueprinter, which you have", rows[2].subtitle)
        assertTrue(browseSections(Demo.state(), BrowseFilter(query = "map")).isEmpty())
    }

    @Test
    fun searchReasons() {
        val s = Demo.state()
        assertEquals(MatchKind.NAME, matchMod(s.mod("minimap"), "map")!!.reason!!.kind)
        assertEquals(listOf(12, 13, 14), matchMod(s.mod("minimap"), "map")!!.reason!!.indices)
        assertEquals("Tag: Visual", matchMod(s.mod("satellite-map"), "Visual")!!.reason!!.text)
        assertEquals("Author: dikka", matchMod(s.mod("minimap"), "dikka")!!.reason!!.text)
        val desc = matchMod(s.mod("blueprinter"), "weapons")!!.reason!!
        assertEquals(MatchKind.DESCRIPTION, desc.kind)
        assertEquals("weapons", desc.snippet!!.substring(desc.highlightRange!!))
        assertNull(matchMod(s.mod("minimap"), "zzzz"))
    }

    @Test
    fun fuzzyBoundaries() {
        assertEquals(230, fuzzy("map", "Map")!!.score)
        assertNull(fuzzy("mp", "Map"))
        assertNotNull(fuzzy("ncl", "Nuclear"))
        assertNull(fuzzy("xyz", "Nuclear Minimap"))
    }

    @Test
    fun tagsCleaned() {
        assertEquals(
            listOf("HUD", "QoL"),
            cleanTags(
                listOf("mod", "MOD", "dikka", "Dikka", "HUD", "hud", " QoL ", ""),
                listOf("dikka"),
            ),
        )
    }

    @Test
    fun numericVersionComparison() {
        assertTrue(compareVersions("1.10.0", "1.9.9") > 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertTrue(compareVersions("1.3.0-beta2", "1.3.0") < 0)
        assertTrue(compareVersions("1.0.0-beta.2", "1.0.0") < 0)
        assertTrue(compareVersions("1.0.0-beta.10", "1.0.0-beta.2") > 0)
        assertTrue(compareVersions("0.5.2", "0.5.1") > 0)
    }

    @Test
    fun formats() {
        assertEquals(
            listOf(
                "today",
                "yesterday",
                "3 days ago",
                "2 weeks ago",
                "2 months ago",
                "2 years ago",
            ),
            listOf(0, 1, 3, 14, 60, 610).map(::ago),
        )
        assertEquals("127\u00a0MB", size(130400))
        assertEquals("1.0\u00a0MB", size(1024))
        assertEquals("1.3M", num(1277950))
        assertEquals("117k", num(116910))
        assertEquals("8.4k", num(8420))
        assertEquals("1 mod", plural(1, "mod", "mods"))
        assertEquals("2 mods", plural(2, "mod", "mods"))
        assertEquals("0.4.9", prevVersion("0.5.0"))
    }

    @Test
    fun skipCounts() {
        val s = Demo.state()
        val b = BrowseFilter(type = "addon", have = "new")
        assertEquals(1, browseList(s, b).size)
        assertEquals(4, browseList(s, b, "have").size)
        assertEquals(16, browseList(s, b, "type").size)
    }

    @Test
    fun filterToggleClear() {
        var b = BrowseFilter().toggleHave("inst").toggleHave("inst")
        assertEquals("all", b.have)
        b = b.toggleType("addon").toggleTag("HUD").clear("type").clear("tags")
        assertFalse(browseFiltered(b))
    }

    @Test
    fun localModsAndPacksHaveNoUpdate() {
        val s = Demo.state()
        assertFalse(hasUpdate(s, "local-testhud"))
        assertFalse(hasUpdate(s.copy(profileId = "friday"), "harrier-kai"))
        assertEquals("Modpack", sourceOf(s.copy(profileId = "friday"), "harrier-kai"))
        assertEquals("Local file", sourceOf(s, "local-skin"))
    }

    @Test
    fun problemsBothWays() {
        val s = Demo.state()
        assertEquals("Conflicts with Free Camera Plus", problems(s, "spectator").single().text)
        assertEquals("Conflicts with Nuclear Spectator", problems(s, "free-cam").single().text)
        assertEquals("File missing", problems(s, "tracers").single().text)
        assertEquals(ProblemKind.INFO, problems(s, "warthog").single().kind)
        assertEquals(3, libList(s, LibraryFilter(filter = "prob")).size)
        assertEquals(
            "Download it again",
            problemFixes(s, "tracers", problems(s, "tracers").single()).first().label,
        )
    }

    @Test
    fun modButtons() {
        val s = Demo.state()
        assertEquals("Install", modAction(s, "wingman").label)
        assertEquals("Repair", modAction(s, "tracers").label)
        assertEquals("Update", modAction(s, "minimap").label)
        assertEquals("Turn on", modAction(s, "vortex").label)
        assertEquals("On", modAction(s, "delivery").label)
        assertTrue(
            modAction(s.copy(connection = CatalogConnection(ConnectionKind.OFFLINE)), "wingman")
                .disabled
        )
    }

    @Test
    fun installAndUndo() = runTest {
        val a = store()
        a.install("carrier-ops")
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(isOn(a.state.value, "carrier-ops"))
        a.undo()
        assertFalse("carrier-ops" in a.state.value.installed)
    }

    @Test
    fun dependencyInstallAndUndo() = runTest {
        val initial = Demo.state().copy(installed = Demo.state().installed - "blueprinter")
        val a = AppStore(initial, Demo.io(), backgroundScope)
        a.install("carrier-ops")
        assertEquals(
            "Carrier Ops needs Blueprinter. Installing both.",
            a.state.value.toasts.last().text,
        )
        advanceTimeBy(4000)
        runCurrent()
        assertTrue(isOn(a.state.value, "blueprinter"))
        a.undo()
        assertFalse("blueprinter" in a.state.value.installed)
    }

    @Test
    fun installKeepsStartingProfile() = runTest {
        val a = store()
        a.install("wingman")
        a.useProfile("hardcore")
        drain()
        assertTrue(isOn(a.state.value, "wingman", "default"))
        assertFalse(isOn(a.state.value, "wingman", "hardcore"))
    }

    @Test
    fun updatePerProfile() = runTest {
        val a = store()
        a.update("harrier-kai")
        drain()
        assertEquals("1.4.0", verOf(a.state.value, "harrier-kai", "default"))
        assertEquals("1.3.2", verOf(a.state.value, "harrier-kai", "friday"))
    }

    @Test
    fun removeDuringUpdateAndUndo() = runTest {
        val a = store()
        a.update("minimap")
        a.uninstall(listOf("minimap"))
        advanceTimeBy(1000)
        runCurrent()
        assertFalse("minimap" in a.state.value.installed)
        a.undo()
        runCurrent()
        assertTrue("minimap" in a.state.value.installed)
    }

    @Test
    fun bulkUndoKeepsProfile() = runTest {
        val a = store()
        a.setOn(listOf("delivery", "sounds"), false)
        a.useProfile("hardcore")
        a.undo()
        a.useProfile("default")
        assertTrue(isOn(a.state.value, "delivery"))
        assertTrue(isOn(a.state.value, "sounds"))
    }

    @Test
    fun importNewPreservesDefault() = runTest {
        val a = store()
        a.importPack(Demo.PACK_IMPORT)
        val p = a.state.value.profileId
        drain()
        assertEquals("0.1.5", verOf(a.state.value, "map-markings", p))
        assertEquals("0.1.6", verOf(a.state.value, "map-markings", "default"))
        assertNotNull(a.state.value.profile().pack)
    }

    @Test
    fun importAddKeepsConflictAndOtherMods() = runTest {
        val a = store()
        a.importPack(Demo.PACK_IMPORT, ImportMode.ADD)
        drain()
        assertEquals("0.1.6", verOf(a.state.value, "map-markings"))
        assertTrue(isOn(a.state.value, "smoke-trails"))
        assertTrue(isOn(a.state.value, "local-testhud"))
    }

    @Test
    fun importUsePackConflict() = runTest {
        val a = store()
        a.importPack(Demo.PACK_IMPORT, ImportMode.ADD, mapOf("map-markings" to "pack"))
        drain()
        assertEquals("0.1.5", verOf(a.state.value, "map-markings"))
    }

    @Test
    fun packUpdateOnlyFriday() = runTest {
        val a = store()
        a.useProfile("friday")
        a.updatePack(Demo.PACK_UPDATE)
        drain()
        assertEquals("1.5.0", verOf(a.state.value, "loadout-presets"))
        assertEquals("1.4.2", verOf(a.state.value, "loadout-presets", "default"))
        assertFalse(isOn(a.state.value, "better-flares"))
        assertEquals("1.4", a.state.value.profile().pack!!.version)
    }

    @Test
    fun brokenTestFindsAndRestores() = runTest {
        val a = store()
        val before = a.state.value.installed.keys.filter { isOn(a.state.value, it) }
        a.startBrokenModTest()
        assertEquals(0, onCount(a.state.value))
        a.answerBrokenModTest(false)
        repeat(12) {
            val s = a.state.value
            val t = s.trouble!!
            if (t.phase == TroublePhase.TEST) {
                t.on.forEach { id ->
                    s.mod(id).newestRelease!!.dependencies.forEach { d ->
                        assertFalse(d.id in before && d.id !in t.on)
                    }
                }
                a.answerBrokenModTest("cockpit-shake" in t.on)
            }
        }
        assertEquals("cockpit-shake", a.state.value.trouble!!.found)
        a.endBrokenModTest(TroubleChoice.KEEP)
        assertNull(a.state.value.trouble)
        assertEquals(before, a.state.value.installed.keys.filter { isOn(a.state.value, it) })
    }

    @Test
    fun brokenTestNotAMod() = runTest {
        val a = store()
        a.startBrokenModTest()
        a.answerBrokenModTest(true)
        assertEquals(TroublePhase.NOMOD, a.state.value.trouble!!.phase)
        assertEquals(17, onCount(a.state.value))
        a.closeBrokenModTest()
        assertNull(a.state.value.trouble)
    }

    @Test
    fun stopTestRestoresProfile() = runTest {
        val a = store()
        a.startBrokenModTest()
        a.answerBrokenModTest(false)
        a.stopBrokenModTest()
        assertEquals(17, onCount(a.state.value))
    }

    @Test
    fun testBlocksProfileSwitch() = runTest {
        val a = store()
        a.startBrokenModTest()
        a.useProfile("hardcore")
        assertEquals("default", a.state.value.profileId)
        assertEquals(
            "Finish or stop the broken-mod test before you change the profile.",
            a.state.value.toasts.last().text,
        )
    }

    @Test
    fun profileSettingsNotInUse() = runTest {
        val a = store()
        a.ovset("onLaunch", "friday")
        a.setSetting("onLaunch", JsonPrimitive("Close NNOMM"), "friday")
        assertEquals("default", a.state.value.profileId)
        assertEquals(
            "Close NNOMM",
            a.state.value.settings.overrides["friday"]!!["onLaunch"]!!.content,
        )
        assertEquals("Keep NNOMM open", a.state.value.settings.general["onLaunch"]!!.content)
    }

    @Test
    fun inheritedSettingLocked() = runTest {
        val a = store()
        a.setSetting("onUpdate", JsonPrimitive("Update by itself"), "friday")
        assertEquals("Tell me", a.state.value.settings.value("onUpdate", "friday")!!.content)
        assertTrue(a.state.value.settings.overrides["friday"].orEmpty().isEmpty())
    }

    @Test
    fun newProfileOwnSettings() = runTest {
        val a = store()
        a.newProfile("Test", ownSettings = true)
        assertTrue(a.state.value.settings.bool("ownSettingsP", a.state.value.profileId))
        assertEquals(0, onCount(a.state.value))
    }

    @Test
    fun copyRenameDeleteUndo() = runTest {
        val a = store()
        val id = a.copyProfile("hardcore")
        assertEquals(8, onCount(a.state.value))
        a.renameProfile(id, "Copy")
        a.deleteProfile(id)
        assertEquals("default", a.state.value.profileId)
        a.undo()
        assertEquals("Copy", a.state.value.profile(id).name)
        assertEquals(8, onCount(a.state.value, id))
    }

    @Test
    fun hideProfilesReturnsDefault() = runTest {
        val a = store()
        a.useProfile("hardcore")
        a.setSetting("showProfiles", JsonPrimitive(false))
        assertEquals("default", a.state.value.profileId)
    }

    @Test
    fun settingsResetKeepsProfileOnly() = runTest {
        val a = store()
        a.ovset("onLaunch", "hardcore")
        a.ovclear("hardcore")
        assertEquals(
            mapOf("ownSettingsP" to JsonPrimitive(true)),
            a.state.value.settings.overrides["hardcore"],
        )
        a.ovset("onLaunch", "hardcore")
        a.ovreset("onLaunch", "hardcore")
        assertFalse("onLaunch" in a.state.value.settings.overrides["hardcore"]!!)
    }

    @Test
    fun commandSearch() {
        val s = Demo.state()
        assertEquals("Your mods", palItems(s, "map").first().group)
        assertEquals(ActionKind.OPENMOD, palItems(s, "map").first().action.kind)
        assertTrue(palItems(s, "map").first().alternative != null)
        assertTrue(palItems(s, "zzzz").isEmpty())
        assertTrue(palItems(s, "").none { it.group == "Servers" || it.label == "Servers" })
    }

    @Test
    fun noJoinSettings() {
        assertTrue(
            SETTINGS.none {
                it.key in
                    setOf(
                        "joinHelper",
                        "joinMods",
                        "pingYellow",
                        "pingRed",
                        "srvRefresh",
                        "clearFav",
                        "tempKeep",
                    )
            }
        )
        assertTrue(SETTINGS.any { it.key == "showServers" })
    }

    @Test
    fun queueCancellationAndThreeSlots() = runTest {
        val a = store()
        listOf("wingman", "awacs", "radio-chatter", "smoke-trails").forEach { a.install(it) }
        advanceTimeBy(261)
        runCurrent()
        assertEquals(3, a.state.value.downloads.count { it.percent > 0 })
        a.cancelDownload("wingman")
        drain()
        assertFalse("wingman" in a.state.value.installed)
        assertTrue("smoke-trails" in a.state.value.installed)
        a.clearFinished()
        assertTrue(a.state.value.downloads.isEmpty())
    }

    @Test
    fun toastLimitAndExpiry() = runTest {
        val a = store()
        repeat(5) { a.newProfile("p$it") }
        assertEquals(3, a.state.value.toasts.size)
        advanceTimeBy(7001)
        runCurrent()
        assertTrue(a.state.value.toasts.isEmpty())
    }

    @Test
    fun playBlockedWhenRunning() = runTest {
        val a = store()
        a.play()
        a.play(true)
        assertTrue(a.state.value.gameStarting)
        runCurrent()
        assertTrue(a.state.value.gameRunning)
        assertEquals(1, a.state.value.toasts.size)
        assertEquals("Starting Nuclear Option with 17 mods", a.state.value.toasts.last().text)
        a.gameClosed()
        a.play(true)
        assertEquals(17, onCount(a.state.value))
    }

    @Test
    fun localFileAdded() = runTest {
        val a = store()
        a.addLocal("sandbox/Other.dll")
        runCurrent()
        assertTrue(isOn(a.state.value, "local-other"))
        assertFalse(hasUpdate(a.state.value, "local-other"))
        a.undo()
        assertFalse("local-other" in a.state.value.installed)
    }

    @Test
    fun sharingBuildsPack() {
        val seed =
            AppState(
                installed =
                    mapOf(
                        "registry" to
                            InstalledMod(
                                "registry",
                                versions = setOf("1", "2"),
                                defaultVersion = "2",
                                profiles = mapOf("default" to ProfileMod(true, "1")),
                            ),
                        "local" to
                            InstalledMod(
                                "local",
                                versions = setOf("local-v"),
                                source = ModSource.LOCAL,
                                defaultVersion = "local-v",
                                profiles = mapOf("default" to ProfileMod(true, "local-v")),
                            ),
                        "off" to
                            InstalledMod(
                                "off",
                                versions = setOf("3"),
                                defaultVersion = "3",
                                profiles = mapOf("default" to ProfileMod(false, "3")),
                            ),
                    )
            )
        val a = AppStore(seed, Demo.io())
        try {
            val registry = PackItem(PackKind.VER, "registry", "1")
            assertEquals(listOf(registry), a.shareProfile().items)
            assertEquals(
                listOf(registry, PackItem(PackKind.LOCAL, "local", "local-v")),
                a.shareProfile(includeLocal = true).items,
            )
        } finally {
            a.close()
        }
    }
}
