package nnomm.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.rules.modAction
import nnomm.core.store.AppStore

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRegressionTest {
    /** The second half is withheld until the test observes progress (or cancels). */
    private class SlowBody(private val chunked: Boolean = false) : AutoCloseable {
        val requests = AtomicInteger()
        val release = CountDownLatch(1)
        val nextChunk = CountDownLatch(1)
        private val executor = Executors.newSingleThreadExecutor()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                this.executor = this@SlowBody.executor
                createContext("/mod.dll") { exchange ->
                    requests.incrementAndGet()
                    try {
                        exchange.sendResponseHeaders(200, if (chunked) 0 else SIZE.toLong())
                        exchange.responseBody.use { out ->
                            repeat(16) { index ->
                                if (index == 1) check(nextChunk.await(60, TimeUnit.SECONDS))
                                if (index == 2) check(release.await(60, TimeUnit.SECONDS))
                                out.write(ByteArray(CHUNK) { index.toByte() })
                                out.flush()
                            }
                        }
                    } catch (_: Exception) {
                        // Cancellation closes the client's socket while the fixture is writing.
                    } finally {
                        exchange.close()
                    }
                }
                start()
            }
        val mod =
            CatalogMod(
                "slow",
                "Slow mod",
                versions =
                    listOf(
                        ModVersion(
                            "2",
                            downloadUrl = "http://127.0.0.1:${server.address.port}/mod.dll",
                        )
                    ),
            )

        override fun close() {
            nextChunk.countDown()
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
            executor.awaitTermination(60, TimeUnit.SECONDS)
        }

        companion object {
            const val CHUNK = 4096
            const val SIZE = CHUNK * 16
        }
    }

    private fun assertNoStaging(home: Path) {
        assertTrue(
            home.resolve("staging").listDirectoryEntries().isEmpty(),
            "Owned staging must be removed",
        )
    }

    @Test
    fun realDownloaderReportsRisingProgressBeforeBodyFinishes() = runBlocking {
        val home = Files.createTempDirectory("nnomm-stream-")
        val client = networkClient("test")
        try {
            SlowBody().use { server ->
                val values = Channel<Int>(Channel.UNLIMITED)
                val download = async {
                    RealDownloader(client, home).download(
                        server.mod,
                        server.mod.versions.single(),
                    ) {
                        values.trySend(it)
                    }
                }
                try {
                    val first = withTimeout(60_000) { values.receive() }
                    server.nextChunk.countDown()
                    val second =
                        withTimeout(60_000) {
                            var next = values.receive()
                            while (next <= first) next = values.receive()
                            next
                        }
                    assertTrue(first in 0..49 && second in 1..50)
                    assertFalse(
                        download.isCompleted,
                        "Progress must arrive while the response is still streaming",
                    )
                    server.release.countDown()
                    val file = withTimeout(60_000) { download.await() }
                    file.use {
                        val expected = ByteArray(SlowBody.SIZE) { (it / SlowBody.CHUNK).toByte() }
                        assertContentEquals(expected, Path.of(file.path).readBytes())
                        var last = second
                        while (true) last = values.tryReceive().getOrNull() ?: break
                        assertEquals(100, last)
                    }
                    assertNoStaging(home)
                } finally {
                    server.release.countDown()
                    download.cancelAndJoin()
                }
            }
        } finally {
            client.close()
            deleteTree(home)
        }
    }

    @Test
    fun realDownloaderRethrowsMidBodyCancellationAndDeletesStaging() = runBlocking {
        val home = Files.createTempDirectory("nnomm-cancel-")
        val client = networkClient("test")
        try {
            SlowBody().use { server ->
                val progressed = CompletableDeferred<Unit>()
                val download = async {
                    RealDownloader(client, home).download(
                        server.mod,
                        server.mod.versions.single(),
                    ) {
                        if (it >= 6) progressed.complete(Unit)
                    }
                }
                try {
                    withTimeout(60_000) { progressed.await() }
                    assertTrue(home.resolve("staging").listDirectoryEntries().isNotEmpty())
                    download.cancel()
                    assertFailsWith<CancellationException> { download.await() }
                    assertNoStaging(home)
                    assertEquals(1, server.requests.get(), "Cancellation must not retry")
                } finally {
                    download.cancelAndJoin()
                }
            }
        } finally {
            client.close()
            deleteTree(home)
        }
    }

    @Test
    fun unknownResponseTotalStaysIndeterminateUntilComplete() = runBlocking {
        val home = Files.createTempDirectory("nnomm-chunked-")
        val client = networkClient("test")
        try {
            SlowBody(chunked = true).use { server ->
                val progress = Channel<Int>(Channel.UNLIMITED)
                val download = async {
                    RealDownloader(client, home).download(
                        server.mod,
                        server.mod.versions.single(),
                    ) {
                        progress.trySend(it)
                    }
                }
                try {
                    assertEquals(-1, withTimeout(60_000) { progress.receive() })
                    assertFalse(download.isCompleted)
                    server.nextChunk.countDown()
                    server.release.countDown()
                    withTimeout(60_000) { download.await() }
                        .use { file ->
                            assertEquals(SlowBody.SIZE.toLong(), Path.of(file.path).fileSize())
                            assertEquals(100, progress.receive())
                            assertTrue(progress.tryReceive().isFailure)
                        }
                    assertNoStaging(home)
                } finally {
                    download.cancelAndJoin()
                }
            }
        } finally {
            client.close()
            deleteTree(home)
        }
    }

    @Test
    fun storeKeepsUnknownTotalAndShowsDownloadingAction() = runTest {
        val original = Demo.io()
        val mod = CatalogMod("chunked", "Chunked", versions = listOf(ModVersion("1")))
        val store =
            AppStore(
                Demo.state().copy(catalog = listOf(mod), installed = emptyMap(), game = GameInfo()),
                original.copy(
                    downloader =
                        object : Downloader {
                            override suspend fun download(
                                mod: CatalogMod,
                                version: ModVersion,
                                progress: (Int) -> Unit,
                            ): DownloadedFile {
                                progress(-1)
                                awaitCancellation()
                            }
                        }
                ),
                backgroundScope,
            )
        try {
            store.install(mod.id)
            runCurrent()
            assertEquals(-1, store.state.value.downloads.single().percent)
            val action = modAction(store.state.value, mod.id)
            assertEquals("Downloading", action.label)
            assertEquals(-1, action.percent)
        } finally {
            store.shutdown()
        }
    }

    @Test
    fun storeCancelClearsProgressStagingAndDownloadingAction() = runBlocking {
        checkStoreCancellation(fromDownloader = false)
    }

    @Test
    fun downloaderCancellationClearsQueueWithoutErrorToast() = runBlocking {
        checkStoreCancellation(fromDownloader = true)
    }

    private suspend fun checkStoreCancellation(fromDownloader: Boolean) {
        val home = Files.createTempDirectory("nnomm-queue-")
        val client = networkClient("test")
        try {
            SlowBody().use { server ->
                val cancelled = CompletableDeferred<Unit>()
                val observed = CountDownLatch(1)
                val workers = SupervisorJob()
                val downloader = RealDownloader(client, home)
                val original = Demo.io()
                var puts = 0
                val state =
                    Demo.state()
                        .copy(
                            catalog = listOf(server.mod),
                            installed =
                                if (fromDownloader)
                                    mapOf(
                                        "slow" to
                                            InstalledMod(
                                                "slow",
                                                versions = setOf("1"),
                                                defaultVersion = "1",
                                                profiles =
                                                    mapOf(
                                                        "default" to ProfileMod(false, "1"),
                                                        "hardcore" to ProfileMod(true, "1"),
                                                    ),
                                            )
                                    )
                                else emptyMap(),
                            game = GameInfo(),
                            downloads = emptyList(),
                            toasts = emptyList(),
                        )
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
                                        try {
                                            return downloader.download(mod, version) {
                                                if (it >= 6) progress(it)
                                                if (fromDownloader && it >= 6) {
                                                    check(observed.await(60, TimeUnit.SECONDS))
                                                    throw CancellationException("Source cancelled")
                                                }
                                            }
                                        } catch (e: CancellationException) {
                                            cancelled.complete(Unit)
                                            throw e
                                        }
                                    }
                                },
                            modStore =
                                object : ModStore by original.modStore {
                                    override suspend fun put(
                                        id: String,
                                        version: String,
                                        file: DownloadedFile,
                                    ) {
                                        puts++
                                    }
                                },
                        ),
                        CoroutineScope(Dispatchers.Default + workers),
                    )
                try {
                    if (fromDownloader) store.update("slow") else store.install("slow")
                    val first =
                        withTimeout(60_000) {
                            store.state.first { it.downloads.any { d -> d.percent in 1..20 } }
                        }
                    assertNotNull(modAction(first, "slow").percent)
                    observed.countDown()
                    if (!fromDownloader) store.cancelDownload("slow")
                    withTimeout(60_000) { cancelled.await() }
                    withTimeout(60_000) { store.state.first { it.downloads.isEmpty() } }
                    assertNoStaging(home)
                    assertNull(modAction(store.state.value, "slow").percent)
                    assertEquals(state.installed, store.state.value.installed)
                    assertEquals(0, puts)
                    assertTrue(
                        store.state.value.toasts.none { it.text.contains("Could not download") }
                    )
                    assertEquals(1, server.requests.get())
                } finally {
                    observed.countDown()
                    server.nextChunk.countDown()
                    server.release.countDown()
                    store.shutdown()
                    withContext(NonCancellable) { withTimeout(60_000) { workers.cancelAndJoin() } }
                }
            }
        } finally {
            client.close()
            deleteTree(home)
        }
    }
}
