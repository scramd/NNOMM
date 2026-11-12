package nnomm.ui.dialogs

import nnomm.ui.shots.MockShot

/** Extra fixtures keep both themes and the 760 px mock viewport reviewable. */
val dialogMockShots = buildList {
    val states =
        listOf(
            "palette" to "page=library&overlay=palette&pq=map",
            "import" to "page=library&overlay=import",
            "import-add" to "page=library&overlay=import&mode=add",
            "pack-update" to "page=library&prof=friday&overlay=packupdate",
            "new-profile" to "page=library&overlay=newprofile",
            "share" to "page=library&overlay=share",
            "rename" to "page=library&overlay=rename",
            "trouble-intro" to "page=library&overlay=trouble",
            "trouble-base" to "page=library&trouble=1&overlay=trouble",
            "trouble-test" to "page=library&trouble=1&overlay=trouble",
            "trouble-found" to "page=library&trouble=1&overlay=trouble",
            "trouble-nomod" to "page=library&trouble=1&overlay=trouble",
            "trouble-inconclusive" to "page=library&trouble=1&overlay=trouble",
            "first" to "mock=first",
        )
    for ((name, address) in states) {
        for (light in listOf(false, true)) {
            for (narrow in listOf(false, true)) {
                add(
                    MockShot(
                        "dialog-$name-${if (light) "light" else "dark"}-${if (narrow) "narrow" else "wide"}",
                        address + if (light) "&theme=light" else "",
                        if (narrow) 728 else 1328,
                        if (narrow) 720 else 792,
                        initialState = { state ->
                            if (name == "trouble-inconclusive") {
                                val ids = state.installed.keys.filter { nnomm.core.rules.isOn(state, it) }
                                state.copy(catalog = state.catalog.map { mod ->
                                    if (mod.id !in ids) mod else mod.copy(versions = mod.versions.map { version ->
                                        version.copy(dependencies = ids.filter { it != mod.id }.map { nnomm.core.model.PackageReference(it) })
                                    })
                                })
                            } else state
                        },
                        seed = { model ->
                            when (name) {
                                "trouble-test", "trouble-inconclusive" -> model.store.answerBrokenModTest(false)
                                "trouble-found" -> {
                                    model.store.answerBrokenModTest(false)
                                    repeat(10) {
                                        if (model.store.state.value.trouble?.phase == nnomm.core.model.TroublePhase.TEST)
                                            model.store.answerBrokenModTest(true)
                                    }
                                }
                                "trouble-nomod" -> model.store.answerBrokenModTest(true)
                            }
                        },
                    )
                )
            }
        }
    }
}
