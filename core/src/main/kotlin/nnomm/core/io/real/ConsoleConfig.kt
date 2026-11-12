package nnomm.core.io.real

/** Preserve other sections and comments when changing BepInEx's console switch. */
internal fun consoleConfig(text: String, enabled: Boolean): String {
    val lines = text.lines().toMutableList()
    val start = lines.indexOfFirst { it.trim() == "[Logging.Console]" }
    if (start < 0) return text.trimEnd() + "\n\n[Logging.Console]\nEnabled = $enabled\n"
    val end =
        (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
    val key =
        (start + 1 until end).firstOrNull {
            lines[it].trim().startsWith("Enabled") &&
                lines[it].substringBefore('=').trim() == "Enabled"
        }
    if (key == null) lines.add(end, "Enabled = $enabled") else lines[key] = "Enabled = $enabled"
    return lines.joinToString("\n")
}
