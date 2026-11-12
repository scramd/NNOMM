package nnomm.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import dev.nucleusframework.application.*
import nnomm.core.demo.Demo
import nnomm.core.model.*
import nnomm.core.store.AppStore
import nnomm.ui.*
import nnomm.ui.kit.*
import nnomm.ui.shell.*
import nnomm.ui.shots.ShotPlatform
import nnomm.ui.theme.NnommTheme

/**
 * Standalone, disk-free desktop fixture. Override :composeApp:run mainClass with a Gradle init
 * script.
 */
fun main() =
    nucleusApplication(
        args = emptyArray(),
        backend = NucleusBackend.Tao,
        enableSingleInstance = false,
    ) {
        val model = remember {
            val original = Demo.state()
            val mods =
                (1..400).map { number ->
                    CatalogMod(
                        id = "stress-$number",
                        name = "Demo mod ${number.toString().padStart(3, '0')}",
                        versions =
                            listOf(
                                ModVersion(
                                    "1.0.0",
                                    gameVersion = original.game.version,
                                    sizeKb = 128,
                                    dateMillis = original.nowMillis,
                                )
                            ),
                    )
                }
            val installed =
                mods.associate { mod ->
                    mod.id to
                        InstalledMod(
                            mod.id,
                            versions = setOf("1.0.0"),
                            defaultVersion = "1.0.0",
                            profiles = mapOf("default" to ProfileMod(true, "1.0.0")),
                        )
                }
            AppModel(
                    AppStore(original.copy(catalog = mods, installed = installed), Demo.io()),
                    ShotPlatform,
                )
                .apply { ui.page = Page.Library }
        }
        DisposableEffect(model) { onDispose { model.close() } }
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "NNOMM Library stress demo",
            state = rememberWindowState(size = DpSize(1360.dp, 860.dp)),
        ) {
            val focus = remember { FocusMode() }
            NnommTheme {
                CompositionLocalProvider(LocalApp provides model, LocalFocusMode provides focus) {
                    Box(
                        Modifier.fillMaxSize()
                            .windowInputMode(focus)
                            .onPreviewKeyEvent(model::shortcut)
                    ) {
                        WindowShell(onClose = ::exitApplication) { AppShell(model) }
                        AppLayers(model)
                    }
                }
            }
        }
    }
