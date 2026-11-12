package nnomm.ui

import nnomm.core.model.SettingsValues

internal fun startupPage(settings: SettingsValues): Page = when (settings.general["startPage"]?.content) {
    "Browse" -> Page.Browse
    "Library" -> Page.Library
    else -> Page.entries.find { it.name == settings.general["lastPage"]?.content } ?: Page.Browse
}

/** An observed transition to running triggers this once; launch requests do not. */
internal fun gameStartedAction(choice: String?, minimise: () -> Unit, close: () -> Unit) {
    when (choice) {
        "Minimise NNOMM" -> minimise()
        "Close NNOMM" -> close()
    }
}
