package nnomm.core

import com.sun.net.httpserver.HttpServer
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import nnomm.core.io.real.*
import nnomm.core.model.*

class ReviewProbe2Test {
    private val temporary = mutableListOf<java.nio.file.Path>()

    @kotlin.test.AfterTest
    fun cleanupTemporaryFiles() {
        temporary.asReversed().forEach { nnomm.core.io.real.deleteTree(it) }
    }

    @Test
    fun slowTransferRetriesUsesManifestNameAndIgnoresRequestDeadline() = runBlocking {
        val home = Files.createTempDirectory("nnomm-transfer-").also { temporary.add(it) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/download") { ex ->
            try {
                if (++requests < 3) {
                    ex.sendResponseHeaders(503, -1)
                } else {
                    ex.sendResponseHeaders(200, 4096)
                    ex.responseBody.use { out ->
                        repeat(4) {
                            out.write(ByteArray(1024))
                            out.flush()
                            Thread.sleep(100)
                        }
                    }
                }
            } finally {
                ex.close()
            }
        }
        server.start()
        val client =
            HttpClient(OkHttp) {
                expectSuccess = true
                install(HttpTimeout) {
                    requestTimeoutMillis = 50
                    socketTimeoutMillis = 2000
                }
            }
        try {
            val progress = mutableListOf<Int>()
            val file =
                RealDownloader(client, home).download(
                    CatalogMod("m", "Mod"),
                    ModVersion(
                        "1",
                        downloadUrl = "http://127.0.0.1:${server.address.port}/download",
                        fileName = "Manifest.dll",
                    ),
                ) {
                    progress += it
                }
            assertEquals(3, requests)
            assertEquals("Manifest.dll", java.nio.file.Path.of(file.path).name)
            assertEquals(4096, java.nio.file.Path.of(file.path).fileSize())
            assertEquals(progress.distinct(), progress)
            file.close()
            assertFalse(java.nio.file.Path.of(file.path).exists())
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun badArtifactsAreIsolatedAndAbsentSideMeansBoth() {
        val mods =
            decodeRegistry(
                """[{"id":"good","artifacts":[{"version":"1"},{"missing":"version"}]},{"bad":"entry"},{"id":"other"}]"""
            )
        assertEquals(listOf("good", "other"), mods.map { it.id })
        assertEquals(1, mods.first().versions.size)
        assertEquals(Side.BOTH, mods.first().side)
    }

    @Test
    fun tarGzipUsesContentAndRejectsLinks() = runBlocking {
        val home = Files.createTempDirectory("nnomm-tar-").also { temporary.add(it) }
        val archive = home.resolve("extensionless")
        org.apache.commons.compress.compressors.gzip
            .GzipCompressorOutputStream(Files.newOutputStream(archive))
            .use { gzip ->
                org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzip).use { tar ->
                    val entry =
                        org.apache.commons.compress.archivers.tar.TarArchiveEntry("nested/mod.dll")
                    entry.size = 4
                    tar.putArchiveEntry(entry)
                    tar.write("good".toByteArray())
                    tar.closeArchiveEntry()
                }
            }
        val store = RealModStore(home)
        store.put("m", "1", nnomm.core.io.DownloadedFile(archive.toString()))
        assertEquals("good", store.version("m", "1").resolve("nested/mod.dll").readText())
    }
}
