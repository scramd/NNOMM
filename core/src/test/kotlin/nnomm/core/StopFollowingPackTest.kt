package nnomm.core

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import nnomm.core.demo.Demo
import nnomm.core.io.real.RealPersistence
import nnomm.core.io.real.deleteTree
import nnomm.core.model.*
import nnomm.core.rules.followsPack
import nnomm.core.rules.packManaged
import nnomm.core.store.AppStore

class StopFollowingPackTest {
    @Test
    fun stopFollowingUnlocksVersionsAndSurvivesRestartWithoutChangingMods() = runBlocking {
        val home = Files.createTempDirectory("pack-follow-test")
        try {
            val persistence = RealPersistence(home)
            val member = Demo.state().installed.keys.first()
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
                                                mods = listOf(member),
                                                followAddress = "https://example.com/pack",
                                                memberVersions = mapOf(member to "1.0"),
                                            )
                                    )
                                else profile
                            }
                    )
                }
            val target = original.profiles.first().id
            assertTrue(packManaged(original, member, target))
            assertTrue(followsPack(original.profiles.first()))
            val store = AppStore(original, Demo.io().copy(persistence = persistence))
            store.stopFollowingPack(target)
            store.shutdown()
            val saved = assertNotNull(persistence.load())
            val reopened = AppStore(saved, Demo.io().copy(persistence = persistence))
            try {
                val actual = reopened.state.value
                assertNull(actual.profiles.first().pack?.followAddress)
                assertNull(actual.profiles.first().pack?.nextVersion)
                // The pack's name and version stay, but it no longer controls any mod.
                assertEquals(PackInfo("Author", "1"), actual.profiles.first().pack)
                assertFalse(packManaged(actual, member, target))
                assertFalse(followsPack(actual.profiles.first()))
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
