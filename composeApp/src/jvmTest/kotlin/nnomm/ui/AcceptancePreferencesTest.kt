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
        fun newest(vararg tags: String) = testReleaseTag(feed(*tags))?.tag
        assertEquals("v0.2.0-beta.2", newest("v0.2.0-beta.2", "v0.1.2"))
        // A normal release newer than the last test version reaches test-version players too.
        assertEquals("v0.2.0", newest("v0.2.0", "v0.2.0-beta.2", "v0.2.0-beta.1"))
        assertEquals("v0.3.0-beta.1", newest("v0.2.1", "v0.3.0-beta.1", "v0.2.0"))
        assertEquals("v0.2.0-beta.10", newest("v0.2.0-beta.9", "v0.2.0-beta.10"))
        assertNull(newest("v0.4-alpha", "5.6.0", "v0.3.0-alpha.1"))
        assertNull(testReleaseTag(feed()))
        assertNull(testReleaseTag("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>v0.2.0-beta.1</title><link href="https://example.com/releases/tag/v0.2.0-beta.1"/></entry></feed>"""))
        assertEquals(null, testReleaseTag(feed("v0.2.0"))?.beta)
        assertEquals(2, testReleaseTag(feed("v0.2.0-beta.2"))?.beta)
    }
    @Test fun updateDownloadsUseTheReleaseTag() {
        // The update files say "0.2.0-beta.1"; the release's tag is "v0.2.0-beta.1".
        assertEquals(
            "https://github.com/scramd/NNOMM/releases/download/v0.2.0-beta.1/NNOMM-0.2.0-beta.1-win-x64.msi",
            releaseDownloadUrl("NNOMM-0.2.0-beta.1-win-x64.msi", "0.2.0-beta.1"),
        )
    }
}
