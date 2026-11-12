package nnomm.core

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import nnomm.core.demo.Demo
import nnomm.core.io.real.RealPersistence
import nnomm.core.io.real.deleteTree
import nnomm.core.model.*
import nnomm.core.store.AppStore

class StopFollowingPackTest {
    @Test
    fun stopFollowingSurvivesRestartWithoutChangingModsOrVersions() = runBlocking {
        val home = Files.createTempDirectory("pack-follow-test")
        try {
            val persistence = RealPersistence(home)
            val original =
                Demo.state().let { state ->
                    state.copy(
                        profiles =
                            state.profiles.mapIndexed { index, profile ->
                                if (index == 0)
                                    profile.copy(
                                        pack =
                                            PackInfo(
                                                "Author",
                                                "1",
                                                nextVersion = "2",
                                                followAddress = "https://example.com/pack",
                                            )
                                    )
                                else profile
                            }
                    )
                }
            val target = original.profiles.first().id
            val store = AppStore(original, Demo.io().copy(persistence = persistence))
            store.stopFollowingPack(target)
            store.shutdown()
            val saved = assertNotNull(persistence.load())
            val reopened = AppStore(saved, Demo.io().copy(persistence = persistence))
            try {
                val actual = reopened.state.value
                assertNull(actual.profiles.first().pack?.followAddress)
                assertNull(actual.profiles.first().pack?.nextVersion)
                assertEquals(
                    original.profiles.first().pack?.copy(followAddress = null, nextVersion = null),
                    actual.profiles.first().pack,
                )
                assertEquals(original.installed, actual.installed)
                assertEquals(original.profiles.drop(1), actual.profiles.drop(1))
                assertEquals(original.profileId, actual.profileId)
            } finally {
                reopened.shutdown()
            }
        } finally {
            deleteTree(home)
        }
    }
}
