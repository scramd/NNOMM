package nnomm.core

import java.nio.file.*
import java.util.Base64
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import nnomm.core.io.real.*

class DoorstopNoticeLifecycleTest {
    @Test
    fun restoreFailureLifecycle() {
        val root = Files.createTempDirectory("review-two-")
        val home = root.resolve("home").createDirectories()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        try {
            val folders = listOf("A", "B", "C").map { root.resolve(it).createDirectories() }
            val blocked = folders.toMutableSet()
            val notices = mutableListOf<String>()
            val original = "[General]\nenabled=true\n".toByteArray()
            fun pending(game: Path) {
                val ini = game.resolve("doorstop_config.ini")
                ini.writeText("[General]\nenabled=false\n")
                DoorstopRestores(home)
                    .write(
                        game,
                        mapOf(
                            "path" to ini.toString(),
                            "bytes" to Base64.getEncoder().encodeToString(original),
                            "changed" to "true",
                        ),
                    )
            }
            val launcher =
                RealGameLauncher(
                    home,
                    scope,
                    detect = { true },
                    notice = { notices.add(it) },
                    writeDoorstop = { path, bytes ->
                        if (path.parent in blocked) throw AccessDeniedException(path.toString())
                        Files.write(path, bytes)
                    },
                )
            val retry =
                launcher.javaClass.getDeclaredMethod("tryRestoreDoorstop", Path::class.java).apply {
                    isAccessible = true
                }
            fun tick() {
                retry.invoke(launcher, null)
            }
            val (a, b, c) = folders
            pending(a)
            tick()
            tick()
            assertEquals(1, notices.size)
            pending(b)
            tick()
            tick()
            assertEquals(2, notices.size)
            assertTrue(notices.last().contains(b.toString()))
            assertFalse(notices.last().contains(a.toString()))
            blocked.remove(a)
            tick()
            assertContentEquals(original, a.resolve("doorstop_config.ini").readBytes())
            blocked.add(a)
            pending(a)
            tick()
            tick()
            assertEquals(
                3,
                notices.size,
                "A must announce again after successful restore while B is still locked",
            )
            pending(c)
            tick()
            tick()
            assertEquals(4, notices.size)
            println("LIFECYCLE notices=$notices")
            blocked.clear()
            tick()
            assertTrue(DoorstopRestores(home).records().isEmpty())
            folders.forEach {
                assertContentEquals(original, it.resolve("doorstop_config.ini").readBytes())
            }
        } finally {
            scope.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun absentFolderMustNotResetAnUnresolvedAnnouncement() {
        val root = Files.createTempDirectory("review-two-absent-")
        val home = root.resolve("home").createDirectories()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        try {
            val a = root.resolve("A").createDirectories()
            val ini = a.resolve("doorstop_config.ini").apply { writeText("disabled") }
            DoorstopRestores(home)
                .write(
                    a,
                    mapOf("path" to ini.toString(), "text" to "original", "changed" to "true"),
                )
            val b = root.resolve("B").createDirectories()
            val bIni = b.resolve("doorstop_config.ini").apply { writeText("disabled") }
            DoorstopRestores(home)
                .write(
                    b,
                    mapOf("path" to bIni.toString(), "text" to "original", "changed" to "true"),
                )
            val notices = mutableListOf<String>()
            val launcher =
                RealGameLauncher(
                    home,
                    scope,
                    detect = { true },
                    notice = { notices.add(it) },
                    writeDoorstop = { path, _ -> throw AccessDeniedException(path.toString()) },
                )
            val retry =
                launcher.javaClass.getDeclaredMethod("tryRestoreDoorstop", Path::class.java).apply {
                    isAccessible = true
                }
            retry.invoke(launcher, null)
            assertEquals(1, notices.size)
            Files.move(a, root.resolve("away"))
            retry.invoke(launcher, null)
            assertFalse(DoorstopRestores(home).records().isEmpty())
            Files.move(root.resolve("away"), a)
            retry.invoke(launcher, null)
            println("ABSENT notices=$notices")
            assertEquals(
                1,
                notices.size,
                "Folder has not restored: its original failure should remain announced",
            )
        } finally {
            scope.cancel()
            root.toFile().deleteRecursively()
        }
    }
}
