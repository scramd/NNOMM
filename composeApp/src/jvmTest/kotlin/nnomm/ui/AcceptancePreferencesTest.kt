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
    @Test fun manualUnsupportedCheckAnswersEvenWhenAutomaticChecksAreDisabled() = kotlinx.coroutines.runBlocking {
        val answers = mutableListOf<String>()
        AppUpdates().check("Never", manual = true, status = answers::add)
        assertEquals(1, answers.size)
        assertContains(answers.single(), "releases page")
        answers.clear()
        AppUpdates().check("Tell me", status = answers::add)
        assertTrue(answers.isEmpty(), "Unsupported startup checks must stay quiet")
    }
    @Test fun testChannelFindsPublishedBetaTagsFromAtom() {
        assertEquals("latest", updaterChannel(false))
        assertEquals("beta", updaterChannel(true))
        fun feed(vararg tags: String) = """<feed xmlns="http://www.w3.org/2005/Atom">${tags.joinToString("") { """<entry><link href="https://github.com/scramd/NNOMM/releases/tag/$it"/></entry>""" }}</feed>"""
        assertEquals("v0.2-beta", testReleaseTag(feed("v0.3", "v0.2-beta")))
        assertEquals("v0.3-beta.1", testReleaseTag(feed("v0.4-alpha-beta", "v0.3-beta.1", "v0.2-beta")))
        assertNull(testReleaseTag(feed("v0.3", "v0.4-alpha")))
        assertNull(testReleaseTag(feed()))
        assertNull(testReleaseTag("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>v0.2-beta</title><link href="https://example.com/releases/tag/v0.2-beta"/></entry></feed>"""))
    }
}
