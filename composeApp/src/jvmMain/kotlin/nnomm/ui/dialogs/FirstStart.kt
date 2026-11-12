package nnomm.ui.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nnomm.ui.*
import nnomm.ui.kit.*
import nnomm.ui.theme.*

class FirstStart(private val model: AppModel) : Overlay {
    @Composable
    override fun Content() {
        val state by model.store.state.collectAsState()
        val c = LocalNnTheme.current.colors
        val scope = rememberCoroutineScope()
        fun finish(page: Page) {
            model.store.completeFirstStart()
            model.ui.mock = "normal"
            model.ui.go(page)
            model.closeOverlay()
        }
        Box(
            Modifier.fillMaxSize().padding(top = 38.dp).background(c.bg).padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Label("NNOMM found your game", size = 26f, weight = 650, width = 114f)
                Label(
                    "Check these three things. You can change all of them later in Settings.",
                    color = c.text2,
                )
                Column(Modifier.border(1.dp, c.line2, RoundedCornerShape(9.dp)).fillMaxWidth()) {
                    CheckRow(
                        NnIcons.folder,
                        "Nuclear Option ${state.game.version.orEmpty()}",
                        if (model.ui.mock == "first")
                            "D:\\SteamLibrary\\steamapps\\common\\Nuclear Option"
                        else state.game.folder.orEmpty(),
                    ) {
                        Button(
                            "Choose another folder",
                            {
                                scope.launch {
                                    model.platform
                                        .chooseFolder("Choose the Nuclear Option folder")
                                        ?.let { // TODO(core): persist this folder without applying
                                            // a profile during first start.
                                            model.store.takeOverExistingMods(it.absolutePath)
                                        }
                                }
                            },
                            size = ControlSize.Small,
                        )
                    }
                    CheckRow(
                        NnIcons.library,
                        "${state.firstStart.existingOn + state.firstStart.existingOff} mods are already in the game folder",
                        "They go into your Library as they are: ${state.firstStart.existingOn} on, ${state.firstStart.existingOff} off.",
                    )
                    CheckRow(
                        NnIcons.check,
                        if (state.game.loaderPresent)
                            "The mod loader BepInEx ${state.game.loaderVersion.orEmpty()} is installed"
                        else "The mod loader BepInEx is not installed",
                        if (state.game.loaderPresent) "NNOMM does not change it."
                        else "NNOMM installs it when a mod needs it.",
                    )
                }
                Label(
                    "NNOMM does not sign in to Steam. It changes nothing in the game folder until you install, uninstall or switch a mod.",
                    color = c.text2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        "Open my Library",
                        { finish(Page.Library) },
                        kind = ButtonKind.Strong,
                        size = ControlSize.Large,
                    )
                    Button(
                        "Look for mods first",
                        { finish(Page.Browse) },
                        kind = ButtonKind.Ghost,
                        size = ControlSize.Large,
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckRow(
    icon: ImageVector,
    title: String,
    description: String,
    action: (@Composable () -> Unit)? = null,
) {
    val c = LocalNnTheme.current.colors
    Row(
        Modifier.fillMaxWidth()
            .border(.5.dp, c.line)
            .heightIn(min = 42.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, color = c.text2)
        Column(Modifier.weight(1f)) {
            Label(title, weight = 600)
            Label(description, color = c.text3)
        }
        action?.invoke()
    }
}
