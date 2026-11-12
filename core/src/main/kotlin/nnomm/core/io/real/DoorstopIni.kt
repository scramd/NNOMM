package nnomm.core.io.real

/**
 * Doorstop's `enabled` key lives in the loader's own section (`[General]` in Doorstop 4,
 * `[UnityDoorstop]` in older files). A key of the same name in another section is not the loader's,
 * so it never counts and is never changed.
 */
internal object DoorstopIni {
    /**
     * The file is read and written as ISO-8859-1, which keeps every byte as it is, whatever the
     * file's real encoding. All keys and headers NNOMM looks for are ASCII.
     */
    val charset: java.nio.charset.Charset = Charsets.ISO_8859_1

    // An optional UTF-8 byte-order mark, as ISO-8859-1 characters, may start the first line.
    private val header =
        Regex("(?im)^(?:\u00EF\u00BB\u00BF)?[ \t]*\\[(?:UnityDoorstop|General)\\][ \t]*(?=\\r?$)")
    private val bom = "\u00EF\u00BB\u00BF"
    private val nextSection = Regex("(?m)^[ \t]*\\[")
    private val key = Regex("(?im)^(\\s*enabled\\s*=)[^\\r\\n]*")

    /**
     * The loader section's body: after its header line up to the next section. A file without a
     * loader section header counts its keys before the first section as the loader's.
     */
    private fun body(text: String): IntRange? {
        val found = header.find(text)
        if (found == null) {
            val end = nextSection.find(text)?.range?.first ?: text.length
            return if (end == 0) null else 0 until end
        }
        val start = found.range.last + 1
        val end = nextSection.find(text, start)?.range?.first ?: text.length
        return start until end
    }

    fun hasEnabled(text: String): Boolean =
        body(text)?.let { key.containsMatchIn(text.substring(it.first, it.last + 1)) } ?: false

    fun setEnabled(text: String, value: Boolean): String {
        val range = body(text) ?: return text
        val part = text.substring(range.first, range.last + 1)
        return text.substring(0, range.first) +
            key.replace(part) { it.groupValues[1] + " " + value } +
            text.substring(range.last + 1)
    }

    /**
     * Puts `enabled = true` into the loader section. Without a loader section it adds Doorstop 4's
     * `[General]` section at the top (after a byte-order mark), the section Doorstop 4 reads.
     */
    fun restoreEnabled(text: String): String {
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val found = header.find(text)
        if (found != null) {
            val at = found.range.last + 1
            return text.substring(0, at) + newline + "enabled = true" + text.substring(at)
        }
        val start = if (text.startsWith(bom)) bom.length else 0
        return text.substring(0, start) +
            "[General]" +
            newline +
            "enabled = true" +
            newline +
            text.substring(start)
    }
}
