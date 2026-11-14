package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import nnomm.core.demo.Demo
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.core.store.AppStore

/** Profile order, the pack update address, import into any profile, and Library placeholders. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileOrderAndImportTargetTest {
    @Test
    fun moveProfileChangesOnlyTheOrder() = runTest {
        val initial = Demo.state()
        assertTrue(initial.profiles.size >= 3)
        val store = AppStore(initial, Demo.io().copy(persistence = null), backgroundScope)
        val last = initial.profiles.last().id
        store.moveProfile(last, 0)
        assertEquals(last, store.state.value.profiles.first().id)
        assertEquals(initial.profiles.map { it.id }.toSet(), store.state.value.profiles.map { it.id }.toSet())
        assertEquals(initial.profileId, store.state.value.profileId)
        // Past the end goes to the end.
        store.moveProfile(last, 99)
        assertEquals(last, store.state.value.profiles.last().id)
        store.close()
    }

    @Test
    fun packAddressCanBeSetChangedAndCleared() = runTest {
        val initial = Demo.state()
        val plain = initial.profiles.first { it.pack == null }
        val store = AppStore(initial, Demo.io().copy(persistence = null), backgroundScope)
        store.setPackAddress(plain.id, "  https://example.com/a.nommpack ")
        val set = store.state.value.profile(plain.id).pack
        assertEquals("https://example.com/a.nommpack", set?.followAddress)
        assertTrue(followsPack(store.state.value.profile(plain.id)))
        // Its mods stay the player's: an address alone does not lock any version.
        assertTrue(set!!.mods.isEmpty())
        store.setPackAddress(plain.id, "https://example.com/b.nommpack")
        assertEquals("https://example.com/b.nommpack", store.state.value.profile(plain.id).pack?.followAddress)
        store.setPackAddress(plain.id, " ")
        assertNull(store.state.value.profile(plain.id).pack?.followAddress)
        assertFalse(followsPack(store.state.value.profile(plain.id)))
        store.close()
    }

    @Test
    fun addImportGoesIntoTheChosenProfileNotTheOneInUse() = runTest {
        val initial = Demo.state()
        val other = initial.profiles.first { it.id != initial.profileId }.id
        val owned = initial.installed.keys.first { !isOn(initial, it, other) }
        val pack = Pack("Into other", "", "1", listOf(PackItem(PackKind.VER, owned, verOf(initial, owned, other))))
        val rows = importRows(initial, pack, ImportMode.ADD, other)
        assertEquals(PackKind.SAME, rows.single().kind)
        val inUseBefore = isOn(initial, owned)
        val store = AppStore(initial, Demo.io().copy(persistence = null), backgroundScope)
        store.importPack(pack, ImportMode.ADD, addTo = other)
        val s = store.state.value
        assertTrue(isOn(s, owned, other))
        assertEquals(inUseBefore, isOn(s, owned, initial.profileId))
        assertEquals(initial.profileId, s.profileId)
        assertEquals(initial.profiles.size, s.profiles.size)
        store.close()
    }

    @Test
    fun downloadingModsAreListedWhereTheyWillBe() {
        val initial = Demo.state()
        val fresh = initial.catalog.first { it.id !in initial.installed && it.installable != null }
        val s =
            initial.copy(
                downloads =
                    listOf(Download(fresh.id, DownloadKind.INSTALL, initial.profileId, fresh.installable!!.version))
            )
        val pending = pendingInstalls(s)
        assertEquals(listOf(fresh.id), pending)
        // Not for another profile, and not once it failed.
        assertTrue(pendingInstalls(s, "nobody").isEmpty())
        assertTrue(pendingInstalls(s.copy(downloads = s.downloads.map { it.copy(error = "x") })).isEmpty())
        val list = libList(s, LibraryFilter(), pending)
        // Same order as once it is installed.
        val installed = s.copy(installed = s.installed + (fresh.id to InstalledMod(fresh.id, versions = setOf("1"), defaultVersion = "1")))
        assertEquals(libList(installed, LibraryFilter()), list)
        // A filter that a new mod cannot match leaves it out.
        assertFalse(fresh.id in libList(s, LibraryFilter(filter = "upd"), pending))
    }

    @Test
    fun aVersionInAnArchiveNnommCannotInstallIsNotImported() {
        val initial = Demo.state()
        val fresh = initial.catalog.first { it.id !in initial.installed && it.installable != null }
        val v = fresh.installable!!.version
        val s =
            initial.copy(
                catalog =
                    initial.catalog.map { m ->
                        if (m.id != fresh.id) m
                        else m.copy(versions = m.versions.map { if (it.version == v) it.copy(fileName = "mod.rar") else it })
                    }
            )
        val row = importRows(s, Pack("Rar", "", "1", listOf(PackItem(PackKind.VER, fresh.id, v)))).single()
        assertEquals(PackKind.UNAVAILABLE, row.kind)
        assertTrue(row.archive)
    }

    @Test
    fun aDownloadThatWillBeOnSortsWithTheModsThatAreOn() {
        val initial = Demo.state()
        val fresh = initial.catalog.first { it.id !in initial.installed && it.installable != null }
        val s = initial.copy(downloads = listOf(Download(fresh.id, DownloadKind.INSTALL, initial.profileId, fresh.installable!!.version)))
        val byOn = LibraryFilter(sort = LibrarySort.ON)
        val list = libList(s, byOn, listOf(fresh.id), setOf(fresh.id))
        val firstOff = list.indexOfFirst { it in s.installed && !isOn(s, it) }
        assertTrue(list.indexOf(fresh.id) < firstOff)
        // For another profile it is off here, so it is not in "On".
        assertFalse(fresh.id in libList(s, LibraryFilter(filter = "on"), listOf(fresh.id), emptySet()))
        assertTrue(fresh.id in libList(s, LibraryFilter(filter = "off"), listOf(fresh.id), emptySet()))
    }

    @Test
    fun longNamesAreCutInMessages() {
        assertEquals("Short", shortName("Short"))
        val long = "a".repeat(100)
        assertEquals(40, shortName(long).length)
        assertTrue(shortName(long).endsWith("…"))
    }
}
