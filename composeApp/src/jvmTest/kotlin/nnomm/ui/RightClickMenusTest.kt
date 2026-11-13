package nnomm.ui

import kotlin.test.*
import nnomm.core.model.ConnectionKind
import nnomm.core.rules.isOn
import nnomm.ui.kit.MenuEntry
import nnomm.ui.library.browseItems
import nnomm.ui.library.modItems
import nnomm.ui.library.selectionItems
import nnomm.ui.shell.profileItems
import nnomm.ui.shots.*

class RightClickMenusTest {
    private fun List<MenuEntry>.items() = filterIsInstance<MenuEntry.Item>()

    private fun List<MenuEntry>.item(text: String) = items().first { it.text == text }

    @Test
    fun browseMenuFollowsTheModState() {
        mockStore("").use { store ->
            val model = AppModel(store, ShotPlatform)
            val s = store.state.value
            val installed = s.installed.keys.first()
            val other = s.catalog.first { it.id !in s.installed }.id
            // An installed mod gets the Library's menu, without the Library's key names.
            assertEquals(
                modItems(model, s, installed).items().map { it.text },
                browseItems(model, s, installed).items().map { it.text },
            )
            assertTrue(browseItems(model, s, installed).items().all { it.hint == null })
            assertEquals(
                listOf("Open details", "Install"),
                browseItems(model, s, other).items().map { it.text },
            )
            val offline = s.copy(connection = s.connection.copy(kind = ConnectionKind.OFFLINE))
            assertFalse(browseItems(model, offline, other).item("Install").enabled)
        }
    }

    @Test
    fun selectionMenuActsOnEverySelectedMod() {
        mockStore("").use { store ->
            val model = AppModel(store, ShotPlatform)
            val picked = store.state.value.installed.keys.take(3).toSet()
            var removed: Set<String>? = null
            var cleared = false
            val menu =
                selectionItems(model, store.state.value, picked, { removed = it }) {
                    cleared = true
                }
            assertEquals(MenuEntry.Heading("3 selected"), menu.first())
            menu.item("Turn off").action()
            assertTrue(picked.none { isOn(store.state.value, it) })
            menu.item("Turn on").action()
            assertTrue(picked.all { isOn(store.state.value, it) })
            menu.items().first { it.danger }.action()
            assertEquals(picked, removed)
            menu.item("Clear the selection").action()
            assertTrue(cleared)
        }
    }

    @Test
    fun profileMenuOffersPlayOnlyWhileTheGameIsNotRunning() {
        mockStore("").use { store ->
            val model = AppModel(store, ShotPlatform)
            val s = store.state.value
            val other = s.profiles.first { it.id != s.profileId }.id
            assertEquals(
                listOf("Use this profile", "Play with this profile"),
                profileItems(model, other).items().take(2).map { it.text },
            )
            assertEquals("Play", profileItems(model, s.profileId).items().first().text)
        }
        mockStore("running=1").use { store ->
            val model = AppModel(store, ShotPlatform)
            val texts = profileItems(model, store.state.value.profileId).items().map { it.text }
            assertTrue(texts.none { it.startsWith("Play") })
            assertEquals("Rename", texts.first())
            assertIs<MenuEntry.Item>(profileItems(model, store.state.value.profileId).first())
        }
    }
}
