package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import nnomm.core.demo.*
import nnomm.core.rules.*
import nnomm.core.store.AppStore

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteProfileUndoTest {
    @Test
    fun undoMakesTheDeletedProfileInUseAgain() = runTest {
        val a = AppStore(Demo.state(), Demo.io(), backgroundScope)
        a.useProfile("friday")
        val on = a.state.value.installed.keys.filter { isOn(a.state.value, it) }
        a.deleteProfile("friday")
        assertEquals("default", a.state.value.profileId)
        a.undo()
        assertEquals("friday", a.state.value.profileId)
        assertTrue(a.state.value.profiles.any { it.id == "friday" })
        assertEquals(on, a.state.value.installed.keys.filter { isOn(a.state.value, it) })
    }

    @Test
    fun undoOfAProfileNotInUseKeepsTheProfileInUse() = runTest {
        val a = AppStore(Demo.state(), Demo.io(), backgroundScope)
        assertEquals("default", a.state.value.profileId)
        a.deleteProfile("friday")
        a.undo()
        assertEquals("default", a.state.value.profileId)
        assertTrue(a.state.value.profiles.any { it.id == "friday" })
    }
}
