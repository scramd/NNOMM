@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package nnomm.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.model.*
import nnomm.core.settings.*
import nnomm.ui.*
import nnomm.ui.kit.*
import nnomm.ui.theme.LocalNnTheme

/** Settings and overrides use the core definitions; only navigation and drafts live here. */
@Composable
fun SettingsPage() {
    val model = LocalApp.current
    val state by remember(model) {
            model.store.state.map { it.copy(toasts = emptyList(), downloads = emptyList()) }.distinctUntilChanged()
        }.collectAsState(model.store.state.value)
    GameFolderChooser(model)
    LaunchedEffect(model) { if (!model.isDemo) model.store.refreshStoreUsage() }
    val view = model.ui.settings
    val colors = LocalNnTheme.current.colors
    val focus = remember { FocusRequester() }
    DisposableEffect(model) {
        val search = model.registerSearch(Page.Settings) { focus.requestFocus() }
        val keys = model.registerKeys(Page.Settings) {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && view.query.isNotEmpty()) {
                view.query = ""
                true
            } else false
        }
        onDispose {
            search()
            keys()
        }
    }
    val profiles = state.profiles
    val showProfiles = state.settings.general["showProfiles"]?.content != "false"
    val profile = profiles.firstOrNull { it.id == view.profileId } ?: state.profile()
    val personal = showProfiles && view.scope == "profile" && profiles.any { it.id == view.profileId }
    val query = view.query.trim().lowercase()
    fun ownCount(id: String) = state.settings.overrides[id].orEmpty().keys.count { key ->
        SETTINGS.any { it.key == key && !it.profileOnly }
    }
    val rows = SETTINGS.filter {
        (if (personal) it.ov else !it.profileOnly) &&
            (it.cat != "servers" || it.key == "showServers") &&
            (query.isEmpty() || "${it.label} ${it.help} ${it.options.joinToString(" ")}".lowercase().contains(query)) &&
            (personal || query.isNotEmpty() || it.cat == view.category)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth <= 800.dp
        Column(Modifier.fillMaxSize()) {
            FlowRow(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Settings", size = 20f, weight = 650, width = 112f)
                if (showProfiles) {
                    SegmentedControl(listOf("All profiles"), if (personal) -1 else 0,
                        { view.scope = "all" })
                    val choices = (if (personal) emptyList() else listOf("One profile")) + profiles.map {
                        it.name + if (ownCount(it.id) > 0) " (has own values)" else ""
                    }
                    SettingsSelect(choices, if (personal) profiles.indexOf(profile) else 0, { index ->
                        val selected = index - if (personal) 0 else 1
                        profiles.getOrNull(selected)?.let {
                            view.profileId = it.id
                            view.scope = "profile"
                        }
                    }, current = personal)
                }
                Spacer(Modifier.weight(1f))
                SettingField(view.query, { view.query = it }, Modifier.width(230.dp), "Search settings", focus, NnIcons.search)
            }
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(if (narrow) 132.dp else 176.dp).fillMaxHeight()
                    .verticalScroll(rememberScrollState()).padding(start = if (narrow) 8.dp else 12.dp, end = 6.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (personal) {
                        Column(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Label(profile.name, size = 14f, weight = 650)
                            val count = ownCount(profile.id)
                            Label(if (count == 0) "It uses the general settings." else if (count == 1)
                                "1 setting has its own value here." else "$count settings have their own value here.", color = colors.text2)
                            if (count > 0) Button("Use the general settings", { model.store.ovclear(profile.id) }, Modifier.requiredWidth(150.dp), size = ControlSize.Small)
                        }
                    } else CATS.forEach { (key, title) ->
                        if (key == "danger") Spacer(Modifier.height(10.dp))
                        SettingsCategory(title, key == "danger", query.isEmpty() && view.category == key) {
                            view.category = key
                            view.query = ""
                        }
                    }
                }
                key(view.category, personal) {
                    val scroll = rememberScrollState()
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 14.dp, end = 22.dp, bottom = 26.dp)) {
                            Column(Modifier.widthIn(max = 740.dp).fillMaxWidth()) {
                                if (personal) Label("These settings can have their own value in ${profile.name}. A dot marks each one that has. All other settings are the same for every profile.",
                                    Modifier.widthIn(max = 434.dp).padding(top = 4.dp, bottom = 16.dp), color = colors.text2)
                                else if (query.isNotEmpty() && rows.isNotEmpty()) Label("${rows.size} ${if (rows.size == 1) "setting" else "settings"} for \"${view.query.trim()}\"", color = colors.text2)
                                if (rows.isEmpty() && query.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Label("No setting matches", size = 18f, weight = 650)
                                    Button("Clear the search", { view.query = "" }, Modifier.padding(top = 12.dp))
                                }
                                rows.groupBy { it.cat }.entries.forEachIndexed { index, (cat, definitions) ->
                                    Label(CATS.firstOrNull { it.first == cat }?.second.orEmpty(),
                                        Modifier.padding(top = if (index == 0) 4.dp else 18.dp, bottom = 4.dp).heightIn(min = 21.dp), size = 15f, weight = 650)
                                    if (!personal && query.isEmpty()) {
                                        val lead = when (cat) {
                                            "servers" -> "The server list comes in a later version."
                                            "danger" -> "NNOMM asks again before each of these."
                                            "profiles" -> "A profile is a list of mods that are on, with the version of each. You can ignore profiles and use only Default."
                                            "keys" -> "Keyboard shortcuts."
                                            else -> null
                                        }
                                        lead?.let { Label(it, Modifier.widthIn(max = 434.dp).padding(bottom = 8.dp), color = colors.text2) }
                                    }
                                    Column(if (cat == "danger") Modifier.padding(top = 8.dp).border(1.dp, colors.danger, RoundedCornerShape(9.dp)).padding(horizontal = 14.dp) else Modifier) {
                                        definitions.forEachIndexed { i, definition ->
                                            SettingRow(model, state, definition, query, if (personal) profile.id else null, narrow,
                                                cat != "danger" || i != definitions.lastIndex)
                                        }
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
        view.notice?.let { message ->
            LaunchedEffect(message) {
                delay(7000)
                view.notice = null
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).background(colors.raise2, RoundedCornerShape(9.dp))
                .border(1.dp, colors.line2, RoundedCornerShape(9.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(message, Modifier.weight(1f))
                Button("Dismiss", { view.notice = null }, kind = ButtonKind.Ghost, size = ControlSize.Small)
            }
        }
    }
}

@Composable
private fun Mark(text: String, query: String, help: Boolean = false) {
    val at = if (query.isEmpty()) -1 else text.lowercase().indexOf(query)
    Label(highlightedText(text, if (at < 0) emptyList() else (at until at + query.length).toList()),
        modifier = if (help) Modifier.widthIn(max = 434.dp).heightIn(min = 17.5.dp) else Modifier.heightIn(min = 18.2.dp),
        color = if (help) LocalNnTheme.current.colors.text2 else LocalNnTheme.current.colors.text,
        size = if (help) 12.5f else 13f, weight = if (help) 400 else 600)
}

@Composable
private fun SettingRow(model: AppModel, state: AppState, definition: SettingDefinition, query: String,
    profile: String?, narrow: Boolean, divider: Boolean) {
    val d = definition
    val c = LocalNnTheme.current.colors
    val own = profile != null && d.key in state.settings.overrides[profile].orEmpty()
    val enabled = profile == null || own || d.profileOnly
    val value = (if (profile != null) state.settings.value(d.key, profile) else state.settings.general[d.key])?.content.orEmpty()
    fun change(value: JsonPrimitive) {
        model.store.setSetting(d.key, value, profile)
        if (d.key == "showProfiles" && value.content == "false") model.ui.settings.scope = "all"
        if (d.key == "view") model.ui.browse.view = if (value.content == "List") "list" else "grid"
    }
    if (d.type == "keys") {
        KEYS.forEach { (label, shortcut) ->
            Row(Modifier.fillMaxWidth().drawBottomBorder(c.line).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(label, Modifier.weight(1f))
                control(Modifier, enabled = false, onClick = { model.store.toast("Keyboard shortcuts cannot be changed yet.") }) { modifier, _, _ ->
                    Box(modifier) { KeyCap(shortcut) }
                }
            }
        }
        return
    }
    val path = d.type == "path"
    var draft by remember(value) { mutableStateOf(value) }
    val pathModifier = Modifier.onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
            change(JsonPrimitive(draft))
            true
        } else if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
            draft = value
            true
        } else false
    }.onFocusChanged { if (!it.hasFocus && draft != value) change(JsonPrimitive(draft)) }
    Column(Modifier.fillMaxWidth().then(if (divider) Modifier.drawBottomBorder(c.line) else Modifier).padding(top = 11.dp, bottom = if (divider) 12.dp else 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val text: @Composable () -> Unit = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (own && !d.profileOnly) Box(Modifier.size(7.dp).background(c.text, RoundedCornerShape(50)))
                    Mark(d.label, query)
                }
                var help = settingHelp(d, state)
                if (d.key == "appUpdate") help = model.ui.updateStatus ?: if (model.isDemo) "Version ${state.appVersion}. You have the newest version." else "Version ${state.appVersion}."
                if (d.key == "loader" && model.isDemo) help = d.help
                if (d.key == "cache") help = "${state.storeUnusedMb ?: 0} MB. Old versions that no profile uses."
                if (help.isNotEmpty()) Box(Modifier.padding(top = 2.dp)) { Mark(help, query, true) }
                if (query.isNotEmpty() && !"${d.label} ${d.help}".lowercase().contains(query)) {
                    d.options.firstOrNull { it.lowercase().contains(query) }?.let { Mark("Option: $it", query, true) }
                }
            }
        }
        val controls: @Composable () -> Unit = {
            FlowRow(if (path) Modifier.fillMaxWidth() else Modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (d.type) {
                    "toggle" -> Switch(value == "true", { change(JsonPrimitive(it)) }, enabled = enabled, description = d.label)
                    "select" -> SettingsSelect(d.options, d.options.indexOf(value).coerceAtLeast(0), { change(JsonPrimitive(d.options[it])) }, enabled = enabled)
                    "text", "path" -> SettingField(value, { change(JsonPrimitive(it)) },
                        if (path) Modifier.weight(1f).widthIn(max = 620.dp) else Modifier.width(if (profile != null) 170.dp else 250.dp), d.placeholder.orEmpty(), enabled = enabled)
                }
                d.actions.forEach { action -> Button(action, { performSettingAction(model, d, action) }, kind = if (d.danger) ButtonKind.Danger else ButtonKind.Default) }
                if (profile != null && !d.profileOnly) Button(if (own) "Use the general setting" else "Change for this profile",
                    { if (own) model.store.ovreset(d.key, profile) else model.store.ovset(d.key, profile) },
                    kind = if (own) ButtonKind.Ghost else ButtonKind.Default, size = ControlSize.Small)
                if (profile == null && d.ov && state.settings.general["showProfiles"]?.content != "false") {
                    val owners = state.profiles.filter { d.key in state.settings.overrides[it.id].orEmpty() }
                    if (owners.isNotEmpty()) LinkButton(owners.joinToString(", ") { it.name } + if (owners.size == 1) " has its own value" else " have their own value", {
                        model.ui.settings.scope = "profile"
                        model.ui.settings.profileId = owners.first().id
                    }, size = 12f)
                }
            }
        }
        if (path || d.type == "sources") {
            text()
            if (path) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingField(draft, { draft = it }, Modifier.weight(1f).widthIn(max = 620.dp).then(pathModifier), d.placeholder.orEmpty(), enabled = enabled)
                d.actions.forEach { action -> Button(action, { performSettingAction(model, d, action) }) }
            } else if (d.type != "sources") controls()
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { text() }
            Box(Modifier.widthIn(max = if (profile != null) 470.dp else 370.dp)) { controls() }
        }
        if (d.type == "sources") RegistrySources(model, state)
    }
}

private fun Modifier.drawBottomBorder(color: androidx.compose.ui.graphics.Color) = then(
    Modifier.drawBehind { drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx()) })

@Composable
internal fun SettingField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "", requester: FocusRequester? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, enabled: Boolean = true) {
    val ui = LocalApp.current.ui
    val latest by rememberUpdatedState(value)
    val clear by rememberUpdatedState(onChange)
    val field = remember { FocusedField({ latest }, { clear("") }) }
    DisposableEffect(field) { onDispose { if (ui.focusedField === field) ui.focusedField = null } }
    TextField(value, onChange, modifier.onFocusChanged {
        if (it.hasFocus) ui.focusedField = field else if (ui.focusedField === field) ui.focusedField = null
    }, placeholder, icon, enabled, focusRequester = requester)
}

@Composable
private fun SettingsSelect(options: List<String>, selected: Int, onSelect: (Int) -> Unit,
    enabled: Boolean = true, current: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val theme = LocalNnTheme.current
    val density = LocalDensity.current
    val width = with(density) {
        (options.maxOfOrNull { measurer.measure(it, theme.text(weight = if (current) 600 else 400)).size.width } ?: 0).toDp() + 41.dp
    }
    Select(options, selected, onSelect, Modifier.width(width), enabled, current = current)
}

@Composable
private fun SettingsCategory(title: String, danger: Boolean, current: Boolean, onClick: () -> Unit) {
    val c = LocalNnTheme.current.colors
    control(Modifier.fillMaxWidth().semantics { selected = current }, onClick = onClick) { modifier, hover, _ ->
        Box(modifier.height(32.dp)
            .background(if (current) c.raise2 else if (hover) c.raise else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(6.dp)).padding(horizontal = 9.dp), contentAlignment = Alignment.CenterStart) {
            Label(title, color = if (danger) c.danger else if (current || hover) c.text else c.text2,
                weight = 500, maxLines = 2)
        }
    }
}
