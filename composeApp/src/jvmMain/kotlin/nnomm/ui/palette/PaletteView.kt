package nnomm.ui.palette

import androidx.compose.runtime.*

/** Snapshot view state, shared by mock addresses and the page. */
class PaletteView {
    var query by mutableStateOf("")
    var index by mutableStateOf(0)
}
