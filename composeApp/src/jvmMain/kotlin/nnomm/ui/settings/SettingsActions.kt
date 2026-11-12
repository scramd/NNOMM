package nnomm.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.*
import io.github.vinceglb.filekit.path
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.model.AppState
import nnomm.core.settings.*
import nnomm.ui.*
import nnomm.ui.kit.*
import nnomm.ui.theme.LocalNnTheme

/** Called by both model constructors, before mock addresses can open overlays. */
fun registerSettingsOverlays(model: AppModel) {
    model.overlayFactories["confirm"] = { SettingsConfirm(it) }
    model.overlayFactories["report"] = { SettingsReport(it) }
    model.overlayFactories["licences"] = { SettingsLicences(it) }
}

internal fun performSettingAction(model: AppModel, definition: SettingDefinition, action: String) {
    if (definition.danger) {
        model.ui.settings.confirmKey = definition.key
        model.openOverlay("confirm")
        return
    }
    val state = model.store.state.value
    if (model.isDemo && action !in listOf("Licences")) {
        model.store.toast("This does nothing in demo mode.")
        return
    }
    when (action) {
        "Repair" -> if (state.game.folder != null) model.store.repairLoader() else feedback(model, "Choose a game folder before repairing the loader")
        "Find again" -> model.store.findGameFolder()
        "Clear" -> model.store.clearUnusedFiles()
        "Move" -> model.chooseStoreFolder()
        "Choose folder" -> model.ui.settings.chooseGameFolder = true
        "Open" -> state.settings.general["store"]?.content?.takeIf { it.isNotBlank() }?.let { model.platform.openFolder(File(it)) }
            ?: feedback(model, "The mod store location is unavailable")
        "Look now" -> model.checkAppUpdates()
        "Open log folder" -> state.game.folder?.let { model.platform.openFolder(File(it, "BepInEx")) }
            ?: feedback(model, "The game log folder is unavailable")
        "Copy report" -> model.openOverlay("report")
        "Licences" -> model.openOverlay("licences")
        else -> feedback(model, action)
    }
}

private fun feedback(model: AppModel, action: String) {
    model.store.toast(action)
}

@Composable
internal fun GameFolderChooser(model: AppModel) {
    if (!model.ui.settings.chooseGameFolder) return
    LaunchedEffect(model) {
        try {
            val folder = withContext(Dispatchers.IO) {
                FileKit.openDirectoryPicker(dialogSettings = FileKitDialogSettings(title = "Choose game folder"))
            }
            folder?.let { model.store.setSetting("gamePath", JsonPrimitive(it.path)) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            model.store.toast("Could not choose a folder: ${e.message}")
        } finally {
            model.ui.settings.chooseGameFolder = false
        }
    }
}

@Composable
internal fun RegistrySources(model: AppModel, state: AppState) {
    val c = LocalNnTheme.current.colors
    var address by remember { mutableStateOf("") }
    val nomnom = "https://kopterbuzz.github.io/NOMNOM/manifest/manifest.json"
    // RealRuntime reads newline-separated source addresses from this setting.
    val sources = state.settings.general["sources"]?.content?.lineSequence()?.filter { it.isNotBlank() }?.toList()
        ?: listOf(nomnom)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf(nomnom) + sources.filter { it != nomnom }).forEach { source ->
            Row(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(source in sources, { on ->
                    if (model.isDemo) {
                        model.store.toast("This does nothing in demo mode.")
                    } else {
                        val next = if (on) sources + source else sources - source
                        model.store.setSetting("sources", JsonPrimitive(next.joinToString("\n")))
                    }
                }, description = if (source == nomnom) "NOMNOM" else source)
                Column(Modifier.weight(1f)) {
                    Label(if (source == nomnom) "NOMNOM" else source, weight = 600, maxLines = 1)
                    Label(source.removePrefix("https://"), color = c.text3, size = 12f, maxLines = 1)
                }
                if (source == nomnom) {
                    val minutes = state.connection.readAtMillis?.let { ((state.nowMillis - it) / 60000).coerceAtLeast(0) }
                    Label("${state.catalog.count { it.localFile == null }} mods, " + if (minutes == null) "not read yet" else "read " + when { minutes == 0L -> "just now"; minutes < 60 -> "$minutes ${if (minutes == 1L) "minute" else "minutes"} ago"; minutes < 1440 -> (minutes / 60).let { "$it ${if (it == 1L) "hour" else "hours"} ago" }; else -> nnomm.core.rules.ago((minutes / 1440).toInt()) }, color = c.text2)
                    Button("Read now", { if (model.isDemo) model.store.toast("This does nothing in demo mode.") else model.store.refreshRegistry() }, size = ControlSize.Small)
                } else Button("Read now", { if (model.isDemo) model.store.toast("This does nothing in demo mode.") else model.store.refreshRegistry() }, size = ControlSize.Small)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingField(address, { address = it }, Modifier.weight(1f), "Address of another registry")
            Button("Add", {
                if (model.isDemo) {
                    model.store.toast("This does nothing in demo mode.")
                } else {
                    val entered = address.trim()
                    if (entered.isNotEmpty() && entered !in sources) {
                        model.store.setSetting("sources", JsonPrimitive((sources + entered).joinToString("\n")))
                        address = ""
                    }
                }
            })
        }
    }
}

private class SettingsConfirm(private val model: AppModel) : Overlay {
    @Composable
    override fun Content() {
        val definition = playerSettings.firstOrNull { it.key == model.ui.settings.confirmKey } ?: return
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        Dialog(true, definition.label + "?", model::closeOverlay, initialFocus = focus, footer = {
            Button("Cancel", model::closeOverlay, Modifier.focusRequester(focus), kind = ButtonKind.Ghost)
            Button(definition.actions.first(), {
                if (model.isDemo) model.store.toast("This does nothing in demo mode.")
                else if (definition.key == "dzSettings") model.store.resetSettings()
                else model.store.removeEverything()
                model.closeOverlay()
            }, kind = ButtonKind.Danger)
        }) {
            Label(definition.help + if (definition.key == "dzSettings") " You cannot undo this." else "")
        }
    }
}

private class SettingsLicences(private val model: AppModel) : Overlay {
    @Composable
    override fun Content() {
        Dialog(true, "Licences", model::closeOverlay, footer = {
            Button("Close", model::closeOverlay)
        }) {
            Label("NNOMM is a fork of NOMM by Combat787 (MIT licence). The mod list is NOMNOM by KopterBuzz.")
            Spacer(Modifier.height(12.dp))
            Label(MIT_LICENCE, size = 12.5f)
            Spacer(Modifier.height(12.dp))
            Label("Archivo", size = 15f, weight = 650)
            Label(ARCHIVO_LICENCE, size = 12.5f)
        }
    }
}

private class SettingsReport(private val model: AppModel) : Overlay {
    @Composable
    override fun Content() {
        var report by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            report = withContext(Dispatchers.IO) { nnomm.core.io.real.problemReport(model.store.state.value) }
        }
        Dialog(true, "Problem report", model::closeOverlay, wide = true, footer = {
            Button("Close", model::closeOverlay, kind = ButtonKind.Ghost)
            Button("Copy", { report?.let(model.platform::copyText) }, enabled = report != null, kind = ButtonKind.Strong)
        }) {
            Label("Check the report before you send it. It includes your game folder and messages from mods.")
            androidx.compose.foundation.text.selection.SelectionContainer {
                Label(report ?: "Reading the report…", size = 12.5f)
            }
        }
    }
}
