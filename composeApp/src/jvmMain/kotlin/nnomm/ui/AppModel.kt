package nnomm.ui

import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File
import nnomm.core.model.UiAction
import nnomm.core.store.AppStore

/**
 * Work that needs the operating system or the window: file dialogs, opening folders and links,
 * the clipboard. The window layer implements it; the render harness uses a no-op version.
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
 * with `LocalApp.current`, collect `store.state`, and call store actions. Nothing slow runs on
 * the UI thread: store actions return at once.
 */
class AppModel(val store: AppStore, val platform: Platform) {
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
                    ui.openMod(it, if (action.value == "new") PanelTab.WhatsNew else PanelTab.About)
                }
            else -> Unit
        }
    }
}

val LocalApp = staticCompositionLocalOf<AppModel> { error("No AppModel provided") }
