package nnomm.ui.kit

import kotlin.test.*

class TileRulesTest {
    @Test
    fun hashMatchesJavascriptUnsignedOverflow() {
        assertEquals(0u, tileHash(""))
        assertEquals(106641849u, tileHash("php23"))
        assertEquals(1676672617u, tileHash("autopilot"))
        // JS for-of visits a surrogate pair once and charCodeAt(0) returns the high surrogate.
        assertEquals(55357u, tileHash("🚀"))
    }

    @Test
    fun initialsMatchTheMock() {
        assertEquals("AP", tileInitials("Auto Pilot"))
        assertEquals("MI", tileInitials("Minimap"))
        assertEquals("FX", tileInitials("F-18   XL"))
        assertEquals("ÆG", tileInitials("Ægis"))
    }
}
