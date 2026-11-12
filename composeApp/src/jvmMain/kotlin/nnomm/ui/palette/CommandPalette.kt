@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package nnomm.ui.palette

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import nnomm.core.model.ActionKind
import nnomm.core.rules.*
import nnomm.ui.*
import nnomm.ui.kit.*
import nnomm.ui.theme.*

class CommandPalette(private val model: AppModel) : Overlay {
    @Composable
    override fun Content() {
        val state by model.store.state.collectAsState()
        val view = model.ui.palette
        val items = remember(state, view.query) { palItems(state, view.query) }
        val index = view.index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val scroll = rememberLazyListState()
        val c = LocalNnTheme.current.colors
        val field = remember { FocusedField({ view.query }, { view.query = "" }) }
        DisposableEffect(field) {
            onDispose { if (model.ui.focusedField === field) model.ui.focusedField = null }
        }
        fun run(alternative: Boolean = false, at: Int = index) {
            val item = items.getOrNull(at) ?: return
            model.closeOverlay()
            val action = if (alternative) item.alternative ?: item.action else item.action
            if (action.kind == ActionKind.OPENMOD) {
                val installed = action.id in state.installed
                if (
                    model.ui.page !in listOf(Page.Library, Page.Browse) ||
                        model.ui.page == Page.Library && !installed
                )
                    model.ui.go(if (installed) Page.Library else Page.Browse)
            }
            model.perform(action)
        }
        DisposableEffect(model, items, index) {
            val remove =
                model.registerOverlayKeys("palette") { event ->
                    when (event.key) {
                        Key.DirectionDown ->
                            view.index = (index + 1).coerceAtMost((items.size - 1).coerceAtLeast(0))
                        Key.DirectionUp -> view.index = (index - 1).coerceAtLeast(0)
                        Key.Enter -> run(event.isCtrlPressed || event.isMetaPressed)
                        else -> return@registerOverlayKeys false
                    }
                    true
                }
            onDispose { remove() }
        }
        LaunchedEffect(index, items) { if (items.isNotEmpty()) scroll.animateScrollToItem(index) }
        Box(
            Modifier.fillMaxSize()
                .background(c.scrim)
                .pointerInput(Unit) { detectTapGestures { model.closeOverlay() } }
                .padding(horizontal = 16.dp, vertical = 52.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = 602.dp)
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup()
                    .background(c.raise, RoundedCornerShape(11.dp))
                    .border(1.dp, c.line2, RoundedCornerShape(11.dp))
                    .pointerInput(Unit) { detectTapGestures {} }
            ) {
                TextField(
                    view.query,
                    {
                        view.query = it
                        view.index = 0
                    },
                    Modifier.fillMaxWidth().onFocusChanged {
                        if (it.hasFocus) model.ui.focusedField = field
                        else if (model.ui.focusedField === field) model.ui.focusedField = null
                    },
                    placeholder = "Mod, profile, server, setting or action",
                    icon = NnIcons.search,
                    palette = true,
                    autofocus = true,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                LazyColumn(Modifier.weight(1f, false).padding(6.dp), state = scroll) {
                    if (items.isEmpty())
                        item {
                            Label(
                                "Nothing matches. Try a mod name or a word such as \"update\".",
                                Modifier.padding(26.dp),
                                color = c.text2,
                            )
                        }
                    itemsIndexed(items) { i, item ->
                        if (i == 0 || items[i - 1].group != item.group)
                            Label(
                                item.group,
                                Modifier.padding(start = 8.dp, top = 8.dp, bottom = 3.dp),
                                color = c.text3,
                                size = 12f,
                            )
                        Row(
                            Modifier.fillMaxWidth()
                                .height(32.dp)
                                .background(
                                    if (i == index) c.raise2 else c.raise,
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable { run(at = i) }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val mod = state.catalog.find { it.name == item.label }
                            if (mod != null)
                                ModTile(
                                    mod.id,
                                    mod.name,
                                    picture = mod.demoPicture,
                                    size = TileSize.Small,
                                )
                            else
                                Icon(
                                    when (item.group) {
                                        "Settings" -> NnIcons.gear
                                        "Go to" -> NnIcons.browse
                                        else -> NnIcons.search
                                    }
                                )
                            Label(
                                highlightedText(item.label, item.indices),
                                Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Label(item.hint, size = 12f, color = c.text3)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    KeyCap("Up")
                    KeyCap("Down")
                    Label("move", size = 12f, color = c.text3)
                    Label(
                        "Enter  ${if (items.getOrNull(index)?.alternative != null) "open it" else "do it"}",
                        size = 12f,
                        color = c.text3,
                    )
                    items.getOrNull(index)?.alternativeHint?.let {
                        Label("Ctrl Enter  $it", size = 12f, color = c.text3)
                    }
                    Label("Esc  close", size = 12f, color = c.text3)
                }
            }
        }
    }
}
