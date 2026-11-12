package nnomm.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.*
import java.io.File
import kotlinx.coroutines.*
import nnomm.core.model.UiAction
import nnomm.core.store.AppStore

/**
 * Work that needs the operating system or the window: file dialogs, opening folders and links, the
 * clipboard. The window layer implements it; the render harness uses a no-op version.
 */
interface Platform {
    /** Opens a file window. Returns the chosen files, or an empty list. */
    suspend fun chooseFiles(title: String, extensions: List<String>, multiple: Boolean): List<File>

    /** Opens a save window. Returns the chosen file, or null. */
    suspend fun chooseSaveFile(title: String, suggestedName: String, extension: String): File?

    fun openFolder(folder: File)

    fun openUrl(url: String)

    fun copyText(text: String)
}

/**
 * One per window: the domain store from :core, the view state, and the platform. Screens read it
 * with `LocalApp.current`, collect `store.state`, and call store actions. Nothing slow runs on the
 * UI thread: store actions return at once.
 */
class AppModel(
    val store: AppStore,
    val platform: Platform,
    val overlayFactories: MutableMap<String, (AppModel) -> Overlay> = mutableMapOf(),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun close() {
        scope.cancel()
        store.close()
    }

    private val pageKeys = mutableMapOf<Page, (androidx.compose.ui.input.key.KeyEvent) -> Boolean>()
    private val overlayKeys =
        mutableMapOf<String, (androidx.compose.ui.input.key.KeyEvent) -> Boolean>()
    private val searchFocus = mutableMapOf<Page, () -> Unit>()

    /** Register in DisposableEffect, and call the returned disposer onDispose. */
    fun registerKeys(
        page: Page,
        handler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
    ): () -> Unit {
        pageKeys[page] = handler
        return { if (pageKeys[page] === handler) pageKeys.remove(page) }
    }

    fun registerOverlayKeys(
        kind: String,
        handler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
    ): () -> Unit {
        overlayKeys[kind] = handler
        return { if (overlayKeys[kind] === handler) overlayKeys.remove(kind) }
    }

    fun registerSearch(page: Page, focus: () -> Unit): () -> Unit {
        searchFocus[page] = focus
        return { if (searchFocus[page] === focus) searchFocus.remove(page) }
    }

    var checkAppUpdates: () -> Unit = {}
    var openAppReleases: () -> Unit = {}

    fun openOverlay(kind: String) {
        val layer = overlayFactories[kind]?.invoke(this) ?: return
        ui.overlayKind = kind
        ui.overlay = layer
    }

    fun closeOverlay() {
        ui.overlay = null
        ui.overlayKind = null
        ui.dragging = false
    }

    fun acceptFiles(files: List<File>) {
        ui.dragging = false
        files.forEach { file ->
            val name = file.name.lowercase()
            when {
                name.endsWith(".nommpack") || name.endsWith(".nomm.json") -> {
                    ui.importFile = file
                    openOverlay("import")
                }
                file.extension.lowercase() in listOf("dll", "zip", "7z") ->
                    store.addLocal(file.absolutePath)
            }
        }
    }

    fun shortcut(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val typing = ui.focusedField != null
        if (
            ui.overlayKind == "palette" &&
                event.key in listOf(Key.DirectionDown, Key.DirectionUp, Key.Enter)
        ) {
            if (overlayKeys["palette"]?.invoke(event) == true) return true
        }
        when {
            ctrl && event.key == Key.K -> {
                if (ui.overlayKind == "palette") closeOverlay() else openOverlay("palette")
            }
            event.key == Key.Escape ->
                when {
                    ui.dismissMenu != null -> ui.dismissMenu?.invoke()
                    ui.downloadsOpen -> ui.downloadsOpen = false
                    ui.overlay != null || ui.dragging -> closeOverlay()
                    ui.focusedField?.value?.invoke()?.isNotEmpty() == true ->
                        ui.focusedField?.clear?.invoke()
                    ui.panel != null -> ui.panel = null
                    ui.library.selection.isNotEmpty() -> ui.library.selection = emptySet()
                    else -> return false
                }
            ctrl && event.key == Key.Enter -> store.play()
            ctrl && event.key == Key.One -> ui.go(Page.Browse)
            ctrl && event.key == Key.Two -> ui.go(Page.Library)
            ctrl && event.key == Key.Three -> if (showServers()) ui.go(Page.Servers)
            ctrl && event.key == Key.Comma -> ui.go(Page.Settings)
            ctrl && event.key == Key.Z && !typing -> {
                val toast =
                    store.state.value.toasts.lastOrNull { it.action == "Undo" } ?: return false
                runToast(toast.id)
            }
            event.key == Key.Slash && !typing && ui.overlay == null ->
                searchFocus[ui.page]?.invoke() ?: return false
            ui.overlay == null && !typing && ui.dismissMenu == null ->
                return pageKeys[ui.page]?.invoke(event) ?: false
            else -> return false
        }
        return true
    }

    fun showServers() = store.state.value.settings.general["showServers"]?.content != "false"

    val ui = UiState()

    /** Runs the action of a toast (Undo, "What's new"...) and follows its UI target. */
    fun runToast(id: Long) {
        store.runToast(id)?.let(::perform)
    }

    /**
     * Follows a UI target that the core hands back (toast actions, command search, problem fixes).
     * The shell completes this list; pages may add the kinds they own.
     */
    fun perform(action: UiAction) {
        when (action.kind) {
            "openMod" ->
                action.id?.let {
                    ui.openMod(
                        it,
                        when (action.value) {
                            "new" -> PanelTab.WhatsNew
                            "needs" -> PanelTab.Needs
                            else -> PanelTab.About
                        },
                    )
                }
            "navigate" -> Page.entries.firstOrNull { it.name.equals(action.id, true) }?.let(ui::go)
            "openSettings" -> {
                ui.go(Page.Settings)
                ui.settings.category = action.id ?: "game"
                ui.settings.scope = "all"
                ui.settings.query = ""
            }
            "useProfile" ->
                action.id?.let {
                    store.useProfile(it)
                    ui.go(Page.Library)
                }
            "install" -> action.id?.let(store::install)
            "update" -> action.id?.let(store::update)
            "repair",
            "repairProblem" -> action.id?.let(store::repair)
            "turnOn" -> action.id?.let { store.setOn(listOf(it), true) }
            "turnOff" -> action.id?.let { store.setOn(listOf(it), false) }
            "paletteToggle" -> action.id?.let(store::paletteToggle)
            "uninstall" -> action.id?.let { store.uninstall(listOf(it)) }
            "play" -> store.play()
            "lookForUpdates" -> store.lookForUpdates()
            "libraryFilter" -> { ui.go(Page.Library); ui.library.filter = action.id ?: action.value ?: "all" }
            "addFile" ->
                scope.launch {
                    acceptFiles(
                        platform.chooseFiles(
                            "Add a mod from a file",
                            listOf("dll", "zip", "7z"),
                            true,
                        )
                    )
                }
            "openGameFolder" -> store.state.value.game.folder?.let { platform.openFolder(File(it)) }
            "newProfile" -> openOverlay("newprofile")
            "import",
            "share",
            "trouble" -> openOverlay(action.kind)
            else -> Unit
        }
    }
}

val LocalApp = staticCompositionLocalOf<AppModel> { error("No AppModel provided") }
