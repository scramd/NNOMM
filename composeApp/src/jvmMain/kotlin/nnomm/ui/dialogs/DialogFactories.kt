package nnomm.ui.dialogs

import nnomm.ui.AppModel
import nnomm.ui.palette.CommandPalette

/** Shared registration for the native window and deterministic mock-address renders. */
fun AppModel.registerDialogFactories() {
    overlayFactories["palette"] = ::CommandPalette
    overlayFactories["import"] = ::ImportDialog
    overlayFactories["packupdate"] = ::PackUpdateDialog
    overlayFactories["newprofile"] = ::NewProfileDialog
    overlayFactories["rename"] = ::RenameDialog
    overlayFactories["share"] = ::ShareDialog
    overlayFactories["trouble"] = ::TroubleDialog
    overlayFactories["first"] = ::FirstStart
}
