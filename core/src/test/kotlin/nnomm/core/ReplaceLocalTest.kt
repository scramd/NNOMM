package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import nnomm.core.demo.Demo
import nnomm.core.model.ActionKind
import nnomm.core.model.ModSource
import nnomm.core.rules.modAction
import nnomm.core.rules.problemFixes
import nnomm.core.rules.problems
import nnomm.core.store.AppStore

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceLocalTest {

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

    @Test
    fun aMissingLocalFileIsChosenAgainNotDownloaded() {
        val seed = Demo.state()
        val local = seed.installed.values.first { it.source == ModSource.LOCAL }
        val s = seed.copy(installed = seed.installed + (local.id to local.copy(fileMissing = true)))
        assertEquals(ActionKind.REPLACEFILE, modAction(s, local.id).action?.kind)
        val missing = problems(s, local.id).first { it.key == "missing" }
        val fixes = problemFixes(s, local.id, missing).map { it.action.kind }
        assertTrue(ActionKind.REPLACEFILE in fixes)
        assertTrue(ActionKind.REPAIRPROBLEM !in fixes)
    }
}
