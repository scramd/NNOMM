package nnomm.ui.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import nnomm.core.demo.Demo
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.ui.*
import nnomm.ui.kit.*
import nnomm.ui.theme.*

internal suspend fun <T> withPackIo(state: AppState, block: suspend (RealPacks) -> T): T =
    withContext(Dispatchers.IO) {
        val home = nnomm.app.settingsDirectory().toPath()
        HttpClient().use { client ->
            block(RealPacks(home, RealModStore(home) { state.catalog }, client))
        }
    }

class ImportDialog(private val model: AppModel) : Overlay {
    @Composable
    override fun Content() {
        val state by model.store.state.collectAsState()
        var pack by remember { mutableStateOf(Demo.PACK_IMPORT) }
        var preview by remember { mutableStateOf<PackPreview?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(model.ui.importFile != null) }
        var importing by remember { mutableStateOf(false) }
        var local by remember { mutableStateOf(false) }
        var keep by remember { mutableStateOf(emptyMap<String, String>()) }
        val scope = rememberCoroutineScope()
        val file = model.ui.importFile
        LaunchedEffect(file) {
            if (file != null) {
                try {
                    preview = withPackIo(state) { it.read(file.toPath()) }
                    val loaded = preview!!.pack
                    val unknown =
                        loaded.items.filter {
                            it.kind != "local" && state.catalog.none { mod -> mod.id == it.id }
                        }
                    require(unknown.isEmpty()) {
                        "Mods are missing from the registry: ${unknown.joinToString { it.id }}"
                    }
                    // TODO(core): classify serialized pack references in importRows itself.
                    pack =
                        loaded.copy(
                            items =
                                loaded.items.map { item ->
                                    if (item.kind != "ver") item
                                    else
                                        item.copy(
                                            kind =
                                                when {
                                                    item.id !in state.installed -> "add"
                                                    verOf(state, item.id) == item.version -> "same"
                                                    else -> "ver"
                                                }
                                        )
                                }
                        )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = "Could not read the modpack: ${e.message}"
                } finally {
                    loading = false
                }
            }
        }
        DisposableEffect(Unit) {
            onDispose { preview?.let { p -> CoroutineScope(Dispatchers.IO).launch { p.close() } } }
        }
        val mode = model.ui.importMode
        val rows =
            importRows(state, pack, mode)
                .filterNot { it.local }
                .sortedBy { listOf("conflict", "add", "up", "other", "same").indexOf(it.kind) }
        fun n(kind: String) = rows.count { it.kind == kind }
        val downloads =
            rows.count {
                it.kind in listOf("add", "up", "other") ||
                    it.kind == "conflict" && keep[it.id] == "pack"
            }
        Dialog(
            true,
            if (file != null && (loading || error != null)) "Import ${file.name}"
            else "Import ${pack.name} ${pack.version}",
            model::closeOverlay,
            wide = true,
            footer = {
                Cancel(model)
                Button(
                    "Import, $downloads ${if (downloads == 1) "download" else "downloads"}",
                    {
                        scope.launch {
                            importing = true
                            try {
                                if (local && preview != null) {
                                    val records =
                                        withPackIo(state) {
                                            it.selectedLocals(preview!!, preview!!.localFiles.keys)
                                        }
                                    model.store.addPreparedLocalMods(records)
                                }
                                // TODO(core): importPack needs ports for the preview's bundled
                                // settings/config.
                                model.store.importPack(pack, mode, keep)
                                model.ui.go(Page.Library)
                                model.ui.library.filter = "all"
                                model.closeOverlay()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Could not import the modpack: ${e.message}"
                            } finally {
                                importing = false
                            }
                        }
                    },
                    kind = ButtonKind.Strong,
                    enabled = !loading && !importing && error == null,
                )
            },
        ) {
            if (!loading && error == null)
                Label("Modpack by ${pack.by}, from ${file?.name ?: pack.fileName.orEmpty()}")
            if (loading) Label("Reading the modpack…")
            error?.let { Note(it, warn = true) }
            if (!loading && error == null) {
                Summary(
                    buildList {
                        add(rows.size.toString() to "mods in the pack")
                        add(n("same").toString() to "you have")
                        add(
                            n("add").toString() to
                                "new, ${size(rows.filter { it.kind == "add" }.sumOf { state.mod(it.id).newestRelease?.sizeKb ?: 0 })}"
                        )
                        if (mode == "new") add(n("other").toString() to "in another version")
                        else {
                            add(n("up").toString() to "get an update")
                            add(
                                n("conflict").toString() to
                                    "version ${if (n("conflict") == 1) "conflict" else "conflicts"}"
                            )
                        }
                    }
                )
                DiffBox {
                    rows.forEach { row ->
                        DiffLine(
                            row.kind,
                            state.catalog.find { it.id == row.id }?.name ?: row.id,
                            if (row.kind == "conflict")
                                "${state.profile().name} has a newer version than the pack"
                            else "",
                        ) {
                            if (row.kind == "conflict")
                                Row {
                                    Button(
                                        "Keep ${row.have}",
                                        { keep = keep + (row.id to "mine") },
                                        size = ControlSize.Small,
                                        kind =
                                            if (keep[row.id] != "pack") ButtonKind.Default
                                            else ButtonKind.Ghost,
                                    )
                                    Button(
                                        "Use ${row.packVersion} from the pack",
                                        { keep = keep + (row.id to "pack") },
                                        size = ControlSize.Small,
                                        kind =
                                            if (keep[row.id] == "pack") ButtonKind.Default
                                            else ButtonKind.Ghost,
                                    )
                                }
                            else
                                Label(
                                    row.text,
                                    color = LocalNnTheme.current.colors.text3,
                                    width = 88f,
                                )
                        }
                    }
                }
                pack.items
                    .filter { it.kind == "local" }
                    .forEach { item ->
                        Note(
                            "This pack also contains a file that is not from the registry: ${item.id} (${size(item.sizeKb ?: 0)}). A mod file can run any code on your computer. Include it only if you trust ${pack.by}.",
                            warn = true,
                            action = {
                                ToggleLine(local, { local = it }, "Include", "Include the file")
                            },
                        )
                    }
                OptionCard(
                    "Make a new profile \"${pack.name}\"",
                    "It gets the pack's mods in the pack's versions. ${state.profile().name} stays as it is.",
                    mode == "new",
                    { model.ui.importMode = "new" },
                )
                OptionCard(
                    "Add the mods to ${state.profile().name}",
                    "The pack's mods are turned on in ${state.profile().name}. Your other mods stay as they are.",
                    mode == "add",
                    { model.ui.importMode = "add" },
                )
            }
        }
    }
}

class PackUpdateDialog(private val model: AppModel) : Overlay {
    @Composable
    override fun Content() {
        val state by model.store.state.collectAsState()
        val p = state.profile()
        // TODO(core): expose the fetched pack preview rather than only nextVersion in PackInfo.
        val pack = Demo.PACK_UPDATE
        val rows = packUpdateRows(state, pack)
        Dialog(
            true,
            "${p.name} ${p.pack?.version.orEmpty()} to ${p.pack?.nextVersion ?: pack.version}",
            model::closeOverlay,
            wide = true,
            leftFooter = { Button("Stop following this pack", {}, kind = ButtonKind.Ghost) },
            footer = {
                Cancel(model, "Not now")
                Button(
                    "Update the profile",
                    {
                        model.store.updatePack(pack)
                        model.closeOverlay()
                    },
                    kind = ButtonKind.Strong,
                )
            },
        ) {
            Label("Modpack by ${p.pack?.by ?: pack.by}")
            Summary(
                listOf(
                        "add" to "added",
                        "rem" to "removed",
                        "chg" to "updated",
                        "same" to "not changed",
                    )
                    .map { (k, title) -> rows.count { it.kind == k }.toString() to title }
            )
            DiffBox {
                rows.forEach { row ->
                    DiffLine(row.kind, state.mod(row.id).name) {
                        Label(row.text, color = LocalNnTheme.current.colors.text3, width = 88f)
                    }
                }
            }
            Label("Only this profile changes. Mods that you added to it yourself stay on.")
        }
    }
}

@Composable
internal fun Summary(values: List<Pair<String, String>>) {
    val colors = LocalNnTheme.current.colors
    val c =
        colors.copy(text = if (colors.bg == DarkColors.bg) DarkColors.text else LightColors.text)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { (value, title) ->
            Column(
                Modifier.weight(1f)
                    .heightIn(min = 59.dp)
                    .border(1.dp, c.line, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Label(value, size = 20f, weight = 650, color = c.text)
                Label(title, color = c.text2)
            }
        }
    }
}

@Composable
internal fun DiffBox(maxHeight: Int = 232, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalNnTheme.current.colors
    val c =
        colors.copy(text = if (colors.bg == DarkColors.bg) DarkColors.text else LightColors.text)
    Column(
        Modifier.fillMaxWidth()
            .heightIn(max = maxHeight.dp)
            .border(1.dp, c.line, RoundedCornerShape(6.dp))
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}

@Composable
internal fun DiffLine(
    kind: String,
    name: String,
    detail: String = "",
    end: @Composable RowScope.() -> Unit,
) {
    val colors = LocalNnTheme.current.colors
    val c =
        colors.copy(text = if (colors.bg == DarkColors.bg) DarkColors.text else LightColors.text)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 28.dp)
            .border(width = .5.dp, color = c.line)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(
            when (kind) {
                "add" -> "+"
                "rem" -> "−"
                "up",
                "chg" -> "↑"
                "conflict" -> "⚠"
                "on" -> "on"
                "off" -> "off"
                else -> "="
            },
            Modifier.width(if (kind in listOf("on", "off")) 22.dp else 18.dp),
            color =
                when (kind) {
                    "add",
                    "on" -> c.on
                    "rem",
                    "conflict" -> c.danger
                    "up",
                    "chg" -> c.accentText
                    else -> c.text3
                },
            weight = 700,
        )
        Row(Modifier.weight(1f)) {
            Label(
                name,
                weight = 600,
                color = if (kind in listOf("rem", "off")) c.text3 else c.text,
                maxLines = 1,
            )
            if (detail.isNotEmpty()) Label(" $detail", color = c.text3, maxLines = 1)
        }
        end()
    }
}
