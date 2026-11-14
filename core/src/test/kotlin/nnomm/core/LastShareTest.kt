package nnomm.core

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import nnomm.core.demo.Demo
import nnomm.core.io.real.RealPersistence
import nnomm.core.io.real.deleteTree
import nnomm.core.model.*
import nnomm.core.store.AppStore

class LastShareTest {
    @Test
    fun theLastShareOfAProfileIsKeptAcrossARestart() = runBlocking {
        val home = Files.createTempDirectory("last-share-test")
        try {
            val persistence = RealPersistence(home)
            val store = AppStore(Demo.state(), Demo.io().copy(persistence = persistence))
            val pack =
                store.shareProfile("Squad night", "1.2", "Mako", followAddress = "https://example.com/squad.nommpack", profileId = "friday")
            assertEquals("Mako", pack.by)
            store.rememberShare("friday", pack)
            store.shutdown()
            val saved = assertNotNull(persistence.load())
            assertEquals(
                ShareInfo("Squad night", "1.2", "Mako", "https://example.com/squad.nommpack"),
                saved.profiles.first { it.id == "friday" }.lastShare,
            )
            assertNull(saved.profiles.first { it.id == "default" }.lastShare)
        } finally {
            deleteTree(home)
        }
    }
}
