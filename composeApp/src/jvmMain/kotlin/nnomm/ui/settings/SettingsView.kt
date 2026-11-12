package nnomm.ui.settings

import androidx.compose.runtime.*

/** Snapshot view state, shared by mock addresses and the page. */
class SettingsView {
    var category by mutableStateOf("game")
    var query by mutableStateOf("")
    var scope by mutableStateOf("all")
    var profileId by mutableStateOf("default")
}
