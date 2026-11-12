@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package nnomm.ui.shell

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import nnomm.core.rules.*
import nnomm.ui.*
import nnomm.ui.browse.BrowsePage
import nnomm.ui.kit.*
import nnomm.ui.library.LibraryPage
import nnomm.ui.panel.ModPanel
import nnomm.ui.settings.SettingsPage
import nnomm.ui.theme.*

@Composable
fun AppShell(model: AppModel) {
    val ui = model.ui
    val theme = LocalNnTheme.current
    val c = theme.colors
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(model) { rootFocus.requestFocus() }
    CompositionLocalProvider(
        LocalApp provides model,
        LocalOptionalApp provides model,
        LocalShortcutHandler provides model::shortcut,
    ) {
        val dropTarget =
            remember(model) {
                object : DragAndDropTarget {
                    override fun onEntered(event: DragAndDropEvent) {
                        if (ui.overlay == null) ui.dragging = true
                    }

                    override fun onExited(event: DragAndDropEvent) {
                        ui.dragging = false
                    }

                    override fun onEnded(event: DragAndDropEvent) {
                        ui.dragging = false
                    }

                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        val files =
                            (event.dragData() as? DragData.FilesList)?.readFiles() ?: return false
                        model.acceptFiles(
                            files.map {
                                if (it.startsWith("file:")) java.io.File(java.net.URI(it))
                                else java.io.File(it)
                            }
                        )
                        return true
                    }
                }
            }
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .background(c.bg)
                .focusRequester(rootFocus)
                .onFocusChanged { ui.pageKeysEnabled = it.isFocused }
                .focusable()
                .dragAndDropTarget({ it.dragData() is DragData.FilesList }, dropTarget)
        ) {
            val narrow = maxWidth <= 940.dp
            Row(Modifier.fillMaxSize()) {
                Sidebar(model, narrow)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { PageContent(ui.page) }
                        if (!narrow) PanelHost(ui.panel)
                    }
                    if (narrow) Box(Modifier.align(Alignment.CenterEnd)) { PanelHost(ui.panel) }
                }
            }
        }
    }
}

/** Place above the complete window, including its title bar. */
@Composable
fun AppLayers(model: AppModel) {
    val toasts by
        remember(model) { model.store.state.map { it.toasts }.distinctUntilChanged() }
            .collectAsState(model.store.state.value.toasts)
    val ui = model.ui
    val theme = LocalNnTheme.current
    CompositionLocalProvider(
        LocalApp provides model,
        LocalOptionalApp provides model,
        LocalShortcutHandler provides model::shortcut,
    ) {
        Box(Modifier.fillMaxSize().onKeyEvent(model::shortcut)) {
            ui.overlay?.Content()
            if (ui.dragging || ui.overlayKind == "drop") DropLayer()
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).fillMaxWidth(.92f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ui.updateStatus?.let { message ->
                    LaunchedEffect(message) {
                        kotlinx.coroutines.delay(7000)
                        ui.updateStatus = null
                    }
                    Toast(
                        message,
                        if (message.startsWith("An NNOMM update")) "Get update" else null,
                        model.openAppReleases,
                    )
                }
                toasts.takeLast(if (ui.updateStatus == null) 3 else 2).forEach { toast ->
                    key(toast.id) {
                        var visible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { visible = true }
                        AnimatedVisibility(
                            visible,
                            enter =
                                fadeIn(tween(theme.panelMillis, easing = LinearOutSlowInEasing)) +
                                    slideInVertically(
                                        tween(theme.panelMillis, easing = LinearOutSlowInEasing)
                                    ) {
                                        6
                                    },
                        ) {
                            Toast(toast.text, toast.action, { model.runToast(toast.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageContent(page: Page) {
    when (page) {
        Page.Browse -> BrowsePage()
        Page.Library -> LibraryPage()
        Page.Settings -> SettingsPage()
        Page.Servers ->
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Label("Servers", size = 20f, weight = 650, width = 112f)
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        NnIcons.server,
                        Modifier.size(28.dp),
                        color = LocalNnTheme.current.colors.text3,
                    )
                    Spacer(Modifier.height(14.dp))
                    Label("Coming later", size = 16f, weight = 650)
                    Spacer(Modifier.height(8.dp))
                    Label(
                        "A list of dedicated servers comes in a later version of NNOMM.",
                        color = LocalNnTheme.current.colors.text2,
                    )
                }
            }
    }
}

@Composable
private fun PanelHost(ref: PanelRef?) {
    val line = LocalNnTheme.current.colors.line
    val duration = LocalNnTheme.current.panelMillis
    if (ref == null) return
    // Reserve the panel width immediately; animate its paint, never table measurement.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val offset by animateDpAsState(if (entered) 0.dp else 24.dp, tween(duration))
    Box(
        Modifier.width(400.dp)
            .fillMaxHeight()
            .offset(x = if (duration == 0) 0.dp else offset)
            .background(LocalNnTheme.current.colors.side)
            .drawBehind {
                drawLine(
                    line,
                    androidx.compose.ui.geometry.Offset.Zero,
                    androidx.compose.ui.geometry.Offset(0f, size.height),
                    1.dp.toPx(),
                )
            }
    ) {
        ModPanel(ref)
    }
}

@Composable
private fun Nav(
    label: String,
    icon: ImageVector,
    narrow: Boolean,
    selected: Boolean = false,
    tail: String? = null,
    action: () -> Unit,
) {
    val c = LocalNnTheme.current.colors
    Tooltip(label) {
        control(Modifier.fillMaxWidth(), onClick = action) { m, hover, _ ->
            Row(
                m.height(32.dp)
                    .background(
                        if (selected) c.raise2 else if (hover) c.raise else Color.Transparent,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = if (narrow) 0.dp else 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement =
                    if (narrow) Arrangement.Center else Arrangement.spacedBy(10.dp),
            ) {
                if (narrow)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(icon, color = if (selected) c.text else c.text2)
                        tail?.let {
                            Label(
                                it,
                                Modifier.align(Alignment.TopEnd),
                                size = 10.5f,
                                color = c.accentText,
                                weight = 600,
                            )
                        }
                    }
                else Icon(icon, color = if (selected) c.text else c.text2)
                if (!narrow) {
                    Label(
                        label,
                        Modifier.weight(1f),
                        color = if (selected) c.text else c.text2,
                        weight = 500,
                    )
                    tail?.let { Label(it, size = 11.5f, weight = 600, color = c.accentText) }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(model: AppModel, narrow: Boolean) {
    val s by
        remember(model) {
                model.store.state
                    .map {
                        it.copy(
                            toasts = emptyList(),
                            downloads = emptyList(),
                            releaseNotes = emptyMap(),
                            notesStatus = emptyMap(),
                        )
                    }
                    .distinctUntilChanged()
            }
            .collectAsState(model.store.state.value)
    val ui = model.ui
    val c = LocalNnTheme.current.colors
    var menu by remember { mutableStateOf<String?>(null) }
    var contextPoint by remember { mutableStateOf<DpOffset?>(null) }
    val dismiss = {
        menu = null
        contextPoint = null
        ui.dismissMenu = null
    }
    fun open(kind: String) {
        menu = kind
        ui.dismissMenu = dismiss
    }
    Column(
        Modifier.width(if (narrow) 52.dp else 216.dp)
            .fillMaxHeight()
            .background(c.side)
            .drawBehind {
                drawLine(
                    c.line,
                    androidx.compose.ui.geometry.Offset(size.width, 0f),
                    androidx.compose.ui.geometry.Offset(size.width, size.height),
                    1.dp.toPx(),
                )
            }
            .padding(
                start = if (narrow) 6.dp else 8.dp,
                end = if (narrow) 6.dp else 8.dp,
                top = 10.dp,
                bottom = 8.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Nav("Browse", NnIcons.browse, narrow, ui.page == Page.Browse) { ui.go(Page.Browse) }
        Nav(
            "Library",
            NnIcons.library,
            narrow,
            ui.page == Page.Library,
            updates(s).size.takeIf { it > 0 }?.toString(),
        ) {
            ui.go(Page.Library)
        }
        if (model.showServers())
            Nav("Servers", NnIcons.server, narrow, ui.page == Page.Servers) { ui.go(Page.Servers) }
        if (s.settings.general["showProfiles"]?.content != "false") {
            Row(
                Modifier.padding(top = if (narrow) 8.dp else 14.dp)
                    .height(28.dp)
                    .fillMaxWidth()
                    .padding(start = if (narrow) 0.dp else 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!narrow) Label("Profiles", Modifier.weight(1f), size = 12f, color = c.text3)
                IconButton(NnIcons.plus, "New profile", { model.openOverlay("newprofile") })
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                s.profiles.forEach { profile ->
                    Box {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        Tooltip(
                            "${profile.name}${if (profile.id == s.profileId) " (in use)" else ""}. Right-click for more."
                        ) {
                            control(
                                Modifier.fillMaxWidth(),
                                onClick = { model.useProfile(profile.id) },
                            ) { rowModifier, hover, _ ->
                                Row(
                                    rowModifier
                                        .height(32.dp)
                                        .background(
                                            if (hover) c.raise else Color.Transparent,
                                            RoundedCornerShape(6.dp),
                                        )
                                        .onPointerEvent(PointerEventType.Press) { event ->
                                            if (event.buttons.isSecondaryPressed) {
                                                contextPoint =
                                                    with(density) {
                                                        event.changes.first().position.let {
                                                            DpOffset(it.x.toDp(), it.y.toDp())
                                                        }
                                                    }
                                                open(profile.id)
                                            }
                                        }
                                        .padding(horizontal = if (narrow) 0.dp else 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (narrow)
                                        Box(
                                            Modifier.size(24.dp)
                                                .background(
                                                    if (profile.id == s.profileId) c.onFill
                                                    else Color.Transparent,
                                                    RoundedCornerShape(50),
                                                )
                                                .border(1.5.dp, c.line2, RoundedCornerShape(50)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Label(
                                                profile.name.take(1).uppercase(),
                                                color =
                                                    if (profile.id == s.profileId) c.on
                                                    else c.text2,
                                                size = 11.5f,
                                            )
                                        }
                                    else {
                                        Box(
                                            Modifier.size(16.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Box(
                                                Modifier.size(10.dp)
                                                    .background(
                                                        if (profile.id == s.profileId) c.on
                                                        else Color.Transparent,
                                                        RoundedCornerShape(50),
                                                    )
                                                    .border(
                                                        1.5.dp,
                                                        if (profile.id == s.profileId) c.on
                                                        else c.text3,
                                                        RoundedCornerShape(50),
                                                    )
                                            )
                                        }
                                        Label(
                                            profile.name,
                                            Modifier.weight(1f),
                                            color =
                                                if (profile.id == s.profileId) c.text else c.text2,
                                        )
                                        Label(
                                            if (profile.pack?.nextVersion != null) "update"
                                            else onCount(s, profile.id).toString(),
                                            size = 11.5f,
                                            weight =
                                                if (profile.pack?.nextVersion != null) 600 else 400,
                                            color =
                                                if (profile.pack?.nextVersion != null) c.accentText
                                                else c.text3,
                                        )
                                    }
                                }
                            }
                        }
                        Menu(
                            menu == profile.id,
                            dismiss,
                            profileItems(model, profile.id),
                            pointerOffset = contextPoint,
                        )
                    }
                }
            }
        }
        if (s.settings.general["showProfiles"]?.content == "false") Spacer(Modifier.weight(1f))
        DownloadQueue(model, narrow)
        Column(
            Modifier.fillMaxWidth()
                .padding(vertical = 4.dp)
                .then(
                    if (narrow) Modifier
                    else
                        Modifier.background(c.bg, RoundedCornerShape(9.dp))
                            .border(1.dp, c.line, RoundedCornerShape(9.dp))
                            .padding(9.dp)
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!narrow) {
                if (s.settings.bool("showProfiles"))
                    Box {
                        Tooltip("Change profile") {
                            Row(
                                Modifier.fillMaxWidth().clickable { open("playwith") },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Label(s.profile().name, Modifier.padding(end = 5.dp), weight = 600)
                                Icon(NnIcons.chevd)
                            }
                        }
                        Menu(
                            menu == "playwith",
                            dismiss,
                            listOf(MenuEntry.Heading("Play with")) +
                                s.profiles.map { p ->
                                    MenuEntry.Item(
                                        p.name,
                                        hint = "${onCount(s, p.id)} mods",
                                        icon =
                                            if (p.id == s.profileId) NnIcons.check
                                            else NnIcons.minus,
                                        action = { model.useProfile(p.id) },
                                    )
                                } +
                                listOf(
                                    MenuEntry.Separator,
                                    MenuEntry.Item(
                                        "New profile",
                                        icon = NnIcons.plus,
                                        action = { model.openOverlay("newprofile") },
                                    ),
                                ),
                        )
                    }
                Label(
                    "${onCount(s)} of ${s.installed.size} mods on",
                    Modifier.offset(y = (-5).dp).padding(bottom = 0.dp),
                    size = 12f,
                    color = c.text2,
                )
            }
            if (s.gameRunning || s.gameStarting)
                Tooltip(if (s.gameRunning) "Nuclear Option is running" else "Starting Nuclear Option…") {
                    Row(
                        Modifier.fillMaxWidth()
                            .height(34.dp)
                            .border(1.dp, c.on, RoundedCornerShape(6.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                    ) {
                        Box(Modifier.size(6.dp).background(c.on, RoundedCornerShape(50)))
                        if (!narrow)
                            Label(
                                if (s.gameRunning) "Game is running" else "Starting…",
                                color = c.on,
                                weight = 600,
                            )
                    }
                }
            else
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    Box(Modifier.weight(1f)) {
                        Tooltip("Start Nuclear Option (Ctrl Enter)") {
                            control(Modifier.fillMaxWidth(), onClick = { model.store.play() }) {
                                m,
                                hover,
                                _ ->
                                Row(
                                    m.height(34.dp)
                                        .background(
                                            if (hover) c.accent.copy(alpha = .85f) else c.accent,
                                            RoundedCornerShape(
                                                topStart = 6.dp,
                                                bottomStart = 6.dp,
                                                topEnd = if (narrow) 6.dp else 0.dp,
                                                bottomEnd = if (narrow) 6.dp else 0.dp,
                                            ),
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement =
                                        Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                ) {
                                    Icon(NnIcons.play, color = c.accentInk)
                                    if (!narrow)
                                        Label(
                                            "Play",
                                            color = c.accentInk,
                                            size = 14f,
                                            width = 112f,
                                            weight = 600,
                                        )
                                }
                            }
                        }
                    }
                    if (!narrow)
                        Box {
                            Tooltip("More ways to start") {
                                control(Modifier, onClick = { open("play") }) { m, _, _ ->
                                    Box(
                                        m.size(30.dp, 34.dp)
                                            .background(
                                                c.accent,
                                                RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(NnIcons.chevd, color = c.accentInk)
                                    }
                                }
                            }
                            Menu(
                                menu == "play",
                                dismiss,
                                listOf(
                                    MenuEntry.Item(
                                        "Play without mods",
                                        icon = NnIcons.play,
                                        action = { model.store.play(true) },
                                    ),
                                    MenuEntry.Separator,
                                    MenuEntry.Item(
                                        "Open the game folder",
                                        icon = NnIcons.folder,
                                        action = {
                                            s.game.folder?.let { model.openPath(java.io.File(it)) }
                                        },
                                    ),
                                    MenuEntry.Item(
                                        "Open the mod log",
                                        icon = NnIcons.file,
                                        action = {
                                            s.game.folder?.let {
                                                model.openPath(
                                                    java.io.File(it, "BepInEx/LogOutput.log")
                                                )
                                            }
                                        },
                                    ),
                                ),
                            )
                        }
                }
        }
        Nav("Settings", NnIcons.gear, narrow, ui.page == Page.Settings) { ui.go(Page.Settings) }
    }
}

@Composable
private fun DownloadQueue(model: AppModel, narrow: Boolean) {
    val s by
        remember(model) {
                model.store.state.map { it.copy(toasts = emptyList()) }.distinctUntilChanged()
            }
            .collectAsState(model.store.state.value)
    val ui = model.ui
    val c = LocalNnTheme.current.colors
    if (s.downloads.isNotEmpty() || ui.downloadsOpen)
        Box {
            Column(
                Modifier.fillMaxWidth()
                    .clickable { ui.downloadsOpen = !ui.downloadsOpen }
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(NnIcons.download)
                    if (!narrow)
                        Label(
                            s.downloads
                                .count { it.percent < 100 }
                                .let {
                                    if (it == 0) "Downloads finished"
                                    else "$it download${if (it == 1) "" else "s"}"
                                },
                            size = 12f,
                        )
                }
                val active = s.downloads.filter { it.percent < 100 }
                if (active.isNotEmpty())
                    ProgressBar(active.map { it.percent }.average().toFloat() / 100, neutral = true)
            }
            AnchoredPopover(
                ui.downloadsOpen,
                { ui.downloadsOpen = false },
                offset = DpOffset(if (narrow) 46.dp else 216.dp, 0.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label("Downloads", weight = 650)
                    LinkButton("Clear finished", { model.store.clearFinished() })
                }
                if (s.downloads.isEmpty()) Label("No downloads.", color = c.text3)
                s.downloads.asReversed().forEach { d ->
                    val source = remember(d.id) { MutableInteractionSource() }
                    val hovered by source.collectIsHoveredAsState()
                    Row(
                        Modifier.fillMaxWidth()
                            .hoverable(source)
                            .background(
                                if (hovered) c.raise2 else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ModTile(d.id, s.mod(d.id).name, s.mod(d.id).demoPicture, TileSize.Small)
                        Label(s.catalog.find { it.id == d.id }?.name ?: d.id, Modifier.weight(1f))
                        Label(
                            if (d.percent >= 100)
                                if (d.kind == nnomm.core.model.DownloadKind.UPDATE) "Updated"
                                else "Installed"
                            else "${d.percent}%",
                            size = 12f,
                            color = c.text3,
                        )
                        if (d.percent < 100)
                            IconButton(
                                NnIcons.x,
                                "Cancel",
                                { model.store.cancelDownload(d.id) },
                                small = true,
                            )
                    }
                    if (d.percent < 100) ProgressBar(d.percent / 100f, neutral = true)
                }
            }
        }
}

private fun profileItems(model: AppModel, id: String): List<MenuEntry> = buildList {
    val s = model.store.state.value
    if (id != s.profileId)
        add(
            MenuEntry.Item(
                "Use this profile",
                icon = NnIcons.check,
                action = { model.useProfile(id) },
            )
        )
    fun layer(kind: String) {
        model.ui.overlayProfileId = id
        model.openOverlay(kind)
    }
    add(MenuEntry.Item("Rename", action = { layer("rename") }))
    add(
        MenuEntry.Item(
            "Make a copy",
            icon = NnIcons.copy,
            action = {
                model.store.copyProfile(id)
                model.ui.go(Page.Library)
            },
        )
    )
    add(
        MenuEntry.Item(
            "Share as a modpack",
            icon = NnIcons.share,
            action = {
                model.useProfile(id)
                layer("share")
            },
        )
    )
    add(
        MenuEntry.Item(
            "Settings for this profile",
            icon = NnIcons.gear,
            action = {
                model.ui.go(Page.Settings)
                model.ui.settings.scope = "profile"
                model.ui.settings.profileId = id
                model.ui.settings.query = ""
            },
        )
    )
    s.profile(id).pack?.nextVersion?.let {
        add(
            MenuEntry.Item(
                "See what changes in $it",
                icon = NnIcons.box,
                action = {
                    model.useProfile(id)
                    layer("packupdate")
                },
            )
        )
    }
    if (id != "default") {
        add(MenuEntry.Separator)
        add(
            MenuEntry.Item(
                "Delete this profile",
                icon = NnIcons.trash,
                danger = true,
                action = { model.store.deleteProfile(id) },
            )
        )
    }
}

@Composable
fun DropLayer() {
    val c = LocalNnTheme.current.colors
    Box(Modifier.fillMaxSize().background(c.scrim).padding(18.dp)) {
        Column(
            Modifier.fillMaxSize()
                .background(c.bg.copy(alpha = .86f), RoundedCornerShape(14.dp))
                .drawBehind {
                    drawRoundRect(
                        c.accent,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                        style =
                            Stroke(
                                2.dp.toPx(),
                                pathEffect =
                                    PathEffect.dashPathEffect(
                                        floatArrayOf(8.dp.toPx(), 6.dp.toPx())
                                    ),
                            ),
                    )
                },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(NnIcons.download, Modifier.size(34.dp), color = c.accent)
            Spacer(Modifier.height(8.dp))
            Label("Drop to install", size = 24f, weight = 650, width = 114f)
            Spacer(Modifier.height(10.dp))
            Label(
                "Mod files (.dll, .zip) go into your Library. If the file is a mod from the registry, NNOMM recognises it and can update it later. A modpack (.nommpack) opens a preview first.",
                Modifier.padding(horizontal = 16.dp),
                textAlign = TextAlign.Center,
                color = c.text2,
            )
        }
    }
}
