package nnomm.ui.dialogs

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keep every replaced pack beside its destination, including when export fails. */
internal suspend fun withPackRecovery(file: File, write: suspend () -> Unit): File? =
    withContext(Dispatchers.IO) {
        val recovery = if (file.exists()) {
            val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"))
            val parent = file.absoluteFile.parentFile.toPath()
            val name = file.nameWithoutExtension
            var number = 1
            var previous = parent.resolve("$name (previous $stamp).nommpack")
            while (true) {
                try { Files.createFile(previous); break }
                catch (_: java.nio.file.FileAlreadyExistsException) {
                    number++
                    previous = parent.resolve("$name (previous $stamp, $number).nommpack")
                }
            }
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
