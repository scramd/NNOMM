package nnomm.app

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import nnomm.ui.dialogs.withPackRecovery

class SaveSafetyTest {
    @Test
    fun extensionIsResolvedBeforeCheckingReplacement() {
        assertEquals(File("pack.nommpack"), finalSaveFile(File("pack"), "nommpack"))
        assertEquals(File("pack.NOMMPACK"), finalSaveFile(File("pack.NOMMPACK"), "nommpack"))
    }

    @Test
    fun everyReplacementKeepsItsOwnRecoverableCopy() = runBlocking {
        val folder = Files.createTempDirectory("pack-save-test").toFile()
        try {
            val file = File(folder, "pack.nommpack").apply { writeText("original") }
            val first = withPackRecovery(file) { file.writeText("second") }!!
            val second = withPackRecovery(file) { file.writeText("third") }!!
            assertEquals("original", first.readText())
            assertEquals("second", second.readText())
            assertEquals("third", file.readText())
            assertNotEquals(first, second)
            assertFailsWith<IllegalStateException> {
                withPackRecovery(file) {
                    file.writeText("partial")
                    error("export failed")
                }
            }
            assertTrue(folder.listFiles()!!.any { it != file && it.readText() == "third" })
        } finally {
            folder.deleteRecursively()
        }
    }
}
