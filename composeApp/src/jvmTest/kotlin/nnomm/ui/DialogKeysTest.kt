@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package nnomm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import kotlin.test.*
import nnomm.core.store.AppStore
import nnomm.ui.dialogs.registerDialogFactories
import nnomm.ui.shell.*
import nnomm.ui.shots.*
import nnomm.ui.theme.NnommTheme

/** Renders the real shell with a dialog open, and sends keys to it. */
internal class DialogScene(
    address: String,
    initialState: (nnomm.core.model.AppState) -> nnomm.core.model.AppState = { it },
    val width: Int = 1328,
    val height: Int = 792,
) : AutoCloseable {
    val store: AppStore = mockStore(address, initialState)
    val model =
        AppModel(store, ShotPlatform).also {
            nnomm.ui.settings.registerSettingsOverlays(it)
            it.registerDialogFactories()
            applyMockAddress(it, address)
        }
    private lateinit var scene: ImageComposeScene

    init {
        swing {
            scene =
                ImageComposeScene(width, height, Density(1f)) {
                    NnommTheme(dark = true, reduceMotion = true) {
                        Box(Modifier.fillMaxSize()) {
                            WindowShell { AppShell(model) }
                            AppLayers(model)
                        }
                    }
                }
        }
        frame()
        frame()
    }

    private var time = 0L

    fun frame() = swing {
        time += 100_000_000L
        scene.render(time).close()
    }

    /** Sends a key the way the window in Main.kt does: Ctrl Enter first to the shortcuts, unused keys last. */
    fun press(key: Key, ctrl: Boolean = false): Boolean {
        var handled = false
        swing {
            val down = KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl)
            handled =
                (ctrl && key == Key.Enter && model.shortcut(down)) ||
                    scene.sendKeyEvent(down) ||
                    model.shortcut(down)
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl))
        }
        frame()
        frame()
        return handled
    }

    fun move(x: Float, y: Float) {
        swing { scene.sendPointerEvent(PointerEventType.Move, Offset(x, y)) }
        frame()
    }

    fun click(x: Float, y: Float) {
        swing {
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), button = PointerButton.Primary, buttons = PointerButtons(isPrimaryPressed = true))
        }
        time += 50_000_000L
        swing { scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), button = PointerButton.Primary) }
        frame()
        frame()
    }

    /** Writes the current frame to composeApp/build/test-shots, to look at. */
    fun save(name: String) = swing {
        time += 100_000_000L
        scene.render(time).use { image ->
            image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.use {
                java.io.File("build/test-shots").apply { mkdirs() }.resolve("$name.png").writeBytes(it.bytes)
            }
        }
    }

    override fun close() {
        swing { scene.close() }
        model.close()
    }

    private fun swing(block: () -> Unit) = javax.swing.SwingUtilities.invokeAndWait(block)
}

class DialogKeysTest {
    @Test
    fun enterConfirmsRenameFromItsTextField() {
        DialogScene("page=library").use { d ->
            d.model.openOverlay("rename", "friday")
            d.frame()
            assertNotNull(d.model.ui.dialogEnter)
            d.press(Key.Enter)
            assertNull(d.model.ui.overlay)
            assertNull(d.model.ui.dialogEnter)
        }
    }

    @Test
    fun enterConfirmsThePackUpdate() {
        DialogScene("page=library").use { d ->
            d.model.openOverlay("packupdate", "friday")
            d.frame()
            assertTrue(d.press(Key.Enter))
            assertNull(d.model.ui.overlay)
            assertEquals("1.4", d.store.state.value.profile("friday").pack?.version)
            assertEquals("default", d.store.state.value.profileId)
        }
    }

    @Test
    fun enterImportsAModpack() {
        DialogScene("page=library&overlay=import").use { d ->
            val before = d.store.state.value.profiles.size
            assertTrue(d.press(Key.Enter))
            d.frame()
            assertNull(d.model.ui.overlay)
            assertEquals(before + 1, d.store.state.value.profiles.size)
        }
    }

    @Test
    fun enterDoesNotConfirmADangerZoneAction() {
        DialogScene("page=settings&cat=danger").use { d ->
            d.model.ui.settings.confirmKey = "dzAll"
            d.model.openOverlay("confirm")
            d.frame()
            assertNull(d.model.ui.dialogEnter)
            val installed = d.store.state.value.installed.size
            d.press(Key.Enter)
            assertEquals(installed, d.store.state.value.installed.size)
        }
    }

    @Test
    fun ctrlEnterDoesNotStartTheGameFromADialog() {
        DialogScene("page=library").use { d ->
            d.model.openOverlay("rename", "friday")
            d.frame()
            val toasts = d.store.state.value.toasts
            d.press(Key.Enter, ctrl = true)
            assertNotNull(d.model.ui.overlay)
            assertEquals(toasts, d.store.state.value.toasts)
            assertFalse(d.store.state.value.gameStarting)
        }
    }

    @Test
    fun profileRowShowsAMenuButtonOnHoverAndDoubleClickRenames() {
        DialogScene("page=library").use { d ->
            // Friday squad is the third profile row in the sidebar.
            d.move(100f, 278f)
            d.save("sidebar-profile-hover")
            d.click(191f, 278f)
            d.save("sidebar-profile-menu")
            assertNotNull(d.model.ui.dismissMenu)
            assertEquals("default", d.store.state.value.profileId)
            d.press(Key.Escape)
            assertNull(d.model.ui.dismissMenu)
            d.click(80f, 278f)
            d.click(80f, 278f)
            assertEquals("rename", d.model.ui.overlayKind)
            assertEquals("friday", d.model.ui.overlayProfileId)
            d.save("sidebar-profile-rename")
        }
    }

    @Test
    fun bothProfileMenusSwitchAndActOnTheProfileInUse() {
        DialogScene("page=library&prof=friday").use { d ->
            val labels =
                nnomm.ui.library.profileMenuItems(d.model).mapNotNull {
                    (it as? nnomm.ui.kit.MenuEntry.Item)?.text ?: (it as? nnomm.ui.kit.MenuEntry.Heading)?.text
                }
            assertEquals("Use profile", labels.first())
            for (name in d.store.state.value.profiles.map { it.name }) assertTrue(name in labels, name)
            for (action in listOf("New profile", "Rename", "Make a copy", "Share as a modpack", "Delete this profile"))
                assertTrue(action in labels, action)
            // The Library header menu.
            d.click(360f, 66f)
            assertNotNull(d.model.ui.dismissMenu)
            d.save("library-profile-menu")
            d.press(Key.Escape)
            // The Play box menu.
            d.click(45f, 664f)
            assertNotNull(d.model.ui.dismissMenu)
            d.save("playbox-profile-menu")
        }
    }
}
