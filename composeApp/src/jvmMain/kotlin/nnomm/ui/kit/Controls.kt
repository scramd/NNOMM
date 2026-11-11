package nnomm.ui.kit

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import nnomm.ui.theme.*

enum class ButtonKind {
    Default,
    Primary,
    Strong,
    Upd,
    Line,
    Ghost,
    Danger,
}

enum class ControlSize {
    Small,
    Normal,
    Large,
}

enum class PreviewState {
    Normal,
    Hover,
    Pressed,
    Focus,
}

/** Pointer focus does not draw a keyboard ring. Preview states support deterministic shots. */
@Composable
internal fun control(
    modifier: Modifier,
    enabled: Boolean = true,
    preview: PreviewState = PreviewState.Normal,
    role: Role = Role.Button,
    onClick: () -> Unit,
    content: @Composable (Modifier, Boolean, Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    var pointerFocus by remember { mutableStateOf(false) }
    val c = LocalNnTheme.current.colors
    content(
        modifier
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .onPreviewKeyEvent {
                pointerFocus = false
                false
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().type == PointerEventType.Press) pointerFocus = true
                    }
                }
            }
            .drawWithContent {
                drawContent()
                if (preview == PreviewState.Focus || (focused && !pointerFocus)) {
                    drawRoundRect(
                        c.text2,
                        topLeft = androidx.compose.ui.geometry.Offset(-3.dp.toPx(), -3.dp.toPx()),
                        size =
                            androidx.compose.ui.geometry.Size(
                                size.width + 6.dp.toPx(),
                                size.height + 6.dp.toPx(),
                            ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                    )
                }
            }
            .clickable(
                source,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = onClick,
            ),
        hovered || preview == PreviewState.Hover,
        pressed || preview == PreviewState.Pressed,
    )
}

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalNnTheme.current.colors.text,
    size: Float = 13f,
    weight: Int = 400,
    width: Float = 100f,
) =
    BasicText(
        text,
        modifier,
        style = LocalNnTheme.current.text(size, weight, width).copy(color = color),
    )

@Composable
fun Icon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    color: Color = LocalNnTheme.current.colors.text2,
    description: String? = null,
) = Image(icon, description, modifier.size(16.dp), colorFilter = ColorFilter.tint(color))

private fun Color.brighten(f: Float) =
    Color(
        (red * f).coerceAtMost(1f),
        (green * f).coerceAtMost(1f),
        (blue * f).coerceAtMost(1f),
        alpha,
    )

@Composable
fun Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Default,
    size: ControlSize = ControlSize.Normal,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    preview: PreviewState = PreviewState.Normal,
) {
    val c = LocalNnTheme.current.colors
    val bg =
        when (kind) {
            ButtonKind.Primary -> c.accent
            ButtonKind.Strong -> c.strong
            ButtonKind.Default -> c.raise2
            else -> Color.Transparent
        }
    val ink =
        when (kind) {
            ButtonKind.Primary -> c.accentInk
            ButtonKind.Strong -> c.strongInk
            ButtonKind.Upd -> c.accentText
            ButtonKind.Danger -> c.danger
            ButtonKind.Ghost -> c.text2
            else -> c.text
        }
    val border =
        when (kind) {
            ButtonKind.Line -> c.line2
            ButtonKind.Upd -> c.accent
            ButtonKind.Danger -> c.danger
            else -> Color.Transparent
        }
    val h =
        when (size) {
            ControlSize.Small -> 24
            ControlSize.Normal -> 28
            ControlSize.Large -> 34
        }
    val pad =
        when (size) {
            ControlSize.Small -> 8
            ControlSize.Normal -> 11
            ControlSize.Large -> 16
        }
    control(modifier, enabled, preview, onClick = onClick) { m, hover, press ->
        val fill =
            if (hover && kind == ButtonKind.Ghost) c.raise2
            else bg.brighten(if (hover && enabled) 1.12f else 1f)
        Row(
            m.alpha(if (enabled) 1f else .45f)
                .height(h.dp)
                .background(fill, RoundedCornerShape(6.dp))
                .border(1.dp, border, RoundedCornerShape(6.dp))
                .padding(horizontal = pad.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, color = ink)
            Label(
                text,
                color = ink,
                size =
                    if (size == ControlSize.Small) 12f
                    else if (size == ControlSize.Large) 14f else 13f,
                weight = if (kind == ButtonKind.Ghost) 500 else 600,
            )
        }
    }
}

@Composable
fun IconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val c = LocalNnTheme.current.colors
    Tooltip(description) {
        control(
            modifier.semantics { contentDescription = description },
            enabled,
            onClick = onClick,
        ) { m, h, _ ->
            Box(
                m.size(if (small) 22.dp else 28.dp)
                    .background(
                        if (h || selected) c.raise2 else Color.Transparent,
                        RoundedCornerShape(6.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, color = if (h || selected) c.text else c.text2)
            }
        }
    }
}

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String = "Turn on",
) {
    val t = LocalNnTheme.current
    val c = t.colors
    val offset by animateDpAsState(if (checked) 14.dp else 2.dp, tween(t.controlMillis))
    control(
        modifier.semantics {
            contentDescription = description
            toggleableState =
                if (checked) androidx.compose.ui.state.ToggleableState.On
                else androidx.compose.ui.state.ToggleableState.Off
        },
        enabled,
        role = Role.Switch,
        onClick = { onCheckedChange(!checked) },
    ) { m, _, _ ->
        Box(
            m.alpha(if (enabled) 1f else .45f)
                .size(30.dp, 18.dp)
                .background(if (checked) c.onFill else c.line2, CircleShape)
        ) {
            Box(
                Modifier.offset(x = offset, y = 2.dp)
                    .size(14.dp)
                    .background(if (checked) c.cream else c.text2, CircleShape)
            )
        }
    }
}

@Composable
fun CheckBox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String = "Select",
) {
    val c = LocalNnTheme.current.colors
    control(
        modifier.semantics {
            contentDescription = description
            toggleableState =
                if (checked) androidx.compose.ui.state.ToggleableState.On
                else androidx.compose.ui.state.ToggleableState.Off
        },
        enabled,
        role = Role.Checkbox,
        onClick = { onCheckedChange(!checked) },
    ) { m, _, _ ->
        Box(
            m.alpha(if (enabled) 1f else .45f)
                .size(15.dp)
                .background(if (checked) c.strong else Color.Transparent, RoundedCornerShape(4.dp))
                .border(1.5.dp, if (checked) c.strong else c.text3, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(NnIcons.check, Modifier.size(11.dp), c.strongInk)
        }
    }
}

@Composable
fun Radio(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalNnTheme.current.colors
    control(
        modifier.semantics { this.selected = selected },
        enabled,
        role = Role.RadioButton,
        onClick = onClick,
    ) { m, _, _ ->
        Box(
            m.size(15.dp).border(1.5.dp, if (selected) c.on else c.text3, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(7.dp).background(c.on, CircleShape))
        }
    }
}

@Composable
fun Chip(
    text: String,
    count: Int? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalNnTheme.current.colors
    control(modifier.semantics { this.selected = selected }, onClick = onClick) { m, h, _ ->
        Row(
            m.height(26.dp)
                .background(if (selected) c.onFill else Color.Transparent, CircleShape)
                .border(1.dp, if (selected) c.onFill else if (h) c.text3 else c.line2, CircleShape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Label(
                text,
                color = if (selected) c.onInk else if (h) c.text else c.text2,
                weight = if (selected) 600 else 400,
            )
            if (count != null)
                Label(
                    count.toString(),
                    Modifier.alpha(.75f),
                    color = if (selected) c.onInk else c.text2,
                    size = 11.5f,
                )
        }
    }
}

@Composable
fun FilterOption(
    text: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalNnTheme.current.colors
    control(modifier.fillMaxWidth().semantics { this.selected = selected }, onClick = onClick) {
        m,
        h,
        _ ->
        Row(
            m.height(25.dp)
                .background(if (h) c.raise else Color.Transparent, RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CheckBox(selected, { onClick() })
            Label(text, Modifier.weight(1f), color = if (selected || h) c.text else c.text2)
            Label("$count", color = c.text3, size = 11.5f)
        }
    }
}

@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalNnTheme.current.colors
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.raise)
            .border(1.dp, c.line, RoundedCornerShape(6.dp))
    ) {
        options.forEachIndexed { i, text ->
            control(
                Modifier.semantics { this.selected = i == selected },
                onClick = { onSelect(i) },
            ) { m, h, _ ->
                Box(
                    m.height(26.dp)
                        .background(if (i == selected || h) c.raise2 else Color.Transparent)
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Label(
                        text,
                        color = if (i == selected) c.text else c.text2,
                        weight = if (i == selected) 600 else 400,
                    )
                }
            }
        }
    }
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalNnTheme.current.colors
    control(modifier, onClick = onClick) { m, h, _ ->
        BasicText(
            text,
            m,
            style =
                LocalNnTheme.current
                    .text()
                    .copy(
                        color = if (h) c.text else c.text2,
                        textDecoration = TextDecoration.Underline,
                    ),
        )
    }
}

@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val t = LocalNnTheme.current
    val c = t.colors
    var focus by remember { mutableStateOf(false) }
    Row(
        modifier
            .height(28.dp)
            .background(c.raise, RoundedCornerShape(6.dp))
            .border(1.dp, if (focus) c.text3 else c.line, RoundedCornerShape(6.dp))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) Icon(icon, color = if (focus) c.text2 else c.text3)
        BasicTextField(
            value,
            onValueChange,
            Modifier.weight(1f).onFocusChanged { focus = it.hasFocus },
            enabled = enabled,
            singleLine = true,
            textStyle = t.text(),
            cursorBrush = SolidColor(c.text),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Label(placeholder, color = c.text3)
                    inner()
                }
            },
        )
    }
}

@Composable
fun KeyCap(text: String) {
    val c = LocalNnTheme.current.colors
    Box(
        Modifier.border(1.dp, c.line2, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Label(text, color = c.text3, size = 11f, width = 88f)
    }
}

enum class BadgeKind {
    Normal,
    Warn,
    Danger,
    Ok,
}

@Composable
fun Badge(text: String, kind: BadgeKind = BadgeKind.Normal, icon: ImageVector? = null) {
    val c = LocalNnTheme.current.colors
    val bg =
        when (kind) {
            BadgeKind.Warn -> c.warnBg
            BadgeKind.Danger -> c.dangerBg
            BadgeKind.Ok -> Color.Transparent
            else -> c.raise2
        }
    val ink =
        when (kind) {
            BadgeKind.Warn -> c.warn
            BadgeKind.Danger -> c.danger
            BadgeKind.Ok -> c.on
            else -> c.text2
        }
    Row(
        Modifier.height(19.dp)
            .background(bg, RoundedCornerShape(4.dp))
            .then(
                if (kind == BadgeKind.Ok) Modifier.border(1.dp, c.onFill, RoundedCornerShape(4.dp))
                else Modifier
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, Modifier.size(12.dp), ink)
        Label(text, color = ink, size = 11.5f, weight = 600)
    }
}

@Composable
fun Tag(text: String, onClick: (() -> Unit)? = null) {
    val c = LocalNnTheme.current.colors
    @Composable
    fun surface(modifier: Modifier, hovered: Boolean = false) {
        Box(
            modifier
                .height(19.dp)
                .background(if (hovered) c.line2 else c.raise2, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Label(text, color = if (hovered) c.text else c.text2, size = 11.5f, width = 92f)
        }
    }
    if (onClick == null) surface(Modifier)
    else control(Modifier, onClick = onClick) { m, h, _ -> surface(m, h) }
}

@Composable
fun Note(
    text: String,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val c = LocalNnTheme.current.colors
    Row(
        modifier
            .background(if (warn) c.warnBg else Color.Transparent, RoundedCornerShape(6.dp))
            .border(1.dp, if (warn) c.warn else c.line2, RoundedCornerShape(6.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(if (warn) NnIcons.alert else NnIcons.info, color = if (warn) c.warn else c.text2)
        Label(text, Modifier.weight(1f), color = if (warn) c.warn else c.text2)
        action?.invoke()
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, neutral: Boolean = false) {
    val t = LocalNnTheme.current
    val pct by animateFloatAsState(progress.coerceIn(0f, 1f), tween(t.progressMillis))
    Box(
        modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(t.colors.line2).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
        }
    ) {
        Box(
            Modifier.fillMaxHeight()
                .fillMaxWidth(pct)
                .background(if (neutral) t.colors.text3 else t.colors.accent)
        )
    }
}

@Composable
fun DownloadPill(progress: Float, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ProgressBar(progress, Modifier.width(54.dp))
        Label(text, size = 12f, color = LocalNnTheme.current.colors.text2)
    }
}

private val tileColors =
    listOf(
            0xff5d6732,
            0xff8a5a2b,
            0xff3f5f5a,
            0xff6b4f6e,
            0xff7a6a2e,
            0xff4d5d78,
            0xff7b4636,
            0xff566b3c,
        )
        .map { Color(it) }

fun tileHash(id: String): UInt {
    var h = 0u
    id.codePoints().forEach { codePoint ->
        val firstCodeUnit =
            if (codePoint > 0xffff) Character.highSurrogate(codePoint).code else codePoint
        h = h * 31u + firstCodeUnit.toUInt()
    }
    return h
}

fun tileInitials(name: String): String {
    val words = name.replace(Regex("[^A-Za-z0-9 ]"), "").split(' ').filter { it.isNotEmpty() }
    return (if (words.size > 1) "${words[0][0]}${words[1][0]}" else name.take(2)).uppercase(
        java.util.Locale.ROOT
    )
}

enum class TileSize(val pixels: Int, val radius: Int, val text: Float) {
    Small(22, 5, 9.5f),
    Normal(28, 6, 11f),
    Medium(40, 8, 15f),
    Large(56, 10, 20f),
}

@Composable
fun ModTile(
    id: String,
    name: String,
    picture: String? = null,
    size: TileSize = TileSize.Normal,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size.pixels.dp)
            .clip(RoundedCornerShape(size.radius.dp))
            .background(tileColors[(tileHash(id) % 8u).toInt()]),
        contentAlignment = Alignment.Center,
    ) {
        val vector = NnIcons.pictures[picture]
        if (vector != null) Image(vector, null, Modifier.fillMaxSize())
        else
            Label(
                tileInitials(name),
                color = Color(0xfff0ecd8),
                size = size.text,
                weight = 700,
                width = 88f,
            )
    }
}

@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val t = LocalNnTheme.current
    if (t.reduceMotion)
        Box(modifier.height(128.dp).background(t.colors.raise2, RoundedCornerShape(9.dp)))
    else {
        val transition = rememberInfiniteTransition()
        val fraction by
            transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)))
        Box(
            modifier.height(128.dp).clip(RoundedCornerShape(9.dp)).drawBehind {
                val x = (fraction * 2f - 1f) * size.width
                drawRect(
                    Brush.linearGradient(
                        listOf(t.colors.raise, t.colors.raise2, t.colors.raise),
                        start = androidx.compose.ui.geometry.Offset(x, 0f),
                        end = androidx.compose.ui.geometry.Offset(x + size.width, 0f),
                    )
                )
            }
        )
    }
}

@Composable
fun ScrollArea(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(state).padding(end = 10.dp), content = content)
        VerticalScrollbar(
            rememberScrollbarAdapter(state),
            Modifier.align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(10.dp)
                .padding(horizontal = 2.dp),
        )
    }
}
