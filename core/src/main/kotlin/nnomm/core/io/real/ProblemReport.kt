package nnomm.core.io.real

import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.io.path.*
import nnomm.core.model.AppState
import nnomm.core.rules.*

/** Read-only report; callers show it before copying or sharing it. */
fun problemReport(state: AppState): String = buildString {
    appendLine("NNOMM ${state.appVersion}")
    appendLine("Game folder: ${state.game.folder ?: "Not selected"}")
    appendLine("Game version: ${state.game.version ?: "Unknown"}")
    appendLine(
        "Mod loader: ${state.game.loaderVersion ?: if (state.game.loaderPresent) "Version unknown" else "Not installed"}"
    )
    appendLine("Profile: ${state.profile().name}")
    appendLine()
    state.installed.keys
        .sortedBy { state.mod(it).name }
        .forEach { id ->
            appendLine(
                "${state.mod(id).name} ${verOf(state, id)}: ${if (isOn(state, id)) "on" else "off"}"
            )
            problems(state, id).forEach { appendLine("  ${it.text}") }
        }
    appendLine()
    appendLine("Last BepInEx log (last 64 KiB):")
    val log = state.game.folder?.let { Path.of(it).resolve("BepInEx/LogOutput.log") }
    if (log?.isRegularFile() != true) appendLine("No game log found.")
    else
        try {
            RandomAccessFile(log.toFile(), "r").use { file ->
                val start = (file.length() - 65536).coerceAtLeast(0)
                file.seek(start)
                val bytes = ByteArray((file.length() - start).toInt())
                file.readFully(bytes)
                val text = bytes.toString(Charsets.UTF_8)
                appendLine(if (start > 0) text.substringAfter('\n', "") else text)
            }
        } catch (_: java.io.IOException) {
            appendLine("Could not read the game log. Close the game and try again.")
        }
}
