package nnomm.ui.settings

import androidx.compose.runtime.*

/** Snapshot view state, shared by mock addresses and the page. */
class SettingsView {
    var chooseGameFolder by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var confirmKey by mutableStateOf<String?>(null)
    var category by mutableStateOf("game")
    var query by mutableStateOf("")
    var scope by mutableStateOf("all")
    var profileId by mutableStateOf("default")
}
