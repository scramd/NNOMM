package nnomm.ui.shots

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import nnomm.ui.AppModel
import nnomm.ui.dialogs.registerDialogFactories
import nnomm.ui.shell.*

data class MockShot(
    val name: String,
    val address: String,
    val width: Int = 1328,
    val height: Int = 792,
    val seed: (AppModel) -> Unit = {},
    val initialState: (nnomm.core.model.AppState) -> nnomm.core.model.AppState = { it },
)

val mockShots =
    listOf(
        MockShot("01-browse", "page=browse", 1328, 792),
        MockShot("02-browse-search", "page=browse&q=hud&panel=mod:fuel-planner", 1328, 792),
        MockShot("03-browse-list-tags", "page=browse&view=list&tags=Map,Visual", 1328, 792),
        MockShot("04-library", "page=library", 1328, 792),
        MockShot(
            "05-library-bulk",
            "page=library&sel=minimap,delivery,spectator&panel=mod:harrier-kai&tab=needs",
            1328,
            792,
        ),
        MockShot(
            "06-library-updates",
            "page=library&lf=upd&dl=minimap&panel=mod:minimap&tab=new",
            1328,
            792,
        ),
        MockShot("08-settings-game", "page=settings&cat=game", 1328, 792),
        MockShot("10-settings-profile", "page=settings&scope=profile&pid=hardcore", 1328, 792),
        MockShot("11-settings-search", "page=settings&sq=update", 1328, 792),
        MockShot("12-palette", "page=library&overlay=palette&pq=map", 1328, 792),
        MockShot("13-import", "page=library&overlay=import", 1328, 792),
        MockShot("14-pack-update", "page=library&prof=friday&overlay=packupdate", 1328, 792),
        MockShot("15-troubleshoot", "page=library&trouble=1", 1328, 792),
        MockShot("16-first-start", "mock=first", 1328, 792),
        MockShot("17-drop", "page=library&overlay=drop", 1328, 792),
        MockShot("18-light-library", "page=library&theme=light&panel=mod:spectator", 1328, 792),
        MockShot("19-light-browse", "page=browse&theme=light", 1328, 792),
        MockShot("20-narrow-browse", "page=browse", 728, 720),
        MockShot("21-narrow-library", "page=library&panel=mod:minimap", 728, 720),
        MockShot("22-empty-library", "page=library&mock=empty", 1328, 792),
        MockShot("23-offline", "page=browse&mock=offline", 1328, 792),
        MockShot("24-new-profile", "page=library&overlay=newprofile", 1328, 792),
        MockShot("25-share", "page=library&overlay=share", 1328, 792),
        MockShot("26-settings-danger", "page=settings&cat=danger", 1328, 792),
        MockShot("27-settings-mods", "page=settings&cat=mods", 1328, 792),
        MockShot("28-import-add", "page=library&overlay=import&mode=add", 1328, 792),
        MockShot("30-local-mod", "page=library&lf=local&panel=mod:local-testhud", 1328, 792),
        MockShot("31-friday", "page=library&prof=friday&panel=mod:harrier-kai", 1328, 792),
        MockShot("32-settings-registry", "page=settings&cat=browse", 1328, 792),
        MockShot("36-browse-tags", "page=browse&tags=HUD,Map", 1328, 792),
        MockShot("37-browse-hide-tags", "page=browse&tags=HUD&hide=Map,Multiplayer", 1328, 792),
    )

val followupShots = listOf("dark", "light").flatMap { theme ->
        listOf(1328, 728).flatMap { width ->
            val size = if (width == 1328) "wide" else "narrow"
            listOf(
                MockShot("followup-storage-$theme-$size", "page=settings&cat=storage&theme=$theme", width, if (width == 1328) 792 else 720),
                MockShot("followup-storage-unknown-$theme-$size", "page=settings&cat=storage&theme=$theme", width, if (width == 1328) 792 else 720,
                    initialState = { it.copy(storeUnusedMb = null) }),
                MockShot("closing-storage-failed-$theme-$size", "page=settings&cat=storage&theme=$theme", width, if (width == 1328) 792 else 720,
                    initialState = { it.copy(storeScanStatus = nnomm.core.model.LoadStatus.FAILED) }),
                MockShot("closing-danger-$theme-$size", "page=settings&cat=danger&theme=$theme", width, if (width == 1328) 792 else 720),
                MockShot("closing-confirm-$theme-$size", "page=settings&cat=danger&theme=$theme", width, if (width == 1328) 792 else 720,
                    seed = { it.ui.settings.confirmKey = "dzAll"; it.openOverlay("confirm") }),
                MockShot("followup-collision-$theme-$size", "page=library&panel=mod:minimap&theme=$theme", width, if (width == 1328) 792 else 720,
                    initialState = { it.copy(applyProblems = listOf("minimap", "delivery").associateWith {
                        "Two enabled mods use the same game folder name. Turn off one of them and Retry."
                    }) }),
            )
        }
    }

private fun nnomm.core.model.AppState.profilesHidden() =
    copy(
        profileId = "default",
        settings =
            settings.copy(
                general =
                    settings.general +
                        ("showProfiles" to kotlinx.serialization.json.JsonPrimitive(false))
            ),
    )

/** States of the basic-feature fixes: hidden profiles, profile menus, dialogs and toasts. */
val featureShots =
    listOf(
        MockShot(
            "feature-import-profiles-hidden",
            "page=library&overlay=import",
            initialState = { it.profilesHidden() },
        ),
    )

fun MockShot.spec() =
    ShotSpec(name, !address.contains("theme=light"), width, height) {
        val model = remember {
            AppModel(mockStore(address, initialState), ShotPlatform).also {
                nnomm.ui.settings.registerSettingsOverlays(it)
                it.registerDialogFactories()
                applyMockAddress(it, address)
                seed(it)
                if (address.contains("mock=first")) it.openOverlay("first")
                if (address.contains("trouble=1")) it.openOverlay("trouble")
            }
        }
        DisposableEffect(model) { onDispose { model.close() } }
        Box(Modifier.fillMaxSize()) {
            WindowShell { AppShell(model) }
            AppLayers(model)
        }
    }
