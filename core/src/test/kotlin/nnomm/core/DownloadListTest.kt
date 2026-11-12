package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import nnomm.core.demo.*
import nnomm.core.io.*
import nnomm.core.model.*
import nnomm.core.store.AppStore

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadListTest {
    /** Fails every transfer until [works] is set, and counts the attempts per profile row. */
    private class Flaky(private val real: Downloader) : Downloader {
        var works = false
        var attempts = 0

        override suspend fun download(
            mod: CatalogMod,
            version: ModVersion,
            progress: (Int) -> Unit,
        ): DownloadedFile {
            attempts++
            if (!works) error("No connection")
            return real.download(mod, version, progress)
        }
    }

    private fun TestScope.storeWithTwoFailedRows(): Pair<AppStore, Flaky> {
        val io = Demo.io()
        val flaky = Flaky(io.downloader)
        val a = AppStore(Demo.state(), io.copy(downloader = flaky), backgroundScope)
        val first = a.state.value.profileId
        a.install("wingman")
        runCurrent()
        val second = a.copyProfile(first)
        runCurrent()
        a.useProfile(second)
        runCurrent()
        a.install("wingman")
        runCurrent()
        assertEquals(
            listOf(first, second),
            a.state.value.downloads.filter { it.id == "wingman" && it.error != null }.map {
                it.profile
            },
            "the same mod failed once in each profile",
        )
        return a to flaky
    }

    @Test
    fun tryAgainStartsTheRowThatWasChosen() = runTest {
        val (a, flaky) = storeWithTwoFailedRows()
        val rows = a.state.value.downloads.filter { it.id == "wingman" }
        flaky.works = true
        a.retryDownload(rows[1])
        runCurrent()
        val after = a.state.value.downloads.filter { it.id == "wingman" }
        assertNotNull(after.single { it.profile == rows[0].profile }.error, "the other row stays")
        assertNull(after.single { it.profile == rows[1].profile }.error, "the chosen row ran")
    }

    @Test
    fun removeTakesOnlyTheRowThatWasChosen() = runTest {
        val (a, _) = storeWithTwoFailedRows()
        val rows = a.state.value.downloads.filter { it.id == "wingman" }
        a.removeFailedDownload(rows[1])
        runCurrent()
        assertEquals(
            listOf(rows[0].profile),
            a.state.value.downloads.filter { it.id == "wingman" }.map { it.profile },
        )
    }
}
