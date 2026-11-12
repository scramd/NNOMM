package nnomm.core.io.real

private val versionText = Regex("\\d+(?:\\.\\d+){1,3}")

/**
 * The game version from Unity's `globalgamemanagers`.
 *
 * A built player holds no field names. PlayerSettings stores three version strings one after the
 * other, each as a little-endian length and its bytes, padded to four bytes: two platform
 * versions and then bundleVersion, the version the game shows. The engine version elsewhere in
 * the file stands alone, so it is never taken for the game version. A file that names
 * bundleVersion in text is read by that name.
 */
internal fun gameVersion(bytes: ByteArray): String? {
    Regex("bundleVersion[\\x00\\s:=]+(\\d+\\.\\d+(?:\\.\\d+){0,2})")
        .find(String(bytes, Charsets.ISO_8859_1))
        ?.let {
            return it.groupValues[1]
        }
    // Returns the string at [at] and the offset after its padding.
    fun versionAt(at: Int): Pair<String, Int>? {
        if (at + 4 > bytes.size) return null
        val length =
            (bytes[at].toInt() and 0xff) or
                ((bytes[at + 1].toInt() and 0xff) shl 8) or
                ((bytes[at + 2].toInt() and 0xff) shl 16) or
                ((bytes[at + 3].toInt() and 0xff) shl 24)
        if (length !in 3..24 || at + 4 + length > bytes.size) return null
        val text = String(bytes, at + 4, length, Charsets.ISO_8859_1)
        if (!versionText.matches(text)) return null
        return text to at + 4 + (length + 3) / 4 * 4
    }
    var at = 0
    while (at + 4 <= bytes.size) {
        val first = versionAt(at)
        val second = first?.let { versionAt(it.second) }
        val third = second?.let { versionAt(it.second) }
        if (third != null) return third.first
        at += 4
    }
    return null
}
