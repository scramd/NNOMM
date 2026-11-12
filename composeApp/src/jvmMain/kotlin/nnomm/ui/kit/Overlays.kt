@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package nnomm.ui.kit

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
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

val LocalDialogAutofocus = staticCompositionLocalOf<FocusRequester?> { null }

val LocalShortcutHandler = staticCompositionLocalOf<(KeyEvent) -> Boolean> { { false } }

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
    pointerOffset: DpOffset? = null,
    initialIndex: Int = -1,
) {
    if (!expanded) return
    val app = nnomm.ui.LocalOptionalApp.current
    val dismiss by rememberUpdatedState(onDismiss)
    val registration = remember { { dismiss() } }
    DisposableEffect(app) {
        app?.ui?.dismissMenu = registration
        onDispose { if (app?.ui?.dismissMenu === registration) app?.ui?.dismissMenu = null }
    }
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val inset = with(LocalDensity.current) { 24.dp.roundToPx() }
    val pointer =
        pointerOffset?.let {
            with(LocalDensity.current) { IntOffset(it.x.roundToPx(), it.y.roundToPx()) }
        }
    val position =
        remember(gap, pointer, inset) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val surfaceWidth = popupContentSize.width - inset * 2
                    val surfaceHeight = popupContentSize.height - inset * 2
                    val x =
                        if (anchorBounds.left + surfaceWidth <= windowSize.width) anchorBounds.left
                        else anchorBounds.right - surfaceWidth
                    val y =
                        if (anchorBounds.bottom + gap + surfaceHeight <= windowSize.height)
                            anchorBounds.bottom + gap
                        else anchorBounds.top - surfaceHeight - gap
                    return IntOffset(
                        (pointer?.let { anchorBounds.left + it.x } ?: x).coerceIn(
                            0,
                            (windowSize.width - surfaceWidth).coerceAtLeast(0),
                        ) - inset,
                        (pointer?.let { anchorBounds.top + it.y } ?: y).coerceIn(
                            0,
                            (windowSize.height - surfaceHeight).coerceAtLeast(0),
                        ) - inset,
                    )
                }
            }
        }
    val shortcut = LocalShortcutHandler.current
    Popup(position, onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier.padding(24.dp).onKeyEvent {
                if (it.key == Key.Escape) false else shortcut(it)
            }
        ) {
            MenuSurface(entries, onDismiss, modifier, initialIndex = initialIndex)
        }
    }
}

/** Also exposed for render galleries; live menus use Menu. */
@Composable
fun MenuSurface(
    entries: List<MenuEntry>,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true,
    initialIndex: Int = -1,
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
                        Modifier.fillMaxWidth()
                            .alpha(if (entry.enabled) 1f else .55f)
                            .focusRequester(requesters[i]),
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
                            entry.hint?.let {
                                Label(
                                    it,
                                    Modifier.padding(start = 14.dp),
                                    color = c.text3,
                                    size = 13f,
                                )
                            }
                        }
                    }
            }
        }
    }
    LaunchedEffect(requestFocus) {
        if (requestFocus)
            (initialIndex.takeIf {
                    it in entries.indices && (entries[it] as? MenuEntry.Item)?.enabled == true
                } ?: entries.indexOfFirst { it is MenuEntry.Item && it.enabled })
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
    height: Dp = 28.dp,
    current: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val c = LocalNnTheme.current.colors
    Box(modifier.alpha(if (enabled) 1f else .55f), propagateMinConstraints = true) {
        control(
            Modifier.onPreviewKeyEvent {
                if (
                    it.type == KeyEventType.KeyDown &&
                        it.key in listOf(Key.DirectionDown, Key.DirectionUp)
                ) {
                    onSelect(
                        (selected + if (it.key == Key.DirectionDown) 1 else -1).coerceIn(
                            0,
                            (options.size - 1).coerceAtLeast(0),
                        )
                    )
                    true
                } else false
            },
            enabled,
            onClick = { open = !open },
        ) { m, _, _ ->
            Row(
                m.height(height)
                    .background(if (current) c.raise2 else c.raise, RoundedCornerShape(6.dp))
                    .border(1.dp, if (current) c.text3 else c.line, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.foundation.text.BasicText(
                    options.getOrElse(selected) { "" },
                    Modifier.weight(1f),
                    style = LocalNnTheme.current.text(weight = if (current) 600 else 400),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Icon(NnIcons.chevd)
            }
        }
        Menu(
            open,
            { open = false },
            options.mapIndexed { i, s ->
                MenuEntry.Item(
                    s,
                    icon = if (i == selected) NnIcons.check else null,
                    action = { onSelect(i) },
                )
            },
            initialIndex = selected,
        )
    }
}

@Composable
fun DialogSurface(
    title: String,
    wide: Boolean = false,
    footer: @Composable RowScope.() -> Unit,
    leftFooter: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalNnTheme.current.colors
    BoxWithConstraints(Modifier.widthIn(max = if (wide) 680.dp else 560.dp).fillMaxWidth()) {
        val bodyHeight =
            if (maxHeight == Dp.Infinity) 500.dp else (maxHeight - 110.dp).coerceAtLeast(0.dp)
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
            ) {
                val theme = LocalNnTheme.current
                CompositionLocalProvider(
                    LocalNnTheme provides theme.copy(colors = c.copy(text = c.text2))
                ) {
                    content()
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Row(Modifier.weight(1f), content = leftFooter)
                footer()
            }
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
    initialFocus: FocusRequester? = null,
    leftFooter: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!open) return
    val c = LocalNnTheme.current.colors
    val focus = remember { FocusRequester() }
    val manager = androidx.compose.ui.platform.LocalFocusManager.current
    Box(
        Modifier.fillMaxSize()
            .background(c.scrim)
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            .padding(start = 16.dp, end = 16.dp, top = 52.dp, bottom = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier.focusRequester(focus)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
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
                .pointerInput(Unit) { detectTapGestures {} }
        ) {
            CompositionLocalProvider(LocalDialogAutofocus provides initialFocus) {
                DialogSurface(title, wide, footer, leftFooter, content)
            }
        }
    }
    LaunchedEffect(Unit) { (initialFocus ?: focus).requestFocus() }
}

@Composable
fun Toast(
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = LocalNnTheme.current.colors
    Row(
        modifier
            .widthIn(max = 1200.dp)
            .dropShadow(
                RoundedCornerShape(9.dp),
                Shadow(radius = 50.dp, color = c.shadow, offset = DpOffset(0.dp, 18.dp)),
            )
            .background(c.strong, RoundedCornerShape(9.dp))
            .padding(start = 13.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label(text, Modifier.weight(1f, fill = false), color = c.strongInk, weight = 500)
        if (action != null)
            control(Modifier, onClick = onAction) { m, _, _ ->
                androidx.compose.foundation.text.BasicText(
                    action,
                    m.padding(horizontal = 8.dp, vertical = 3.dp),
                    style =
                        LocalNnTheme.current
                            .text(weight = 700)
                            .copy(
                                color = c.strongInk,
                                textDecoration =
                                    androidx.compose.ui.text.style.TextDecoration.Underline,
                            ),
                )
            }
    }
}

/** Generic focusable anchored surface for the queue and other rich popovers. */
@Composable
fun AnchoredPopover(
    expanded: Boolean,
    onDismiss: () -> Unit,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val shortcut = LocalShortcutHandler.current
    val c = LocalNnTheme.current.colors
    Popup(
        alignment = Alignment.BottomStart,
        offset =
            with(LocalDensity.current) {
                IntOffset(
                    offset.x.roundToPx() - 24.dp.roundToPx(),
                    offset.y.roundToPx() + 24.dp.roundToPx(),
                )
            },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.padding(24.dp)
                .width(300.dp)
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        onDismiss()
                        true
                    } else shortcut(it)
                }
                .dropShadow(
                    RoundedCornerShape(9.dp),
                    Shadow(50.dp, c.shadow, offset = DpOffset(0.dp, 18.dp)),
                )
                .background(c.raise, RoundedCornerShape(9.dp))
                .border(1.dp, c.line2, RoundedCornerShape(9.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}
