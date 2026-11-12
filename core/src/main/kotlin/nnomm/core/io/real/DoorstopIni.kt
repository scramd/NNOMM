package nnomm.core.io.real

/** A byte-preserving editor for the first section of each name, as read by Doorstop. */
internal object DoorstopIni {
    val charset = Charsets.ISO_8859_1
    private const val UTF8_BOM = "\u00ef\u00bb\u00bf"

    // Wine PROFILE_isspaceW; CR/LF are already split as line endings.
    private fun profileSpace(c: Char) = c in '\u0009'..'\u000d' || c == '\u001a' || c == ' '

    class Document internal constructor(bytes: ByteArray) {
        private val encoding =
            when {
                bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() ->
                    Charsets.UTF_16LE
                bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() ->
                    Charsets.UTF_16BE
                else -> charset
            }
        private val prefix =
            when {
                encoding != charset -> bytes.copyOfRange(0, 2)
                bytes.size >= 3 &&
                    bytes.copyOfRange(0, 3).contentEquals(UTF8_BOM.toByteArray(charset)) ->
                    bytes.copyOfRange(0, 3)
                else -> byteArrayOf()
            }
        private val lines =
            split(
                try {
                    encoding
                        .newDecoder()
                        .decode(
                            java.nio.ByteBuffer.wrap(bytes, prefix.size, bytes.size - prefix.size)
                        )
                        .toString()
                } catch (e: java.nio.charset.CharacterCodingException) {
                    throw nnomm.core.io.DoorstopSettingsFailure(e)
                }
            )
        private val newline
            get() = lines.firstOrNull { it.end.isNotEmpty() }?.end ?: "\n"

        private fun section(name: String): Int? =
            lines.indices.firstOrNull { header(lines[it].text)?.equals(name, true) == true }

        private fun key(section: Int, name: String): Int? =
            ((section + 1) until lines.size)
                .takeWhile { header(lines[it].text) == null }
                .firstOrNull { keyName(lines[it].text)?.equals(name, true) == true }

        private fun value(at: Int): String {
            val value = lines[at].text.substringAfter('=', "").trim(::profileSpace)
            return if (
                value.length >= 2 &&
                    (value.first() == '"' || value.first() == '\'') &&
                    value.last() == value.first()
            )
                value.substring(1, value.lastIndex)
            else value
        }

        val healthy: Boolean
            get() =
                section("General")
                    ?.let { key(it, "target_assembly") }
                    ?.let { value(it).isNotEmpty() } == true

        // Doorstop initializes false and recognizes only true/false; missing keys default true.
        val explicitlyDisabled: Boolean
            get() =
                section("General")
                    ?.let { key(it, "enabled") }
                    ?.let { '=' in lines[it].text && !value(it).equals("true", true) } == true

        fun hasEnabled(): Boolean = section("General")?.let { key(it, "enabled") != null } == true

        fun setEnabled(value: Boolean, includeLegacy: Boolean = false): ByteArray {
            val names = if (includeLegacy) listOf("General", "UnityDoorstop") else listOf("General")
            if (names.none { section(it) != null }) {
                lines.addAll(
                    0,
                    listOf(Line("[General]", newline), Line("enabled = $value", newline)),
                )
            } else
                for (name in names) {
                    val start = section(name) ?: continue
                    val at = key(start, "enabled")
                    if (at != null) {
                        val line = lines[at]
                        line.text =
                            if ('=' in line.text) line.text.substringBefore('=') + "= $value"
                            else line.text.trimEnd(::profileSpace) + " = $value"
                    } else {
                        val end = lines[start].end.ifEmpty { newline }
                        lines[start].end = end
                        lines.add(start + 1, Line("enabled = $value", end))
                    }
                }
            return bytes()
        }

        fun bytes(): ByteArray =
            prefix + lines.joinToString("") { it.text + it.end }.toByteArray(encoding)
    }

    private data class Line(var text: String, var end: String)

    // Only CRLF, LF and CR are line endings. In particular, byte 0x85 is ordinary data.
    private fun split(text: String): MutableList<Line> {
        val lines = mutableListOf<Line>()
        var start = 0
        var at = 0
        while (at < text.length) {
            if (text[at] != '\r' && text[at] != '\n') {
                at++
                continue
            }
            val end = if (text[at] == '\r' && text.getOrNull(at + 1) == '\n') at + 2 else at + 1
            lines += Line(text.substring(start, at), text.substring(at, end))
            at = end
            start = at
        }
        if (start < text.length) lines += Line(text.substring(start), "")
        return lines
    }

    // An unmatched '[' is a key line, not a section boundary.
    private fun header(text: String): String? {
        val trimmed = text.trim(::profileSpace)
        if (!trimmed.startsWith('[')) return null
        val last = trimmed.lastIndexOf(']')
        return if (last > 0) trimmed.substring(1, last).trim(::profileSpace) else null
    }

    private fun keyName(text: String): String? {
        val trimmed = text.trimStart(::profileSpace)
        if (trimmed.startsWith(';') || trimmed.startsWith('#')) return null
        return trimmed.substringBefore('=').trim(::profileSpace)
    }

    fun read(bytes: ByteArray) = Document(bytes)

    fun hasEnabled(text: String) = read(text.toByteArray(charset)).hasEnabled()

    fun setEnabled(text: String, value: Boolean) =
        String(read(text.toByteArray(charset)).setEnabled(value, true), charset)

    fun restoreEnabled(text: String) =
        String(read(text.toByteArray(charset)).setEnabled(true), charset)
}
