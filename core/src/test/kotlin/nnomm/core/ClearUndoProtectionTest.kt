package nnomm.core

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import nnomm.core.io.DownloadedFile
import nnomm.core.io.PayloadOrigin
import nnomm.core.io.real.*
import nnomm.core.model.AppState

class ClearUndoProtectionTest {
    @Test
    fun verifiedUnusedVersionSurvivesUndoAndIsClearedAfterExpiry() = runBlocking {
        val home = Files.createTempDirectory("clear-undo-")
        try {
            val archive = home.resolve("mod.zip")
            ZipOutputStream(Files.newOutputStream(archive)).use {
                it.putNextEntry(ZipEntry("mod.dll"))
                it.write("registry payload".toByteArray())
                it.closeEntry()
            }
            val store = RealModStore(home)
            store.put(
                "mod",
                "1",
                DownloadedFile(archive.toString(), PayloadOrigin.REGISTRY_DOWNLOAD, digest(archive)),
            )
            val version = store.version("mod", "1")
            val state = AppState() // No installed mod or profile refers to this version.
            @Suppress("UNCHECKED_CAST")
            val undoUntil =
                RealModStore::class.java.getDeclaredField("undoUntil").let {
                    it.isAccessible = true
                    it.get(store) as MutableMap<String, Long>
                }
            undoUntil.clear() // Stored in an earlier session; no recent-put protection.
            assertEquals(listOf(version), store.unused(state), "The control must be clearable")
            store.remove(listOf("mod")) // Uninstall opens the Undo window.
            assertTrue(store.unused(state).isEmpty())
            store.clearUnused(state)
            assertEquals("registry payload", version.resolve("mod.dll").readText())
            undoUntil["mod"] = 0L // Expire Undo without sleeping.
            assertEquals(listOf(version), store.unused(state))
            store.clearUnused(state)
            assertFalse(
                version.exists(),
                "The same verified version must be deleted after Undo expires",
            )
        } finally {
            deleteTree(home)
        }
    }
}
