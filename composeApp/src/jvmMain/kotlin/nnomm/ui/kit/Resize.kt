package nnomm.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Properties
import nnomm.ui.theme.LocalNnTheme

/** The side areas the player can resize by dragging their inner edge. */
enum class Pane(val default: Dp, val min: Dp, val max: Dp) {
    Sidebar(216.dp, 160.dp, 360.dp),
    BrowseFilters(186.dp, 150.dp, 360.dp),
    ModPanel(400.dp, 320.dp, 680.dp),
    SettingsSections(176.dp, 140.dp, 320.dp),
}

/** Widths the player set. Kept with the window placement, so they survive a restart. */
object PaneWidths {
    private val widths = mutableStateMapOf<Pane, Dp>()

    fun of(pane: Pane): Dp = widths[pane] ?: pane.default

    fun set(pane: Pane, width: Dp) {
        widths[pane] = width.coerceIn(pane.min, pane.max)
    }

    /** The width to show: the player's width, but at most [room], so the page beside it keeps space. */
    fun fit(pane: Pane, room: Dp): Dp = of(pane).coerceAtMost(room).coerceAtLeast(pane.min)

    fun reset(pane: Pane) {
        widths.remove(pane)
    }

    private fun key(pane: Pane) = "pane." + pane.name

    fun load(properties: Properties) {
        Pane.entries.forEach { pane ->
            properties
                .getProperty(key(pane))
                ?.toFloatOrNull()
                ?.takeIf { it.isFinite() }
                ?.let { set(pane, it.dp) }
        }
    }

    fun save(properties: Properties) {
        Pane.entries.forEach { pane ->
            val width = widths[pane]
            if (width != null) properties.setProperty(key(pane), width.value.toString())
            else properties.remove(key(pane))
        }
    }
}

private val resizeCursor = PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR))

/**
 * A grip on the inner edge of [pane], inside the pane's own box. Drag to resize; double-click
 * for the default width. [atStart] puts the grip on the left edge, for a pane on the right side
 * of the window: dragging it to the left makes the pane wider.
 */
@Composable
fun BoxScope.PaneEdge(pane: Pane, shown: Dp, atStart: Boolean = false) {
    val c = LocalNnTheme.current.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val shownNow by rememberUpdatedState(shown)
    // The grip moves with the edge, so drag distances are measured in window coordinates.
    var gripX by remember { mutableStateOf(0f) }
    Box(
        Modifier.align(if (atStart) Alignment.CenterStart else Alignment.CenterEnd)
            .fillMaxHeight()
            .width(6.dp)
            .onGloballyPositioned { gripX = it.positionInWindow().x }
            .hoverable(source)
            .pointerHoverIcon(resizeCursor)
            .pointerInput(pane, atStart) {
                var lastDown = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val now = down.uptimeMillis
                    if (now - lastDown < 400) {
                        lastDown = 0L
                        PaneWidths.reset(pane)
                        down.consume()
                        return@awaitEachGesture
                    }
                    lastDown = now
                    down.consume()
                    val startX = gripX + down.position.x
                    val startWidth = shownNow
                    dragging = true
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val moved = with(density) { (gripX + change.position.x - startX).toDp() }
                            PaneWidths.set(pane, if (atStart) startWidth - moved else startWidth + moved)
                            change.consume()
                        }
                    } finally {
                        dragging = false
                    }
                }
            }
    ) {
        if (hovered || dragging)
            Box(
                Modifier.align(if (atStart) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(c.text3)
            )
    }
}
