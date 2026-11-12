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
    var facetsOpen by mutableStateOf(false)
}
