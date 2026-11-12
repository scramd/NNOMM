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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import nnomm.core.demo.demoStore
import nnomm.core.io.real.RealRuntime
import nnomm.core.store.AppStore
import nnomm.ui.*
import nnomm.ui.dialogs.registerDialogFactories
import nnomm.ui.kit.Label
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

fun settingsDirectory(): File = nnomm.core.io.real.settingsHome().toFile()

/** The session retains the runtime resources as well as its store. */
class AppSession(val store: AppStore, private val runtime: RealRuntime? = null) {
    suspend fun shutdown() =
        withContext(Dispatchers.IO) {
            if (runtime != null) runtime.shutdown() else store.shutdown()
        }
}

suspend fun createStore(args: Array<String>): AppSession =
    withContext(Dispatchers.IO) {
        val demo = "--demo" in args || "--kit" in args || System.getenv("NNOMM_DEMO") == "1"
        if (demo) AppSession(demoStore())
        else {
            val notices =
                kotlinx.coroutines.channels.Channel<String>(
                    kotlinx.coroutines.channels.Channel.UNLIMITED
                )
            val home = settingsDirectory().toPath()
            // Development-only: inert unless explicitly enabled with an isolated sandbox home.
            val fakeLauncher =
                System.getenv("NNOMM_SANDBOX_LAUNCHER")?.let { command ->
                    val sandbox =
                        File(System.getProperty("user.home"), "nnomm-sandbox").toPath().toRealPath()
                    require(home.toAbsolutePath().normalize().startsWith(sandbox)) {
                        "A fake launcher requires a sandbox home."
                    }
                    listOf(command)
                }
            val runtime =
                RealRuntime.open(
                    home,
                    fakeLauncher = fakeLauncher,
                    notice = { notices.trySend(it) },
                )
            AppSession(runtime.store, runtime).also {
                // Forward startup notices as well as notices emitted by later disk/network actions.
                runtime.store.forwardNotices(notices)
            }
        }
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
        val scope = rememberCoroutineScope()
        var session by remember { mutableStateOf<AppSession?>(null) }
        var model by remember { mutableStateOf<AppModel?>(null) }
        var startupError by remember { mutableStateOf<String?>(null) }
        var closing by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            // NonCancellable ensures a close during startup still gets a session to drain.
            withContext(NonCancellable) {
                try {
                    val opened = createStore(args)
                    session = opened
                    model =
                        AppModel(opened.store, platform).apply {
                            nnomm.ui.settings.registerSettingsOverlays(this)
                            registerDialogFactories()
                            if (!store.state.value.firstStart.complete) openOverlay("first")
                        }
                } catch (e: Exception) {
                    startupError = "Could not open NNOMM's settings. Your files have been kept."
                    System.err.println("NNOMM startup failed: ${e.javaClass.simpleName}")
                }
            }
        }
        // Collect only what the window root needs, so downloads and toasts do not recompose it.
        val generalFlow =
            remember(model) {
                model?.store?.state?.map { it.settings.general }?.distinctUntilChanged()
            }
        val general =
            generalFlow?.collectAsState(model!!.store.state.value.settings.general)?.value.orEmpty()
        val firstStartFlow =
            remember(model) {
                model?.store?.state?.map { it.firstStart.complete }?.distinctUntilChanged()
            }
        val firstStartDone =
            firstStartFlow?.collectAsState(model!!.store.state.value.firstStart.complete)?.value
        DisposableEffect(Unit) {
            onDispose {
                model?.close()
                platform.close()
            }
        }
        LaunchedEffect(
            model,
            firstStartDone,
            LaunchRequests.pendingFiles.toList(),
        ) {
            val ready = model ?: return@LaunchedEffect
            if (!ready.store.state.value.firstStart.complete) return@LaunchedEffect
            val files = LaunchRequests.pendingFiles.toList()
            LaunchRequests.pendingFiles.clear()
            ready.acceptFiles(
                files.map { if (it.startsWith("file:")) File(java.net.URI(it)) else File(it) }
            )
        }
        var updateReady by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Unit>?>(null) }
        val saveAndExit: () -> Unit = {
            if (!closing) {
                closing = true
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
                    while (session == null && startupError == null) delay(25)
                    try {
                        session?.shutdown()
                        val ready = updateReady
                        if (ready != null) ready.complete(Unit) else exitApplication()
                    } catch (e: Exception) {
                        closing = false
                        updateReady?.completeExceptionally(e)
                        updateReady = null
                        startupError = "Could not save your changes. Close NNOMM to try again."
                    }
                }
            }
        }
        val updates = remember { AppUpdates() }
        val isDemo = "--demo" in args || System.getenv("NNOMM_DEMO") == "1" || "--kit" in args
        fun checkUpdates(manual: Boolean) {
            if (!isDemo)
                scope.launch {
                    updates.check(general["appUpdate"]?.content ?: "Tell me", general["channel"]?.content == "true", manual = manual,
                        status = { message -> scope.launch { model?.ui?.updateStatus = message } },
                        beforeInstall = {
                            val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
                            withContext(Dispatchers.Main) { updateReady = ready; saveAndExit() }
                            ready.await()
                        })
                }
        }
        model?.checkAppUpdates = { checkUpdates(true) }
        model?.openAppReleases = { platform.openUrl(if (general["channel"]?.content == "true") "https://github.com/scramd/NNOMM/releases" else NNOMM_LATEST_RELEASE_URL) }
        LaunchedEffect(model) { if (!isDemo) checkUpdates(false) }
        val values = general
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
                onPreviewKeyEvent = { event ->
                    if (System.getenv("NNOMM_LOG_KEYS") == "1")
                        System.err.println(
                            "NNOMM key=${event.key} type=${event.type} utf16=${event.utf16CodePoint} native=${event.nativeKeyEvent}"
                        )
                    val m = model
                    m != null &&
                        (m.suppressHandledCharacter(event) ||
                            (m.ui.overlayKind == "palette" && m.shortcut(event)) ||
                            ((event.isCtrlPressed || event.isMetaPressed) &&
                                event.key == Key.Enter &&
                                m.shortcut(event)))
                },
                onKeyEvent = { model?.shortcut(it) ?: false },
            ) {
                val win = nucleusWindow
                val launchFlow = remember(model) { model?.store?.state?.map { it.gameRunning }?.distinctUntilChanged() }
                LaunchedEffect(model, win) {
                    var wasRunning = model?.store?.state?.value?.gameRunning ?: false
                    launchFlow?.collect { running ->
                        if (running && !wasRunning) {
                            val snapshot = model!!.store.state.value
                            nnomm.ui.gameStartedAction(snapshot.settings.value("onLaunch", snapshot.profileId)?.content,
                                { win.setMinimized(true) }, saveAndExit)
                        }
                        wasRunning = running
                    }
                }
                val drag = Modifier.windowDragArea(window = checkNotNull(win.unsafe.taoWindow))
                val colors = LocalNnTheme.current.colors
                val maximized = state.placement == WindowPlacement.Maximized
                val focusMode = remember { nnomm.ui.kit.FocusMode() }
                CompositionLocalProvider(nnomm.ui.kit.LocalFocusMode provides focusMode) {
                    Box(
                        Modifier.fillMaxSize()
                            .onKeyEvent { model?.shortcut(it) ?: false }
                            .then(Modifier.windowInputMode(focusMode))
                    ) {
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
                                    onCommand = { model?.openOverlay("palette") },
                                    onMinimize = { win.setMinimized(true) },
                                    onMaximize = { win.setMaximized(!win.isMaximized) },
                                    onClose = saveAndExit,
                                    dragModifier = drag,
                                )
                            },
                        ) {
                            val ready = model
                            if (closing || startupError != null || ready == null) {
                                Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = androidx.compose.ui.Alignment.Center,
                                ) {
                                    Label(
                                        startupError
                                            ?: if (closing) "Saving your changes…"
                                            else "Opening NNOMM…"
                                    )
                                }
                            } else if ("--kit" in args) KitGallery() else AppShell(ready)
                        }
                        if ("--kit" !in args && !closing && startupError == null)
                            model?.let { AppLayers(it) }
                    }
                }
            }
        }
    }
}
