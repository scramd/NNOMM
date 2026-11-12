package nnomm.ui.shots

import java.net.URLDecoder
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.demo.Demo
import nnomm.core.model.*
import nnomm.core.store.AppStore
import nnomm.ui.*

/** All mock-address state is applied before the first composition. */
fun applyMockAddress(model: AppModel, address: String) {
    val p =
        address
            .removePrefix("#")
            .split('&')
            .mapNotNull {
                val pair = it.split('=', limit = 2)
                if (pair.size == 2) pair[0] to URLDecoder.decode(pair[1], Charsets.UTF_8) else null
            }
            .toMap()
    val ui = model.ui
    ui.page = Page.entries.firstOrNull { it.name.equals(p["page"], true) } ?: Page.Browse
    ui.mock = p["mock"] ?: "normal"
    p["prof"]?.let(model.store::useProfile)
    p["theme"]?.let {
        model.store.setSetting("theme", JsonPrimitive(if (it == "light") "Light" else "Dark"))
    }
    ui.browse.query = p["q"].orEmpty()
    ui.library.query = p["q"].orEmpty()
    ui.browse.view = p["view"] ?: "grid"
    ui.browse.tags = p["tags"]?.split(',')?.toSet().orEmpty()
    ui.library.filter = p["lf"] ?: "all"
    ui.library.selection = p["sel"]?.split(',')?.toSet().orEmpty()
    ui.settings.category = p["cat"] ?: "game"
    ui.settings.scope = p["scope"] ?: "all"
    ui.settings.profileId = p["pid"] ?: "default"
    ui.settings.query = p["sq"].orEmpty()
    ui.palette.query = p["pq"].orEmpty()
    ui.importMode = p["mode"] ?: "new"
    p["panel"]?.removePrefix("mod:")?.let { id ->
        if (p["panel"]?.startsWith("mod:") == true)
            ui.openMod(
                id,
                when (p["tab"]) {
                    "new" -> PanelTab.WhatsNew
                    "needs" -> PanelTab.Needs
                    else -> PanelTab.About
                },
            )
    }
    p["overlay"]?.let { if (it == "drop") ui.dragging = true else model.openOverlay(it) }
    if (p["trouble"] == "1") model.store.startBrokenModTest()
}

/** Seed immutable domain states rather than starting asynchronous downloads during a render. */
fun mockStore(address: String): AppStore {
    val p =
        address
            .removePrefix("#")
            .split('&')
            .map { it.split('=', limit = 2) }
            .filter { it.size == 2 }
            .associate { it[0] to it[1] }
    var s = Demo.state()
    s =
        when (p["mock"]) {
            "empty" -> s.copy(installed = emptyMap())
            "offline" -> s.copy(connection = s.connection.copy(kind = ConnectionKind.OFFLINE))
            "loading" -> s.copy(connection = s.connection.copy(kind = ConnectionKind.LOADING))
            "first" ->
                s.copy(firstStart = FirstStart(complete = false, existingOn = 17, existingOff = 8))
            else -> s
        }
    if (p["running"] == "1") s = s.copy(gameRunning = true)
    p["dl"]?.let { id ->
        s =
            s.copy(
                downloads =
                    listOf(
                        Download(id, "update", s.profileId, s.mod(id).newestRelease!!.version, 42)
                    )
            )
    }
    return AppStore(s, Demo.io())
}

object ShotPlatform : Platform {
    override suspend fun chooseFiles(title: String, extensions: List<String>, multiple: Boolean) =
        emptyList<java.io.File>()

    override suspend fun chooseSaveFile(
        title: String,
        suggestedName: String,
        extension: String,
    ): java.io.File? = null

    override fun openFolder(folder: java.io.File) {}

    override fun openUrl(url: String) {}

    override fun copyText(text: String) {}
}
