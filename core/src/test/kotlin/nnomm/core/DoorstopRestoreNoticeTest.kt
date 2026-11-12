package nnomm.core

import java.nio.file.*
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import nnomm.core.io.real.*
import nnomm.core.model.*

/** A second game folder's failed restore is announced, also while another one is failing. */
class DoorstopRestoreNoticeTest {
    @Test
    fun eachNewlyFailedFolderIsAnnounced() = runBlocking {
        val original = "[General]\nenabled=true\ntarget_assembly=x\n".toByteArray()
        val home = Files.createTempDirectory("nnomm-notice-")
        val root = Files.createTempDirectory("nnomm-notice-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val a = root.resolve("A").createDirectories()
            val b = root.resolve("B").createDirectories()
            a.resolve("NuclearOption.exe").writeText("")
            b.resolve("NuclearOption.exe").writeText("")
            val aIni =
                a.resolve("doorstop_config.ini").apply { writeText("[General]\nenabled=false\n") }
            val bIni = b.resolve("doorstop_config.ini").apply { writeBytes(original) }
            DoorstopRestores(home)
                .write(
                    a,
                    mapOf(
                        "path" to aIni.toString(),
                        "bytes" to Base64.getEncoder().encodeToString(original),
                        "changed" to "true",
                    ),
                )
            val notices = CopyOnWriteArrayList<String>()
            val lockB = AtomicBoolean(false)
            val launcher =
                RealGameLauncher(
                    home,
                    scope,
                    listOf("sh", "-c", "sleep 0.3"),
                    detect = { false },
                    notice = { notices += it },
                    writeDoorstop = { path, bytes ->
                        if (path.parent == a || (path.parent == b && lockB.get()))
                            throw FileSystemException(
                                path.toString(),
                                null,
                                "used by another process",
                            )
                        Files.write(path, bytes)
                    },
                )
            launcher.start(AppState(game = GameInfo(b.toString())), true)
            lockB.set(true)
            withTimeout(5000) { launcher.running.first { !it } }
            withTimeout(10000) { while (notices.none { it.contains(b.toString()) }) delay(100) }
            assertTrue(notices.any { it.contains(a.toString()) }, notices.toString())
            lockB.set(false)
            withTimeout(15000) { while (!bIni.readBytes().contentEquals(original)) delay(100) }
        } finally {
            scope.cancel()
            home.toFile().deleteRecursively()
            root.toFile().deleteRecursively()
        }
    }
}
