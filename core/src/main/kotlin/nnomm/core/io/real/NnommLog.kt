package nnomm.core.io.real

import java.nio.file.*
import java.time.Instant
import kotlin.io.path.*
import nnomm.core.io.Logger

/**
 * NNOMM's own error log, nnomm.log in its settings folder. The packaged Windows app has no console,
 * so without this file errors were invisible. Kept below 1 MiB; the previous part is nnomm.old.log.
 */
object NnommLog {
    @Volatile var file: Path? = null

    val logger = Logger { action, error ->
        java.util.logging.Logger.getLogger("nnomm.core").log(java.util.logging.Level.WARNING, action, error)
        write(action, error)
    }

    @Synchronized
    fun write(action: String, error: Throwable?) {
        val target = file ?: return
        try {
            if (target.exists() && target.fileSize() > 1024 * 1024)
                Files.move(target, target.resolveSibling("nnomm.old.log"), StandardCopyOption.REPLACE_EXISTING)
            target.appendText("${Instant.now()} $action\n" + (error?.stackTraceToString().orEmpty()) + "\n")
        } catch (_: Exception) {
            // Logging must never break the action that failed.
        }
    }

    /** The end of the log, for the problem report. */
    fun tail(bytes: Int = 16 * 1024): String? {
        val target = file?.takeIf { it.isRegularFile() } ?: return null
        return try {
            val text = target.readBytes().let { it.copyOfRange((it.size - bytes).coerceAtLeast(0), it.size) }.toString(Charsets.UTF_8)
            if (target.fileSize() > bytes) text.substringAfter('\n', "") else text
        } catch (_: Exception) {
            null
        }
    }
}
