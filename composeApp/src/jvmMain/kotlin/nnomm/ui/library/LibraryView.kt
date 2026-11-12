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
}
