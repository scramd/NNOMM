package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import nnomm.core.demo.*
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.core.store.AppStore

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileNamesTest {
    private fun TestScope.store() = AppStore(Demo.state(), Demo.io(), backgroundScope)

    private fun AppStore.names() = state.value.profiles.map { it.name }

    @Test
    fun uniqueNamesCountUpAndIgnoreCase() {
        val s = Demo.state().copy(profiles = listOf(Profile("default", "Default"), Profile("a", "Squad"), Profile("b", "Squad (2)")))
        assertEquals("Solo", uniqueProfileName(s, " Solo "))
        assertEquals("squad (3)", uniqueProfileName(s, "squad"))
        assertEquals("Default (2)", uniqueProfileName(s, "Default"))
        assertEquals("Squad", uniqueProfileName(s, "Squad", except = "a"))
    }

    @Test
    fun newProfileGetsAUniqueName() = runTest {
        val a = store()
        val first = a.newProfile("Squad night")
        val second = a.newProfile("Squad night")
        assertEquals("Squad night", a.state.value.profile(first).name)
        assertEquals("Squad night (2)", a.state.value.profile(second).name)
    }

    @Test
    fun aCopyOfACopyDoesNotRepeatTheName() = runTest {
        val a = store()
        val one = a.copyProfile("friday")
        val two = a.copyProfile("friday")
        assertEquals("Friday squad copy", a.state.value.profile(one).name)
        assertEquals("Friday squad copy (2)", a.state.value.profile(two).name)
    }

    @Test
    fun renameKeepsNamesUniqueAndIgnoresABlankName() = runTest {
        val a = store()
        a.renameProfile("friday", "Default")
        assertEquals("Default (2)", a.state.value.profile("friday").name)
        a.renameProfile("friday", "   ")
        assertEquals("Default (2)", a.state.value.profile("friday").name)
        // Renaming a profile to its own name keeps it.
        a.renameProfile("friday", "Default (2)")
        assertEquals("Default (2)", a.state.value.profile("friday").name)
        assertEquals(a.names().size, a.names().map { it.lowercase() }.toSet().size)
    }

    @Test
    fun importingAPackTwiceGivesTwoNames() = runTest {
        val a = store()
        a.importPack(Demo.PACK_IMPORT, ImportMode.NEW, newProfileId = "one")
        a.importPack(Demo.PACK_IMPORT, ImportMode.NEW, newProfileId = "two")
        val one = a.state.value.profile("one").name
        val two = a.state.value.profile("two").name
        assertEquals(Demo.PACK_IMPORT.name, one)
        assertEquals("${Demo.PACK_IMPORT.name} (2)", two)
    }
}
