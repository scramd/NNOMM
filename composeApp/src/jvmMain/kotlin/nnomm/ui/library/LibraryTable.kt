@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package nnomm.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import nnomm.ui.LocalApp
import nnomm.ui.kit.*
import nnomm.ui.theme.LocalNnTheme

/** Kit table primitives, with a scrollable footline as in the mock's .scroll container. */
@Composable
internal fun LibraryTable(
    keys: List<String>,
    modifier: Modifier,
    selection: RowSelection<String>,
    state: LazyListState,
    onOpen: (String) -> Unit,
    isOff: (String) -> Boolean,
    header: @Composable (String) -> Unit,
    cell: @Composable (String, String) -> Unit,
    rowMenu: (String) -> List<MenuEntry>,
    footer: @Composable () -> Unit,
) {
    val model = LocalApp.current
    val focus = remember { FocusRequester() }
    val c = LocalNnTheme.current.colors
    LaunchedEffect(keys) { selection.retain(keys.toSet()) }
    BoxWithConstraints(
        modifier
            .focusRequester(focus)
            .onFocusChanged { model.ui.pageKeysEnabled = it.isFocused }
            .focusable()
    ) {
        val narrow = maxWidth <= 800.dp
        LazyList(Modifier.fillMaxSize(), state) {
            stickyHeader {
                Box(
                    Modifier.fillMaxWidth().height(30.dp).background(c.bg).drawBehind {
                        drawLine(
                            c.line2,
                            Offset(0f, size.height - .5f),
                            Offset(size.width, size.height - .5f),
                        )
                    }
                ) {
                    TableCells(LibraryColumns, narrow, header)
                }
            }
            items(keys, key = { it }) { key ->
                DisposableEffect(key) {
        val dispose = model.store.metadataVisible(key)
        onDispose { dispose() }
    }
                RightClickMenu(
                    { rowMenu(key) },
                    onOpen = {
                        focus.requestFocus()
                        // The menu and the keys it names must mean this row.
                        selection.contextTarget(key)
                    },
                ) {
                    TableRow(
                        Modifier.fillMaxWidth(),
                        key in selection.picked,
                        key == selection.focused,
                        isOff(key),
                        onSelect = { shift, ctrl ->
                            focus.requestFocus()
                            if (shift || ctrl) selection.select(keys, key, shift, ctrl = true)
                            else {
                                selection.activate(key)
                                onOpen(key)
                            }
                        },
                    ) {
                        TableCells(LibraryColumns, narrow) { cell(key, it) }
                    }
                }
            }
            item(key = "library-footline") { footer() }
        }
    }
}
