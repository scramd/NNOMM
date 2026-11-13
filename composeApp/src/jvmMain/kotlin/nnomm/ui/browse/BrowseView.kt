package nnomm.ui.browse

import androidx.compose.runtime.*

/** Snapshot view state, shared by mock addresses and the page. */
class BrowseView {
    var query by mutableStateOf("")
    var view by mutableStateOf("grid")
    var tags by mutableStateOf(emptySet<String>())
    var sort by mutableStateOf("downloads")
    var have by mutableStateOf("")
    var kind by mutableStateOf("")
    var game by mutableStateOf("")
    var tagMode by mutableStateOf("any")
    var allTags by mutableStateOf(false)
    var facetsOpen by mutableStateOf(false)
    /** Tags whose mods are hidden, in the order the player hid them. */
    var hideTags by mutableStateOf(emptySet<String>())
    /** The tag list under "Hide mods with" is open. */
    var hidePick by mutableStateOf(false)
    /** A filter row that takes keyboard focus after the row the player used goes away. */
    var focusKey by mutableStateOf<String?>(null)
    /**
     * True after a hide with the pointer, until the pointer moves. The next tag moves under the
     * pointer, and must not show its Hide button or take the second click of a double click.
     */
    var hideGuard by mutableStateOf(false)
    var hideAtMillis = 0L

    /** Show only mods with [tag]; a hidden tag stops being hidden. */
    fun showTag(tag: String) {
        hideTags -= tag
        tags += tag
    }

    fun hideTag(tag: String) {
        tags -= tag
        hideTags += tag
        hidePick = false
    }
}
