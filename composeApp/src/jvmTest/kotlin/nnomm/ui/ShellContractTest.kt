@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package nnomm.ui

import androidx.compose.ui.input.key.*
import java.io.File
import kotlin.test.*
import nnomm.ui.shots.*

class ShellContractTest {
    @Test
    fun shortcutsRespectLayersAndTyping() {
        mockStore("").use { store ->
            val model = AppModel(store, ShotPlatform)
            val layer =
                object : Overlay {
                    @androidx.compose.runtime.Composable override fun Content() {}
                }
            model.overlayFactories["palette"] = { layer }
            fun press(key: Key, ctrl: Boolean = false) =
                model.shortcut(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl))
            press(Key.K, true)
            assertSame(layer, model.ui.overlay)
            press(Key.K, true)
            assertNull(model.ui.overlay)
            press(Key.Two, true)
            assertEquals(Page.Library, model.ui.page)
            var cleared = false
            model.ui.focusedField = FocusedField({ "text" }, { cleared = true })
            model.ui.openMod("minimap")
            press(Key.Escape)
            assertTrue(cleared)
            assertNotNull(model.ui.panel)
            model.ui.focusedField = null
            press(Key.Escape)
            assertNull(model.ui.panel)
            var pageKey = false
            model.registerKeys(Page.Library) {
                pageKey = true
                true
            }
            model.ui.overlay = layer
            assertFalse(press(Key.DirectionDown))
            assertFalse(pageKey)
            model.closeOverlay()
            assertTrue(press(Key.DirectionDown))
            assertTrue(pageKey)
        }
    }

    @Test
    fun mockAddressPreservesPageStateAndDomainSeed() {
        val address =
            "page=library&theme=light&prof=friday&lf=upd&sel=minimap,delivery&panel=mod:minimap&tab=new&running=1&dl=minimap"
        mockStore(address).use { store ->
            val model = AppModel(store, ShotPlatform)
            applyMockAddress(model, address)
            assertEquals(Page.Library, model.ui.page)
            assertEquals("friday", store.state.value.profileId)
            assertEquals(setOf("minimap", "delivery"), model.ui.library.selection)
            assertEquals(PanelRef("minimap", PanelTab.WhatsNew), model.ui.panel)
            assertEquals("upd", model.ui.library.filter)
            assertTrue(store.state.value.gameRunning)
            assertEquals(42, store.state.value.downloads.single().percent)
        }
    }

    @Test
    fun fileImportWaitsForFactoryAndKeepsFile() {
        mockStore("").use { store ->
            val model = AppModel(store, ShotPlatform)
            val file = File("training.nomm.json")
            model.acceptFiles(listOf(file))
            assertEquals(file, model.ui.importFile)
            assertNull(model.ui.overlay)
            var called = false
            model.overlayFactories["import"] = {
                called = true
                object : Overlay {
                    @androidx.compose.runtime.Composable override fun Content() {}
                }
            }
            model.acceptFiles(listOf(file))
            assertTrue(called)
            assertEquals("import", model.ui.overlayKind)
        }
    }

    @Test
    fun allNonServerPicturesHaveAddresses() {
        assertEquals(30, mockShots.size)
        assertEquals(30, mockShots.map { it.name }.distinct().size)
        assertTrue(mockShots.none { it.name.take(2) in setOf("07", "09", "29", "33", "34", "35") })
        assertEquals(2, mockShots.count { it.width == 728 && it.height == 720 })
    }
}
