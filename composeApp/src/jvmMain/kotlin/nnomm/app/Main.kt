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
import java.io.File
import java.util.Properties
import nnomm.ui.shell.*
import nnomm.ui.shots.KitGallery
import nnomm.ui.theme.*

/**
 * UI integration seam: later pack-import UI consumes pendingFiles, without disk work on the UI
 * thread.
 */
object LaunchRequests {
    val pendingFiles = mutableStateListOf<String>()
    var onCommand: () -> Unit = {}
}

fun settingsDirectory(): File =
    System.getenv("NNOMM_HOME")?.let(::File)
        ?: File(
            System.getenv("APPDATA")
                ?: System.getenv("XDG_CONFIG_HOME")
                ?: "${System.getProperty("user.home")}/.config",
            "NNOMM",
        )

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
            if (uri.scheme == "file" && uri.path.endsWith(".nommpack"))
                if (uri.toString() !in LaunchRequests.pendingFiles)
                    LaunchRequests.pendingFiles.add(uri.toString())
        }
        LaunchedEffect(Unit) {
            args
                .filter { it.endsWith(".nommpack") }
                .forEach {
                    val path = if (it.startsWith("file:")) it else File(it).toURI().toString()
                    if (path !in LaunchRequests.pendingFiles) LaunchRequests.pendingFiles.add(path)
                }
        }
        val state =
            rememberWindowState(
                placement = initialPlacement,
                size =
                    DpSize(
                        properties.getProperty("width")?.toFloatOrNull()?.coerceAtLeast(720f)?.dp
                            ?: 1328.dp,
                        properties.getProperty("height")?.toFloatOrNull()?.coerceAtLeast(560f)?.dp
                            ?: 792.dp,
                    ),
            )
        val dark = if ("--light" in args) false else isSystemInDarkMode()
        NnommTheme(dark = dark) {
            DecoratedWindow(
                title = "NNOMM",
                state = state,
                minimumSize = DpSize(720.dp, 560.dp),
                onCloseRequest = {
                    properties.setProperty("placement", state.placement.name)
                    if (state.placement == WindowPlacement.Floating) {
                        properties.setProperty("width", state.size.width.value.toString())
                        properties.setProperty("height", state.size.height.value.toString())
                    }
                    runCatching {
                            placementFile.parentFile.mkdirs()
                            placementFile.outputStream().use {
                                properties.store(it, "NNOMM window")
                            }
                        }
                        .onFailure { System.err.println("Could not save window placement") }
                    exitApplication()
                },
                onPreviewKeyEvent = {
                    if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.K) {
                        LaunchRequests.onCommand()
                        true
                    } else false
                },
            ) {
                val win = nucleusWindow
                val drag = Modifier.windowDragArea(window = checkNotNull(win.unsafe.taoWindow))
                val colors = LocalNnTheme.current.colors
                WindowScaffold(
                    modifier =
                        Modifier.fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.bg)
                            .border(1.dp, colors.line2, RoundedCornerShape(10.dp))
                            .padding(1.dp),
                    titleBar = {
                        TitleBar(
                            onCommand = { LaunchRequests.onCommand() },
                            onMinimize = { win.setMinimized(true) },
                            onMaximize = { win.setMaximized(!win.isMaximized) },
                            onClose = { win.close() },
                            dragModifier = drag,
                        )
                    },
                ) {
                    if ("--kit" in args) KitGallery()
                }
            }
        }
    }
}
