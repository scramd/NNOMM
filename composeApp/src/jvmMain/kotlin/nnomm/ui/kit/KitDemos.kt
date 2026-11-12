package nnomm.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nnomm.ui.theme.LocalNnTheme

/** Real lazy-table exercise, deliberately larger than the registry. */
@Composable
fun KitTableDemo(modifier: Modifier = Modifier) {
    val keys = remember { (1..500).toList() }
    var on by remember { mutableStateOf(setOf(1, 2, 3, 5, 8)) }
    var opened by remember { mutableStateOf<Int?>(null) }
    val selection = rememberRowSelection<Int>()
    Column(modifier.fillMaxSize().background(LocalNnTheme.current.colors.bg)) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("Library", size = 20f, weight = 650)
            Label("500 rows, ${selection.picked.size} selected")
            opened?.let { Label("Opened mod $it") }
        }
        Table(
            keys,
            Modifier.weight(1f),
            selection = selection,
            onToggle = { on = if (it in on) on - it else on + it },
            onToggleSelection = { selected ->
                on = if (selected.all { it in on }) on - selected else on + selected
            },
            onOpen = { opened = it },
            onDelete = { selection.clear() },
            isOff = { it !in on },
            header = { column ->
                when (column) {
                    "select" ->
                        CheckBox(
                            selection.picked.size == keys.size,
                            { if (it) selection.selectAll(keys) else selection.clear() },
                        )
                    "name" -> SortHeading("Name", true, onClick = {})
                    "version" -> SortHeading("Version", false, onClick = {})
                    "source" -> SortHeading("Source", false, onClick = {})
                    "size" -> SortHeading("Size", false, onClick = {})
                    "date" -> SortHeading("Released", false, onClick = {})
                }
            },
        ) { key, column ->
            when (column) {
                "select" ->
                    CheckBox(key in selection.picked, { selection.select(keys, key, ctrl = true) })
                "on" -> Switch(key in on, { on = if (it) on + key else on - key })
                "icon" -> ModTile("mod$key", "Example mod", off = key !in on)
                "name" -> NameCell("Example mod $key")
                "version" -> TableValue("1.2.$key")
                "source" -> TableValue("NOMNOM")
                "size" -> TableValue("2.4 MB")
                "date" -> TableValue("2 days ago")
                "menu" -> IconButton(NnIcons.more, "More actions", {}, small = true)
            }
        }
    }
}

@Composable
fun KitCardsDemo(modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf(0) }
    val mods = remember { (1..12).toList() }
    Column(
        modifier.fillMaxSize().background(LocalNnTheme.current.colors.bg).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label("Updated this week", size = 16f, weight = 600)
        CardStrip(mods, { it }) { id ->
            ModCard(
                "mod$id",
                "Example mod $id",
                "by KopterBuzz",
                "A small improvement for your next flight.",
                { current = id },
                current = current == id,
                strip = true,
                footer = {
                    Label(
                        "12.4k",
                        Modifier.weight(1f),
                        size = 12f,
                        color = LocalNnTheme.current.colors.text3,
                    )
                    Button("Install", {}, size = ControlSize.Small)
                },
            )
        }
        PanelTabs(listOf("About", "What's new", "Needs"), 0, {})
        CardGrid(mods, { it }, Modifier.weight(1f)) { id ->
            ModCard(
                "mod$id",
                "Example mod $id",
                "by KopterBuzz",
                "A longer description that demonstrates the two-line clamp used by the registry cards in the Browse grid.",
                { current = id },
                current = current == id,
                tags = listOf("Quality of life", "Tools"),
                footer = {
                    Label(
                        "12.4k",
                        Modifier.weight(1f),
                        size = 12f,
                        color = LocalNnTheme.current.colors.text3,
                    )
                    Button("Install", {}, size = ControlSize.Small)
                },
            )
        }
    }
}
