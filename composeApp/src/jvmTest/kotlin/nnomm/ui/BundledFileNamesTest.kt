package nnomm.ui

import java.nio.file.Files
import kotlin.test.*
import nnomm.core.io.real.PackPreview
import nnomm.core.model.*
import nnomm.ui.dialogs.bundledFileName

class BundledFileNamesTest {
    @Test fun warningsUsePayloadNamesInsteadOfContentIds() {
        val root = Files.createTempDirectory("bundled-names")
        try {
            val folder = Files.createDirectories(root.resolve("local-hash"))
            Files.writeString(folder.resolve("PacificWingSkins.dll"), "fixture")
            val item = PackItem(PackKind.LOCAL, "local-content-hash")
            val pack = Pack("Test", "Author", "1", listOf(item))
            val preview = PackPreview(pack, mapOf(item.id to folder.toString()), null, null)
            assertEquals("PacificWingSkins.dll", bundledFileName(item, preview))
            assertEquals("PacificWingSkins.dll", bundledFileName(item.copy(id = "PacificWingSkins.dll"), null))
        } finally { root.toFile().deleteRecursively() }
    }
}
