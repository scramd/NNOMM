@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import kotlinx.coroutines.flow.*
import nnomm.ui.*
import nnomm.ui.kit.*
import nnomm.ui.theme.*

class CommandPalette(private val model: AppModel) : Overlay {
    @Composable
    override fun Content() {
        val state by remember(model) {
            model.store.state.map { it.copy(toasts = emptyList(), downloads = emptyList()) }.distinctUntilChanged()
        }.collectAsState(model.store.state.value)
        val view = model.ui.palette
        val items = remember(state, view.query) {
            val base = palItems(state, view.query)
            val server = PaletteItem("Go to", "Servers", "", nnomm.core.model.UiAction("navigate", "servers"))
            val match = fuzzy(view.query.trim(), "Servers")
            if (model.showServers() && (view.query.isBlank() || match != null)) {
                val at = base.indexOfLast { it.group == "Go to" } + 1
                base.toMutableList().apply { add(at, server.copy(indices = match?.indices.orEmpty())) }
            } else base
        }
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
                            view.index = if (items.isEmpty()) 0 else (index + 1) % items.size
                        Key.DirectionUp -> view.index = if (items.isEmpty()) 0 else (index + items.size - 1) % items.size
                        Key.Enter -> run(event.isCtrlPressed || event.isMetaPressed)
                        else -> return@registerOverlayKeys false
                    }
                    true
                }
            onDispose { remove() }
        }
        LaunchedEffect(index, items) {
            if (items.isNotEmpty() && scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.scrollToItem(index)
        }
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
                    placeholder = "Mod, profile, setting or action",
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
                            else if (item.group != "Profiles")
                                Icon(
                                    when (item.group) {
                                        "Settings" -> NnIcons.gear
                                        "Go to" -> when (item.action.id) {
                                            "library" -> NnIcons.library
                                            "servers" -> NnIcons.server
                                            "settings" -> NnIcons.gear
                                            else -> NnIcons.browse
                                        }
                                        else -> when (item.action.kind) {
                                            ActionKind.PLAY -> NnIcons.play
                                            ActionKind.LOOKFORUPDATES -> NnIcons.refresh
                                            ActionKind.ADDFILE, ActionKind.NEWPROFILE -> NnIcons.plus
                                            ActionKind.IMPORT -> NnIcons.box
                                            ActionKind.SHARE -> NnIcons.share
                                            ActionKind.TROUBLE -> NnIcons.split
                                            else -> NnIcons.folder
                                        }
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
                FlowRow(
                    Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        KeyCap("Up")
                        KeyCap("Down")
                        Label("move", size = 12f, color = c.text3)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        KeyCap("Enter")
                        Label(if (items.getOrNull(index)?.alternative != null) "open it" else "do it", size = 12f, color = c.text3)
                    }
                    items.getOrNull(index)?.alternativeHint?.let {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            KeyCap("Ctrl Enter")
                            Label(it, size = 12f, color = c.text3)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        KeyCap("Esc")
                        Label("close", size = 12f, color = c.text3)
                    }
                }
            }
        }
    }
}
