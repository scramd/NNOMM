package nnomm.ui.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import nnomm.ui.*
import nnomm.ui.kit.*
import nnomm.ui.theme.*

class FirstStart(private val model: AppModel) : Overlay {
    @Composable
    override fun Content() {
        val state by model.store.state.collectAsState()
        val c = LocalNnTheme.current.colors
        val scope = rememberCoroutineScope()
        var game by remember { mutableStateOf(state.game) }
        var on by remember { mutableStateOf(state.firstStart.existingOn) }
        var off by remember { mutableStateOf(state.firstStart.existingOff) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val demo = model.store.io.modStore !is nnomm.core.io.real.RealModStore
        suspend fun preview(folder: String) {
            busy = true
            error = null
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        require(Files.isRegularFile(Path.of(folder).resolve("NuclearOption.exe"))) {
                            "Choose a folder containing NuclearOption.exe."
                        }
                        val info = model.store.io.gameFolder.inspect(folder)
                        fun count(location: String): Int {
                            val path = Path.of(folder).resolve("BepInEx/$location")
                            if (!Files.isDirectory(path)) return 0
                            return Files.list(path).use { paths ->
                                paths
                                    .filter { !it.fileName.toString().startsWith(".") }
                                    .count()
                                    .toInt()
                            }
                        }
                        Triple(info, count("plugins"), count("disabledPlugins"))
                    }
                game = result.first
                on = result.second
                off = result.third
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not read that folder. Choose another folder."
            } finally {
                busy = false
            }
        }
        LaunchedEffect(Unit) {
            if (!demo) game.folder?.takeIf { !game.folderMissing }?.let { preview(it) }
        }
        fun finish(page: Page) {
            if (busy) return
            scope.launch {
                busy = true
                try {
                    if (demo) model.store.completeFirstStart()
                    else model.store.confirmFirstStart(checkNotNull(game.folder))
                    model.ui.mock = "normal"
                    model.ui.go(page)
                    model.closeOverlay()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = "Could not take over these mods. Your files have been kept."
                } finally {
                    busy = false
                }
            }
        }
        Box(
            Modifier.fillMaxSize().padding(top = 38.dp).background(c.bg).padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Label(
                    if (game.folder == null || game.folderMissing) "Choose your game folder"
                    else "Check your game",
                    size = 26f,
                    weight = 650,
                    width = 114f,
                )
                Label(
                    if (game.folder == null || game.folderMissing) "Choose the game folder. You can change it later in Settings."
                    else "Check these three things. You can change all of them later in Settings.",
                    color = c.text2,
                )
                Column(Modifier.border(1.dp, c.line2, RoundedCornerShape(9.dp)).fillMaxWidth()) {
                    CheckRow(
                        NnIcons.folder,
                        if (game.folder == null || game.folderMissing) "Game folder not found"
                        else game.version?.takeUnless { it == "unknown" }?.let { "Nuclear Option $it" } ?: "Game version is unknown",
                        if (model.ui.mock == "first" && game.folder != null && !game.folderMissing)
                            "D:\\SteamLibrary\\steamapps\\common\\Nuclear Option"
                        else game.folder ?: "Choose the folder containing NuclearOption.exe.",
                    ) {
                        Button(
                            if (game.folder == null || game.folderMissing) "Choose folder" else "Choose another folder",
                            {
                                scope.launch {
                                    model.platform
                                        .chooseFolder("Choose the Nuclear Option folder")
                                        ?.let { preview(it.absolutePath) }
                                }
                            },
                            size = ControlSize.Small,
                            enabled = !busy,
                        )
                    }
                    if (game.folder != null && !game.folderMissing) {
                        CheckRow(
                            NnIcons.library,
                            "${on + off} mods are already in the game folder",
                            "They go into your Library as they are: ${on} on, ${off} off.",
                        )
                        CheckRow(
                            NnIcons.check,
                            if (game.loaderPresent)
                                "The mod loader BepInEx ${game.loaderVersion.orEmpty()} is installed"
                            else "The mod loader BepInEx is not installed",
                            if (game.loaderPresent) "NNOMM does not change it."
                            else "NNOMM installs it when a mod needs it.",
                        )
                    }
                }
                Label(
                    "NNOMM does not sign in to Steam. It changes nothing in the game folder until you install, uninstall or switch a mod.",
                    color = c.text2,
                )
                error?.let { Label(it, color = c.text2) }
                if (busy) Label("Reading your mods…", color = c.text2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        "Open my Library",
                        { finish(Page.Library) },
                        kind = ButtonKind.Strong,
                        enabled = !busy && game.folder != null && !game.folderMissing,
                        size = ControlSize.Large,
                    )
                    Button(
                        "Look for mods first",
                        { finish(Page.Browse) },
                        kind = ButtonKind.Ghost,
                        enabled = !busy && game.folder != null && !game.folderMissing,
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
