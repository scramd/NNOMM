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

/**
 * The menu of the profile name in the Play box and in the Library header: the profiles to switch
 * to, New profile, and the actions for the profile in use. Both places show the same menu.
 */
internal fun profileMenuItems(model: AppModel): List<MenuEntry> {
    val s = model.store.state.value
    return listOf(MenuEntry.Heading("Use profile")) +
        s.profiles.map { p ->
            MenuEntry.Item(
                p.name,
                hint = "${onCount(s, p.id)} mods",
                icon = if (p.id == s.profileId) NnIcons.check else NnIcons.minus,
                action = { model.useProfile(p.id) },
            )
        } +
        listOf(
            MenuEntry.Separator,
            MenuEntry.Item("New profile", icon = NnIcons.plus, action = { model.openOverlay("newprofile") }),
            MenuEntry.Separator,
        ) +
        profileItems(model, s)
}

/** The actions for the profile in use. */
internal fun profileItems(model: AppModel, s: AppState): List<MenuEntry> = buildList {
    val p = s.profile()
    fun overlay(kind: String) = model.openOverlay(kind, p.id)
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
    if (followsPack(p))
        add(MenuEntry.Item("Stop following this pack") { model.store.stopFollowingPack(p.id) })
    if (p.id != "default") {
        add(MenuEntry.Separator)
        add(
            MenuEntry.Item("Delete this profile", icon = NnIcons.trash, danger = true) {
                model.store.deleteProfile(p.id)
            }
        )
    }
}

/** [keys] is false where the Library's keys do not work, so the menu must not name them. */
internal fun modItems(
    model: AppModel,
    s: AppState,
    id: String,
    keys: Boolean = true,
): List<MenuEntry> = buildList {
    fun key(name: String) = name.takeIf { keys }
    add(MenuEntry.Item("Open details", hint = key("Enter")) { model.ui.openMod(id) })
    add(
        MenuEntry.Item(if (isOn(s, id)) "Turn off" else "Turn on", hint = key("Space")) {
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
    add(MenuEntry.Item("Open the mod folder", icon = NnIcons.folder) { model.openModFolder(id) })
    add(MenuEntry.Item("Open its settings file", icon = NnIcons.file) { model.openModSettings(id) })
    add(MenuEntry.Separator)
    add(
        MenuEntry.Item(
            if (s.settings.bool("showProfiles") && s.profiles.size > 1) "Uninstall (all profiles)"
            else "Uninstall",
            hint = key("Delete"),
            icon = NnIcons.trash,
            danger = true,
        ) {
            model.store.uninstall(listOf(id))
        }
    )
}

internal fun moreItems(model: AppModel): List<MenuEntry> =
    listOf(
        MenuEntry.Item("Import a modpack", icon = NnIcons.box) { model.importPack() },
        MenuEntry.Item("Share this profile as a modpack", icon = NnIcons.share) {
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
            model.store.state.value.game.folder?.let { model.openPath(File(it, "BepInEx/plugins")) }
        },
    )

/** The row menu when the row is one of several selected mods: the actions of the selection bar. */
internal fun selectionItems(
    model: AppModel,
    s: AppState,
    picked: Set<String>,
    remove: (Set<String>) -> Unit,
    clear: () -> Unit,
): List<MenuEntry> = buildList {
    val ids = picked.toList()
    add(MenuEntry.Heading("${ids.size} selected"))
    add(MenuEntry.Item("Turn on") { model.store.setOn(ids, true) })
    add(MenuEntry.Item("Turn off") { model.store.setOn(ids, false) })
    val updates = ids.filter { hasUpdate(s, it) }
    if (updates.isNotEmpty())
        add(
            MenuEntry.Item("Update (${updates.size})", icon = NnIcons.up) {
                updates.forEach(model.store::update)
            }
        )
    add(MenuEntry.Separator)
    add(
        MenuEntry.Item(
            if (s.settings.bool("showProfiles") && s.profiles.size > 1) "Uninstall (all profiles)"
            else "Uninstall",
            hint = "Delete",
            icon = NnIcons.trash,
            danger = true,
        ) {
            remove(picked)
        }
    )
    add(MenuEntry.Separator)
    add(MenuEntry.Item("Clear the selection", icon = NnIcons.x, action = clear))
}

/** Right-click on a mod in Browse. An installed mod gets the Library's menu. */
internal fun browseItems(model: AppModel, s: AppState, id: String): List<MenuEntry> {
    if (id in s.installed) return modItems(model, s, id, keys = false)
    val next = modAction(s, id)
    return buildList {
        add(MenuEntry.Item("Open details") { model.ui.openMod(id) })
        next.action?.let { action ->
            add(
                MenuEntry.Item(next.label, icon = NnIcons.download, enabled = !next.disabled) {
                    model.perform(action)
                }
            )
        }
    }
}
