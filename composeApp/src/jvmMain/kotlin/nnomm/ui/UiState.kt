package nnomm.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nnomm.ui.browse.BrowseView
import nnomm.ui.library.LibraryView
import nnomm.ui.palette.PaletteView
import nnomm.ui.settings.SettingsView

/** The pages of the sidebar. Servers shows only "Coming later" in the first version. */
enum class Page {
    Browse,
    Library,
    Servers,
    Settings,
}

/** Tabs of the mod detail panel (`pMod` in the mock's views.js). */
enum class PanelTab {
    About,
    Readme,
    WhatsNew,
    Needs,
}

/** The open detail panel. */
data class PanelRef(val modId: String, val tab: PanelTab = PanelTab.About)

/**
 * A dialog or full-window layer (command search, pack import, new profile, broken-mod test, drop
 * target...). Each dialog defines its own class that implements this, in `nnomm.ui.dialogs` or
 * `nnomm.ui.palette`, and the shell draws the open one.
 */
interface Overlay {
    /** Draws the layer. The shell places it above the window content and below toasts. */
    @androidx.compose.runtime.Composable fun Content()
}

/**
 * View state that is not domain state (domain state lives in `AppStore.state` in :core). One
 * instance per window. Every field is Compose snapshot state, so reading it in a composable
 * recomposes on change. Each page owns its own view class in its package.
 */
class UiState {
    var updateStatus by mutableStateOf<String?>(null)
    var onPageChanged: (Page) -> Unit = {}
    var page by mutableStateOf(Page.Browse)
    var panel by mutableStateOf<PanelRef?>(null)
    var overlay by mutableStateOf<Overlay?>(null)
    /** The sidebar's download list popover. */
    var overlayKind by mutableStateOf<String?>(null)
    var overlayProfileId by mutableStateOf<String?>(null)
    var importFile by mutableStateOf<java.io.File?>(null)
    var importMode by mutableStateOf("new")
    var mock by mutableStateOf("normal")
    var dragging by mutableStateOf(false)
    var dismissMenu: (() -> Unit)? = null
    /** The open dialog's primary action, run by Enter. Null when Enter must not act. */
    var dialogEnter: (() -> Unit)? = null
    var focusedField: FocusedField? by mutableStateOf(null)
    var pageKeysEnabled by mutableStateOf(true)
    var downloadsOpen by mutableStateOf(false)

    val browse = BrowseView()
    val library = LibraryView()
    val settings = SettingsView()
    val palette = PaletteView()

    fun go(to: Page) {
        page = to
        onPageChanged(to)
        panel = null
    }

    fun openMod(id: String, tab: PanelTab = PanelTab.About) {
        panel = PanelRef(id, tab)
    }
}

/** Register from a focused editable field; clear it when focus leaves. */
data class FocusedField(val value: () -> String, val clear: () -> Unit)
