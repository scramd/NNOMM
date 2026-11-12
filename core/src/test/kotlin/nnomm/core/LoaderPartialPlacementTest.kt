package nnomm.core

import java.nio.file.*
import java.util.zip.*
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.*
import nnomm.core.demo.Demo
import nnomm.core.io.*
import nnomm.core.io.real.*
import nnomm.core.model.*
import nnomm.core.store.AppStore
import nnomm.core.store.loaderFailureHelp

class LoaderPartialPlacementTest {
    // Regression ports of the review probes: ownership publication must be retryable
    // even after the file transaction and its journal are gone.
    @Test
    fun reviewOwnershipWriteDeniedLosesInstalledFiles() = runBlocking {
        val f = fixture(false, null, orders[0])
        val player = f.game.resolve("player.txt").apply { writeText("keep me") }
        val permissions = Files.getPosixFilePermissions(f.home)
        DiskFaults.step = {
            if (it == "journal-deleted")
                Files.setPosixFilePermissions(
                    f.home,
                    java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"),
                )
        }
        try {
            assertFailsWith<AccessDeniedException> { f.folder.installLoader(f.game.toString()) }
        } finally {
            DiskFaults.step = {}
            Files.setPosixFilePermissions(f.home, permissions)
        }
        assertNull(record(f).read())
        assertTrue(f.folder.recoveryPending)
        // Retry must recover even though BepInEx is already detected.
        f.folder.installLoader(f.game.toString())
        assertRecordedPlacements(f)
        assertFalse(f.folder.recoveryPending)
        f.folder.repairLoader(f.game.toString())
        assertUninstallKeepsPlayer(f, player)
    }

    private class Crash : Error()

    private suspend fun crashAfterTransaction(f: F) {
        DiskFaults.step = { if (it == "journal-deleted") throw Crash() }
        try {
            assertFailsWith<Crash> { f.folder.installLoader(f.game.toString()) }
        } finally {
            DiskFaults.step = {}
        }
        assertNull(record(f).read())
        assertTrue(f.folder.recoveryPending)
        assertTrue(f.home.resolve("transactions").listDirectoryEntries("*.txn").isEmpty())
    }

    private suspend fun assertUninstallKeepsPlayer(f: F, player: Path) {
        val owned = record(f).read().orEmpty()
        f.folder.removeLoader(f.game.toString())
        owned.forEach { assertFalse(f.game.resolve(it).exists(), it) }
        assertEquals("keep me", player.readText())
        assertTrue(f.game.resolve("NuclearOption.exe").exists())
        assertTrue(record(f).read()!!.isEmpty())
    }

    @Test
    fun reviewCrashAfterLoaderTransactionLosesOwnership() = runBlocking {
        for (recovery in listOf("startup", "recover", "install", "repair", "uninstall")) {
            val f = fixture(false, null, orders[0])
            // Identical shipped bytes already owned by the player must not be claimed.
            val player = f.game.resolve("winhttp.dll").apply { writeText("shipped proxy") }
            crashAfterTransaction(f)
            when (recovery) {
                "startup" -> {
                    val settings = nnomm.core.settings.defaultSettings()
                    RealPersistence(f.home)
                        .save(
                            AppState(
                                settings =
                                    settings.copy(
                                        general =
                                            settings.general +
                                                mapOf(
                                                    "checkUpdates" to
                                                        kotlinx.serialization.json.JsonPrimitive(
                                                            "Only when I ask"
                                                        ),
                                                    "gamePath" to
                                                        kotlinx.serialization.json.JsonPrimitive(
                                                            f.game.toString()
                                                        ),
                                                )
                                    )
                            )
                        )
                    val runtime = RealRuntime.open(f.home)
                    try {
                        assertFalse(runtime.store.state.value.gameIoPending)
                    } finally {
                        runtime.shutdown()
                    }
                }
                "recover" -> {
                    f.folder.recoverPending()
                    f.folder.recoverPending()
                }
                "install" -> f.folder.installLoader(f.game.toString())
                "repair" -> f.folder.repairLoader(f.game.toString())
            }
            if (recovery != "uninstall") {
                assertEquals(3, record(f).read()!!.size)
                assertFalse("winhttp.dll" in record(f).read()!!)
                assertFalse(f.folder.recoveryPending)
            }
            f.folder.removeLoader(f.game.toString())
            for (relative in
                listOf(
                    "doorstop_config.ini",
                    "BepInEx/core/BepInEx.dll",
                    "BepInEx/core/Extra.dll",
                )) assertFalse(f.game.resolve(relative).exists(), "$recovery: $relative")
            assertEquals("shipped proxy", player.readText())
            assertTrue(f.game.resolve("NuclearOption.exe").exists())
            assertTrue(record(f).read()!!.isEmpty())
        }
    }

    @Test
    fun fullPromotionRecoversOwnershipAfterRestartWithoutDownload() = runBlocking {
        for (phase in listOf("ownership-published", "journal-deleted")) {
            val f = fixture(false, null, orders[0])
            val player = f.game.resolve("player.txt").apply { writeText("keep me") }
            DiskFaults.step = { if (it == phase) throw Crash() }
            try {
                assertFailsWith<Crash> { f.folder.installLoader(f.game.toString()) }
            } finally {
                DiskFaults.step = {}
            }
            assertTrue(f.folder.inspect(f.game.toString()).loaderPresent)
            assertNull(record(f).read())
            val restarted =
                RealGameFolder(
                    f.home,
                    RealModStore(f.home),
                    object : Downloader {
                        override suspend fun download(
                            mod: CatalogMod,
                            version: ModVersion,
                            progress: (Int) -> Unit,
                        ): DownloadedFile = error("Recovery must not download")
                    },
                    running = { false },
                )
            restarted.recoverPending()
            restarted.recoverPending()
            assertRecordedPlacements(f)
            assertEquals(4, record(f).read()!!.size)
            assertFalse(restarted.recoveryPending)
            restarted.removeLoader(f.game.toString())
            for (relative in orders[0]) assertFalse(f.game.resolve(relative).exists())
            assertEquals("keep me", player.readText())
            assertTrue(f.game.resolve("NuclearOption.exe").exists())
        }
    }

    @Test
    fun recoveryDoesNotClaimChangedOrMissingTargets() = runBlocking {
        val f = fixture(false, null, orders[0])
        crashAfterTransaction(f)
        val player = f.game.resolve("winhttp.dll").apply { writeText("keep me") }
        f.game.resolve("doorstop_config.ini").deleteExisting()
        f.folder.recoverPending()
        assertEquals(
            setOf("BepInEx/core/BepInEx.dll", "BepInEx/core/Extra.dll"),
            record(f).read()!!.toSet(),
        )
        assertUninstallKeepsPlayer(f, player)
    }

    @Test
    fun recoverySettlesInterruptedTransactionsBeforeOwnership() = runBlocking {
        for (stage in listOf("prepared", "promoted")) {
            val f = fixture(false, null, orders[0])
            val player = f.game.resolve("player.txt").apply { writeText("keep me") }
            DiskFaults.step = { if (it == stage) throw Crash() }
            try {
                assertFailsWith<Crash> { f.folder.installLoader(f.game.toString()) }
            } finally {
                DiskFaults.step = {}
            }
            f.folder.recoverPending()
            assertRecordedPlacements(f)
            assertFalse(f.folder.recoveryPending)
            assertUninstallKeepsPlayer(f, player)
        }
    }

    @Test
    fun interruptedRepairKeepsExistingUnownedIniAndAdoptedBinaries() = runBlocking {
        val f = fixture(true, "[General]\ntarget_assembly=\n".toByteArray(), orders[0])
        record(f).write(listOf("BepInEx/core/BepInEx.dll"))
        DiskFaults.step = { if (it == "journal-deleted") throw Crash() }
        try {
            assertFailsWith<Crash> { f.folder.repairLoader(f.game.toString()) }
        } finally {
            DiskFaults.step = {}
        }
        f.folder.removeLoader(f.game.toString())
        assertContentEquals(shipped, f.game.resolve("doorstop_config.ini").readBytes())
        assertFalse(f.game.resolve("BepInEx/core/BepInEx.dll").exists())
        assertFalse(f.game.resolve("BepInEx/core/Extra.dll").exists())
        assertEquals("player proxy", f.game.resolve("winhttp.dll").readText())
    }

    @Test
    fun missingGameKeepsPendingOwnershipUntilItReturns() = runBlocking {
        val f = fixture(false, null, orders[0])
        crashAfterTransaction(f)
        val parked = f.game.resolveSibling("parked-game")
        Files.move(f.game, parked)
        f.folder.recoverPending()
        assertTrue(f.folder.recoveryPending)
        assertFalse(f.game.exists())
        Files.move(parked, f.game)
        f.folder.recoverPending()
        assertRecordedPlacements(f)
        assertFalse(f.folder.recoveryPending)
    }

    private val shipped =
        "[General]\nenabled=true\ntarget_assembly=BepInEx\\core\\BepInEx.Preloader.dll\n"
            .toByteArray()
    private val dll = "5.4.23.4".toByteArray(Charsets.UTF_16LE)
    private val roots = mutableListOf<Path>()

    private fun temp() = Files.createTempDirectory("loader-partial-").also { roots += listOf(it) }

    @AfterTest
    fun clean() {
        DiskFaults.beforeMove = {}
        DiskFaults.step = {}
        roots.forEach { it.toFile().deleteRecursively() }
    }

    private fun zip(archive: Path, entries: List<Pair<String, ByteArray>>) =
        ZipOutputStream(Files.newOutputStream(archive)).use { z ->
            for ((n, d) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(d)
                z.closeEntry()
            }
        }

    private class F(
        val home: Path,
        val game: Path,
        val folder: RealGameFolder,
        val notices: MutableList<String>,
    )

    private fun fixture(withLoader: Boolean, ini: ByteArray?, order: List<String>): F {
        val home = temp()
        home.resolve("staging").createDirectories()
        val game = home.resolve("game").createDirectories()
        game.resolve("NuclearOption.exe").writeText("")
        if (withLoader) {
            game.resolve("BepInEx/core").createDirectories()
            game.resolve("BepInEx/core/BepInEx.dll").writeBytes(dll)
            game.resolve("winhttp.dll").writeText("player proxy")
        }
        if (ini != null) game.resolve("doorstop_config.ini").writeBytes(ini)
        val data =
            mapOf(
                ".doorstop_version" to "4.3.0".toByteArray(),
                "changelog.txt" to "log".toByteArray(),
                "BepInEx/core/BepInEx.dll" to dll,
                "winhttp.dll" to "shipped proxy".toByteArray(),
                "doorstop_config.ini" to shipped,
                "BepInEx/core/Extra.dll" to "extra".toByteArray(),
            )
        val archive = home.resolve("loader.zip")
        zip(archive, order.map { it to data.getValue(it) })
        val dl =
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ) = DownloadedFile(archive.toString())
            }
        val notices = mutableListOf<String>()
        return F(
            home,
            game,
            RealGameFolder(
                home,
                RealModStore(home),
                dl,
                running = { false },
                notice = { notices += it },
            ),
            notices,
        )
    }

    private fun record(f: F) =
        JsonFile(
            f.home.resolve("loader-${Integer.toHexString(f.game.toString().hashCode())}.json"),
            ListSerializer(String.serializer()),
        )

    private val orders =
        listOf(
            listOf(
                "BepInEx/core/BepInEx.dll",
                "winhttp.dll",
                "BepInEx/core/Extra.dll",
                "doorstop_config.ini",
            ),
            listOf(
                "doorstop_config.ini",
                "BepInEx/core/BepInEx.dll",
                "winhttp.dll",
                "BepInEx/core/Extra.dll",
            ),
            listOf(
                "winhttp.dll",
                "doorstop_config.ini",
                "BepInEx/core/Extra.dll",
                "BepInEx/core/BepInEx.dll",
            ),
        )

    private fun assertRecordedPlacements(f: F, previous: List<String> = emptyList()) {
        val placed =
            listOf(
                    "doorstop_config.ini",
                    "BepInEx/core/BepInEx.dll",
                    "winhttp.dll",
                    "BepInEx/core/Extra.dll",
                )
                .filter { relative ->
                    val path = f.game.resolve(relative)
                    val expected =
                        when (relative) {
                            "doorstop_config.ini" -> shipped
                            "BepInEx/core/BepInEx.dll" -> dll
                            "winhttp.dll" -> "shipped proxy".toByteArray()
                            else -> "extra".toByteArray()
                        }
                    path.isRegularFile() && path.readBytes().contentEquals(expected)
                }
        assertEquals((previous + placed).toSet(), record(f).read()!!.toSet())
    }

    // Assertion ports of VerifyTwoProbeTest's laterOwnedMoveFailsAfterIni and
    // installLaterMoveFails, with Retry and Uninstall checks for partial ownership.
    @Test
    fun laterOwnedMoveFailsAfterIni() = runBlocking {
        for (order in orders) for (missing in listOf(true, false)) {
            val f =
                fixture(
                    true,
                    if (missing) null else "[General]\ntarget_assembly=\n".toByteArray(),
                    order,
                )
            val previous = listOf("BepInEx/core/BepInEx.dll", "winhttp.dll", "doorstop_config.ini")
            record(f).write(previous)
            val proxy = f.game.resolve("winhttp.dll")
            DiskFaults.beforeMove = { if (it == proxy) throw AccessDeniedException(it.toString()) }
            val failure = assertFails { f.folder.repairLoader(f.game.toString()) }
            DiskFaults.beforeMove = {}
            assertTrue(loaderFailureHelp(failure).contains("close programs"))
            assertFalse(loaderFailureHelp(failure).contains("connection"))
            assertContentEquals(shipped, f.game.resolve("doorstop_config.ini").readBytes())
            assertEquals("player proxy", proxy.readText())
            assertTrue(f.notices.isEmpty(), f.notices.toString())
            assertRecordedPlacements(f, previous)
            f.folder.repairLoader(f.game.toString())
            assertEquals("shipped proxy", proxy.readText())
            assertRecordedPlacements(f, previous)
        }
    }

    @Test
    fun installLaterMoveFailsAndRetryKeepsOwnership() = runBlocking {
        for (order in orders) {
            val f = fixture(false, null, order)
            val target = f.game.resolve("BepInEx/core/BepInEx.dll")
            DiskFaults.beforeMove = { if (it == target) throw AccessDeniedException(it.toString()) }
            val failure = assertFails { f.folder.installLoader(f.game.toString()) }
            DiskFaults.beforeMove = {}
            assertTrue(loaderFailureHelp(failure).contains("close programs"))
            assertFalse(f.folder.inspect(f.game.toString()).loaderPresent)
            assertTrue(f.notices.isEmpty())
            assertRecordedPlacements(f)
            f.folder.installLoader(f.game.toString())
            assertTrue(f.folder.inspect(f.game.toString()).loaderPresent)
            assertRecordedPlacements(f)
            assertEquals(4, record(f).read()!!.size)
        }
    }

    @Test
    fun uninstallRemovesPartialInstallButKeepsPlayerFiles() = runBlocking {
        for (order in orders) {
            val f = fixture(false, null, order)
            val target = f.game.resolve("BepInEx/core/BepInEx.dll")
            val player = f.game.resolve("player.txt").apply { writeText("keep me") }
            DiskFaults.beforeMove = { if (it == target) throw AccessDeniedException(it.toString()) }
            assertFails { f.folder.installLoader(f.game.toString()) }
            DiskFaults.beforeMove = {}
            assertRecordedPlacements(f)
            val placed = record(f).read()!!
            assertTrue(placed.isNotEmpty())
            f.folder.removeLoader(f.game.toString())
            placed.forEach { assertFalse(f.game.resolve(it).exists(), it) }
            assertEquals("keep me", player.readText())
            assertTrue(f.game.resolve("NuclearOption.exe").exists())
            assertTrue(record(f).read()!!.isEmpty())
        }
    }

    @Test
    fun partialLoaderOperationsOfferRetryWithoutSuccessToasts() = runBlocking {
        for (repair in listOf(false, true)) {
            val f =
                fixture(
                    repair,
                    if (repair) "[General]\ntarget_assembly=\n".toByteArray() else null,
                    orders[0],
                )
            if (repair) record(f).write(listOf("BepInEx/core/BepInEx.dll", "winhttp.dll"))
            val target = f.game.resolve(if (repair) "winhttp.dll" else "BepInEx/core/BepInEx.dll")
            val io = Demo.io()
            val app =
                AppStore(
                    Demo.state().copy(game = f.folder.inspect(f.game.toString())),
                    io.copy(gameFolder = f.folder, persistence = null),
                )
            DiskFaults.beforeMove = { if (it == target) throw AccessDeniedException(it.toString()) }
            try {
                if (repair) app.repairLoader() else app.play()
                withTimeout(5000) {
                    while (
                        app.state.value.toasts.none {
                            it.action == "Retry" && it.text.contains("mod loader")
                        }
                    ) delay(10)
                }
                val toasts = app.state.value.toasts
                val failure = toasts.last { it.action == "Retry" }
                assertTrue(failure.text.contains("close programs"), failure.text)
                assertTrue(
                    toasts.none {
                        it.text.contains("Repair finished") ||
                            it.text.contains("Installed the mod loader") ||
                            it.text.contains("Replaced the damaged")
                    }
                )
                assertTrue(f.notices.isEmpty())
                assertEquals(!repair, "doorstop_config.ini" in record(f).read()!!)
            } finally {
                app.shutdown()
                DiskFaults.beforeMove = {}
            }
        }
    }

    // Port of VerifyLastProbeTest: refuse the alphabetically last archive file,
    // after the core DLL is already detected, then run the actual toast Retry.
    @Test
    fun retryAfterRefusedProxyFinishesInstallBeforeSuccess() = runBlocking {
        val order =
            listOf(
                ".doorstop_version",
                "BepInEx/core/BepInEx.dll",
                "BepInEx/core/Extra.dll",
                "changelog.txt",
                "doorstop_config.ini",
                "winhttp.dll",
            )
        val f = fixture(false, null, order)
        val proxy = f.game.resolve("winhttp.dll")
        val moves = mutableListOf<String>()
        val io = Demo.io()
        val app =
            AppStore(
                Demo.state().copy(game = f.folder.inspect(f.game.toString())),
                io.copy(gameFolder = f.folder, persistence = null),
            )
        DiskFaults.beforeMove = {
            moves += f.game.relativize(it).toString().replace('\\', '/')
            if (it == proxy) throw AccessDeniedException(it.toString())
        }
        try {
            app.play()
            withTimeout(5000) {
                while (
                    app.state.value.toasts.none {
                        it.action == "Retry" && it.text.contains("mod loader")
                    }
                ) delay(10)
            }
            assertEquals(
                listOf("doorstop_config.ini") + order.filter { it != "doorstop_config.ini" },
                moves,
            )
            assertTrue(f.folder.inspect(f.game.toString()).loaderPresent)
            assertFalse(proxy.exists())
            assertTrue(app.state.value.toasts.none { it.text.contains("Installed the mod loader") })
            // Install must not overwrite files already placed, including player edits.
            val core = f.game.resolve("BepInEx/core/BepInEx.dll")
            val playerDll = dll + byteArrayOf(0, 0)
            core.writeBytes(playerDll)
            val retryMoves = mutableListOf<Path>()
            var successBeforeProxy = false
            DiskFaults.beforeMove = {
                if (
                    it.startsWith(f.game) &&
                        f.game.relativize(it).toString().replace('\\', '/') in order
                ) {
                    retryMoves.add(it)
                    if (it == proxy)
                        successBeforeProxy =
                            app.state.value.toasts.any { toast ->
                                toast.text.contains("Installed the mod loader")
                            }
                }
            }
            val retry = app.state.value.toasts.last { it.action == "Retry" }
            app.runToast(retry.id)
            withTimeout(5000) {
                while (
                    app.state.value.toasts.none { it.text.contains("Installed the mod loader") }
                ) delay(10)
            }
            assertEquals(listOf(proxy), retryMoves)
            assertFalse(successBeforeProxy)
            assertEquals("shipped proxy", proxy.readText())
            assertContentEquals(playerDll, core.readBytes())
            assertTrue(order.all { f.game.resolve(it).isRegularFile() })
            assertEquals(order.toSet(), record(f).read()!!.toSet())
        } finally {
            app.shutdown()
            DiskFaults.beforeMove = {}
        }
    }

    @Test
    fun installDoesNotFillMissingFilesOfAnAdoptedLoader() = runBlocking {
        val f = fixture(true, null, orders[0])
        f.folder.installLoader(f.game.toString())
        assertFalse(f.game.resolve("doorstop_config.ini").exists())
        assertFalse(f.game.resolve("BepInEx/core/Extra.dll").exists())
        assertNull(record(f).read())
        assertEquals("player proxy", f.game.resolve("winhttp.dll").readText())
    }

    // Port of the adopted-loader Repair/Uninstall probe, including recovery
    // before ownership publication. Only a newly created INI belongs to NNOMM.
    @Test
    fun repairOnlyOwnsAnIniItCreated() = runBlocking {
        for (kind in listOf("disabled", "damaged", "missing", "owned")) {
            for (crash in listOf(false, true)) {
                val original =
                    when (kind) {
                        "damaged" -> "[General]\ntarget_assembly=\n".toByteArray()
                        "missing" -> null
                        else ->
                            "[General]\nenabled=false\ntarget_assembly=player.dll\n".toByteArray()
                    }
                val f = fixture(true, original, orders[0])
                if (kind == "owned") record(f).write(listOf("doorstop_config.ini"))
                if (crash) DiskFaults.step = { if (it == "journal-deleted") throw Crash() }
                try {
                    if (crash) assertFailsWith<Crash> { f.folder.repairLoader(f.game.toString()) }
                    else f.folder.repairLoader(f.game.toString())
                } finally {
                    DiskFaults.step = {}
                }
                val ini = f.game.resolve("doorstop_config.ini")
                val repaired = ini.readBytes()
                assertTrue(DoorstopIni.read(repaired).healthy)
                assertFalse(DoorstopIni.read(repaired).explicitlyDisabled)
                // A fresh object must recover without the archive or its staging files.
                val restarted =
                    RealGameFolder(
                        f.home,
                        RealModStore(f.home),
                        object : Downloader {
                            override suspend fun download(
                                mod: CatalogMod,
                                version: ModVersion,
                                progress: (Int) -> Unit,
                            ): DownloadedFile = error("No download during recovery")
                        },
                        running = { false },
                    )
                restarted.recoverPending()
                val ownedIni = kind == "missing" || kind == "owned"
                assertEquals(ownedIni, "doorstop_config.ini" in record(f).read()!!)
                restarted.removeLoader(f.game.toString())
                if (ownedIni) assertFalse(ini.exists())
                else assertContentEquals(repaired, ini.readBytes())
                assertContentEquals(dll, f.game.resolve("BepInEx/core/BepInEx.dll").readBytes())
                assertEquals("player proxy", f.game.resolve("winhttp.dll").readText())
                assertFalse(f.game.resolve("BepInEx/core/Extra.dll").exists())
            }
        }
    }

    @Test
    fun verifiesBinaryBytesBeforePublishingOwnershipAndSuccess() = runBlocking {
        val f = fixture(false, null, orders[0])
        val proxy = f.game.resolve("winhttp.dll")
        DiskFaults.step = { if (it == "ownership-published") proxy.writeText("different bytes") }
        val failure = assertFails { f.folder.installLoader(f.game.toString()) }
        DiskFaults.step = {}
        assertTrue(loaderFailureHelp(failure).contains("close programs"))
        assertTrue(f.notices.isEmpty())
        assertRecordedPlacements(f)
        assertFalse("winhttp.dll" in record(f).read()!!)
        f.folder.removeLoader(f.game.toString())
        assertEquals("different bytes", proxy.readText())
    }

    @Test
    fun reviewPreparedCrashMustNotClaimPlayerSuppliedFiles() = runBlocking {
        val f = fixture(false, null, orders[0])
        DiskFaults.step = { if (it == "prepared") throw Crash() }
        try {
            assertFailsWith<Crash> { f.folder.installLoader(f.game.toString()) }
        } finally {
            DiskFaults.step = {}
        }
        assertTrue(orders[0].none { f.game.resolve(it).exists() })
        // The player puts in the official loader after NNOMM crashed, before reopening it.
        for (relative in orders[0]) {
            val target = f.game.resolve(relative)
            target.parent.createDirectories()
            target.writeBytes(
                when (relative) {
                    "doorstop_config.ini" -> shipped
                    "BepInEx/core/BepInEx.dll" -> dll
                    "winhttp.dll" -> "shipped proxy".toByteArray()
                    else -> "extra".toByteArray()
                }
            )
        }
        f.folder.recoverPending()
        f.folder.removeLoader(f.game.toString())
        assertTrue(
            orders[0].all { f.game.resolve(it).exists() },
            "Uninstall deleted files supplied by the player after a pre-promotion crash",
        )
    }

    @Test
    fun reviewUnreadablePromotionMustKeepRecoveryPending() = runBlocking {
        val f = fixture(false, null, orders[0])
        crashAfterTransaction(f)
        val proxy = f.game.resolve("winhttp.dll")
        val perms = Files.getPosixFilePermissions(proxy)
        try {
            Files.setPosixFilePermissions(
                proxy,
                java.nio.file.attribute.PosixFilePermissions.fromString("---------"),
            )
            f.folder.recoverPending()
        } finally {
            Files.setPosixFilePermissions(proxy, perms)
        }
        f.folder.recoverPending()
        f.folder.removeLoader(f.game.toString())
        assertFalse(proxy.exists(), "NNOMM lost ownership permanently on a transient read failure")
    }

    @Test
    fun reviewForeignOwnershipThroughEveryLoaderAction() = runBlocking {
        for (action in listOf("install", "repair", "removeEverything")) {
            val f = fixture(false, null, orders[0])
            DiskFaults.step = { if (it == "prepared") throw Crash() }
            try {
                assertFailsWith<Crash> { f.folder.installLoader(f.game.toString()) }
            } finally {
                DiskFaults.step = {}
            }
            for (relative in orders[0]) {
                val target = f.game.resolve(relative)
                target.parent.createDirectories()
                target.writeBytes(
                    when (relative) {
                        "doorstop_config.ini" -> shipped
                        "BepInEx/core/BepInEx.dll" -> dll
                        "winhttp.dll" -> "shipped proxy".toByteArray()
                        else -> "extra".toByteArray()
                    }
                )
            }
            if (action == "removeEverything") {
                val app =
                    AppStore(
                        Demo.state()
                            .copy(
                                installed = emptyMap(),
                                game = f.folder.inspect(f.game.toString()),
                            ),
                        Demo.io().copy(gameFolder = f.folder, persistence = null),
                    )
                try {
                    app.removeEverything()
                    withTimeout(5000) {
                        while (
                            app.state.value.toasts.none {
                                it.text.contains("mod loader files were removed") ||
                                    it.text.contains("Existing mod loader files were kept") ||
                                    it.text.contains("Could not remove")
                            }
                        ) delay(10)
                    }
                } finally {
                    app.shutdown()
                }
            } else {
                if (action == "install") f.folder.installLoader(f.game.toString())
                else f.folder.repairLoader(f.game.toString())
            }
            assertTrue(record(f).read().orEmpty().isEmpty())
            assertTrue(orders[0].all { f.game.resolve(it).exists() })
        }
    }

    @Test
    fun reviewEveryPerFileCrashKeepsPreexistingPlayerFiles() = runBlocking {
        for (phase in
            listOf(
                "prepared",
                "old-rename-before-journal",
                "old-moved",
                "new-rename-before-journal",
                "promoted",
                "ownership-published",
                "journal-deleted",
            )) {
            val counts =
                if (phase in listOf("prepared", "ownership-published", "journal-deleted")) 1..1
                else 1..3
            for (at in counts) {
                val f = fixture(false, null, orders[0])
                val player = f.game.resolve("winhttp.dll").apply { writeText("shipped proxy") }
                var count = 0
                DiskFaults.step = { if (it == phase && ++count == at) throw Crash() }
                try {
                    assertFailsWith<Crash> { f.folder.installLoader(f.game.toString()) }
                } finally {
                    DiskFaults.step = {}
                }
                f.folder.recoverPending()
                f.folder.recoverPending()
                assertFalse("winhttp.dll" in record(f).read().orEmpty(), "$phase/$at")
                f.folder.removeLoader(f.game.toString())
                assertEquals("shipped proxy", player.readText(), "$phase/$at")
                assertTrue(f.game.resolve("NuclearOption.exe").exists())
            }
        }
    }
}
