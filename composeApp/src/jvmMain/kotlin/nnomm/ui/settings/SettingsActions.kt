package nnomm.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    model.overlayFactories["licences"] = { SettingsLicences(it) }
}

internal fun performSettingAction(model: AppModel, definition: SettingDefinition, action: String) {
    if (definition.danger) {
        model.ui.settings.confirmKey = definition.key
        model.openOverlay("confirm")
        return
    }
    val state = model.store.state.value
    when (action) {
        "Repair" -> if (state.game.folder != null) model.store.repairLoader() else todo(model, "Choose a game folder before repairing the loader")
        "Choose folder" -> model.ui.settings.chooseGameFolder = true
        "Open" -> state.settings.general["store"]?.content?.takeIf { it.isNotBlank() }?.let { model.platform.openFolder(File(it)) }
            ?: todo(model, "The mod store location is unavailable")
        "Look now" -> model.checkAppUpdates()
        "Open log folder" -> state.game.folder?.let { model.platform.openFolder(File(it, "BepInEx")) }
            ?: todo(model, "The game log folder is unavailable")
        "Copy report" -> todo(model, "Copy report needs the last game log and report export")
        "Licences" -> model.openOverlay("licences")
        else -> todo(model, action)
    }
}

private fun todo(model: AppModel, action: String) {
    model.ui.settings.notice = "TODO(core): $action"
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
            model.ui.settings.notice = "Could not choose a folder: ${e.message}"
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
    // TODO(core): SettingsValues currently accepts primitives only. Preserve the source list as
    // newline-separated addresses until core supplies a registry configuration type and reader.
    val sources = state.settings.general["sources"]?.content?.lineSequence()?.filter { it.isNotBlank() }?.toList()
        ?: listOf(nomnom)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf(nomnom) + sources.filter { it != nomnom }).forEach { source ->
            Row(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(source in sources, { on ->
                    val next = if (on) sources + source else sources - source
                    model.store.setSetting("sources", JsonPrimitive(next.joinToString("\n")))
                    todo(model, "Registry selection is saved; the registry reader does not support switching sources yet")
                }, description = if (source == nomnom) "NOMNOM" else source)
                Column(Modifier.weight(1f)) {
                    Label(if (source == nomnom) "NOMNOM" else source, weight = 600, maxLines = 1)
                    Label(source.removePrefix("https://"), color = c.text3, size = 12f, maxLines = 1)
                }
                if (source == nomnom) {
                    val minutes = state.connection.readAtMillis?.let { ((state.nowMillis - it) / 60000).coerceAtLeast(0) }
                    Label("${state.catalog.count { it.localFile == null }} mods, " + if (minutes == null) "not read yet" else "read $minutes ${if (minutes == 1L) "minute" else "minutes"} ago", color = c.text2)
                    Button("Read now", model.store::refreshRegistry, size = ControlSize.Small)
                } else Button("Read now", { todo(model, "Reading additional registries") }, size = ControlSize.Small)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingField(address, { address = it }, Modifier.weight(1f), "Address of another registry")
            Button("Add", {
                val entered = address.trim()
                if (entered.isNotEmpty() && entered !in sources) {
                    model.store.setSetting("sources", JsonPrimitive((sources + entered).joinToString("\n")))
                    address = ""
                    todo(model, "Registry address is saved; reading additional registries is not available yet")
                }
            })
        }
    }
}

private class SettingsConfirm(private val model: AppModel) : Overlay {
    @Composable
    override fun Content() {
        val definition = SETTINGS.firstOrNull { it.key == model.ui.settings.confirmKey } ?: return
        Dialog(true, definition.label + "?", model::closeOverlay, footer = {
            Button("Cancel", model::closeOverlay, kind = ButtonKind.Ghost)
            Button(definition.actions.first(), {
                todo(model, definition.actions.first())
                model.closeOverlay()
            }, kind = ButtonKind.Danger)
        }) {
            Label(definition.help + " You cannot undo this.")
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
