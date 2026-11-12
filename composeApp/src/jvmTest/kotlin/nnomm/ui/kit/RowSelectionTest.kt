package nnomm.ui.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RowSelectionTest {
    @Test
    fun shiftKeepsTheAnchorWhileCtrlTogglesAndExtends() {
        val rows = (0 until 500).toList()
        val selection = RowSelection<Int>()
        selection.select(rows, 100)
        selection.select(rows, 105, shift = true)
        assertEquals((100..105).toSet(), selection.picked)
        selection.select(rows, 98, shift = true)
        assertEquals((98..100).toSet(), selection.picked)
        selection.select(rows, 300, ctrl = true)
        selection.select(rows, 302, shift = true, ctrl = true)
        assertEquals(setOf(98, 99, 100, 300, 301, 302), selection.picked)
        selection.select(rows, 301, ctrl = true)
        assertEquals(setOf(98, 99, 100, 300, 302), selection.picked)
    }

    @Test
    fun filteringDropsMissingKeysAndSortingPreservesIdentity() {
        val selection = RowSelection<String>()
        selection.select(listOf("a", "b", "c"), "b")
        selection.retain(setOf("c", "b", "a"))
        assertEquals(setOf("b"), selection.picked)
        assertEquals("b", selection.focused)
        selection.retain(setOf("a", "c"))
        assertEquals(emptySet(), selection.picked)
        assertNull(selection.focused)
        assertNull(selection.anchor)
    }
}
