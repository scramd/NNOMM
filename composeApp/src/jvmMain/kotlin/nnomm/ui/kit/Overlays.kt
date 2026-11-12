@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package nnomm.ui.kit

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import nnomm.ui.theme.*

@Composable
fun Tooltip(text: String, content: @Composable () -> Unit) {
    val c = LocalNnTheme.current.colors
    TooltipArea(
        tooltip = {
            Box(
                Modifier.background(c.strong, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Label(text, color = c.strongInk, size = 12f)
            }
        },
        delayMillis = 500,
        content = content,
    )
}

sealed interface MenuEntry {
    data class Item(
        val text: String,
        val hint: String? = null,
        val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
        val danger: Boolean = false,
        val enabled: Boolean = true,
        val action: () -> Unit,
    ) : MenuEntry

    data class Heading(val text: String) : MenuEntry

    data object Separator : MenuEntry
}

/** Keep Menu in the same Box as its trigger: that Box supplies the anchor bounds. */
@Composable
fun Menu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    entries: List<MenuEntry>,
    modifier: Modifier = Modifier,
) {
    if (!expanded) return
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val position =
        remember(gap) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x =
                        if (anchorBounds.left + popupContentSize.width <= windowSize.width)
                            anchorBounds.left
                        else anchorBounds.right - popupContentSize.width
                    val y =
                        if (
                            anchorBounds.bottom + gap + popupContentSize.height <= windowSize.height
                        )
                            anchorBounds.bottom + gap
                        else anchorBounds.top - popupContentSize.height - gap
                    return IntOffset(
                        x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                        y.coerceIn(
                            0,
                            (windowSize.height - popupContentSize.height).coerceAtLeast(0),
                        ),
                    )
                }
            }
        }
    Popup(position, onDismiss, properties = PopupProperties(focusable = true)) {
        MenuSurface(entries, onDismiss, modifier)
    }
}

/** Also exposed for render galleries; live menus use Menu. */
@Composable
fun MenuSurface(
    entries: List<MenuEntry>,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true,
) {
    val c = LocalNnTheme.current.colors
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val requesters = remember(entries) { entries.map { FocusRequester() } }
    Column(
        modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = 190.dp)
            .dropShadow(
                RoundedCornerShape(9.dp),
                Shadow(radius = 50.dp, color = c.shadow, offset = DpOffset(0.dp, 18.dp)),
            )
            .background(c.raise, RoundedCornerShape(9.dp))
            .border(1.dp, c.line2, RoundedCornerShape(9.dp))
            .padding(5.dp)
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) false
                else
                    when (it.key) {
                        Key.Escape -> {
                            onDismiss()
                            true
                        }
                        Key.DirectionDown -> {
                            focus.moveFocus(FocusDirection.Next)
                            true
                        }
                        Key.DirectionUp -> {
                            focus.moveFocus(FocusDirection.Previous)
                            true
                        }
                        else -> false
                    }
            }
    ) {
        entries.forEachIndexed { i, entry ->
            when (entry) {
                MenuEntry.Separator ->
                    Box(
                        Modifier.padding(vertical = 4.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(c.line)
                    )
                is MenuEntry.Heading ->
                    Label(
                        entry.text,
                        Modifier.padding(start = 9.dp, end = 9.dp, top = 5.dp, bottom = 3.dp),
                        color = c.text3,
                        size = 12f,
                    )
                is MenuEntry.Item ->
                    control(
                        Modifier.fillMaxWidth().focusRequester(requesters[i]),
                        enabled = entry.enabled,
                        onClick = {
                            onDismiss()
                            entry.action()
                        },
                    ) { m, h, _ ->
                        Row(
                            m.height(29.dp)
                                .background(
                                    if (h) c.raise2 else Color.Transparent,
                                    RoundedCornerShape(5.dp),
                                )
                                .padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            entry.icon?.let {
                                Icon(it, color = if (entry.danger) c.danger else c.text)
                            }
                            Label(
                                entry.text,
                                Modifier.weight(1f),
                                color = if (entry.danger) c.danger else c.text,
                            )
                            entry.hint?.let { Label(it, color = c.text3, size = 12f) }
                        }
                    }
            }
        }
    }
    LaunchedEffect(requestFocus) {
        if (requestFocus)
            entries
                .indexOfFirst { it is MenuEntry.Item && it.enabled }
                .takeIf { it >= 0 }
                ?.let { requesters[it].requestFocus() }
    }
}

@Composable
fun Select(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val c = LocalNnTheme.current.colors
    Box(modifier) {
        control(Modifier, enabled, onClick = { open = !open }) { m, _, _ ->
            Row(
                m.height(28.dp)
                    .background(c.raise, RoundedCornerShape(6.dp))
                    .border(1.dp, c.line, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Label(options.getOrElse(selected) { "" })
                Icon(NnIcons.chevd)
            }
        }
        Menu(
            open,
            { open = false },
            options.mapIndexed { i, s -> MenuEntry.Item(s, action = { onSelect(i) }) },
        )
    }
}

@Composable
fun DialogSurface(
    title: String,
    wide: Boolean = false,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalNnTheme.current.colors
    BoxWithConstraints(Modifier.widthIn(max = if (wide) 680.dp else 560.dp).fillMaxWidth()) {
        val bodyHeight =
            if (maxHeight == Dp.Infinity) 500.dp else (maxHeight - 110.dp).coerceIn(0.dp, 500.dp)
        Column(
            Modifier.fillMaxWidth()
                .dropShadow(
                    RoundedCornerShape(11.dp),
                    Shadow(radius = 50.dp, color = c.shadow, offset = DpOffset(0.dp, 18.dp)),
                )
                .background(c.raise, RoundedCornerShape(11.dp))
                .border(1.dp, c.line2, RoundedCornerShape(11.dp))
        ) {
            Label(
                title,
                Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp),
                size = 17f,
                weight = 650,
                width = 110f,
            )
            Column(
                Modifier.heightIn(max = bodyHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                content = footer,
            )
        }
    }
}

/**
 * Put last in the app's full-size Box. Popup owns keyboard focus and traps Tab within its layer.
 */
@Composable
fun Dialog(
    open: Boolean,
    title: String,
    onDismiss: () -> Unit,
    wide: Boolean = false,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!open) return
    val c = LocalNnTheme.current.colors
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(c.scrim)
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
                .padding(24.dp)
                .onPreviewKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                        onDismiss()
                        true
                    } else false
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.pointerInput(Unit) { detectTapGestures {} }) {
                DialogSurface(title, wide, footer, content)
            }
        }
    }
}

@Composable
fun Toast(text: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalNnTheme.current.colors
    Row(
        modifier
            .dropShadow(
                RoundedCornerShape(9.dp),
                Shadow(radius = 50.dp, color = c.shadow, offset = DpOffset(0.dp, 18.dp)),
            )
            .background(c.strong, RoundedCornerShape(9.dp))
            .padding(start = 13.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label(text, color = c.strongInk, weight = 500)
        control(Modifier, onClick = onAction) { m, _, _ ->
            androidx.compose.foundation.text.BasicText(
                action,
                m.padding(horizontal = 8.dp, vertical = 3.dp),
                style =
                    LocalNnTheme.current
                        .text(weight = 700)
                        .copy(
                            color = c.strongInk,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        ),
            )
        }
    }
}
