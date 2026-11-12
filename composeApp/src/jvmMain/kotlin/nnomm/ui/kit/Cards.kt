package nnomm.ui.kit

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import nnomm.ui.theme.LocalNnTheme

/** Non-interactive glyph, for a parent that owns the single click and Tab stop. */
@Composable
internal fun SelectionGlyph(selected: Boolean, radio: Boolean) {
    val c = LocalNnTheme.current.colors
    val shape = if (radio) CircleShape else RoundedCornerShape(4.dp)
    Box(
        Modifier.size(if (radio) 14.dp else 15.dp)
            .background(if (selected && !radio) c.strong else Color.Transparent, shape)
            .border(
                if (selected && radio) 4.5.dp else 1.5.dp,
                if (selected) c.strong else c.text3,
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected && !radio)
            Icon(NnIcons.check, Modifier.size(11.dp), c.strongInk, strokeWidth = 2.2f)
    }
}

@Composable
fun OptionCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalNnTheme.current.colors
    control(
        modifier.fillMaxWidth().semantics { this.selected = selected },
        role = Role.RadioButton,
        onClick = onClick,
    ) { m, _, _ ->
        Row(
            m.border(
                    1.dp,
                    if (selected) c.text3 else c.line,
                    RoundedCornerShape(LocalNnTheme.current.radius),
                )
                .padding(horizontal = 11.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.padding(top = 2.dp)) { SelectionGlyph(selected, true) }
            Column {
                Label(title, weight = 600)
                Label(description, color = c.text2)
            }
        }
    }
}

@Composable
fun ModCard(
    id: String,
    name: String,
    author: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    picture: String? = null,
    imageUrl: String? = null,
    strip: Boolean = false,
    tags: List<String> = emptyList(),
    why: String? = null,
    footer: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalNnTheme.current.colors
    val shape = RoundedCornerShape(LocalNnTheme.current.radiusLarge)
    control(modifier.semantics { selected = current }, onClick = onClick) { m, hover, _ ->
        Column(
            m.background(c.raise, shape)
                .border(
                    if (current) 2.dp else 1.dp,
                    if (current) c.text3 else if (hover) c.line2 else c.line,
                    shape,
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModTile(id, name, picture, TileSize.Medium, imageUrl = imageUrl)
                Column(Modifier.weight(1f)) {
                    Label(name, size = 13.5f, weight = 600, maxLines = 2, lineHeight = 16.875.sp)
                    Label(author, color = c.text3, size = 12f, maxLines = 1)
                }
            }
            Label(
                description,
                Modifier.heightIn(min = if (strip) 0.dp else 35.dp),
                color = c.text2,
                size = 12.5f,
                maxLines = if (strip) 1 else 2,
            )
            if (tags.isNotEmpty())
                Layout(content = { tags.forEach { Tag(it) } }) { measurables, constraints ->
                    val chips = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity)) }
                    val gap = 4.dp.roundToPx()
                    var used = 0
                    val visible = chips.takeWhile { chip ->
                        val next = used + (if (used == 0) 0 else gap) + chip.width
                        (next <= constraints.maxWidth).also { if (it) used = next }
                    }
                    layout(used, visible.maxOfOrNull { it.height } ?: 0) {
                        var x = 0
                        visible.forEach { it.placeRelative(x, 0); x += it.width + gap }
                    }
                }
            why?.let { Label(it, color = c.text3, size = 12f, maxLines = 1) }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                content = footer,
            )
        }
    }
}

@Composable
fun <T : Any> CardGrid(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    card: @Composable (T) -> Unit,
) {
    LazyVerticalGrid(
        GridCells.Adaptive(228.dp),
        modifier,
        state,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = key) { card(it) }
    }
}

@Composable
fun <T : Any> CardStrip(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    card: @Composable (T) -> Unit,
) {
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier.fillMaxWidth().hoverable(interaction)) {
        LazyRow(
            Modifier.fillMaxWidth()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (scroll.canScrollForward) drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Black, Color.Transparent),
                            startX = (size.width - 56.dp.toPx()).coerceAtLeast(0f),
                            endX = size.width,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
            state = scroll,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 10.dp),
        ) {
            items(items, key = key) { Box(Modifier.width(244.dp)) { card(it) } }
        }
        if (hovered || scroll.isScrollInProgress) androidx.compose.foundation.HorizontalScrollbar(
            androidx.compose.foundation.rememberScrollbarAdapter(scroll),
            Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        )
    }
}

@Composable
fun PanelTabs(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalNnTheme.current.colors
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp).drawBehind {
            drawLine(
                c.line,
                androidx.compose.ui.geometry.Offset(0f, size.height),
                androidx.compose.ui.geometry.Offset(size.width, size.height),
            )
        }
    ) {
        titles.forEachIndexed { i, title ->
            control(
                Modifier.semantics { this.selected = i == selected },
                onClick = { onSelect(i) },
            ) { m, hover, _ ->
                Box(
                    m.drawBehind {
                            if (i == selected)
                                drawRect(
                                    c.strong,
                                    topLeft =
                                        androidx.compose.ui.geometry.Offset(
                                            0f,
                                            size.height - 2.dp.toPx(),
                                        ),
                                    size =
                                        androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()),
                                )
                        }
                        .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp)
                ) {
                    Label(
                        title,
                        color = if (selected == i || hover) c.text else c.text2,
                        weight = 500,
                    )
                }
            }
        }
    }
}

@Composable
fun NavItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    current: Boolean = false,
    count: Int? = null,
    initial: String? = null,
    dot: Color? = null,
    tail: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalNnTheme.current.colors
    control(modifier.fillMaxWidth().semantics { selected = current }, onClick = onClick) {
        m,
        hover,
        _ ->
        Row(
            m.height(32.dp)
                .background(
                    if (current) c.raise2 else if (hover) c.raise else Color.Transparent,
                    RoundedCornerShape(LocalNnTheme.current.radius),
                )
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val ink = if (current || hover) c.text else c.text2
            icon?.let { Icon(it, color = ink) }
            if (initial != null)
                Box(
                    Modifier.size(22.dp).background(c.raise2, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Label(initial, size = 11f, weight = 600)
                }
            dot?.let { Box(Modifier.size(6.dp).background(it, CircleShape)) }
            Label(title, Modifier.weight(1f), color = ink, weight = 500, maxLines = 1)
            count?.let { Label(it.toString(), color = c.accentText, size = 11.5f, weight = 600) }
            tail?.invoke(this)
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = NnIcons.browse,
    action: (@Composable () -> Unit)? = null,
) {
    val c = LocalNnTheme.current.colors
    Box(modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 380.dp).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, Modifier.size(28.dp), c.text3)
            Label(
                title,
                size = 16f,
                weight = 600,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Label(
                description,
                color = c.text2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            action?.invoke()
        }
    }
}

@Composable
fun SplitButton(
    text: String,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Default,
    play: Boolean = false,
    icon: ImageVector? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        Button(
            text,
            onClick,
            Modifier.weight(1f),
            kind = kind,
            size = if (play) ControlSize.Large else ControlSize.Normal,
            icon = icon,
            shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp),
        )
        Button(
            "More actions",
            onMenu,
            Modifier.width(if (play) 30.dp else 32.dp).semantics {
                contentDescription = "More actions"
            },
            kind = kind,
            size = if (play) ControlSize.Large else ControlSize.Normal,
            icon = NnIcons.chevd,
            shape = RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp),
            iconOnly = true,
        )
    }
}
