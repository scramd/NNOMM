@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package nnomm.ui.library

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.ui.*
import nnomm.ui.kit.*
import nnomm.ui.theme.LocalNnTheme

@Composable
fun LibraryPage() {
    val model = LocalApp.current
    val view = model.ui.library
    // Toast and download ticks must not invalidate the library's rules or every row.
    val stateFlow =
        remember(model) {
            model.store.state
                .map { it.copy(toasts = emptyList(), downloads = emptyList()) }
                .distinctUntilChanged()
        }
    val s by stateFlow.collectAsState(model.store.state.value)
    val filter = LibraryFilter(view.filter, view.query, view.sort, if (view.descending) -1 else 1)
    val ids = remember(s, filter) { libList(s, filter) }
    val counts = remember(s) { libCounts(s) }
    var previousInstalled by remember { mutableStateOf(s.installed.keys) }
    LaunchedEffect(s.installed) {
        val removed = previousInstalled - s.installed.keys
        if (model.ui.panel?.modId in removed) model.ui.panel = null
        previousInstalled = s.installed.keys
    }
    val selection = remember {
        RowSelection<String>().apply {
            selectAll(view.selection.toList())
            view.anchorId?.let(::activate)
            view.focusedId?.let(::focus)
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val search = remember { FocusRequester() }
    val field = remember { FocusedField({ view.query }, { view.query = "" }) }
    val t = LocalNnTheme.current
    val c = t.colors
    LaunchedEffect(view.selection) {
        if (selection.picked != view.selection) selection.selectAll(view.selection.toList())
    }
    LaunchedEffect(selection) {
        snapshotFlow { Triple(selection.picked, selection.focused, selection.anchor) }
            .collect {
                view.selection = it.first
                view.focusedId = it.second
                view.anchorId = it.third
            }
    }
    val currentIds by rememberUpdatedState(ids)
    fun toggle(picked: Set<String>) {
        if (picked.isNotEmpty())
            model.store.setOn(picked.toList(), !picked.all { isOn(model.store.state.value, it) })
    }
    fun remove(picked: Set<String>) {
        val index = currentIds.indexOf(selection.focused)
        val next = currentIds.getOrNull((index + 1).coerceAtMost(currentIds.lastIndex))
        model.store.uninstall(picked.toList())
        selection.retain((currentIds - picked).toSet())
        if (next != null && next !in picked) selection.focus(next)
    }
    DisposableEffect(model) {
        val keys =
            model.registerKeys(Page.Library) { event ->
                val rows = currentIds
                val index = rows.indexOf(selection.focused)
                val picked = selection.picked.ifEmpty { setOfNotNull(selection.focused) }
                when {
                    (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.A ->
                        selection.selectAll(rows)
                    event.key == Key.DirectionDown || event.key == Key.DirectionUp -> {
                        if (rows.isEmpty()) return@registerKeys false
                        val next =
                            if (index < 0) 0
                            else
                                (index + if (event.key == Key.DirectionDown) 1 else -1).coerceIn(
                                    rows.indices
                                )
                        if (event.isShiftPressed)
                            selection.selectAll(
                                (selection.picked + setOfNotNull(rows.getOrNull(index), rows[next]))
                                    .toList()
                            )
                        selection.focus(rows[next])
                        model.ui.panel?.let { model.ui.openMod(rows[next], it.tab) }
                        scope.launch {
                            val row =
                                listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                    it.index == next + 1
                                }
                            if (
                                row == null ||
                                    row.offset < 30 ||
                                    row.offset + row.size > listState.layoutInfo.viewportEndOffset
                            ) {
                                listState.scrollToItem(next + 1, -30)
                            }
                        }
                    }
                    event.key == Key.Spacebar && picked.isNotEmpty() -> toggle(picked)
                    event.key == Key.Enter && selection.focused != null ->
                        model.ui.openMod(selection.focused!!)
                    event.key == Key.Delete && picked.isNotEmpty() -> remove(picked)
                    else -> return@registerKeys false
                }
                true
            }
        val focus = model.registerSearch(Page.Library) { search.requestFocus() }
        onDispose {
            keys()
            focus()
            if (model.ui.focusedField === field) model.ui.focusedField = null
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FlowRow(
                Modifier.fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Label(
                    "Library",
                    Modifier.padding(end = 6.dp),
                    size = 20f,
                    weight = 650,
                    width = 112f,
                )
                if (s.settings.bool("showProfiles"))
                    LibraryMenu({ profileItems(model, model.store.state.value) }) { open ->
                        LibraryProfileButton(s.profile().name, open)
                    }
                Spacer(Modifier.weight(1f))
                if (counts.getValue("upd") > 0)
                    Tooltip(
                        if (s.profiles.size > 1 && s.settings.bool("showProfiles"))
                            "Updates the versions that ${s.profile().name} uses"
                        else ""
                    ) {
                        Button(
                            "Update all (${counts.getValue("upd")})",
                            { model.store.updateAll() },
                            kind = ButtonKind.Upd,
                            icon = NnIcons.up,
                        )
                    }
                Button(
                    "Add from a file",
                    {
                        scope.launch {
                            model.acceptFiles(
                                model.platform.chooseFiles(
                                    "Add a mod from a file",
                                    listOf("dll", "zip", "7z", "nommpack", "nomm.json"),
                                    true,
                                )
                            )
                        }
                    },
                    icon = NnIcons.plus,
                )
                LibraryMenu({ moreItems(model) }) { IconButton(NnIcons.more, "More", it) }
            }
            if (s.installed.isEmpty()) {
                LibraryEmpty(
                    "No mods yet",
                    "Install a mod from Browse, or drop a mod file or a modpack on this window.",
                    icon = NnIcons.library,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            "Browse the most downloaded mods",
                            {
                                model.ui.browse.sort = "downloads"
                                model.ui.go(Page.Browse)
                            },
                            kind = ButtonKind.Strong,
                        )
                        Button(
                            "Import a modpack",
                            {
                                model.ui.importMode = "new"
                                model.openOverlay("import")
                            },
                        )
                    }
                }
            } else {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                            "all" to "All",
                            "on" to "On",
                            "off" to "Off",
                            "upd" to "Updates",
                            "local" to "Local files",
                            "prob" to "Problems",
                        )
                        .forEach { (key, title) ->
                            Chip(title, counts[key], view.filter == key, { view.filter = key })
                        }
                    Spacer(Modifier.weight(1f))
                    TextField(
                        view.query,
                        { view.query = it },
                        Modifier.width(200.dp).onFocusChanged {
                            model.ui.focusedField =
                                if (it.hasFocus) field
                                else model.ui.focusedField?.takeUnless { it === field }
                        },
                        "Filter by name",
                        NnIcons.search,
                        focusRequester = search,
                    )
                }
                s.trouble
                    ?.takeIf { it.phase in listOf("test", "base") }
                    ?.let {
                        Note(
                            "You are looking for a broken mod. ${it.off.size} mods are off for the test. NNOMM turns them on again at the end.",
                            Modifier.padding(horizontal = 18.dp).padding(bottom = 10.dp),
                            warn = true,
                            icon = NnIcons.split,
                            action = {
                                Button(
                                    "Continue",
                                    { model.openOverlay("trouble") },
                                    size = ControlSize.Small,
                                )
                            },
                        )
                    }
                if (s.gameRunning)
                    Note(
                        "Nuclear Option is running. Changes apply the next time you start the game.",
                        Modifier.padding(horizontal = 18.dp).padding(bottom = 10.dp),
                    )
                s.profile()
                    .pack
                    ?.takeIf { it.nextVersion != null }
                    ?.let {
                        Note(
                            "${s.profile().name} ${it.nextVersion} by ${it.by} is out. You have ${it.version}.",
                            Modifier.padding(horizontal = 18.dp).padding(bottom = 10.dp),
                            icon = NnIcons.box,
                            action = {
                                Button(
                                    "See what changes",
                                    { model.openOverlay("packupdate") },
                                    kind = ButtonKind.Upd,
                                    size = ControlSize.Small,
                                )
                            },
                        )
                    }
                BoxWithConstraints(Modifier.weight(1f)) {
                    Column {
                        LibraryTable(
                            ids,
                            Modifier.fillMaxSize(),
                            selection = selection,
                            state = listState,
                            onOpen = { model.ui.openMod(it) },
                            isOff = { !isOn(s, it) },
                            header = { column ->
                                if (column == "select")
                                    CheckBox(
                                        ids.isNotEmpty() && ids.all { it in selection.picked },
                                        {
                                            if (ids.all { it in selection.picked })
                                                selection.clear()
                                            else selection.selectAll(ids)
                                        },
                                        description = "Select all",
                                    )
                                else {
                                    val heading =
                                        mapOf(
                                            "on" to ("on" to "On"),
                                            "name" to ("name" to "Name"),
                                            "version" to ("ver" to "Version"),
                                            "source" to ("source" to "Source"),
                                            "size" to ("size" to "Size"),
                                            "date" to ("upd" to "Released"),
                                        )[column]
                                    heading?.let { (key, title) ->
                                        Tooltip(
                                            when (key) {
                                                "ver" -> "Sort: mods with an update first"
                                                "upd" -> "Release date of the version you have"
                                                else -> ""
                                            }
                                        ) {
                                            SortHeading(title, view.sort == key, !view.descending) {
                                                if (view.sort == key)
                                                    view.descending = !view.descending
                                                else {
                                                    view.sort = key
                                                    view.descending = false
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            cell = { id, column -> LibraryCell(s, id, column, selection, ids) },
                            footer = {
                                if (ids.isEmpty())
                                    LibraryEmpty("No mod in this view", "") {
                                        Button(
                                            "Show all mods",
                                            {
                                                view.query = ""
                                                view.filter = "all"
                                            },
                                        )
                                    }
                                FlowRow(
                                    Modifier.padding(
                                        start = 18.dp,
                                        end = 18.dp,
                                        top = 10.dp,
                                        bottom = if (selection.picked.isNotEmpty()) 70.dp else 16.dp,
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    listOf(
                                            "Mod loader BepInEx ${s.game.loaderVersion ?: "5.4.23.4"}",
                                            "Game ${s.game.version ?: "—"}",
                                            "${size(s.installed.keys.sumOf { s.mod(it).newestRelease?.sizeKb ?: 0 })} of mods",
                                        )
                                        .forEach { Label(it, color = c.text3, size = 12f) }
                                }
                            },
                        )
                    }
                }
            }
        }
        if (selection.picked.isNotEmpty())
            Row(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .background(c.raise2, RoundedCornerShape(9.dp))
                    .border(1.dp, c.line2, RoundedCornerShape(9.dp))
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Label("${selection.picked.size} selected", weight = 600)
                Button(
                    "Turn on",
                    { model.store.setOn(selection.picked.toList(), true) },
                    size = ControlSize.Small,
                )
                Button(
                    "Turn off",
                    { model.store.setOn(selection.picked.toList(), false) },
                    size = ControlSize.Small,
                )
                if (selection.picked.any { hasUpdate(s, it) })
                    Button(
                        "Update",
                        {
                            selection.picked
                                .filter { hasUpdate(s, it) }
                                .forEach(model.store::update)
                        },
                        size = ControlSize.Small,
                        kind = ButtonKind.Upd,
                    )
                Tooltip(
                    if (s.settings.bool("showProfiles") && s.profiles.size > 1)
                        "Removes the mods from this computer, for all profiles"
                    else "Removes the mods from this computer"
                ) {
                    Button(
                        "Uninstall",
                        { remove(selection.picked) },
                        size = ControlSize.Small,
                        kind = ButtonKind.Danger,
                    )
                }
                IconButton(NnIcons.x, "Clear the selection", { selection.clear() }, small = true)
            }
    }
}

@Composable
private fun LibraryCell(
    s: AppState,
    id: String,
    column: String,
    selection: RowSelection<String>,
    ids: List<String>,
) {
    val model = LocalApp.current
    val mod = s.mod(id)
    var shift by remember { mutableStateOf(false) }
    when (column) {
        "select" ->
            CheckBox(
                id in selection.picked,
                { selection.select(ids, id, shift = shift, ctrl = true) },
                modifier =
                    Modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) {
                        shift = it.keyboardModifiers.isShiftPressed
                    },
                description = "Select ${mod.name}",
            )
        "on" ->
            Switch(
                isOn(s, id),
                { model.store.toggle(id) },
                modifier = Modifier.alpha(if (isOn(s, id)) 1f else .8f),
                description = "${if (isOn(s, id)) "Turn off" else "Turn on"} ${mod.name}",
            )
        "icon" ->
            ModTile(
                id,
                mod.name,
                mod.demoPicture,
                TileSize.Normal,
                off = !isOn(s, id),
                imageUrl = mod.imageUrl,
            )
        "name" ->
            LibraryNameCell(
                mod.name,
                badges = {
                    problems(s, id).forEach { problem ->
                        Tooltip(
                            if (problem.kind.isEmpty())
                                "The author made this version for game ${mod.newestRelease?.gameVersion}. It may still work."
                            else "${problem.text}. Click for ways to fix it."
                        ) {
                            if (problem.kind.isEmpty()) Badge(problem.text)
                            else
                                LibraryMenu({
                                    listOf(MenuEntry.Heading(problem.text)) +
                                        problemFixes(model.store.state.value, id, problem).map { fix
                                            ->
                                            MenuEntry.Item(fix.label, danger = fix.danger) {
                                                model.perform(fix.action)
                                            }
                                        }
                                }) { open ->
                                    Badge(
                                        problem.text,
                                        if (problem.kind == "danger") BadgeKind.Danger
                                        else BadgeKind.Warn,
                                        NnIcons.alert,
                                        open,
                                    )
                                }
                        }
                    }
                },
            )
        "version" -> LibraryVersion(s, id)
        "source" -> TableValue(sourceOf(s, id))
        "size" ->
            LibrarySize(mod.newestRelease?.sizeKb?.let(::size) ?: "—", Modifier.fillMaxWidth())
        "date" -> TableValue(if (mod.updated == null) "—" else ago(updOf(s, id)))
        "menu" ->
            LibraryMenu({ modItems(model, model.store.state.value, id) }) {
                IconButton(NnIcons.more, "More", it, small = true)
            }
    }
}

@Composable
private fun LibraryVersion(s: AppState, id: String) {
    val model = LocalApp.current
    val downloads by
        remember(model, id) {
                model.store.state
                    .map { state ->
                        state.downloads.firstOrNull {
                            it.id == id && it.percent < 100 && it.error == null
                        }
                    }
                    .distinctUntilChanged()
            }
            .collectAsState(
                model.store.state.value.downloads.firstOrNull {
                    it.id == id && it.percent < 100 && it.error == null
                }
            )
    val version = verOf(s, id).orEmpty()
    val c = LocalNnTheme.current.colors
    when {
        downloads != null -> DownloadPill(downloads!!.percent / 100f, "${downloads!!.percent}%")
        hasUpdate(s, id) ->
            Tooltip(
                "Update to ${s.mod(id).newestRelease?.version}${if (s.settings.bool("showProfiles") && s.profiles.size > 1) " in ${s.profile().name}" else ""}"
            ) {
                control(Modifier, onClick = { model.store.update(id) }) { modifier, _, _ ->
                    Row(
                        modifier,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Label(
                            version,
                            color = c.accentText,
                            weight = 600,
                            width = 88f,
                            maxLines = 1,
                        )
                        Icon(NnIcons.chevr, Modifier.size(12.dp), c.accentText)
                        Label(
                            s.mod(id).newestRelease!!.version,
                            color = c.accentText,
                            weight = 600,
                            width = 88f,
                            maxLines = 1,
                        )
                    }
                }
            }
        packManaged(s, id) ->
            Tooltip("The modpack sets this version. It changes when the pack updates.") {
                TableValue(version)
            }
        else -> TableValue(version)
    }
}

@Composable
private fun LibraryNameCell(name: String, badges: @Composable RowScope.() -> Unit) {
    val c = LocalNnTheme.current.colors
    val off = LocalTableOff.current
    BoxWithConstraints {
        val badgeWidth = maxWidth * .65f
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Label(
                name,
                Modifier.weight(1f, fill = false),
                color = if (off) c.text3 else c.text,
                weight = if (off) 400 else 600,
                maxLines = 1,
            )
            Row(
                Modifier.widthIn(max = badgeWidth),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                content = badges,
            )
        }
    }
}

@Composable
private fun LibraryProfileButton(name: String, onClick: () -> Unit) {
    val c = LocalNnTheme.current.colors
    Tooltip("Actions for this profile") {
        control(Modifier, onClick = onClick) { modifier, _, _ ->
            Row(
                modifier.height(28.dp).padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Label(name, color = c.text2, weight = 500)
                Icon(NnIcons.chevd, color = c.text2)
            }
        }
    }
}

@Composable
private fun LibrarySize(text: String, modifier: Modifier = Modifier) {
    val c = LocalNnTheme.current.colors
    Label(
        text,
        modifier,
        color = if (LocalTableOff.current) c.text3 else c.text2,
        width = 88f,
        maxLines = 1,
        textAlign = TextAlign.End,
    )
}

@Composable
private fun LibraryEmpty(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    action: @Composable () -> Unit,
) {
    val c = LocalNnTheme.current.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 380.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon?.let { Icon(it, Modifier.size(28.dp), c.text3) }
            Label(title, size = 16f, weight = 600, textAlign = TextAlign.Center)
            if (description.isNotEmpty())
                Label(description, color = c.text2, textAlign = TextAlign.Center)
            action()
        }
    }
}
