package nnomm.ui.dialogs

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keep every replaced pack beside its destination, including when export fails. */
internal suspend fun withPackRecovery(file: File, write: suspend () -> Unit): File? =
    withContext(Dispatchers.IO) {
        val recovery = if (file.exists()) {
            val previous = Files.createTempFile(file.absoluteFile.parentFile.toPath(), "${file.name}.previous-", ".nommpack")
            try {
                Files.copy(file.toPath(), previous, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                Files.deleteIfExists(previous)
                throw e
            }
            previous.toFile()
        } else null
        write()
        recovery
    }
