package nnomm.ui.settings

import nnomm.core.settings.SETTINGS

// TODO(core): move this wording into the shared settings definitions after the parallel core pass.
internal val playerSettings by lazy {
    SETTINGS.map { definition ->
        when (definition.key) {
            "dzAll" -> definition.copy(
                label = "Remove all mods",
                actions = listOf("Remove all mods"),
                help = "Registry mods leave every profile. Local and taken-over mods stay in the Library, turned off, with their stored copies kept. Only loader files NNOMM installed are removed. An adopted loader, your own files, profiles and recovery copies stay.",
            )
            "logs" -> definition.copy(help = definition.help.replace("A text with", "A report with"))
            else -> definition
        }
    }
}
