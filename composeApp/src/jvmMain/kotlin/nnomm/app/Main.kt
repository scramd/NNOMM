package nnomm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import dev.nucleusframework.application.*
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.windowDragArea
import io.github.vinceglb.filekit.FileKit
import java.awt.dnd.*
import java.io.File
import java.util.Properties
import kotlinx.coroutines.launch
import nnomm.core.demo.demoStore
import nnomm.core.store.AppStore
import nnomm.ui.*
import nnomm.ui.kit.windowInputMode
import nnomm.ui.shell.*
import nnomm.ui.shots.KitGallery
import nnomm.ui.theme.*

/**
 * UI integration seam: later pack-import UI consumes pendingFiles, without disk work on the UI
 * thread.
 */
object LaunchRequests {
    val pendingFiles = mutableStateListOf<String>()
}

fun settingsDirectory(): File =
    System.getenv("NNOMM_HOME")?.let(::File)
        ?: File(
            System.getenv("APPDATA")
                ?: System.getenv("XDG_CONFIG_HOME")
                ?: "${System.getProperty("user.home")}/.config",
            "NNOMM",
        )

/** TODO(core): replace the real-mode branch with durable IO when it is merged. */
fun createStore(args: Array<String>): AppStore {
    val demo = "--demo" in args || System.getenv("NNOMM_DEMO") == "1"
    return if (demo) demoStore() else demoStore()
}

fun main(args: Array<String>) {
    System.setProperty("nucleus.app.id", "io.github.scramd.nnomm")
    System.setProperty("nucleus.app.name", "NNOMM")
    FileKit.init("NNOMM")
    val placementFile = File(settingsDirectory(), "window.properties")
    val properties =
        Properties().apply {
            if (placementFile.isFile) placementFile.inputStream().use { load(it) }
        }
    val initialPlacement =
        properties.getProperty("placement")?.let { value ->
            WindowPlacement.entries.find { it.name == value }
        } ?: WindowPlacement.Floating
    val formatted =
        args.map { if (File(it).isFile) File(it).toURI().toString() else it }.toTypedArray()
    nucleusApplication(
        args = formatted,
        backend = NucleusBackend.Tao,
        enableSingleInstance = true,
    ) {
        onDeepLink { uri ->
            if (
                uri.scheme == "file" &&
                    listOf(".nommpack", ".nomm.json", ".dll", ".zip", ".7z").any {
                        uri.path.endsWith(it, true)
                    }
            )
                if (uri.toString() !in LaunchRequests.pendingFiles)
                    LaunchRequests.pendingFiles.add(uri.toString())
        }
        LaunchedEffect(Unit) {
            args
                .filter { arg ->
                    listOf(".nommpack", ".nomm.json", ".dll", ".zip", ".7z").any {
                        arg.endsWith(it, true)
                    }
                }
                .forEach {
                    val path = if (it.startsWith("file:")) it else File(it).toURI().toString()
                    if (path !in LaunchRequests.pendingFiles) LaunchRequests.pendingFiles.add(path)
                }
        }
        val state =
            rememberWindowState(
                placement = initialPlacement,
                position =
                    if (
                        properties.getProperty("x")?.toFloatOrNull() != null &&
                            properties.getProperty("y")?.toFloatOrNull() != null
                    )
                        WindowPosition(
                            properties.getProperty("x").toFloat().dp,
                            properties.getProperty("y").toFloat().dp,
                        )
                    else WindowPosition.PlatformDefault,
                size =
                    DpSize(
                        properties.getProperty("width")?.toFloatOrNull()?.coerceAtLeast(720f)?.dp
                            ?: 1328.dp,
                        properties.getProperty("height")?.toFloatOrNull()?.coerceAtLeast(560f)?.dp
                            ?: 792.dp,
                    ),
            )
        val platform = remember { DesktopPlatform() }
        val model = remember { AppModel(createStore(args), platform) }
        val storeState by model.store.state.collectAsState()
        val scope = rememberCoroutineScope()
        DisposableEffect(Unit) {
            onDispose {
                model.close()
                platform.close()
            }
        }
        LaunchedEffect(LaunchRequests.pendingFiles.toList()) {
            val files = LaunchRequests.pendingFiles.toList()
            LaunchRequests.pendingFiles.clear()
            model.acceptFiles(
                files.map { if (it.startsWith("file:")) File(java.net.URI(it)) else File(it) }
            )
        }
        val saveAndExit: () -> Unit = {
            properties.setProperty("placement", state.placement.name)
            if (state.placement == WindowPlacement.Floating) {
                properties.setProperty("width", state.size.width.value.toString())
                properties.setProperty("height", state.size.height.value.toString())
                properties.setProperty("x", state.position.x.value.toString())
                properties.setProperty("y", state.position.y.value.toString())
            }
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                            placementFile.parentFile.mkdirs()
                            placementFile.outputStream().use {
                                properties.store(it, "NNOMM window")
                            }
                        }
                        .onFailure { System.err.println("Could not save window placement") }
                }
                exitApplication()
            }
        }
        val updates = remember { AppUpdates() }
        val isDemo = "--demo" in args || System.getenv("NNOMM_DEMO") == "1" || "--kit" in args
        model.checkAppUpdates = {
            if (!isDemo)
                scope.launch {
                    updates.check(storeState.settings.general["appUpdate"]?.content ?: "Tell me") {
                        message ->
                        scope.launch { model.ui.updateStatus = message }
                    }
                }
        }
        model.openAppReleases = { platform.openUrl(NNOMM_LATEST_RELEASE_URL) }
        LaunchedEffect(Unit) { if (!isDemo) model.checkAppUpdates() }
        val values = storeState.settings.general
        val dark =
            when {
                "--light" in args -> false
                values["theme"]?.content == "Light" -> false
                values["theme"]?.content == "Same as Windows" -> isSystemInDarkMode()
                else -> true
            }
        val systemMotionOff =
            java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("win.animation") == false
        NnommTheme(
            dark = dark,
            textPercent = values["textSize"]?.content?.removeSuffix("%")?.toIntOrNull() ?: 100,
            comfortable = values["density"]?.content == "Comfortable",
            reduceMotion =
                values["motion"]?.content == "Off" ||
                    (values["motion"]?.content == "Same as Windows" && systemMotionOff),
        ) {
            DecoratedWindow(
                title = "NNOMM",
                state = state,
                minimumSize = DpSize(720.dp, 560.dp),
                onCloseRequest = saveAndExit,
                onPreviewKeyEvent = model::shortcut,
            ) {
                val win = nucleusWindow
                val drag = Modifier.windowDragArea(window = checkNotNull(win.unsafe.taoWindow))
                val colors = LocalNnTheme.current.colors
                val maximized = state.placement == WindowPlacement.Maximized
                val focusMode = remember { nnomm.ui.kit.FocusMode() }
                CompositionLocalProvider(nnomm.ui.kit.LocalFocusMode provides focusMode) {
                Box(Modifier.fillMaxSize().then(Modifier.windowInputMode(focusMode))) {
                    WindowScaffold(
                        modifier =
                            Modifier.fillMaxSize()
                                .clip(RoundedCornerShape(if (maximized) 0.dp else 10.dp))
                                .background(colors.bg)
                                .then(
                                    if (maximized) Modifier
                                    else
                                        Modifier.border(
                                            1.dp,
                                            colors.line2,
                                            RoundedCornerShape(10.dp),
                                        )
                                )
                                .padding(1.dp),
                        titleBar = {
                            TitleBar(
                                onCommand = { model.openOverlay("palette") },
                                onMinimize = { win.setMinimized(true) },
                                onMaximize = { win.setMaximized(!win.isMaximized) },
                                onClose = saveAndExit,
                                dragModifier = drag,
                            )
                        },
                    ) {
                        if ("--kit" in args) KitGallery() else AppShell(model)
                    }
                    if ("--kit" !in args) AppLayers(model)
                }
                }
            }
        }
    }
}
