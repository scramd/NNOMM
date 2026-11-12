package nnomm.ui.panel

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.WeakHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import nnomm.core.demo.Demo
import nnomm.core.io.real.RealModStore
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.ui.*
import nnomm.ui.kit.*
import nnomm.ui.kit.Label as KitLabel
import nnomm.ui.theme.LocalNnTheme

/** Window-local document positions survive panel dismissal as well as row/tab changes. */
private class PanelMemory {
    val scrolls = mutableMapOf<Pair<String, PanelTab>, ScrollState>()
}

private val memories = WeakHashMap<AppModel, PanelMemory>()

@Composable
fun ModPanel(ref: PanelRef) {
    val model = LocalApp.current
    LaunchedEffect(ref.modId) { model.store.loadMetadata(setOf(ref.modId)) }
    val state by
        remember(model) {
                model.store.state.map { it.copy(toasts = emptyList()) }.distinctUntilChanged()
            }
            .collectAsState(model.store.state.value)
    val mod = state.catalog.firstOrNull { it.id == ref.modId } ?: return
    val memory = remember(model) { memories.getOrPut(model) { PanelMemory() } }
    val tab = if (mod.localFile != null) PanelTab.About else ref.tab
    val scope = rememberCoroutineScope()
    val demo = state.nowMillis == Demo.NOW && model.store.io.modStore !is RealModStore
    LaunchedEffect(mod.id, tab) {
        if (tab == PanelTab.WhatsNew) model.store.loadReleaseNotes(mod.id)
    }
    val loading = state.notesStatus[mod.id] == LoadStatus.LOADING
    val noteError = state.notesStatus[mod.id] == LoadStatus.FAILED
    val c = LocalNnTheme.current.colors
    val installed = state.installed[mod.id]
    var wasInstalled by remember(mod.id) { mutableStateOf(installed != null) }
    LaunchedEffect(mod.id, installed != null) {
        if (wasInstalled && installed == null && model.ui.panel?.modId == mod.id) {
            model.ui.panel = null
        }
        wasInstalled = installed != null
    }
    val latest = mod.newestRelease
    val version = verOf(state, mod.id) ?: latest?.version.orEmpty()
    val multi = state.settings.bool("showProfiles") && state.profiles.size > 1
    val profile = state.profile()
    val dependencies = latest?.dependencies.orEmpty().map { it.id }
    val conflicts =
        (latest?.incompatibilities.orEmpty().map { it.id } +
                state.catalog
                    .filter {
                        it.newestRelease?.incompatibilities?.any { other -> other.id == mod.id } ==
                            true
                    }
                    .map { it.id })
            .distinct()
    fun openPanel(id: String, nextTab: PanelTab = PanelTab.About) {
        model.ui.openMod(id, nextTab)
    }
    Column(Modifier.fillMaxSize().background(c.side)) {
        Column(
            Modifier.fillMaxWidth()
                .bottomLine()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                ModTile(mod.id, mod.name, mod.demoPicture, TileSize.Large, imageUrl = mod.imageUrl)
                Column(Modifier.weight(1f)) {
                    Label(mod.name, size = 17f, weight = 650, width = 110f, lineHeight = 20.4.sp)
                    Label(
                        (if (mod.localFile != null) "Local file " else "") +
                            mod.authors.joinToString(", "),
                        color = c.text2,
                    )
                }
                IconButton(NnIcons.x, "Close (Esc)", { model.ui.panel = null })
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val action = modAction(state, mod.id)
                when {
                    action.percent != null -> DownloadPill(action.percent!! / 100f, action.label)
                    installed == null ->
                        InstallButton(latest?.sizeKb?.let(::size).orEmpty(), !action.disabled) {
                            model.store.install(mod.id)
                        }
                    installed.fileMissing ->
                        Button("Repair", { model.store.repair(mod.id) }, kind = ButtonKind.Line)
                    hasUpdate(state, mod.id) ->
                        Button(
                            "Update to ${latest?.version}",
                            { model.store.update(mod.id) },
                            kind = ButtonKind.Upd,
                            size = ControlSize.Large,
                            enabled = !action.disabled,
                        )
                }
                if (installed != null) {
                    Row(
                        Modifier.heightIn(min = 28.dp).align(Alignment.CenterVertically),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            isOn(state, mod.id),
                            { model.store.toggle(mod.id) },
                            description = "On in ${profile.name}",
                        )
                        Label(
                            (if (isOn(state, mod.id)) "On" else "Off") +
                                if (multi) " in ${profile.name}" else ""
                        )
                    }
                    Box(
                        Modifier.weight(1f).align(Alignment.CenterVertically),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        PanelMenu(
                            entries =
                                buildList {
                                    add(
                                        MenuEntry.Item("Open details", hint = "Enter") {
                                            openPanel(mod.id)
                                        }
                                    )
                                    add(
                                        MenuEntry.Item(
                                            if (isOn(state, mod.id)) "Turn off" else "Turn on",
                                            hint = "Space",
                                        ) {
                                            model.store.toggle(mod.id)
                                        }
                                    )
                                    if (hasUpdate(state, mod.id)) {
                                        add(
                                            MenuEntry.Item(
                                                "Update to ${latest?.version}",
                                                icon = NnIcons.up,
                                            ) {
                                                model.store.update(mod.id)
                                            }
                                        )
                                        add(
                                            MenuEntry.Item("See what's new") {
                                                openPanel(mod.id, PanelTab.WhatsNew)
                                            }
                                        )
                                    }
                                    add(MenuEntry.Separator)
                                    add(
                                        MenuEntry.Item(
                                            "Open the mod folder",
                                            icon = NnIcons.folder,
                                        ) {
                                            model.openModFolder(mod.id)
                                        }
                                    )
                                    add(
                                        MenuEntry.Item(
                                            "Open its settings file",
                                            icon = NnIcons.file,
                                        ) {
                                            model.openModSettings(mod.id)
                                        }
                                    )
                                    add(MenuEntry.Separator)
                                    add(
                                        MenuEntry.Item(
                                            if (multi) "Uninstall (all profiles)" else "Uninstall",
                                            hint = "Delete",
                                            danger = true,
                                            icon = NnIcons.trash,
                                        ) {
                                            model.store.uninstall(listOf(mod.id))
                                        }
                                    )
                                }
                        ) { expand ->
                            IconButton(NnIcons.more, "More", expand)
                        }
                    }
                }
            }
            problems(state, mod.id)
                .filter { it.kind != nnomm.core.model.ProblemKind.INFO }
                .forEach { problem ->
                    PanelMenu(
                        problemFixes(state, mod.id, problem).map { fix ->
                            MenuEntry.Item(fix.label, danger = fix.danger) {
                                model.perform(fix.action)
                            }
                        }
                    ) { expand ->
                        val ink =
                            if (problem.kind == nnomm.core.model.ProblemKind.DANGER) c.danger
                            else c.warn
                        control(Modifier, onClick = expand) { modifier, _, _ ->
                            Row(
                                modifier
                                    .height(26.dp)
                                    .background(
                                        if (problem.kind == nnomm.core.model.ProblemKind.DANGER)
                                            c.dangerBg
                                        else c.warnBg,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(NnIcons.alert, color = ink)
                                Label(problem.text, color = ink, size = 11f, weight = 500)
                                Icon(NnIcons.chevd, color = ink)
                            }
                        }
                    }
                }
            val meta =
                if (mod.localFile != null)
                    listOf(
                        "Added" to age(state, mod.added),
                        "Size" to (latest?.sizeKb?.let(::size) ?: "Unknown"),
                        "Version in the file" to version,
                    )
                else
                    listOf(
                        "Downloads" to num(mod.downloadCount),
                        "Newest version" to
                            "${latest?.version ?: "Unknown"}, ${age(state, mod.updated)}",
                        "Size" to (latest?.sizeKb?.let(::size) ?: "Unknown"),
                        (if (installed != null)
                            if (multi) "Version in ${profile.name}" else "Your version"
                        else "First release") to
                            (if (installed != null) version else age(state, mod.added)),
                        "Made for game" to (latest?.gameVersion ?: "Unknown"),
                        "Kind" to
                            ((if (latest?.type == ModType.ADDON) "Add-on" else "Mod") +
                                if (mod.side == Side.SERVER) ", server only" else ""),
                    )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                meta.chunked(3).forEach { cells ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        cells.forEach { (label, value) ->
                            Column(Modifier.weight(1f)) {
                                Label(
                                    label,
                                    Modifier.heightIn(min = 16.8.dp),
                                    size = 12f,
                                    color = c.text3,
                                )
                                Label(
                                    value,
                                    Modifier.heightIn(min = 16.8.dp),
                                    size = 12f,
                                    color =
                                        if (
                                            label == "Made for game" &&
                                                latest?.gameVersion != effectiveGameVersion(state)
                                        )
                                            c.warn
                                        else c.text,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (mod.localFile == null) {
            val count = dependencies.size + conflicts.size
            PanelTabBar(
                listOf("About", "What's new", "Needs" + if (count > 0) " $count" else ""),
                tab.ordinal,
                { openPanel(mod.id, PanelTab.entries[it]) },
            )
        }
        val scroll = memory.scrolls.getOrPut(mod.id to tab) { ScrollState(0) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (tab) {
                    PanelTab.About -> {
                        Paragraph(mod.description)
                        if (mod.localFile == null) {
                            if (mod.tags.isNotEmpty())
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    mod.tags.forEach { tag ->
                                        Tag(
                                            tag,
                                            {
                                                model.ui.browse.tags = model.ui.browse.tags + tag
                                                model.ui.go(Page.Browse)
                                            },
                                        )
                                    }
                                }
                            if (state.settings.bool("prerelease"))
                                mod.newerPreRelease?.let { pre ->
                                    Note(
                                        "Test version ${pre.version} is out. It can be unstable.",
                                        action = {
                                            Button(
                                                "Install ${pre.version}",
                                                { model.store.changeVersion(mod.id, pre.version) },
                                                size = ControlSize.Small,
                                            )
                                        },
                                    )
                                }
                            if (
                                !mod.githubOwner.isNullOrBlank() &&
                                    !mod.githubRepository.isNullOrBlank()
                            ) {
                                ActionLink("Mod page on GitHub", NnIcons.ext) {
                                    model.platform.openUrl(
                                        "https://github.com/${mod.githubOwner}/${mod.githubRepository}"
                                    )
                                }
                            }
                        }
                        if (installed != null)
                            Column {
                                Heading("On this computer")
                                if (multi)
                                    state.profiles.forEach { p ->
                                        Kv {
                                            Label(p.name)
                                            Spacer(Modifier.weight(1f))
                                            if (mod.localFile == null && isOn(state, mod.id, p.id))
                                                Label(
                                                    verOf(state, mod.id, p.id).orEmpty(),
                                                    color = c.text2,
                                                    size = 12f,
                                                    width = 88f,
                                                )
                                            Switch(
                                                isOn(state, mod.id, p.id),
                                                { model.store.toggle(mod.id, p.id) },
                                                description = "On in ${p.name}",
                                            )
                                        }
                                    }
                                if (mod.localFile == null)
                                    Kv {
                                        Label(
                                            if (multi) "Version in ${profile.name}" else "Version"
                                        )
                                        Spacer(Modifier.weight(1f))
                                        if (packManaged(state, mod.id))
                                            Label(
                                                "$version, set by the modpack",
                                                size = 12f,
                                                color = c.text2,
                                            )
                                        else {
                                            val versions =
                                                ((if (demo) Demo.notes(mod.id).map { it.version }
                                                    else
                                                        mod.versions
                                                            .sortedWith { a, b ->
                                                                compareVersions(
                                                                    b.version,
                                                                    a.version,
                                                                )
                                                            }
                                                            .map { it.version }) + version)
                                                    .distinct()
                                            Select(
                                                versions.map {
                                                    it +
                                                        if (it == latest?.version) " (newest)"
                                                        else ""
                                                },
                                                versions.indexOf(version),
                                                { model.store.changeVersion(mod.id, versions[it]) },
                                                modifier = Modifier.width(IntrinsicSize.Max),
                                                height = 24.dp,
                                            )
                                        }
                                    }
                                else
                                    Kv {
                                        Button(
                                            "Replace with a newer file",
                                            { model.replaceLocal(mod.id) },
                                            kind = ButtonKind.Ghost,
                                            icon = NnIcons.file,
                                        )
                                    }
                                Kv {
                                    ActionLink("Open the mod folder", NnIcons.folder) {
                                        model.openModFolder(mod.id)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    LinkButton(
                                        "Open its settings file",
                                        { model.openModSettings(mod.id) },
                                    )
                                }
                                Kv(line = false) {
                                    ActionLink(
                                        "Uninstall" +
                                            if (multi && mod.localFile == null) " (all profiles)"
                                            else "",
                                        NnIcons.trash,
                                        c.danger,
                                    ) {
                                        model.store.uninstall(listOf(mod.id))
                                    }
                                }
                            }
                    }
                    PanelTab.WhatsNew -> {
                        when {
                            loading -> Paragraph("Loading release notes…")
                            noteError -> Paragraph("Could not load release notes.")
                            else -> {
                                val notes =
                                    (if (demo) Demo.notes(mod.id)
                                        else state.releaseNotes[mod.id].orEmpty())
                                        .ifEmpty {
                                            mod.versions
                                                .filter { it.category == ReleaseKind.RELEASE }
                                                .map {
                                                    ReleaseNote(
                                                        it.version,
                                                        age(state, it.dateMillis),
                                                        emptyList(),
                                                    )
                                                }
                                        }
                                notes.forEach { note ->
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .bottomLine()
                                            .padding(vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Label(note.version, Modifier.heightIn(min = 18.2.dp), weight = 600)
                                            Label(note.date, color = c.text3)
                                            if (installed != null && version == note.version)
                                                Badge(
                                                    if (multi) "in ${profile.name}"
                                                    else "installed",
                                                    BadgeKind.Ok,
                                                )
                                        }
                                        if (note.lines.isEmpty())
                                            Paragraph(
                                                "The author gave no notes for this version.",
                                                c.text3,
                                            )
                                        else
                                            Column(Modifier.padding(start = 2.dp, end = 6.dp)) {
                                                note.lines.forEach { line ->
                                                    Row(Modifier.heightIn(min = 18.2.dp)) {
                                                        Label(
                                                            "•",
                                                            Modifier.width(14.dp),
                                                            color = c.text2,
                                                        )
                                                        KitLabel(
                                                            line,
                                                            Modifier.weight(1f),
                                                            color = c.text2,
                                                            lineHeight = 18.2.sp,
                                                        )
                                                    }
                                                }
                                            }
                                    }
                                }
                            }
                        }
                        Paragraph("The notes come from the mod's release page.", c.text3)
                    }
                    PanelTab.Needs -> {
                        Column {
                            Heading("Needs these mods")
                            if (dependencies.isEmpty()) Paragraph("Nothing listed.")
                            dependencies.forEach { id ->
                                RelatedRow(state, id, ::openPanel) {
                                    val action = modAction(state, id)
                                    if (action.percent != null)
                                        DownloadPill(action.percent!! / 100f, action.label)
                                    else {
                                        Label(
                                            if (id !in state.installed) "Not installed"
                                            else if (isOn(state, id)) "On" else "Off",
                                            color = c.text2,
                                            size = 12f,
                                        )
                                        when {
                                            id !in state.installed ->
                                                Button(
                                                    "Install",
                                                    { model.store.install(id) },
                                                    size = ControlSize.Small,
                                                    enabled = !action.disabled,
                                                )
                                            !isOn(state, id) ->
                                                Button(
                                                    "Turn on",
                                                    { model.store.setOn(listOf(id), true) },
                                                    kind = ButtonKind.Line,
                                                    size = ControlSize.Small,
                                                )
                                            else -> Icon(NnIcons.check, color = c.on)
                                        }
                                    }
                                }
                            }
                        }
                        if (conflicts.isNotEmpty())
                            Column {
                                Heading("Does not work together with")
                                conflicts.forEach { id ->
                                    RelatedRow(state, id, ::openPanel) {
                                        Label(
                                            if (isOn(state, id)) "On"
                                            else if (id in state.installed) "Off"
                                            else "Not installed",
                                            color = c.text2,
                                            size = 12f,
                                        )
                                    }
                                }
                                Paragraph(
                                    "The authors marked these mods as not compatible. Use one of them at a time.",
                                    c.text3,
                                )
                            }
                        val usedBy =
                            state.catalog.filter {
                                it.id in state.installed &&
                                    it.newestRelease?.dependencies?.any { dep ->
                                        dep.id == mod.id
                                    } == true
                            }
                        if (usedBy.isNotEmpty())
                            Column {
                                Heading("Your mods that need this one")
                                usedBy.forEach { RelatedRow(state, it.id, ::openPanel) {} }
                            }
                    }
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(scroll),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun RelatedRow(
    state: AppState,
    id: String,
    open: (String, PanelTab) -> Unit,
    end: @Composable RowScope.() -> Unit,
) {
    val mod = state.catalog.firstOrNull { it.id == id } ?: return
    Kv {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModTile(mod.id, mod.name, mod.demoPicture, TileSize.Small, imageUrl = mod.imageUrl)
            LinkButton(mod.name, { open(id, PanelTab.About) })
        }
        end()
    }
}

@Composable
private fun PanelMenu(entries: List<MenuEntry>, anchor: @Composable (() -> Unit) -> Unit) {
    val model = LocalApp.current
    var expanded by remember { mutableStateOf(false) }
    val dismiss = remember { { expanded = false } }
    DisposableEffect(expanded) {
        if (expanded) model.ui.dismissMenu = dismiss
        onDispose { if (model.ui.dismissMenu === dismiss) model.ui.dismissMenu = null }
    }
    Box {
        anchor { expanded = !expanded }
        Menu(expanded, dismiss, entries)
    }
}

@Composable
private fun Modifier.bottomLine(): Modifier {
    val line = LocalNnTheme.current.colors.line
    return drawBehind {
        drawLine(
            line,
            androidx.compose.ui.geometry.Offset(0f, size.height),
            androidx.compose.ui.geometry.Offset(size.width, size.height),
        )
    }
}

@Composable
private fun Kv(line: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Row(
        (if (line) Modifier.bottomLine() else Modifier).fillMaxWidth().heightIn(min = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun InstallButton(size: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalNnTheme.current.colors
    control(Modifier, enabled = enabled, onClick = onClick) { modifier, hover, _ ->
        Row(
            modifier
                .height(34.dp)
                .background(
                    if (hover) c.strong.copy(alpha = .9f) else c.strong,
                    RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("Install", color = c.strongInk, size = 14f, weight = 600)
            if (size.isNotEmpty()) Label(size, color = c.strongInk.copy(alpha = .75f), size = 14f)
        }
    }
}

@Composable
private fun Heading(text: String) = Label(text, Modifier.padding(bottom = 6.dp), weight = 600)

@Composable
private fun Paragraph(text: String, color: Color = LocalNnTheme.current.colors.text2) =
    Label(text, color = color, lineHeight = 19.5.sp)

@Composable
private fun ActionLink(
    text: String,
    icon: ImageVector,
    color: Color = LocalNnTheme.current.colors.text2,
    action: () -> Unit,
) {
    control(Modifier, onClick = action) { modifier, hovered, _ ->
        Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, color = color)
            Label(text, color = color, modifier = Modifier.bottomLine())
        }
    }
}

private fun age(state: AppState, date: Long?) =
    if (date == null) "Unknown" else ago(daysAgo(state, date))

/** Match the mock's inherited 1.4 line height, including the two-line metadata cells. */
@Composable
private fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalNnTheme.current.colors.text,
    size: Float = 13f,
    weight: Int = 400,
    width: Float = 100f,
    lineHeight: TextUnit = (size * 1.4f).sp,
) = KitLabel(text, modifier, color, size, weight, width, lineHeight = lineHeight,
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
        androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        androidx.compose.ui.text.style.LineHeightStyle.Trim.None))

@Composable
private fun PanelTabBar(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalNnTheme.current.colors
    Row(
        Modifier.fillMaxWidth().bottomLine().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        titles.forEachIndexed { index, title ->
            control(Modifier, onClick = { onSelect(index) }) { modifier, hovered, _ ->
                Box(
                    modifier
                        .drawBehind {
                            if (index == selected)
                                drawRect(
                                    c.text,
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
                        Modifier.heightIn(min = 17.55.dp),
                        color = if (index == selected || hovered) c.text else c.text2,
                        weight = 500,
                    )
                }
            }
        }
    }
}
