package nnomm.core.config

import java.nio.file.Files
import java.nio.file.Path

/** Read-only lookup: never creates configs, which BepInEx writes after the plugin first runs. */
fun modConfigFiles(gameFolder: Path, modFolder: Path, id: String, name: String): List<Path> {
    val config = gameFolder.resolve("BepInEx/config")
    if (!Files.isDirectory(config) || !Files.isDirectory(modFolder)) return emptyList()
    val dlls =
        Files.walk(modFolder).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".dll", true) }
                .toList()
        }
    val guids = dlls.flatMap { pluginGuids(Files.readAllBytes(it)) }.toSet()
    val files =
        Files.list(config).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".cfg", true) }
                .sorted()
                .toList()
        }
    val owned = files.filter { it.fileName.toString().dropLast(4) in guids }
    if (owned.isNotEmpty()) return owned
    val names =
        (listOf(id, name) + dlls.map { it.fileName.toString().dropLast(4) })
            .map(::configName)
            .filter { it.isNotEmpty() }
            .toSet()
    return files.filter { configName(it.fileName.toString().dropLast(4)) in names }
}

private fun configName(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

/**
 * BepInPlugin's first constructor string is serialized after the custom-attribute 01 00 prolog as
 * an ECMA-335 SerString (compressed UTF-8 byte length). Search those blobs, rather than arbitrary
 * dotted strings such as assembly references or URLs.
 */
internal fun pluginGuids(bytes: ByteArray): Set<String> {
    if (!bytes.toString(Charsets.ISO_8859_1).contains("BepInPlugin")) return emptySet()
    val result = mutableSetOf<String>()
    for (i in 0 until bytes.size - 3) {
        if (bytes[i] != 1.toByte() || bytes[i + 1] != 0.toByte()) continue
        var pos = i + 2
        val first = bytes[pos++].toInt() and 255
        val count =
            when {
                first < 0x80 -> first
                first < 0xc0 && pos < bytes.size ->
                    ((first and 0x3f) shl 8) or (bytes[pos++].toInt() and 255)
                first < 0xe0 && pos + 2 < bytes.size -> {
                    val value =
                        ((first and 0x1f) shl 24) or
                            ((bytes[pos].toInt() and 255) shl 16) or
                            ((bytes[pos + 1].toInt() and 255) shl 8) or
                            (bytes[pos + 2].toInt() and 255)
                    pos += 3
                    value
                }
                else -> continue
            }
        if (count !in 3..1024 || count > bytes.size - pos) continue
        val value = String(bytes, pos, count, Charsets.UTF_8)
        if (value.matches(Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*"))) result += value
    }
    return result
}
