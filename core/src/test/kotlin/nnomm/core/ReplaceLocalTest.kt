package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import nnomm.core.demo.Demo
import nnomm.core.demo.DemoLauncher
import nnomm.core.model.ModSource
import nnomm.core.store.AppStore

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceLocalTest {
    @Test
    fun demoGameEndsAndCanStartAgain() = runTest {
        val launcher = DemoLauncher(backgroundScope)
        repeat(2) {
            launcher.start(Demo.state(), false)
            assertTrue(launcher.running.value)
            runCurrent()
            advanceTimeBy(20_000)
            runCurrent()
            assertFalse(launcher.running.value)
        }
    }

    @Test
    fun replacementKeepsIdentitySwitchesAndUndo() = runTest {
        val seed = Demo.state()
        val old = seed.installed.values.first { it.source == ModSource.LOCAL }
        AppStore(seed, Demo.io(), backgroundScope).use { store ->
            store.replaceLocal(old.id, "/sandbox/newer.dll")
            runCurrent()
            val replaced = store.state.value.installed.getValue(old.id)
            assertEquals(seed.installed.keys, store.state.value.installed.keys)
            assertEquals(
                old.profiles.mapValues { it.value.on },
                replaced.profiles.mapValues { it.value.on },
            )
            assertNotEquals(old.defaultVersion, replaced.defaultVersion)
            assertEquals("newer.dll", replaced.fileName)
            assertTrue(replaced.profiles.values.all { it.version == replaced.defaultVersion })
            store.undo()
            assertEquals(old, store.state.value.installed[old.id])
            assertEquals(seed.mod(old.id), store.state.value.mod(old.id))
        }
    }
}
