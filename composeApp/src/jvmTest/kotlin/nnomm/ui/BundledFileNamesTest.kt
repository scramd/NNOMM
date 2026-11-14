package nnomm.ui

import java.nio.file.Files
import kotlin.test.*
import nnomm.core.io.real.bundledModName

class BundledFileNamesTest {
    @Test fun bundledModsAreNamedByTheirDllNotTheirContentId() {
        val root = Files.createTempDirectory("bundled-names")
        try {
            val folder = Files.createDirectories(root.resolve("local-hash"))
            Files.writeString(folder.resolve("PacificWingSkins.dll"), "fixture")
            Files.writeString(folder.resolve("README.md"), "fixture")
            assertEquals("PacificWingSkins", bundledModName(folder))
            val named = Files.createDirectories(root.resolve("NuclearMapFocus"))
            Files.writeString(named.resolve("a.dll"), "fixture")
            Files.writeString(named.resolve("b.dll"), "fixture")
            assertEquals("NuclearMapFocus", bundledModName(named))
            assertEquals("Loose", bundledModName(Files.writeString(root.resolve("Loose.dll"), "x")))
        } finally { root.toFile().deleteRecursively() }
    }
}
