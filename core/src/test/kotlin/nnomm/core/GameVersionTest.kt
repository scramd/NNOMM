package nnomm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import nnomm.core.io.real.gameVersion

class GameVersionTest {
    private fun text(value: String): ByteArray {
        val padded = (value.length + 3) / 4 * 4
        return byteArrayOf(value.length.toByte(), 0, 0, 0) +
            value.toByteArray(Charsets.ISO_8859_1) +
            ByteArray(padded - value.length)
    }

    // The layout of a built Unity player: no field names, the engine version alone near the
    // start, and later two platform versions followed by the game's own version.
    private val builtPlayer =
        ByteArray(48) +
            "2022.3.62f2".toByteArray() +
            ByteArray(5) +
            text("public.app-category.games") +
            ByteArray(140) +
            text("1.0") +
            text("1.0") +
            text("0.34.6") +
            ByteArray(64)

    @Test
    fun readsTheVersionOfABuiltPlayer() {
        assertEquals("0.34.6", gameVersion(builtPlayer))
    }

    @Test
    fun aNamedVersionStillCounts() {
        assertEquals("0.31.2", gameVersion("xx bundleVersion: 0.31.2 xx".toByteArray()))
    }

    @Test
    fun theEngineVersionAloneIsNotTheGameVersion() {
        assertNull(gameVersion(ByteArray(48) + "2022.3.62f2".toByteArray() + ByteArray(64)))
        assertNull(gameVersion(ByteArray(0)))
    }
}
