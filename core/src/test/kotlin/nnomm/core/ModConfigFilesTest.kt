package nnomm.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import nnomm.core.config.*

class ModConfigFilesTest {
    private lateinit var root: Path

    @BeforeTest fun prepare() { root = Files.createTempDirectory("nnomm-config-test") }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    private fun blob(guid: String): ByteArray {
        val text = guid.toByteArray()
        val length =
            if (text.size < 128) byteArrayOf(text.size.toByte())
            else byteArrayOf((0x80 or (text.size shr 8)).toByte(), text.size.toByte())
        return "MZ\u0000BepInPlugin\u0000".toByteArray() + byteArrayOf(1, 0) + length + text
    }

    @Test
    fun serializedGuidAndMalformedData() {
        assertEquals(setOf("author.plugin"), pluginGuids(blob("author.plugin")))
        assertEquals(setOf("SimplePlugin"), pluginGuids(blob("SimplePlugin")))
        val long = "author." + "x".repeat(130)
        assertEquals(setOf(long), pluginGuids(blob(long)))
        assertTrue(pluginGuids(blob("author.plugin").dropLast(3).toByteArray()).isEmpty())
        assertTrue(pluginGuids("BepInPlugin\u0000unrelated.plugin".toByteArray()).isEmpty())
        assertTrue(pluginGuids(blob("https://example.org")).isEmpty())
    }

    @Test
    fun guidOwnershipWinsThenExactNameFallback() {
        val config = Files.createDirectories(root.resolve("BepInEx/config"))
        val mod = Files.createDirectories(root.resolve("store/mod/nested"))
        Files.write(mod.resolve("different.dll"), blob("author.plugin") + blob("other.plugin"))
        val guid = Files.createFile(config.resolve("author.plugin.cfg"))
        val second = Files.createFile(config.resolve("other.plugin.cfg"))
        val named = Files.createFile(config.resolve("My Mod.cfg"))
        Files.createFile(config.resolve("My Mod extra.cfg"))
        assertEquals(listOf(guid, second), modConfigFiles(root, mod.parent, "mod", "My Mod"))
        Files.delete(guid)
        Files.delete(second)
        assertEquals(listOf(named), modConfigFiles(root, mod.parent, "mod", "My Mod"))
        Files.delete(named)
        assertTrue(modConfigFiles(root, mod.parent, "mod", "My Mod").isEmpty())
    }

    @Test
    fun missingDirectoriesAreNotCreated() {
        assertTrue(modConfigFiles(root, root.resolve("absent"), "mod", "Mod").isEmpty())
        assertFalse(Files.exists(root.resolve("BepInEx")))
    }
}
