package nnomm.ui.library

import androidx.compose.runtime.*

/** Snapshot view state, shared by mock addresses and the page. */
class LibraryView {
    var query by mutableStateOf("")
    var filter by mutableStateOf("all")
    var sort by mutableStateOf("name")
    var descending by mutableStateOf(false)
    var selection by mutableStateOf(emptySet<String>())
    var focusedId by mutableStateOf(null as String?)
    var anchorId by mutableStateOf(null as String?)
    /** Mods added to the Library a moment ago, which stand out in the list. */
    var fresh by mutableStateOf(emptySet<String>())
    /**
     * Mods added a moment ago that do not stand out: they came into an empty Library, or into a new
     * profile made by a modpack import, where every mod is new.
     */
    var quiet = emptySet<String>()
    /** The new profile that a modpack import makes, and the pack's files it turns on there. */
    var quietProfile: String? = null
    var quietIds = emptySet<String>()

    fun isFresh(id: String) = id in fresh

    /** The player clicked or used the mod, so it no longer needs to stand out. */
    fun seen(id: String) {
        if (id in fresh) fresh = fresh - id
    }
}
