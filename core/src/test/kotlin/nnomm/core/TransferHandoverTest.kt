package nnomm.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.store.AppStore

@OptIn(ExperimentalCoroutinesApi::class)
class TransferHandoverTest {
    @Test
    fun immediateRetryAfterCancelActuallyRestarts() = runTest {
        val base = Demo.state()
        val mod = CatalogMod("probe", "Probe", versions = listOf(ModVersion("1")))
        val state =
            base.copy(
                catalog = listOf(mod),
                installed = emptyMap(),
                downloads = emptyList(),
                game = GameInfo(),
            )
        var calls = 0
        val enteredCleanup = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        val original = Demo.io()
        val store =
            AppStore(
                state,
                original.copy(
                    downloader =
                        object : Downloader {
                            override suspend fun download(
                                mod: CatalogMod,
                                version: ModVersion,
                                progress: (Int) -> Unit,
                            ): DownloadedFile {
                                calls++
                                if (calls == 1) {
                                    try {
                                        progress(25)
                                        awaitCancellation()
                                    } finally {
                                        withContext(NonCancellable) {
                                            enteredCleanup.complete(Unit)
                                            releaseCleanup.await()
                                        }
                                    }
                                }
                                progress(50)
                                releaseReplacement.await()
                                return DownloadedFile("fixture")
                            }
                        }
                ),
                backgroundScope,
            )
        try {
            store.install("probe")
            runCurrent()
            store.cancelDownload("probe")
            runCurrent()
            assertTrue(enteredCleanup.isCompleted)
            store.install("probe")
            runCurrent()
            releaseCleanup.complete(Unit)
            runCurrent()
            assertEquals(
                50,
                store.state.value.downloads.single().percent,
                "Old completion must keep the replacement row",
            )
            releaseReplacement.complete(Unit)
            runCurrent()
            assertEquals(2, calls, "Retry must start a fresh transfer")
            assertTrue("probe" in store.state.value.installed)
        } finally {
            releaseCleanup.complete(Unit)
            releaseReplacement.complete(Unit)
            store.close()
            runCurrent()
        }
    }

    @Test fun cancelAtBodyCompletionLeavesNoStaging() = checkCancelledReturn(false)

    @Test fun cancelAtBodyCompletionOnDefaultLeavesNoStaging() = checkCancelledReturn(true)

    private fun checkCancelledReturn(onDefault: Boolean) = runBlocking {
        val home = Files.createTempDirectory("nnomm-cancel-boundary-")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mod.dll") { e ->
            e.sendResponseHeaders(200, 4)
            e.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4)) }
            e.close()
        }
        server.start()
        val client = networkClient("probe")
        try {
            val mod =
                CatalogMod(
                    "probe",
                    "Probe",
                    versions =
                        listOf(
                            ModVersion(
                                "1",
                                downloadUrl = "http://127.0.0.1:${server.address.port}/mod.dll",
                            )
                        ),
                )
            val task =
                async(if (onDefault) Dispatchers.Default else coroutineContext) {
                    val owner = currentCoroutineContext()[Job]!!
                    RealDownloader(client, home)
                        .download(mod, mod.versions.single()) { if (it == 100) owner.cancel() }
                        .use {}
                }
            assertFailsWith<CancellationException> { task.await() }
            assertTrue(
                home.resolve("staging").listDirectoryEntries().isEmpty(),
                "Cancelled return must close the staged payload",
            )
        } finally {
            client.close()
            server.stop(0)
            deleteTree(home)
        }
    }
}
