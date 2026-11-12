@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package nnomm.ui.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.*
import nnomm.ui.kit.*
import nnomm.ui.theme.*

/** Dialogs use the mock's wrapping footer, including the four guided-test actions. */
@Composable
internal fun Dialog(
    open: Boolean,
    title: String,
    onDismiss: () -> Unit,
    wide: Boolean = false,
    initialFocus: FocusRequester? = null,
    leftFooter: @Composable RowScope.() -> Unit = {},
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!open) return
    val c = LocalNnTheme.current.colors
    val focus = remember { FocusRequester() }
    val manager = LocalFocusManager.current
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(c.scrim)
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            .padding(start = 16.dp, end = 16.dp, top = 52.dp, bottom = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val available = maxHeight
        Column(
            Modifier.widthIn(max = if (wide) 680.dp else 560.dp)
                .fillMaxWidth()
                .focusRequester(focus)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .focusable()
                .onPreviewKeyEvent {
                    when {
                        it.type != KeyEventType.KeyDown -> false
                        it.key == Key.Escape -> {
                            onDismiss()
                            true
                        }
                        it.key == Key.Tab -> {
                            manager.moveFocus(
                                if (it.isShiftPressed) FocusDirection.Previous
                                else FocusDirection.Next
                            )
                            true
                        }
                        else -> false
                    }
                }
                .dropShadow(
                    RoundedCornerShape(11.dp),
                    Shadow(50.dp, c.shadow, offset = DpOffset(0.dp, 18.dp)),
                )
                .background(c.raise, RoundedCornerShape(11.dp))
                .border(1.dp, c.line2, RoundedCornerShape(11.dp))
                .pointerInput(Unit) { detectTapGestures {} }
        ) {
            Label(
                title,
                Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp)
                    .heightIn(min = 24.dp),
                size = 17f,
                weight = 650,
                width = 110f,
            )
            Column(
                Modifier.heightIn(max = (available - 150.dp).coerceAtLeast(0.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val theme = LocalNnTheme.current
                CompositionLocalProvider(
                    LocalNnTheme provides theme.copy(colors = c.copy(text = c.text2))
                ) {
                    content()
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            CompositionLocalProvider(LocalDialogAutofocus provides initialFocus) {
                Layout(
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    content = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            content = leftFooter,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = footer)
                    },
                ) { children, constraints ->
                    val loose = constraints.copy(minWidth = 0, minHeight = 0)
                    val left = children[0].measure(loose)
                    val right = children[1].measure(loose)
                    val gap = 8.dp.roundToPx()
                    val wraps =
                        left.width > 0 && left.width + gap + right.width > constraints.maxWidth
                    val height =
                        if (wraps) left.height + gap + right.height
                        else maxOf(left.height, right.height)
                    layout(constraints.maxWidth, height) {
                        left.placeRelative(0, 0)
                        right.placeRelative(
                            constraints.maxWidth - right.width,
                            if (wraps) left.height + gap else 0,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { (initialFocus ?: focus).requestFocus() }
}

/** The mock option cards have a 56 dp minimum target and keep their title in primary ink. */
@Composable
internal fun OptionCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalNnTheme.current
    val ink = if (theme.colors.bg == DarkColors.bg) DarkColors.text else LightColors.text
    CompositionLocalProvider(
        LocalNnTheme provides theme.copy(colors = theme.colors.copy(text = ink))
    ) {
        nnomm.ui.kit.OptionCard(
            title,
            description,
            selected,
            onClick,
            Modifier.heightIn(min = 56.dp),
        )
    }
}
