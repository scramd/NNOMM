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

    @Test
    fun namesWithoutSpacesAreSplitIntoWords() {
        assertEquals(listOf("NO", "Tac", "Map"), nameWords("NOTacMap"))
        assertEquals("NM", tileInitials("NuclearMinimap"))
        assertEquals("NM", tileInitials("NuclearMapTrails"))
        assertEquals("CC", tileInitials("ChaseCamPlus"))
        assertEquals("NA", tileInitials("NOAutopilot"))
        assertEquals("KT", tileInitials("KaceyTronic-RWR"))
        assertEquals("NF", tileInitials("NO-FuelBurnEnduranceTimer"))
        assertEquals("MD", tileInitials("mouse_dpi_plus"))
        assertEquals("F1", tileInitials("F-18"))
        assertEquals("SI", tileInitials("SITREP"))
        assertEquals("UU", tileInitials("uuvr"))
        assertEquals("ÆK", tileInitials("ÆgisKai"))
        // Spaces keep the earlier rule.
        assertEquals("NM", tileInitials("Nuclear Minimap"))
        assertEquals("MA", tileInitials("MapTrails 日本語"))
    }

    @Test
    fun nuclearIsLeftOutOnlyWhenChosenAndTwoWordsFollow() {
        assertEquals("MT", tileInitials("NuclearMapTrails", skipNuclear = true))
        assertEquals("MT", tileInitials("Nuclear Map Trails", skipNuclear = true))
        assertEquals("OV", tileInitials("NuclearOptionVRFix", skipNuclear = true))
        assertEquals("NM", tileInitials("NuclearMinimap", skipNuclear = true))
        assertEquals("NU", tileInitials("Nuclear", skipNuclear = true))
        assertEquals("NM", tileInitials("NuclearMapTrails", skipNuclear = false))
        assertEquals("BB", tileInitials("BothBrakesMod", skipNuclear = true))
    }
}
