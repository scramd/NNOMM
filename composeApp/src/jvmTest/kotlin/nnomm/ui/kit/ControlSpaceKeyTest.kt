@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package nnomm.ui.kit

import androidx.compose.ui.input.key.*
import kotlin.test.*

class ControlSpaceKeyTest {
    private fun typed() = KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = 32)

    @Test fun taoActivatesEachTypedSpace() {
        val keys = ControlSpaceKey()
        repeat(2) { assertTrue(keys.typedActivation(typed())) }
    }

    @Test fun physicalSequenceLeavesActivationToClickable() {
        val keys = ControlSpaceKey()
        assertFalse(keys.typedActivation(KeyEvent(Key.Spacebar, KeyEventType.KeyDown)))
        assertFalse(keys.typedActivation(typed()))
        assertTrue(keys.consumeDuplicate)
        assertFalse(keys.typedActivation(KeyEvent(Key.Spacebar, KeyEventType.KeyUp)))
        assertFalse(keys.consumeDuplicate)
        assertTrue(keys.typedActivation(typed()))
    }

    @Test fun typingAndModifiedSpaceAreUntouched() {
        val keys = ControlSpaceKey()
        assertFalse(keys.typedActivation(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = 47)))
        assertFalse(keys.typedActivation(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = 32, isCtrlPressed = true)))
    }
}
