@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package nnomm.ui.shots

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import java.io.File
import nnomm.ui.kit.*
import nnomm.ui.shell.*
import nnomm.ui.theme.*
import org.jetbrains.skia.EncodedImageFormat

/** Size is the app frame only, excluding the mock's page margins and test buttons. */
data class ShotSpec(
    val name: String,
    val dark: Boolean,
    val width: Int = 1328,
    val height: Int = 792,
    val content: @Composable () -> Unit,
)

object Shots {
    val registry =
        mutableListOf<ShotSpec>().apply {
            for (dark in listOf(true, false)) {
                val theme = if (dark) "dark" else "light"
                add(ShotSpec("kit-$theme", dark) { WindowShell { KitGallery() } })
                add(ShotSpec("window-$theme", dark) { WindowShell() })
                add(ShotSpec("window-narrow-$theme", dark, 728, 720) { WindowShell() })
            }
        }
}

fun main(args: Array<String>) {
    val output = File(args.firstOrNull() ?: "composeApp/build/shots").apply { mkdirs() }
    Shots.registry.forEach { spec ->
        ImageComposeScene(
                width = spec.width,
                height = spec.height,
                density = Density(1f),
                content = { NnommTheme(dark = spec.dark, reduceMotion = true) { spec.content() } },
            )
            .use { scene ->
                scene.render(1_000_000_000L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)?.use {
                        File(output, "${spec.name}.png").writeBytes(it.bytes)
                    }
                }
            }
        println("Rendered ${spec.name}: ${spec.width} x ${spec.height}")
    }
}

@Composable
private fun GalleryRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun KitGallery() {
    val c = LocalNnTheme.current.colors
    var checked by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val entries =
        listOf(
            MenuEntry.Heading("Profile"),
            MenuEntry.Item("Share as modpack", "Ctrl S", NnIcons.share, action = {}),
            MenuEntry.Item("Duplicate", icon = NnIcons.copy, action = {}),
            MenuEntry.Separator,
            MenuEntry.Item("Delete profile", danger = true, action = {}),
        )
    Box(Modifier.fillMaxSize().background(c.bg)) {
        ScrollArea(Modifier.fillMaxSize().padding(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Component kit", size = 20f, weight = 650, width = 112f)
                GalleryRow {
                    ButtonKind.entries.forEach {
                        Button(it.name, {}, kind = it, icon = NnIcons.plus)
                    }
                }
                GalleryRow {
                    ControlSize.entries.forEach { Button(it.name, {}, size = it) }
                    Button("Disabled", {}, enabled = false)
                    PreviewState.entries.forEach { Button(it.name, {}, preview = it) }
                }
                GalleryRow {
                    Switch(checked, { checked = it })
                    Switch(false, {}, enabled = false)
                    CheckBox(checked, { checked = it })
                    CheckBox(false, {})
                    Radio(checked, { checked = !checked })
                    Radio(false, {})
                    Chip("All", 25, selected == 0, { selected = 0 })
                    Chip("Updates", 3, selected == 1, { selected = 1 })
                    SegmentedControl(listOf("Any tag", "All tags"), selected, { selected = it })
                    IconButton(NnIcons.gear, "Settings", {})
                    IconButton(NnIcons.more, "More", {}, small = true)
                    LinkButton("Clear filters", {})
                }
                GalleryRow {
                    TextField(
                        text,
                        { text = it },
                        Modifier.width(260.dp),
                        "Search mods",
                        NnIcons.search,
                    )
                    Select(
                        listOf("Most downloaded", "Recently updated", "Name"),
                        selected,
                        { selected = it },
                    )
                    Box {
                        Button("Open menu", { menu = true })
                        Menu(menu, { menu = false }, entries)
                    }
                    Button("Open dialog", { dialog = true })
                    KeyCap("Ctrl K")
                }
                GalleryRow {
                    Badge("Installed")
                    Badge("Needs a mod", BadgeKind.Warn, NnIcons.alert)
                    Badge("File missing", BadgeKind.Danger)
                    Badge("On", BadgeKind.Ok, NnIcons.check)
                    Tag("Quality of life")
                    DownloadPill(.64f, "64%")
                    ProgressBar(.4f, Modifier.width(160.dp))
                    ProgressBar(.6f, Modifier.width(100.dp), neutral = true)
                }
                Note("The saved list is available while you are offline.", Modifier.fillMaxWidth())
                Note(
                    "This mod was made for another game version.",
                    Modifier.fillMaxWidth(),
                    warn = true,
                )
                GalleryRow {
                    TileSize.entries.forEach { ModTile("autopilot", "Auto Pilot", size = it) }
                    NnIcons.pictures.keys.forEach {
                        ModTile(it, it, picture = it, size = TileSize.Medium)
                    }
                }
                GalleryRow { NnIcons.all.values.forEach { Icon(it) } }
                GalleryRow {
                    Column(Modifier.width(210.dp)) {
                        FilterOption("Installed", 25, true, {})
                        FilterOption("Not installed", 252, false, {})
                        Skeleton(Modifier.fillMaxWidth().height(70.dp))
                    }
                    MenuSurface(entries, requestFocus = false)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val measure = rememberTextMeasurer()
                        val t = LocalNnTheme.current
                        listOf(88f, 100f, 112f).forEach { width ->
                            val sample = "Archivo width 0123456789"
                            val result = measure.measure(sample, t.text(width = width))
                            Label("$sample ($width%: ${result.size.width}px)", width = width)
                        }
                        Toast("Mod uninstalled", "Undo", {})
                        Label(
                            "NNOMM is based on NOMM by Combat787. Registry: NOMNOM by KopterBuzz.",
                            size = 11.5f,
                            color = c.text3,
                        )
                    }
                }
                Box(Modifier.width(560.dp)) {
                    DialogSurface(
                        "New profile",
                        footer = {
                            Button("Cancel", {}, kind = ButtonKind.Ghost)
                            Button("Create profile", {}, kind = ButtonKind.Strong)
                        },
                    ) {
                        Label("Keep a separate set of mods for each way you play.", color = c.text2)
                        TextField(text, { text = it }, Modifier.fillMaxWidth(), "Profile name")
                    }
                }
            }
        }
        Dialog(
            dialog,
            "New profile",
            { dialog = false },
            footer = {
                Button("Cancel", { dialog = false }, kind = ButtonKind.Ghost)
                Button("Create profile", { dialog = false }, kind = ButtonKind.Strong)
            },
        ) {
            TextField(text, { text = it }, Modifier.fillMaxWidth(), "Profile name")
        }
    }
}
