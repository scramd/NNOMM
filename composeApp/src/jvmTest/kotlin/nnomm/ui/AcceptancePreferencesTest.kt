package nnomm.ui

import kotlin.test.*
import kotlinx.serialization.json.JsonPrimitive
import nnomm.app.*
import nnomm.core.model.SettingsValues

class AcceptancePreferencesTest {
    @Test fun launchChoicesInvokeOnlyTheirEffect() {
        var minimised = 0
        var closed = 0
        gameStartedAction("Keep NNOMM open", { minimised++ }, { closed++ })
        assertEquals(0, minimised + closed)
        gameStartedAction("Minimise NNOMM", { minimised++ }, { closed++ })
        assertEquals(1, minimised)
        assertEquals(0, closed)
        gameStartedAction("Close NNOMM", { minimised++ }, { closed++ })
        assertEquals(1, closed)
    }
    @Test fun startupChoicesSelectTheSavedOrExplicitPage() {
        fun settings(choice: String) = SettingsValues(mapOf("startPage" to JsonPrimitive(choice), "lastPage" to JsonPrimitive("Settings")))
        assertEquals(Page.Settings, startupPage(settings("The page I used last")))
        assertEquals(Page.Browse, startupPage(settings("Browse")))
        assertEquals(Page.Library, startupPage(settings("Library")))
    }
    @Test fun testChannelFindsAnActualPrereleaseTag() {
        assertEquals("latest", updaterChannel(false))
        assertEquals("beta", updaterChannel(true))
        assertEquals("0.2-beta", testReleaseTag("""[{"tag_name":"0.1","prerelease":false},{"tag_name":"0.2-beta","prerelease":true}]"""))
        assertNull(testReleaseTag("""[{"tag_name":"0.1","prerelease":false}]"""))
    }
}
