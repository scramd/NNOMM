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
                    // The version of this build, not the default of the first version.
                    version = BuildKonfig.VERSION,
                    notice = { notices.trySend(it) },
                )
            AppSession(runtime.store, runtime).also {
                // Forward startup notices as well as notices emitted by later disk/network actions.
                runtime.store.forwardNotices(notices)
            }
        }
    }

/**
 * The framework's count of "a second NNOMM was started". Its public effect waits for the next
 * drawn frame, and a minimized window draws none, so the count is read directly. The framework
 * does not publish it; without it a second start still brings a visible window to the front.
 */
private fun restoreRequests(): kotlinx.coroutines.flow.StateFlow<*>? =
    runCatching {
            val bus = Class.forName("dev.nucleusframework.application.SingleInstanceRestoreBus")
            bus.getMethod("getSignal").invoke(bus.getField("INSTANCE").get(null))
                as kotlinx.coroutines.flow.StateFlow<*>
        }
        .getOrNull()

fun main(args: Array<String>) {
    System.setProperty("nucleus.app.id", "io.github.scramd.nnomm")
    System.setProperty("nucleus.app.name", "NNOMM")
    FileKit.init("NNOMM")
    val placementFile = File(settingsDirectory(), "window.properties")
    val properties =
        Properties().apply {
            if (placementFile.isFile) placementFile.inputStream().use { load(it) }
        }
    nnomm.ui.kit.PaneWidths.load(properties)
    // A window that was never moved has no position; such a saved value is not a number.
    fun saved(key: String) = properties.getProperty(key)?.toFloatOrNull()?.takeIf { it.isFinite() }
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
                    if (saved("x") != null && saved("y") != null)
                        WindowPosition(saved("x")!!.dp, saved("y")!!.dp)
                    else WindowPosition.PlatformDefault,
                size =
                    DpSize(
                        saved("width")?.coerceAtLeast(720f)?.dp ?: 1328.dp,
                        saved("height")?.coerceAtLeast(560f)?.dp ?: 792.dp,
                    ),
            )
        val openedSize = remember { state.size }
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
                    startupError = if (e is java.nio.channels.OverlappingFileLockException || e.message?.contains("already open", ignoreCase = true) == true)
                        "NNOMM is already open. Close its other window and try again."
                    else "Could not open NNOMM's settings. Your files have been kept."
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
        var openWindow by remember { mutableStateOf<NucleusWindow?>(null) }
        val saveAndExit: () -> Unit = {
            if (!closing) {
                closing = true
                properties.setProperty("placement", state.placement.name)
                nnomm.ui.kit.PaneWidths.save(properties)
                if (state.placement == WindowPlacement.Floating) {
                    // The window reports itself up to 1 dp larger than the size it was opened
                    // with. Keep the opened size unless the player resized, so that the window
                    // does not grow with every start.
                    fun kept(now: Float, opened: Float) =
                        if (kotlin.math.abs(now - opened) <= 1.5f) opened else now
                    properties.setProperty(
                        "width",
                        kept(state.size.width.value, openedSize.width.value).toString(),
                    )
                    properties.setProperty(
                        "height",
                        kept(state.size.height.value, openedSize.height.value).toString(),
                    )
                    // Until the player moves the window, the state holds no position: ask the
                    // window itself.
                    val bounds =
                        if (state.position.isSpecified) null
                        else runCatching { openWindow?.boundsOnScreen() }.getOrNull()
                    val x = bounds?.x ?: state.position.x.value
                    val y = bounds?.y ?: state.position.y.value
                    if (x.isFinite() && y.isFinite()) {
                        properties.setProperty("x", x.toString())
                        properties.setProperty("y", y.toString())
                    } else {
                        properties.remove("x")
                        properties.remove("y")
                    }
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
                SideEffect { openWindow = win }
                // A second start of NNOMM shows this window, also when it is minimized. The
                // signal is collected directly: a minimized window draws no frames, so an
                // effect that waits for recomposition would not run.
                DisposableEffect(win) {
                    val job =
                        restoreRequests()?.let { requests ->
                            CoroutineScope(Dispatchers.Default).launch {
                                var seen = requests.value
                                requests.collect { count ->
                                    if (count != seen) {
                                        seen = count
                                        withContext(Dispatchers.Main) {
                                            win.setMinimized(false)
                                            win.toFront()
                                            win.requestFocus()
                                        }
                                    }
                                }
                            }
                        }
                    onDispose { job?.cancel() }
                }
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
                CompositionLocalProvider(
                    nnomm.ui.kit.LocalFocusMode provides focusMode,
                    nnomm.ui.kit.LocalTileSkipNuclear provides
                        (values["tileSkipNuclear"]?.content == "true"),
                ) {
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
