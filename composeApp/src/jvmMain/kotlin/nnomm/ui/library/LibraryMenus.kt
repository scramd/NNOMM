package nnomm.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import java.io.File
import nnomm.core.model.AppState
import nnomm.core.rules.*
import nnomm.ui.*
import nnomm.ui.kit.*

/** Each popup participates in the shell's first Escape step. */
@Composable
internal fun LibraryMenu(entries: () -> List<MenuEntry>, anchor: @Composable (() -> Unit) -> Unit) {
    val model = LocalApp.current
    var open by remember { mutableStateOf(false) }
    val dismiss = remember { { open = false } }
    DisposableEffect(open) {
        if (open) model.ui.dismissMenu = dismiss
        onDispose { if (model.ui.dismissMenu === dismiss) model.ui.dismissMenu = null }
    }
    Box {
        anchor { open = !open }
        Menu(open, dismiss, if (open) entries() else emptyList())
    }
}

internal fun profileItems(model: AppModel, s: AppState): List<MenuEntry> = buildList {
    val p = s.profile()
    fun overlay(kind: String) {
        model.ui.overlayProfileId = p.id
        model.openOverlay(kind)
    }
    add(MenuEntry.Item("Rename") { overlay("rename") })
    add(MenuEntry.Item("Make a copy", icon = NnIcons.copy) { model.store.copyProfile(p.id) })
    add(MenuEntry.Item("Share as a modpack", icon = NnIcons.share) { overlay("share") })
    add(
        MenuEntry.Item("Settings for this profile", icon = NnIcons.gear) {
            model.ui.go(Page.Settings)
            model.ui.settings.scope = "profile"
            model.ui.settings.profileId = p.id
            model.ui.settings.query = ""
        }
    )
    p.pack?.nextVersion?.let { next ->
        add(
            MenuEntry.Item("See what changes in $next", icon = NnIcons.box) {
                overlay("packupdate")
            }
        )
    }
    if (p.id != "default") {
        add(MenuEntry.Separator)
        add(
            MenuEntry.Item("Delete this profile", icon = NnIcons.trash, danger = true) {
                model.store.deleteProfile(p.id)
            }
        )
    }
}

internal fun modItems(model: AppModel, s: AppState, id: String): List<MenuEntry> = buildList {
    add(MenuEntry.Item("Open details", hint = "Enter") { model.ui.openMod(id) })
    add(
        MenuEntry.Item(if (isOn(s, id)) "Turn off" else "Turn on", hint = "Space") {
            model.store.toggle(id)
        }
    )
    if (hasUpdate(s, id)) {
        add(
            MenuEntry.Item("Update to ${s.mod(id).newestRelease?.version}", icon = NnIcons.up) {
                model.store.update(id)
            }
        )
        add(MenuEntry.Item("See what's new") { model.ui.openMod(id, PanelTab.WhatsNew) })
    }
    add(MenuEntry.Separator)
    // TODO(core): expose managed mod/config paths rather than guessing a path under the game
    // folder.
    add(MenuEntry.Item("Open the mod folder", icon = NnIcons.folder, enabled = false) {})
    add(MenuEntry.Item("Open its settings file", icon = NnIcons.file, enabled = false) {})
    add(MenuEntry.Separator)
    add(
        MenuEntry.Item(
            if (s.settings.bool("showProfiles") && s.profiles.size > 1) "Uninstall (all profiles)"
            else "Uninstall",
            hint = "Delete",
            icon = NnIcons.trash,
            danger = true,
        ) {
            model.store.uninstall(listOf(id))
        }
    )
}

internal fun moreItems(model: AppModel): List<MenuEntry> =
    listOf(
        MenuEntry.Item("Import a modpack", icon = NnIcons.box) {
            model.ui.importMode = "new"
            model.openOverlay("import")
        },
        MenuEntry.Item("Share this profile as a modpack", icon = NnIcons.share) {
            model.ui.overlayProfileId = model.store.state.value.profileId
            model.openOverlay("share")
        },
        MenuEntry.Separator,
        MenuEntry.Item("Look for updates", icon = NnIcons.refresh) { model.store.lookForUpdates() },
        MenuEntry.Item("Find the mod that breaks the game", icon = NnIcons.split) {
            model.openOverlay("trouble")
        },
        MenuEntry.Separator,
        MenuEntry.Item(
            "Open the mods folder",
            icon = NnIcons.folder,
            enabled = model.store.state.value.game.folder != null,
        ) {
            model.store.state.value.game.folder?.let {
                model.platform.openFolder(File(it, "BepInEx/plugins"))
            }
        },
    )
