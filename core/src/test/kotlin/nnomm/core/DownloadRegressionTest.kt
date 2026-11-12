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
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.rules.modAction
import nnomm.core.store.AppStore

class DownloadRegressionTest {
    /** The second half is withheld until the test observes progress (or cancels). */
    private class SlowBody : AutoCloseable {
        val requests = AtomicInteger()
        val release = CountDownLatch(1)
        private val executor = Executors.newSingleThreadExecutor()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                this.executor = this@SlowBody.executor
                createContext("/mod.dll") { exchange ->
                    requests.incrementAndGet()
                    try {
                        exchange.sendResponseHeaders(200, SIZE.toLong())
                        exchange.responseBody.use { out ->
                            repeat(16) { index ->
                                if (index == 8) check(release.await(10, TimeUnit.SECONDS))
                                out.write(ByteArray(CHUNK) { index.toByte() })
                                out.flush()
                                Thread.sleep(140)
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
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        companion object {
            const val CHUNK = 65536
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
                    val first = withTimeout(5000) { values.receive() }
                    val second =
                        withTimeout(5000) {
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
                    val file = withTimeout(5000) { download.await() }
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
                        if (it >= 12) progressed.complete(Unit)
                    }
                }
                try {
                    withTimeout(5000) { progressed.await() }
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
                                                progress(it)
                                                if (fromDownloader && it >= 25)
                                                    throw CancellationException("Source cancelled")
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
                    )
                try {
                    if (fromDownloader) store.update("slow") else store.install("slow")
                    val first =
                        withTimeout(5000) {
                            store.state.first { it.downloads.any { d -> d.percent in 1..20 } }
                        }
                    assertNotNull(modAction(first, "slow").percent)
                    withTimeout(5000) {
                        store.state.first {
                            it.downloads.any { d -> d.percent > first.downloads.single().percent }
                        }
                    }
                    if (!fromDownloader) store.cancelDownload("slow")
                    withTimeout(5000) { cancelled.await() }
                    withTimeout(5000) { store.state.first { it.downloads.isEmpty() } }
                    assertNoStaging(home)
                    assertNull(modAction(store.state.value, "slow").percent)
                    assertEquals(state.installed, store.state.value.installed)
                    assertEquals(0, puts)
                    assertTrue(
                        store.state.value.toasts.none { it.text.contains("Could not download") }
                    )
                    assertEquals(1, server.requests.get())
                } finally {
                    store.shutdown()
                }
            }
        } finally {
            client.close()
            deleteTree(home)
        }
    }
}
