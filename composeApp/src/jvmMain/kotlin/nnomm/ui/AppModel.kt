@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package nnomm.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import nnomm.core.model.ActionKind
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

    suspend fun chooseFolder(title: String): File? = null

    /** Opens a folder in the file manager, or a file with its program. Throws when that fails. */
    suspend fun openFolder(folder: File)

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

    var isDemo: Boolean = store.io.persistence == null

    fun chooseStoreFolder() {
        scope.launch { platform.chooseFolder("Move mod store")?.let { store.moveStore(it.absolutePath) } }
    }

    var checkAppUpdates: () -> Unit = {}
    var openAppReleases: () -> Unit = {}

    /** [profileId] is the profile a profile dialog acts on; null means the profile in use. */
    fun openOverlay(kind: String, profileId: String? = null) {
        if (!store.state.value.firstStart.complete && kind != "first") return
        ui.overlayProfileId = profileId
        val layer = overlayFactories[kind]?.invoke(this) ?: return
        if (kind == "palette") {
            ui.palette.query = ""
            ui.palette.index = 0
        }
        ui.overlayKind = kind
        ui.overlay = layer
    }

    fun closeOverlay() {
        if (ui.overlayKind == "first" && !store.state.value.firstStart.complete) return
        ui.overlay = null
        ui.overlayKind = null
        ui.dragging = false
        ui.importFile = null
    }

    fun acceptFiles(files: List<File>) {
        ui.dragging = false
        files.forEach { file ->
            val name = file.name.lowercase()
            when {
                name.endsWith(".nommpack") || name.endsWith(".nomm.json") -> {
                    ui.importMode = "new"
                    ui.importFile = file
                    openOverlay("import")
                }
                file.extension.lowercase() in listOf("dll", "zip", "7z") ->
                    store.addLocal(file.absolutePath)
                else -> notify("NNOMM cannot use ${file.name}")
            }
        }
    }

    private var handledCharacter: Int? = null

    /**
     * Windows can emit a physical key and then its character; X11 Tao emits only the character for
     * these keys. Do not run a command twice or insert its slash.
     */
    fun suppressHandledCharacter(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyUp) handledCharacter = null
        if (event.type != KeyEventType.Unknown || event.utf16CodePoint != handledCharacter)
            return false
        handledCharacter = null
        return true
    }

    fun shortcut(raw: KeyEvent): Boolean {
        val handled = dispatchShortcut(shortcutEvent(raw))
        if (handled && raw.type == KeyEventType.KeyDown) {
            handledCharacter =
                when (raw.key) {
                    Key.Spacebar -> 32
                    Key.Slash -> 47
                    else -> null
                }
        }
        return handled
    }

    private fun dispatchShortcut(event: KeyEvent): Boolean {

        if (event.type != KeyEventType.KeyDown) return false
        if (ui.overlayKind == "first")
            return event.key == Key.Escape ||
                ((event.isCtrlPressed || event.isMetaPressed) &&
                    event.key in listOf(Key.K, Key.Enter, Key.One, Key.Two, Key.Three, Key.Comma))
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val typing = ui.focusedField != null
        if (
            ui.overlayKind == "palette" &&
                event.key in listOf(Key.DirectionDown, Key.DirectionUp, Key.Enter)
        ) {
            if (overlayKeys["palette"]?.invoke(event) == true) return true
        }
        if (ui.overlayKind == "palette") {
            when {
                event.key == Key.Escape || (ctrl && event.key == Key.K) -> closeOverlay()
                ctrl && event.key in listOf(Key.One, Key.Two, Key.Three, Key.Comma, Key.Enter) -> Unit
                else -> return false
            }
            return true
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
            // Enter runs the dialog's primary action when no focused control took it.
            ui.overlay != null &&
                (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                !ctrl &&
                !event.isAltPressed &&
                !event.isShiftPressed &&
                ui.dismissMenu == null -> ui.dialogEnter?.invoke() ?: return false
            // Ctrl Enter starts the game, but not from inside a dialog.
            ctrl && event.key == Key.Enter -> if (ui.overlay == null && !ui.dragging) store.play()
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
            ui.overlay == null && !typing && ui.dismissMenu == null && ui.pageKeysEnabled ->
                return pageKeys[ui.page]?.invoke(event) ?: false
            else -> return false
        }
        return true
    }

    fun notify(message: String) {
        ui.updateStatus = message
    }

    fun useProfile(id: String) {
        ui.library.selection = emptySet()
        ui.library.anchorId = null
        ui.library.focusedId = null
        store.useProfile(id)
        ui.go(Page.Library)
    }

    fun importPack() {
        ui.importFile = null
        ui.importMode = "new"
        scope.launch { nnomm.ui.dialogs.choosePackFor(this@AppModel) }
    }

    fun replaceLocal(id: String) {
        scope.launch {
            platform
                .chooseFiles("Replace with a newer file", listOf("dll", "zip", "7z"), false)
                .firstOrNull()
                ?.let { store.replaceLocal(id, it.absolutePath) }
        }
    }

    fun openPath(path: File?) {
        if (store.io.modStore !is nnomm.core.io.real.RealModStore) {
            notify("This does nothing in demo mode.")
            return
        }
        scope.launch {
            val exists = withContext(Dispatchers.IO) { path?.exists() == true }
            if (!exists) notify("${path?.name ?: "The file or folder"} was not found.")
            else
                try {
                    platform.openFolder(path!!)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    store.io.logger.error("open ${path?.absolutePath}", e)
                    notify("Could not open ${path?.name}. ${e.message.orEmpty()}".trim())
                }
        }
    }

    fun openModFolder(id: String) {
        val disk = store.io.modStore as? nnomm.core.io.real.RealModStore
        val version = nnomm.core.rules.verOf(store.state.value, id)
        openPath(if (disk != null && version != null) disk.version(id, version).toFile() else null)
    }

    fun openModSettings(id: String) {
        val disk = store.io.modStore as? nnomm.core.io.real.RealModStore
        if (disk == null) {
            notify("This does nothing in demo mode.")
            return
        }
        val state = store.state.value
        val version = nnomm.core.rules.verOf(state, id) ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    state.game.folder?.let { folder ->
                        nnomm.core.config.modConfigFiles(
                            java.nio.file.Path.of(folder), disk.version(id, version), id, state.mod(id).name,
                        )
                    }.orEmpty()
                }
            }.onSuccess { files ->
                if (files.isEmpty()) notify("This mod has no settings file yet. It appears after the game has run once with the mod.")
                else files.forEach { openPath(it.toFile()) }
            }.onFailure { notify("Could not find this mod's settings files.") }
        }
    }

    fun showServers() = store.state.value.settings.general["showServers"]?.content != "false"

    val ui = UiState().apply {
        page = startupPage(store.state.value.settings)
        onPageChanged = { store.setSetting("lastPage", kotlinx.serialization.json.JsonPrimitive(it.name)) }
    }

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
            ActionKind.RETRYAPPLY -> store.retryApply()
            ActionKind.OPENMOD ->
                action.id?.let {
                    ui.openMod(
                        it,
                        when (action.value) {
                            "readme" -> PanelTab.Readme
                            "new" -> PanelTab.WhatsNew
                            "needs" -> PanelTab.Needs
                            else -> PanelTab.About
                        },
                    )
                }
            ActionKind.NAVIGATE ->
                Page.entries.firstOrNull { it.name.equals(action.id, true) }?.let(ui::go)
            ActionKind.OPENSETTINGS -> {
                ui.go(Page.Settings)
                ui.settings.category = action.id ?: "game"
                ui.settings.scope = "all"
                ui.settings.query = ""
            }
            ActionKind.USEPROFILE -> action.id?.let { useProfile(it) }
            ActionKind.INSTALL -> action.id?.let(store::install)
            ActionKind.UPDATE -> action.id?.let(store::update)
            ActionKind.REPAIR,
            ActionKind.REPAIRPROBLEM -> action.id?.let(store::repair)
            ActionKind.TURNON -> action.id?.let { store.setOn(listOf(it), true) }
            ActionKind.TURNOFF -> action.id?.let { store.setOn(listOf(it), false) }
            ActionKind.PALETTETOGGLE -> action.id?.let(store::paletteToggle)
            ActionKind.UNINSTALL -> action.id?.let { store.uninstall(listOf(it)) }
            ActionKind.PLAY -> store.play()
            ActionKind.LOOKFORUPDATES -> store.lookForUpdates()
            ActionKind.LIBRARYFILTER -> {
                ui.go(Page.Library)
                ui.library.selection = emptySet()
                ui.library.filter = action.id ?: action.value ?: "all"
            }
            ActionKind.ADDFILE ->
                scope.launch {
                    acceptFiles(
                        platform.chooseFiles(
                            "Add a mod from a file",
                            listOf("dll", "zip", "7z"),
                            true,
                        )
                    )
                }
            ActionKind.OPENGAMEFOLDER -> openPath(store.state.value.game.folder?.let(::File))
            ActionKind.NEWPROFILE -> openOverlay("newprofile")
            ActionKind.IMPORT -> importPack()
            ActionKind.SHARE,
            ActionKind.TROUBLE -> openOverlay(action.kind.name.lowercase())
            else -> Unit
        }
    }
}

val LocalOptionalApp = staticCompositionLocalOf<AppModel?> { null }

val LocalApp = staticCompositionLocalOf<AppModel> { error("No AppModel provided") }

/**
 * Tao sends character input separately with an unknown key/type on X11. Interpret only the two page
 * commands; never turn arbitrary typed text into physical key presses.
 */
internal fun shortcutEvent(event: KeyEvent): KeyEvent {
    if (event.type == KeyEventType.KeyUp) return event
    val key =
        when (event.utf16CodePoint) {
            32 -> Key.Spacebar
            47 -> Key.Slash
            else -> return event
        }
    if (event.key.nativeKeyCode != 0) return event
    return KeyEvent(
        key,
        KeyEventType.KeyDown,
        event.utf16CodePoint,
        isCtrlPressed = event.isCtrlPressed,
        isMetaPressed = event.isMetaPressed,
        isAltPressed = event.isAltPressed,
        isShiftPressed = event.isShiftPressed,
    )
}
