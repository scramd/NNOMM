package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import nnomm.core.demo.*
import nnomm.core.io.PackSource
import nnomm.core.model.*
import nnomm.core.store.AppStore

@OptIn(ExperimentalCoroutinesApi::class)
class LookForUpdatesTest {
    @Test
    fun lookForUpdatesAlsoReadsFollowedModpacks() = runTest {
        val seed =
            Demo.state().let { s ->
                s.copy(
                    profiles =
                        s.profiles.map {
                            if (it.id == "friday") it.copy(pack = it.pack!!.copy(nextVersion = null)) else it
                        }
                )
            }
        var reads = 0
        val source =
            object : PackSource {
                override suspend fun following(info: PackInfo): Pack {
                    reads++
                    return Pack("Friday squad", info.by, "1.4", emptyList(), followAddress = info.followAddress)
                }
            }
        val a = AppStore(seed, Demo.io().copy(packSource = source), backgroundScope)
        assertNull(a.state.value.profile("friday").pack?.nextVersion)
        a.lookForUpdates()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, reads)
        assertEquals("1.4", a.state.value.profile("friday").pack?.nextVersion)
        val toast = a.state.value.toasts.last()
        assertTrue(toast.text.endsWith("Friday squad has a modpack update"), toast.text)
    }
}
