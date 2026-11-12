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
                    )
                )
            }
        }
    }
}
