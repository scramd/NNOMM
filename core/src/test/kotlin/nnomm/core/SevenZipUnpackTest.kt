package nnomm.core

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import nnomm.core.io.real.deleteTree
import nnomm.core.io.real.unpack
import nnomm.core.io.real.unpackSevenZip
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile

class SevenZipUnpackTest {
    private fun checkFixture(name: String) = runBlocking {
        val root = Files.createTempDirectory("sevenzip-test-")
        try {
            val archive = root.resolve("input.7z")
            javaClass.getResourceAsStream("/sevenzip/$name.7z")!!.use { Files.copy(it, archive) }
            val output = root.resolve("output")
            unpack(archive, output)
            assertContentEquals(
                ByteArray(256) { it.toByte() },
                output.resolve("plugin.dll").readBytes(),
            )
        } finally {
            deleteTree(root)
        }
    }

    @Test fun bcj2() = checkFixture("bcj2")

    @Test fun lzma2() = checkFixture("lzma2")

    private fun withArchive(
        entries: List<SevenZArchiveEntry>,
        check: suspend (java.nio.file.Path, java.nio.file.Path) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("sevenzip-safety-")
        try {
            val archive = root.resolve("input.7z")
            SevenZOutputFile(archive.toFile()).use { out ->
                entries.forEach { entry ->
                    out.putArchiveEntry(entry)
                    if (!entry.isDirectory && entry.hasStream()) out.write(byteArrayOf(1, 2, 3))
                    out.closeArchiveEntry()
                }
            }
            check(archive, root.resolve("output"))
        } finally {
            deleteTree(root)
        }
    }

    private fun entry(name: String) =
        SevenZArchiveEntry().also {
            it.name = name
            it.setHasStream(true)
        }

    @Test
    fun rejectsTraversal() =
        withArchive(listOf(entry("../outside.dll"))) { archive, target ->
            assertFailsWith<IllegalArgumentException> { unpack(archive, target) }
            assertFalse(target.parent.resolve("outside.dll").exists())
        }

    @Test
    fun rejectsCaseAliases() =
        withArchive(listOf(entry("a/mod.dll"), entry("A/other.dll"))) { archive, target ->
            assertFailsWith<IllegalArgumentException> { unpack(archive, target) }
        }

    @Test
    fun refusesExistingOutput() =
        withArchive(listOf(entry("plugin.dll"))) { archive, target ->
            target.createDirectories()
            target.resolve("plugin.dll").writeText("keep")
            assertFailsWith<FileAlreadyExistsException> { unpack(archive, target) }
            assertEquals("keep", target.resolve("plugin.dll").readText())
        }

    @Test
    fun rejectsSymlinkAttributes() =
        withArchive(
            listOf(
                entry("link").also {
                    it.setHasWindowsAttributes(true)
                    it.windowsAttributes = (0xa1ff shl 16) or 0x8000
                }
            )
        ) { archive, target ->
            assertFailsWith<IllegalArgumentException> { unpack(archive, target) }
        }

    @Test
    fun rejectsDirectoryOnlyArchive() =
        withArchive(
            listOf(
                SevenZArchiveEntry().also {
                    it.name = "empty"
                    it.isDirectory = true
                }
            )
        ) { archive, target ->
            assertFailsWith<IllegalArgumentException> { unpack(archive, target) }
        }

    @Test
    fun preservesBudgetFailure() =
        withArchive(listOf(entry("plugin.dll"))) { archive, target ->
            target.createDirectories()
            val failure = IllegalArgumentException("test byte limit")
            val actual =
                assertFailsWith<IllegalArgumentException> {
                    unpackSevenZip(archive, { target.resolve(it) }, { throw failure })
                }
            assertSame(failure, actual)
        }

    @Test
    fun preservesCancellationFromCallback() =
        withArchive(listOf(entry("plugin.dll"))) { archive, target ->
            target.createDirectories()
            val failure = CancellationException("test cancellation")
            val job = Job()
            try {
                assertFailsWith<CancellationException> {
                    withContext(job) {
                        unpackSevenZip(archive, { target.resolve(it) }, { job.cancel(failure) })
                    }
                }
            } finally {
                job.cancel()
            }
        }
}
