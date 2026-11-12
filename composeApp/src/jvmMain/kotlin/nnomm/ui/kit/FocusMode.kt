package nnomm.ui.kit

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*

/** One instance per window, shared by every control and in-window overlay. */
@Stable
class FocusMode {
    var keyboard by mutableStateOf(false)
        private set

    internal fun keyboard() {
        keyboard = true
    }

    internal fun pointer() {
        keyboard = false
    }
}

val LocalFocusMode = staticCompositionLocalOf { FocusMode() }

/** Install on the window's root, outside scrolling or clipping containers. */
fun Modifier.windowInputMode(mode: FocusMode): Modifier =
    onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown) mode.keyboard()
            false
        }
        .pointerInput(mode) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) mode.pointer()
                }
            }
        }
