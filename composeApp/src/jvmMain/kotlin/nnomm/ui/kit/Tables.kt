@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package nnomm.ui.kit

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch
import nnomm.ui.theme.LocalNnTheme

@Composable
fun LazyList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val c = LocalNnTheme.current.colors
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = state, content = content)
        VerticalScrollbar(
            rememberScrollbarAdapter(state),
            Modifier.align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(10.dp)
                .padding(horizontal = 2.dp),
            style = LocalScrollbarStyle.current.copy(unhoverColor = c.line2, hoverColor = c.line2),
        )
    }
}

/** Selection uses stable keys, so sorting does not silently select another mod. */
@Stable
class RowSelection<K> {
    var picked by mutableStateOf<Set<K>>(emptySet())
        private set

    var focused by mutableStateOf<K?>(null)
        private set

    var anchor by mutableStateOf<K?>(null)
        private set

    fun select(keys: List<K>, key: K, shift: Boolean = false, ctrl: Boolean = false) {
        focused = key
        val start = keys.indexOf(anchor)
        val end = keys.indexOf(key)
        if (shift && start >= 0 && end >= 0) {
            val range = keys.subList(minOf(start, end), maxOf(start, end) + 1).toSet()
            picked = if (ctrl) picked + range else range
        } else {
            anchor = key
            picked =
                if (ctrl) {
                    if (key in picked) picked - key else picked + key
                } else setOf(key)
        }
    }

    fun focus(key: K) {
        focused = key
    }

    fun activate(key: K) {
        focused = key
        anchor = key
    }

    fun selectAll(keys: List<K>) {
        picked = keys.toSet()
    }

    fun clear() {
        picked = emptySet()
    }

    fun retain(keys: Set<K>) {
        picked = picked.intersect(keys)
        if (focused !in keys) focused = null
        if (anchor !in keys) anchor = null
    }
}

@Composable fun <K> rememberRowSelection(): RowSelection<K> = remember { RowSelection() }

/** Install on the table container. Row children can retain independent keyboard controls. */
fun <K> Modifier.tableKeyboard(
    keys: List<K>,
    selection: RowSelection<K>,
    reveal: (Int) -> Unit,
    onToggle: (K) -> Unit = {},
    onToggleSelection: (Set<K>) -> Unit = { keys -> keys.forEach(onToggle) },
    onOpen: (K) -> Unit = {},
    onDelete: (Set<K>) -> Unit = {},
): Modifier =
    onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val index = keys.indexOf(selection.focused)
            when {
                event.isCtrlPressed && event.key == Key.A -> selection.selectAll(keys)
                event.key == Key.DirectionDown ||
                    event.key == Key.DirectionUp ||
                    event.key == Key.MoveHome ||
                    event.key == Key.MoveEnd -> {
                    if (keys.isEmpty()) return@onKeyEvent false
                    val next =
                        when (event.key) {
                            Key.MoveHome -> 0
                            Key.MoveEnd -> keys.lastIndex
                            Key.DirectionDown -> (index + 1).coerceIn(keys.indices)
                            else -> (if (index < 0) 0 else index - 1).coerceIn(keys.indices)
                        }
                    if (!event.isShiftPressed) selection.activate(keys[next])
                    else
                        selection.select(
                            keys,
                            keys[next],
                            event.isShiftPressed,
                            event.isCtrlPressed,
                        )
                    reveal(next)
                }
                event.key == Key.Spacebar ->
                    onToggleSelection(selection.picked.ifEmpty { setOfNotNull(selection.focused) })
                event.key == Key.Enter -> selection.focused?.let(onOpen)
                event.key == Key.Delete ->
                    onDelete(selection.picked.ifEmpty { setOfNotNull(selection.focused) })
                event.key == Key.Escape && selection.picked.isNotEmpty() -> selection.clear()
                else -> return@onKeyEvent false
            }
            true
        }
        .focusable()

/** Null width is the flexible name column. Source and size disappear below 800 dp. */
data class TableColumn(
    val id: String,
    val width: Dp? = null,
    val narrowWidth: Dp? = width,
    val hideNarrow: Boolean = false,
)

val LibraryColumns =
    listOf(
        TableColumn("select", 15.dp),
        TableColumn("on", 30.dp),
        TableColumn("icon", 28.dp),
        TableColumn("name"),
        TableColumn("version", 132.dp, 118.dp),
        TableColumn("source", 104.dp, hideNarrow = true),
        TableColumn("size", 62.dp, hideNarrow = true),
        TableColumn("date", 84.dp, 78.dp),
        TableColumn("menu", 24.dp),
    )

@Composable
fun TableCells(columns: List<TableColumn>, narrow: Boolean, content: @Composable (String) -> Unit) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        columns
            .filterNot { narrow && it.hideNarrow }
            .forEach { column ->
                val width = if (narrow) column.narrowWidth else column.width
                Box(if (width == null) Modifier.weight(1f) else Modifier.width(width)) {
                    content(column.id)
                }
            }
    }
}

@Composable
fun SortHeading(text: String, current: Boolean, ascending: Boolean = true, onClick: () -> Unit) {
    val c = LocalNnTheme.current.colors
    control(Modifier, onClick = onClick) { m, hover, _ ->
        Row(
            m,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Label(text, color = if (current || hover) c.text else c.text3, size = 12f, maxLines = 1)
            if (current) Icon(if (ascending) NnIcons.chevu else NnIcons.chevd, Modifier.size(12.dp))
        }
    }
}

@Composable
fun TableRow(
    modifier: Modifier = Modifier,
    picked: Boolean = false,
    focused: Boolean = false,
    off: Boolean = false,
    onSelect: (shift: Boolean, ctrl: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val t = LocalNnTheme.current
    var hover by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(t.rowHeight)
            .onPointerEvent(PointerEventType.Enter) { hover = true }
            .onPointerEvent(PointerEventType.Exit) { hover = false }
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) {
                shift = it.keyboardModifiers.isShiftPressed
                ctrl = it.keyboardModifiers.isCtrlPressed
            }
            .drawBehind {
                if (picked || hover) drawRect(if (picked) t.colors.raise2 else t.colors.raise)
                drawLine(
                    t.colors.line,
                    Offset(0f, size.height - .5.dp.toPx()),
                    Offset(size.width, size.height - .5.dp.toPx()),
                )
                if (focused)
                    drawRect(
                        t.colors.accent,
                        size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height),
                    )
            }
            .semantics { selected = picked }
            .clickable(
                interactionSource =
                    remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) {
                onSelect(shift, ctrl)
            }
    ) {
        CompositionLocalProvider(LocalTableOff provides off, content = content)
    }
}

val LocalTableOff = compositionLocalOf { false }

@Composable
fun TableValue(text: String, modifier: Modifier = Modifier) {
    val c = LocalNnTheme.current.colors
    Label(
        text,
        modifier,
        color = if (LocalTableOff.current) c.text3 else c.text2,
        width = 88f,
        maxLines = 1,
    )
}

/** Constrains badges before the title, preserving a usable name in narrow tables. */
@Composable
fun NameCell(
    name: String,
    modifier: Modifier = Modifier,
    badges: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalNnTheme.current.colors
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Label(
            name,
            Modifier.weight(1f),
            color = if (LocalTableOff.current) c.text3 else c.text,
            weight = if (LocalTableOff.current) 400 else 600,
            maxLines = 1,
        )
        if (badges != null)
            Row(
                Modifier.widthIn(max = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                content = badges,
            )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <K : Any> Table(
    keys: List<K>,
    modifier: Modifier = Modifier,
    columns: List<TableColumn> = LibraryColumns,
    selection: RowSelection<K> = rememberRowSelection(),
    state: LazyListState = rememberLazyListState(),
    onToggle: (K) -> Unit = {},
    onToggleSelection: (Set<K>) -> Unit = { keys -> keys.forEach(onToggle) },
    onOpen: (K) -> Unit = {},
    onDelete: (Set<K>) -> Unit = {},
    isOff: (K) -> Boolean = { false },
    header: @Composable (String) -> Unit,
    cell: @Composable (K, String) -> Unit,
) {
    val t = LocalNnTheme.current
    val scope = rememberCoroutineScope()
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(keys) { selection.retain(keys.toSet()) }
    BoxWithConstraints(modifier) {
        val narrow = maxWidth <= 800.dp
        LazyList(
            Modifier.fillMaxSize()
                .focusRequester(requester)
                .tableKeyboard(
                    keys,
                    selection,
                    reveal = { index ->
                        scope.launch {
                            val target = index + 1
                            val visible = state.layoutInfo.visibleItemsInfo
                            val row = visible.firstOrNull { it.index == target }
                            if (
                                row == null ||
                                    row.offset < 30 ||
                                    row.offset + row.size > state.layoutInfo.viewportEndOffset
                            ) {
                                state.scrollToItem(target, -30)
                            }
                        }
                    },
                    onToggle = onToggle,
                    onToggleSelection = onToggleSelection,
                    onOpen = onOpen,
                    onDelete = onDelete,
                ),
            state,
        ) {
            stickyHeader {
                Box(
                    Modifier.fillMaxWidth().height(30.dp).background(t.colors.bg).drawBehind {
                        drawLine(
                            t.colors.line2,
                            Offset(0f, size.height - .5f),
                            Offset(size.width, size.height - .5f),
                        )
                    }
                ) {
                    TableCells(columns, narrow, header)
                }
            }
            items(keys, key = { it }) { key ->
                TableRow(
                    Modifier.fillMaxWidth(),
                    key in selection.picked,
                    key == selection.focused,
                    isOff(key),
                    onSelect = { shift, ctrl ->
                        requester.requestFocus()
                        if (shift || ctrl) selection.select(keys, key, shift, ctrl)
                        else {
                            selection.activate(key)
                            onOpen(key)
                        }
                    },
                ) {
                    TableCells(columns, narrow) { cell(key, it) }
                }
            }
        }
    }
}
