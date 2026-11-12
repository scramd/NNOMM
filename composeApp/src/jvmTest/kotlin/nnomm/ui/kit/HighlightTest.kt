package nnomm.ui.kit

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class HighlightTest {
    @Test
    fun groupsContiguousMatchesAndIgnoresOutOfBoundsPositions() {
        val text =
            highlightedText("Minimap", listOf(-1, 0, 1, 1, 2, 5, 99), Color.Yellow, Color.Black)
        assertEquals("Minimap", text.text)
        assertEquals(listOf(0 to 3, 5 to 6), text.spanStyles.map { it.start to it.end })
        assertEquals(Color.Yellow, text.spanStyles.first().item.background)
    }
}
