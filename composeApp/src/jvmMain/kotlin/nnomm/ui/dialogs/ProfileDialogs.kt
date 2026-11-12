package nnomm.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.ui.*
import nnomm.ui.kit.*
import nnomm.ui.theme.*

internal fun AppModel.dialogProfile() =
    store.state.value.profiles.find { it.id == ui.overlayProfileId } ?: store.state.value.profile()

internal suspend fun AppModel.choosePack() {
    val files = platform.chooseFiles("Choose the modpack file", listOf("nommpack", "json"), false)
    if (files.isNotEmpty()) {
        ui.importMode = "new"
        acceptFiles(files)
    }
}

class NewProfileDialog(private val model: AppModel) : Overlay {
    @Composable
    override fun Content() {
        var name by remember { mutableStateOf("") }
        var from by remember {
            mutableStateOf(
                if (
                    model.store.state.value.settings.general["newFrom"]?.content ==
                        "With all mods off"
                )
                    "off"
                else "copy"
            )
        }
        var own by remember { mutableStateOf(model.store.state.value.settings.bool("ownSettings")) }
        val scope = rememberCoroutineScope()
        val focus = remember { FocusRequester() }
        Dialog(
            true,
            "New profile",
            model::closeOverlay,
            initialFocus = focus,
            footer = {
                Cancel(model)
                Button(
                    if (from == "pack") "Choose the modpack file" else "Create profile",
                    {
                        if (from == "pack") scope.launch { model.choosePack() }
                        else {
                            model.store.newProfile(name, from == "copy", own)
                            model.ui.library.filter = "all"
                            model.ui.go(Page.Library)
                            model.closeOverlay()
                        }
                    },
                    kind = ButtonKind.Strong,
                )
            },
        ) {
            if (from != "pack")
                Field("Name", name, { name = it }, "For example: Squad night", focus)
            OptionCard(
                "Start with all mods off",
                "Your installed mods are in the list. You turn on the ones you want.",
                from == "off",
                { from = "off" },
            )
            OptionCard(
                "Copy ${model.store.state.value.profile().name}",
                "The same mods are on. After that the two profiles change separately.",
                from == "copy",
                { from = "copy" },
            )
            OptionCard(
                "Start from a modpack file",
                "NNOMM shows what the pack contains before it installs anything.",
                from == "pack",
                { from = "pack" },
            )
            if (from == "pack")
                Label("The profile gets the name of the pack. You can rename it later.")
            else
                ToggleLine(
                    own,
                    { own = it },
                    "This profile keeps its own mod settings (the values you change with F1 in the game)",
                    "Own mod settings",
                )
        }
    }
}

class RenameDialog(private val model: AppModel) : Overlay {
    private val profile = model.dialogProfile()

    @Composable
    override fun Content() {
        var name by remember { mutableStateOf(profile.name) }
        val focus = remember { FocusRequester() }
        Dialog(
            true,
            "Rename profile",
            model::closeOverlay,
            initialFocus = focus,
            footer = {
                Cancel(model)
                Button(
                    "Rename",
                    {
                        model.store.renameProfile(profile.id, name)
                        model.closeOverlay()
                    },
                    kind = ButtonKind.Strong,
                )
            },
        ) {
            DialogTextField(
                name,
                { name = it },
                Modifier.fillMaxWidth(),
                focusRequester = focus,
                autofocus = true,
            )
        }
    }
}

class ShareDialog(private val model: AppModel) : Overlay {
    private val profile = model.store.state.value.profile()

    @Composable
    override fun Content() {
        val state by model.store.state.collectAsState()
        var name by remember { mutableStateOf(profile.name) }
        var version by remember { mutableStateOf("1.0") }
        var address by remember { mutableStateOf("") }
        var local by remember { mutableStateOf(false) }
        var settings by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val on = state.installed.keys.filter { isOn(state, it, profile.id) }
        val locals = on.filter { state.installed[it]?.source == ModSource.LOCAL }
        val problems = on.count { hasProblem(state.copy(profileId = profile.id,
                                                game = if (profile.id != state.profileId && state.settings.bool("ownSettingsP", profile.id)) state.game.copy(folder = null) else state.game), it) }
        Dialog(
            true,
            "Share ${profile.name} as a modpack",
            model::closeOverlay,
            leftFooter = {
                Button(
                    "Copy the mod list as text",
                    {
                        model.platform.copyText(
                            on.joinToString("\n") {
                                "${state.mod(it).name} ${verOf(state, it, profile.id)}"
                            }
                        )
                        model.store.toast("Copied")
                    },
                    kind = ButtonKind.Ghost,
                )
            },
            footer = {
                Cancel(model)
                Button(
                    "Save the modpack file",
                    {
                        scope.launch {
                            if (model.isDemo) {
                                model.store.toast("This does nothing in demo mode.")
                                return@launch
                            }
                            saving = true
                            try {
                                val file =
                                    model.platform.chooseSaveFile(
                                        "Save the modpack file",
                                        "${name.replace(Regex("\\s+"), "-")}-$version.nommpack",
                                        "nommpack",
                                    )
                                if (file != null) {
                                    // shareProfile operates on the active profile. Build explicitly
                                    // for a sidebar target.
                                    val pack =
                                        Pack(
                                            name,
                                            "",
                                            version,
                                            on.filter { local || it !in locals }
                                                .map {
                                                    PackItem(
                                                        if (it in locals) PackKind.LOCAL else PackKind.VER,
                                                        it,
                                                        verOf(state, it, profile.id),
                                                    )
                                                },
                                            followAddress = address.takeIf { it.isNotBlank() },
                                            includeSettings = settings,
                                        )
                                    val recovery = withPackRecovery(file) {
                                        withPackIo(state) {
                                            it.write(
                                                file.toPath(),
                                                pack,
                                                state.copy(profileId = profile.id,
                                                    game = if (profile.id != state.profileId && state.settings.bool("ownSettingsP", profile.id)) state.game.copy(folder = null) else state.game),
                                            )
                                        }
                                    }
                                    model.store.toast("Saved ${file.name}" + recovery?.let { ". Previous file kept as ${it.name}." }.orEmpty(), "Show the file", undo = { model.platform.openFolder(file.parentFile) })
                                    model.closeOverlay()
                                }
                            } catch (e: Exception) {
                                error = "Could not save the modpack: ${e.message}"
                            } finally {
                                saving = false
                            }
                        }
                    },
                    kind = ButtonKind.Strong,
                    enabled = !saving,
                )
            },
        ) {
            Label(
                "The file lists the ${on.size} mods that are on in this profile, with their versions. Other players import it and get the same set."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { Field("Pack name", name, { name = it }) }
                Column(Modifier.width(110.dp)) { Field("Version", version, { version = it }) }
            }
            if (locals.isNotEmpty())
                Note(
                    "${locals.joinToString(" and ") { state.mod(it).name }} ${if (locals.size == 1) "is a local file" else "are local files"}. With this on, NNOMM puts ${if (locals.size == 1) "it" else "them"} inside the pack file (${if (locals.any { state.mod(it).newestRelease?.sizeKb == null }) "size unknown" else size(locals.sumOf { state.mod(it).newestRelease?.sizeKb ?: 0 })}). Players get a warning before they import it.",
                    action = {
                        ToggleLine(local, { local = it }, "Include", "Include local files")
                    },
                    icon = NnIcons.file,
                )
            if (problems > 0)
                Note(
                    "$problems ${if (problems == 1) "mod has" else "mods have"} a problem in this profile. Players who import the pack get the same problem.",
                    warn = true,
                    action = {
                        Button(
                            "Show",
                            {
                                model.ui.library.filter = "all"
                            model.ui.go(Page.Library)
                                model.ui.library.filter = "prob"
                                model.closeOverlay()
                            },
                            size = ControlSize.Small,
                        )
                    },
                )
            ToggleLine(
                settings,
                { settings = it },
                "Include my mod settings, so the pack arrives configured",
                "Include mod settings",
            )
            Field("Address for updates (optional)", address, { address = it }, "https://...")
            Label(
                "If you put the pack file online, enter its address. Players then get a notice when you save a newer version there. Without an address, players import each new file by hand.",
                color = LocalNnTheme.current.colors.text3,
                size = 12f,
            )
            error?.let { Note(it, warn = true) }
        }
    }
}

@Composable
internal fun Cancel(model: AppModel, text: String = "Cancel") {
    Button(text, model::closeOverlay, kind = ButtonKind.Ghost)
}

@Composable
internal fun Field(
    label: String,
    value: String,
    set: (String) -> Unit,
    placeholder: String = "",
    focus: FocusRequester? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Label(label, Modifier.heightIn(min = 18.dp), color = LocalNnTheme.current.colors.text2)
        DialogTextField(
            value,
            set,
            Modifier.fillMaxWidth(),
            placeholder = placeholder,
            focusRequester = focus,
            autofocus = focus != null,
        )
    }
}

@Composable
internal fun ToggleLine(
    value: Boolean,
    set: (Boolean) -> Unit,
    text: String,
    description: String = text,
) {
    Row(
        modifier = Modifier.heightIn(min = 18.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(value, set, description = description)
        Label(text)
    }
}

/** Native import entry point used by command search. */
suspend fun choosePackFor(model: AppModel) {
    model.choosePack()
}

@Composable
internal fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    focusRequester: FocusRequester? = null,
    autofocus: Boolean = false,
) {
    val model = LocalApp.current
    val currentValue by rememberUpdatedState(value)
    val currentSet by rememberUpdatedState(onValueChange)
    val field = remember { FocusedField({ currentValue }, { currentSet("") }) }
    DisposableEffect(field) {
        onDispose { if (model.ui.focusedField === field) model.ui.focusedField = null }
    }
    TextField(
        value,
        onValueChange,
        modifier.onFocusChanged {
            if (it.hasFocus) model.ui.focusedField = field
            else if (model.ui.focusedField === field) model.ui.focusedField = null
        },
        placeholder = placeholder,
        bg = true,
        focusRequester = focusRequester,
        autofocus = autofocus,
    )
}
