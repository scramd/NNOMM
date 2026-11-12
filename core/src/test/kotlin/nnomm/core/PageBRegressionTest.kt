package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.demo.Demo
import nnomm.core.io.GameFolder
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.core.store.AppStore

@OptIn(ExperimentalCoroutinesApi::class)
class PageBRegressionTest {
    @Test
    fun importPreviewMatchesMock() {
        val rows = importRows(Demo.state(), Demo.PACK_IMPORT)
        assertEquals(5, rows.count { it.kind == PackKind.SAME })
        assertEquals(3, rows.count { it.kind == PackKind.OTHER })
        assertEquals(8, rows.count { it.kind in setOf(PackKind.ADD, PackKind.OTHER) })
        assertEquals(
            1,
            importRows(Demo.state(), Demo.PACK_IMPORT, ImportMode.ADD).count {
                it.kind == PackKind.CONFLICT
            },
        )
        assertTrue(rows.filter { it.kind != PackKind.LOCAL }.all { it.packVersion != null })
    }

    @Test
    fun missingVersionNeverQueuesDownload() = runTest {
        val initial = Demo.state()
        val owned = initial.installed.keys.first()
        val absent = initial.catalog.first { it.id !in initial.installed }.id
        val pack =
            Pack(
                "Missing versions",
                "",
                "1",
                listOf(PackItem(PackKind.VER, owned), PackItem(PackKind.ADD, absent)),
            )
        val rows = importRows(initial, pack)
        assertEquals(PackKind.SAME, rows[0].kind)
        assertEquals(PackKind.UNAVAILABLE, rows[1].kind)
        val store = AppStore(initial, Demo.io(), backgroundScope)
        store.importPack(pack)
        assertTrue(store.state.value.downloads.isEmpty())
        store.close()
    }

    @Test
    fun newestGamePathInspectionWins() = runTest {
        val io = Demo.io()
        val folder =
            object : GameFolder by io.gameFolder {
                override suspend fun inspect(folder: String): GameInfo {
                    delay(if (folder == "old") 100 else 1)
                    return GameInfo(folder = folder)
                }
            }
        val store = AppStore(Demo.state(), io.copy(gameFolder = folder), backgroundScope)
        store.setSetting("gamePath", JsonPrimitive("old"))
        runCurrent()
        store.setSetting("gamePath", JsonPrimitive("latest"))
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertEquals("latest", store.state.value.game.folder)
        assertEquals("latest", store.state.value.settings.general["gamePath"]?.content)
        store.close()
    }
}
