package nnomm.ui.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
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
        block(requireNotNull(state.packSource as? RealPacks) { "Pack files are unavailable in demo mode." })
    }

class ImportDialog(private val model: AppModel) : Overlay {
    @Composable
    override fun Content() {
        val state by model.store.state.collectAsState()
        var pack by remember { mutableStateOf(Demo.PACK_IMPORT) }
        var localNames by remember { mutableStateOf(emptyMap<String, String>()) }
        var localSizes by remember { mutableStateOf(emptyMap<String, Long>()) }
        var preview by remember { mutableStateOf<PackPreview?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(model.ui.importFile != null) }
        var importing by remember { mutableStateOf(false) }
        var includeSettings by remember { mutableStateOf(false) }
        var selectedLocalIds by remember { mutableStateOf(emptySet<String>()) }
        var keep by remember { mutableStateOf(emptyMap<String, String>()) }
        val scope = rememberCoroutineScope()
        val file = model.ui.importFile
        LaunchedEffect(file) {
            if (file != null) {
                try {
                    preview = withPackIo(state) { it.read(file.toPath()) }
                    withContext(Dispatchers.IO) {
                        val files = preview!!.localFiles.mapValues { java.nio.file.Path.of(it.value) }
                        localNames = files.mapValues { bundledModName(it.value) }
                        localSizes = files.mapValues { bundledSizeKb(it.value) }
                    }
                    val loaded = preview!!.pack
                    // TODO(core): classify serialized pack references in importRows itself.
                    pack =
                        loaded.copy(
                            items =
                                loaded.items.map { item ->
                                    if (item.kind != PackKind.VER) item
                                    else
                                        item.copy(
                                            kind =
                                                when {
                                                    item.id !in state.installed -> PackKind.ADD
                                                    verOf(state, item.id) == item.version -> PackKind.SAME
                                                    else -> PackKind.VER
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
        val mode = if (model.ui.importMode == "add") ImportMode.ADD else ImportMode.NEW
        val rows =
            importRows(state, pack, mode)
                .filterNot { it.local }
                .sortedBy { listOf(PackKind.CONFLICT, PackKind.ADD, PackKind.UP, PackKind.OTHER, PackKind.SAME, PackKind.UNAVAILABLE).indexOf(it.kind) }
        // Before the registry has loaded, every mod would look missing from it. Offline, the store refuses the import.
        val registryReady = state.connection.kind == ConnectionKind.READY
        val skipped = if (registryReady) rows.filter { it.kind == PackKind.UNAVAILABLE } else emptyList()
        val missing = skipped.filter { row -> state.catalog.none { it.id == row.id } }
        val goneKept = if (registryReady) rows.filter { it.kind == PackKind.SAME && it.goneVersion != null } else emptyList()
        val bundled = pack.items.filter { it.kind == PackKind.LOCAL }
        var profileName by remember(pack.name) { mutableStateOf(pack.name) }
        fun n(kind: PackKind) = rows.count { it.kind == kind }
        val downloads =
            rows.count {
                it.kind in listOf(PackKind.ADD, PackKind.UP, PackKind.OTHER) ||
                    it.kind == PackKind.CONFLICT && keep[it.id] == "pack"
            }
        Dialog(
            true,
            if (file != null && (loading || error != null)) "Import ${file.name}"
            else "Import ${pack.name} ${pack.version}",
            model::closeOverlay,
            wide = true,
            subtitle = if (!loading && error == null) "Modpack" + pack.by.takeIf { it.isNotBlank() }?.let { " by $it" }.orEmpty() + ", from ${file?.name ?: pack.fileName.orEmpty()}" else null,
            footer = {
                Cancel(model)
                Button(
                    if (downloads == 0) "Import" else "Import, $downloads ${if (downloads == 1) "download" else "downloads"}",
                    {
                        scope.launch {
                            importing = true
                            try {
                                if (selectedLocalIds.isNotEmpty() && preview != null) {
                                    val records =
                                        withPackIo(state) {
                                            it.selectedLocals(preview!!, selectedLocalIds)
                                        }
                                    model.store.addPreparedLocalMods(records)
                                }
                                val target = java.util.UUID.randomUUID().toString()
                                val settings = if (includeSettings && mode == ImportMode.NEW) preview?.settings ?: SettingsValues() else null
                                if (settings != null) withPackIo(state) { it.importSettings(preview!!, target) }
                                model.store.importPack(
                                    if (mode == ImportMode.NEW) pack.copy(name = profileName.trim().ifEmpty { pack.name }) else pack,
                                    mode,
                                    keep,
                                    target,
                                    settings,
                                    // Pack previews name bundled mods by content hash, the same id selectedLocals gives them.
                                    includedLocals = selectedLocalIds.toList(),
                                )
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
                    enabled = !loading && !importing && error == null && registryReady,
                )
            },
        ) {
            if (loading) Label("Reading the modpack…")
            error?.let { Note(it, warn = true) }
            if (!loading && error == null && state.connection.kind == ConnectionKind.LOADING) Label("Reading the registry…")
            if (!loading && error == null && state.connection.kind == ConnectionKind.OFFLINE) {
                Note(
                    "No connection to the registry. Importing a modpack needs a connection.",
                    warn = true,
                    action = { Button("Try again", model.store::refreshRegistry, size = ControlSize.Small, icon = NnIcons.refresh) },
                )
            }
            if (!loading && error == null && (skipped.isNotEmpty() || goneKept.isNotEmpty())) {
                Note(notInstallableText(state, skipped, missing, goneKept), warn = true)
            }
            if (!loading && error == null) {
                Summary(
                    buildList {
                        add(pack.items.size.toString() to "mods in the pack")
                        add(n(PackKind.SAME).toString() to "you have")
                        add(
                            n(PackKind.ADD).toString() to
                                "new, " + if (rows.filter { it.kind == PackKind.ADD }.any { state.mod(it.id).newestRelease?.sizeKb == null }) "size unknown" else size(rows.filter { it.kind == PackKind.ADD }.sumOf { state.mod(it.id).newestRelease?.sizeKb ?: 0 })
                        )
                        if (mode == ImportMode.NEW) add(n(PackKind.OTHER).toString() to "in another version")
                        else {
                            add(n(PackKind.UP).toString() to "get an update")
                            add(
                                n(PackKind.CONFLICT).toString() to
                                    "version ${if (n(PackKind.CONFLICT) == 1) "conflict" else "conflicts"}"
                            )
                        }
                    }
                )
                DiffBox {
                    rows.forEach { row ->
                        DiffLine(
                            row.kind.key(),
                            state.catalog.find { it.id == row.id }?.name ?: row.id,
                            if (row.kind == PackKind.CONFLICT)
                                "${state.profile().name} has a newer version than the pack"
                            else "",
                        ) {
                            if (row.kind == PackKind.CONFLICT)
                                SegmentedControl(
                                    listOf("Keep ${row.have}", "Use ${row.packVersion} from the pack"),
                                    if (keep[row.id] == "pack") 1 else 0,
                                    { keep = keep + (row.id to if (it == 0) "mine" else "pack") },
                                    small = true,
                                )
                            else
                                Label(
                                    row.text,
                                    color = LocalNnTheme.current.colors.text3,
                                    width = 88f,
                                )
                        }
                    }
                }
                if (bundled.isNotEmpty()) {
                    val one = bundled.size == 1
                    val who = pack.by.takeIf { it.isNotBlank() } ?: "the person who sent you this pack"
                    Note(
                        (if (one) "This pack also contains a mod as a file. It does not come from the registry, so NNOMM cannot check it."
                        else "This pack also contains ${bundled.size} mods as files. They do not come from the registry, so NNOMM cannot check them.") +
                            " A mod can run any code on your computer. Turn a file on only if you trust $who." +
                            (if (mode == ImportMode.NEW) " Files you turn on are on in the new profile." else " Files you turn on are on in ${state.profile().name}."),
                        warn = true,
                    )
                    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        bundled.forEach { item ->
                            val name = localNames[item.id] ?: item.id
                            val kb = localSizes[item.id] ?: item.sizeKb
                            ToggleLine(
                                item.id in selectedLocalIds,
                                { include -> selectedLocalIds = if (include) selectedLocalIds + item.id else selectedLocalIds - item.id },
                                name + (kb?.let { ", ${size(it)}" } ?: ""),
                                "Include $name",
                            )
                        }
                    }
                }
                if (mode == ImportMode.NEW && (preview?.settings != null || preview?.modSettingsFolder != null)) {
                    ToggleLine(includeSettings, { includeSettings = it }, "Include the pack's mod settings", "These settings change how mods behave.")
                }
                OptionCard(
                    "Make a new profile",
                    "It gets the pack's mods in the pack's versions. ${state.profile().name} stays as it is.",
                    mode == ImportMode.NEW,
                    { model.ui.importMode = "new" },
                )
                if (mode == ImportMode.NEW) {
                    Row(Modifier.padding(start = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label("Profile name")
                        DialogTextField(profileName, { profileName = it }, Modifier.weight(1f), placeholder = pack.name)
                    }
                }
                OptionCard(
                    "Add the mods to ${state.profile().name}",
                    "The pack's mods are turned on in ${state.profile().name}. Your other mods stay as they are.",
                    mode == ImportMode.ADD,
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
        var fetched by remember { mutableStateOf<Pack?>(null) }
        var message by remember { mutableStateOf("Reading the pack update…") }
        LaunchedEffect(p.id) {
            if (!model.isDemo) {
                try {
                    fetched = p.pack?.let { info -> model.store.io.packSource?.following(info) }
                    if (fetched == null) message = "No pack update is available."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = "Could not read the pack update. Try again later." }
            }
        }
        val pack = (if (model.isDemo) Demo.PACK_UPDATE else fetched)
        if (pack == null) {
            Dialog(true, "Pack update", model::closeOverlay,
                leftFooter = { Button("Stop following this pack", {
                    model.store.stopFollowingPack(p.id)
                    model.closeOverlay()
                }, kind = ButtonKind.Ghost) },
                footer = { Cancel(model, "Close") }) { Label(message) }
            return
        }
        val rows = packUpdateRows(state, pack)
        Dialog(
            true,
            "${p.name} ${p.pack?.version.orEmpty()} to ${p.pack?.nextVersion ?: pack.version}",
            model::closeOverlay,
            wide = true,
            subtitle = "Modpack" + (p.pack?.by ?: pack.by).takeIf { it.isNotBlank() }?.let { " by $it" }.orEmpty(),
            leftFooter = { Button("Stop following this pack", {
                model.store.stopFollowingPack(p.id)
                model.closeOverlay()
            }, kind = ButtonKind.Ghost) },
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
            Summary(
                listOf(
                        PackKind.ADD to "added",
                        PackKind.REM to "removed",
                        PackKind.CHG to "updated",
                        PackKind.SAME to "not changed",
                    )
                    .map { (k, title) -> rows.count { it.kind == k }.toString() to title }
            )
            DiffBox {
                rows.forEach { row ->
                    DiffLine(row.kind.key(), state.mod(row.id).name) {
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
                    .heightIn(min = 60.dp)
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
            .drawBehind { drawLine(c.line, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height)) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (kind == "conflict" || kind in listOf("up", "chg")) Icon(
            if (kind == "conflict") NnIcons.alert else NnIcons.up,
            Modifier.size(18.dp), if (kind == "conflict") c.warn else c.accentText,
        ) else Label(
            when (kind) {
                "add" -> "+"
                "rem" -> "−"
                "up",
                "chg" -> "↑"
                "conflict" -> "⚠"
                "on" -> "on"
                "off" -> "off"
                "missing" -> "×"
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
                if (kind == "rem") androidx.compose.ui.text.AnnotatedString(name, spanStyle = androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) else androidx.compose.ui.text.AnnotatedString(name),
                weight = 600,
                color = if (kind in listOf("rem", "off")) c.text3 else c.text,
                maxLines = 1,
            )
            if (detail.isNotEmpty()) Label(" $detail", color = c.text3, maxLines = 1)
        }
        end()
    }
}

internal fun PackKind.key(): String =
    when (this) {
        PackKind.ADD -> "add"
        PackKind.REM -> "rem"
        PackKind.UP -> "up"
        PackKind.CHG -> "chg"
        PackKind.CONFLICT -> "conflict"
        PackKind.UNAVAILABLE -> "missing"
        else -> ""
    }

/** One warning for every pack mod that cannot be installed the way the pack asks. */
internal fun notInstallableText(s: AppState, skipped: List<ImportRow>, missing: List<ImportRow>, kept: List<ImportRow>): String {
    val lines = buildList {
        missing.forEach { add("${it.id}: not in the registry. Left out.") }
        skipped.filter { it !in missing && it.goneVersion == null }
            .forEach { add("${s.mod(it.id).name}: the pack does not say which version. Left out.") }
        skipped.filter { it.goneVersion != null }
            .forEach { add("${s.mod(it.id).name}: the registry no longer has version ${it.goneVersion}. Left out.") }
        kept.forEach { add("${s.mod(it.id).name}: the registry no longer has version ${it.goneVersion}. Uses your version ${it.have}.") }
    }
    val head =
        if (lines.size == 1) "1 mod in this pack cannot be installed the way the pack asks:"
        else "${lines.size} mods in this pack cannot be installed the way the pack asks:"
    val registry = missing.isNotEmpty() || kept.isNotEmpty() || skipped.any { it.goneVersion != null }
    val foot =
        (if (registry) "The registry is the online list of mods that NNOMM downloads from. " else "") +
            "The rest of the pack installs normally."
    return head + "\n" + lines.joinToString("\n") { "\u2022  $it" } + "\n" + foot
}
