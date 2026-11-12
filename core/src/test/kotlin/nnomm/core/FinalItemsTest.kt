package nnomm.core

import kotlin.test.*
import kotlinx.coroutines.*
import nnomm.core.demo.Demo
import nnomm.core.io.GameFolder
import nnomm.core.model.*
import nnomm.core.settings.*
import nnomm.core.store.AppStore
import nnomm.core.store.loaderFailureHelp

class FinalItemsTest {
    /** Cards register independently; every card that stays visible must receive metadata. */
    @Test
    fun perCardMetadataRequestsAllLoad() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bgStarted = CompletableDeferred<Unit>()
        val bgRelease = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val meta =
            object : nnomm.core.io.MetaSource {
                override suspend fun metadata(
                    mod: CatalogMod,
                    version: ModVersion,
                ): nnomm.core.io.VersionMetadata {
                    synchronized(calls) { calls += mod.id }
                    if (mod.id == "background") {
                        bgStarted.complete(Unit)
                        bgRelease.await()
                    }
                    if (mod.id == "a") {
                        first.complete(Unit)
                        release.await()
                    }
                    return nnomm.core.io.VersionMetadata(sizeKb = 1, dateMillis = 123456789L)
                }
            }
        val mods =
            listOf("background", "a", "b", "c", "d").map {
                CatalogMod(
                    it,
                    it,
                    versions = listOf(ModVersion("1", downloadUrl = "https://example.com/$it")),
                )
            }
        val app =
            AppStore(
                AppState(catalog = mods, settings = defaultSettings()),
                Demo.io().copy(meta = meta, persistence = null),
                scope = this,
            )
        try {
            withTimeout(5000) { bgStarted.await() }
            // Four cards on screen compose one after another; all stay visible.
            app.metadataVisible("a")
            withTimeout(5000) { first.await() }
            app.metadataVisible("b")
            app.metadataVisible("c")
            app.metadataVisible("d")
            release.complete(Unit)
            withTimeout(5000) { while (synchronized(calls) { calls.toSet().size < 5 }) delay(10) }
            withTimeout(5000) {
                while (
                    app.state.value.catalog
                        .filter { it.id != "background" }
                        .any { it.newestRelease?.dateMillis != 123456789L }
                ) delay(10)
            }
            val seen = synchronized(calls) { calls.filter { it != "background" } }
            assertEquals(
                setOf("a", "b", "c", "d"),
                seen.toSet(),
                "visible cards b/c skipped: $seen",
            )
        } finally {
            release.complete(Unit)
            bgRelease.complete(Unit)
            app.shutdown()
        }
    }

    @Test
    fun realDiskFullAndDownloadFailures() {
        if (java.nio.file.Files.exists(java.nio.file.Path.of("/dev/full"))) {
            val full =
                assertFailsWith<java.io.IOException> {
                    java.nio.file.Files.newOutputStream(java.nio.file.Path.of("/dev/full")).use {
                        it.write(ByteArray(65536))
                        it.flush()
                    }
                }
            assertTrue(loaderFailureHelp(Exception(full)).contains("Free some space"))
        }
        assertTrue(
            loaderFailureHelp(java.io.IOException("There is not enough space on the disk"))
                .contains("Free some space")
        )
        for (failure in
            listOf(
                java.io.IOException("unexpected end of stream on https://github.com/..."),
                javax.net.ssl.SSLHandshakeException("PKIX path building failed"),
            )) {
            assertEquals(
                "Check your connection and try again.",
                loaderFailureHelp(nnomm.core.io.LoaderDownloadFailure(failure)),
            )
        }
        assertTrue(
            loaderFailureHelp(IllegalArgumentException("SHA-256 mismatch for BepInEx"))
                .contains("could not be checked")
        )
        assertTrue(
            loaderFailureHelp(
                    nnomm.core.io.LoaderDownloadFailure(
                        IllegalArgumentException("SHA-256 mismatch for BepInEx")
                    )
                )
                .contains("could not be checked")
        )
        assertFalse(loaderFailureHelp(nnomm.core.io.LoaderRepairRefused()).contains("Retry"))
    }

    /**
     * Turning on the first mod is the normal loader install path (DESIGN: "installs it when you
     * turn on your first mod").
     */
    @Test
    fun loaderInstallFailureWhenTurningOnFirstMod() = runBlocking {
        val io = Demo.io()
        var attempts = 0
        var offline = true
        val folder =
            object : GameFolder by io.gameFolder {
                override suspend fun installLoader(folder: String) {
                    attempts++
                    if (offline) throw java.net.UnknownHostException("github.com")
                    io.gameFolder.installLoader(folder)
                }
            }
        val s = Demo.state()
        val off = s.installed.filterValues { it.profiles[s.profileId]?.on != true }.keys.first()
        val state =
            s.copy(
                game = s.game.copy(loaderPresent = false),
                installed =
                    s.installed.mapValues { (_, m) ->
                        m.copy(profiles = m.profiles.mapValues { (_, p) -> p.copy(on = false) })
                    },
            )
        val app = AppStore(state, io.copy(gameFolder = folder, persistence = null))
        try {
            app.setOn(listOf(off), true)
            withTimeout(5000) {
                while (app.state.value.toasts.none { it.action == "Retry" }) delay(10)
            }
            val texts = app.state.value.toasts.map { it.text }
            assertTrue(texts.any { it.contains("connection") }, "$texts")
            app.awaitDiskIdle()
            assertEquals(1, attempts)
            assertTrue(
                app.state.value.installed.getValue(off).profiles.getValue(state.profileId).on
            )
            assertFalse(app.state.value.game.loaderPresent)
            offline = false
            app.runToast(app.state.value.toasts.first { it.action == "Retry" }.id)
            app.awaitDiskIdle()
            assertEquals(2, attempts)
            assertTrue(app.state.value.game.loaderPresent)
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun emptyLibraryRemovalMessages() = runBlocking {
        val io = Demo.io()
        val s = Demo.state()
        val app = AppStore(s.copy(installed = emptyMap()), io.copy(persistence = null))
        try {
            app.removeEverything()
            app.awaitDiskIdle()
            assertTrue(
                app.state.value.toasts.any { it.text.contains("There are no mods to remove") }
            )
            assertFalse(
                app.state.value.toasts.any { it.text.contains("loader files were removed") }
            )
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun removalMessagesDescribeTheActualResult() = runBlocking {
        for (result in
            listOf(
                nnomm.core.io.LoaderRemoval(),
                nnomm.core.io.LoaderRemoval(3),
                nnomm.core.io.LoaderRemoval(0, true),
                nnomm.core.io.LoaderRemoval(2, true),
            )) {
            val io = Demo.io()
            val folder =
                object : GameFolder by io.gameFolder {
                    override suspend fun removeLoader(folder: String) = result
                }
            val app =
                AppStore(
                    Demo.state().copy(installed = emptyMap()),
                    io.copy(gameFolder = folder, persistence = null),
                )
            try {
                app.removeEverything()
                app.awaitDiskIdle()
                val text = app.state.value.toasts.joinToString { it.text }
                assertEquals(result.removed > 0, text.contains("loader files were removed"), text)
                assertEquals(result.kept, text.contains("loader files were kept"), text)
            } finally {
                app.shutdown()
            }
        }
    }

    @Test
    fun unsupportedRepairHasInstructionsWithoutRetry() = runBlocking {
        val io = Demo.io()
        val folder =
            object : GameFolder by io.gameFolder {
                override suspend fun repairLoader(folder: String) {
                    throw nnomm.core.io.LoaderRepairRefused()
                }
            }
        val app = AppStore(Demo.state(), io.copy(gameFolder = folder, persistence = null))
        try {
            app.repairLoader()
            app.awaitDiskIdle()
            val toast = app.state.value.toasts.last()
            assertNull(toast.action)
            assertNull(toast.callback)
            assertTrue(toast.text.contains("original installer"))
        } finally {
            app.shutdown()
        }
    }
}
