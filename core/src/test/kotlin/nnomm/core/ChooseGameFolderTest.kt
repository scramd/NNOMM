package nnomm.core

import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import nnomm.core.demo.Demo
import nnomm.core.io.GameFolder
import nnomm.core.io.real.deleteTree
import nnomm.core.store.AppStore

class ChooseGameFolderTest {
    private fun store(found: Int): AppStore {
        val demo = Demo.io()
        val game = object : GameFolder by demo.gameFolder {
            override suspend fun modsToTakeOver(folder: String) = found
        }
        return AppStore(Demo.state(), demo.copy(gameFolder = game))
    }

    private suspend fun AppStore.awaitToast() =
        withTimeout(5000) {
            while (state.value.toasts.isEmpty()) delay(10)
            state.value.toasts.last()
        }

    @Test
    fun aFolderWithoutTheGameIsRefused() = runBlocking {
        val dir = Files.createTempDirectory("not-the-game")
        val a = store(found = 3)
        try {
            val before = a.state.value.settings.general["gamePath"]
            a.chooseGameFolder(dir.toString())
            val toast = a.awaitToast()
            assertTrue(toast.text.contains("NuclearOption.exe"), toast.text)
            assertEquals(before, a.state.value.settings.general["gamePath"])
        } finally {
            a.shutdown()
            deleteTree(dir)
        }
    }

    @Test
    fun theGameFolderIsUsedAndItsModsCanBeTakenOver() = runBlocking {
        val dir = Files.createTempDirectory("game")
        dir.resolve("NuclearOption.exe").createFile()
        val a = store(found = 2)
        try {
            a.chooseGameFolder(dir.toString())
            val toast = a.awaitToast()
            assertEquals(dir.toString(), a.state.value.settings.general["gamePath"]?.content)
            assertTrue(toast.text.startsWith("2 mods are already in this game folder"), toast.text)
            assertEquals("Take over", toast.action)
        } finally {
            a.shutdown()
            deleteTree(dir)
        }
    }

    @Test
    fun noOfferWithoutModsToTakeOver() = runBlocking {
        val dir = Files.createTempDirectory("game")
        dir.resolve("NuclearOption.exe").createFile()
        val a = store(found = 0)
        try {
            a.chooseGameFolder(dir.toString())
            withTimeout(5000) {
                while (a.state.value.settings.general["gamePath"]?.content != dir.toString()) delay(10)
            }
            delay(100)
            assertTrue(a.state.value.toasts.none { it.action == "Take over" })
        } finally {
            a.shutdown()
            deleteTree(dir)
        }
    }
}
