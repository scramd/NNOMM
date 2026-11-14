package nnomm.core.io.real

import java.net.URLEncoder
import java.nio.file.*
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import nnomm.core.io.*
import nnomm.core.model.*

private fun isGameProcess(p: ProcessHandle): Boolean =
    p.info().command().orElse("").substringAfterLast('/').substringAfterLast('\\').let {
        it.equals("NuclearOption.exe", true) ||
            it == "NuclearOption" ||
            p.info().arguments().orElse(emptyArray()).any { arg ->
                arg.substringAfterLast('/')
                    .substringAfterLast('\\')
                    .equals("NuclearOption.exe", true)
            }
    }

internal fun gameProcessRunning(): Boolean =
    ProcessHandle.allProcesses().use { all -> all.anyMatch(::isGameProcess) }

/**
 * Closes Nuclear Option like Stop in Steam: first ask its windows to close (taskkill without /F on
 * Windows, SIGTERM elsewhere), then end it after [graceMs].
 */
internal suspend fun stopGameProcesses(extra: ProcessHandle? = null, graceMs: Long = 10_000): Boolean =
    withContext(Dispatchers.IO) {
        val targets =
            (ProcessHandle.allProcesses().use { all -> all.filter(::isGameProcess).toList() } + listOfNotNull(extra))
                .distinctBy { it.pid() }
                .filter { it.isAlive }
        val windows = System.getProperty("os.name").startsWith("Windows")
        targets.forEach { p ->
            if (windows) runCatching { ProcessBuilder("taskkill", "/PID", p.pid().toString()).start().waitFor() }
            else p.destroy()
        }
        withTimeoutOrNull(graceMs) { while (targets.any { it.isAlive }) delay(250) }
        targets.filter { it.isAlive }.forEach { it.destroyForcibly() }
        withTimeoutOrNull(5_000) { while (targets.any { it.isAlive }) delay(250) }
        targets.none { it.isAlive }
    }

/**
 * Windows Steam-library search retained from NOMM's Paths.kt (MIT, 3dacae5). Other OSes use the
 * setting.
 */
suspend fun findGameFolder(settings: SettingsValues): String? =
    withContext(Dispatchers.IO) {
        settings.general["gamePath"]
            ?.content
            ?.takeIf { it.isNotBlank() && Path.of(it).resolve("NuclearOption.exe").isRegularFile() }
            ?.let {
                return@withContext it
            }
        if (!System.getProperty("os.name").startsWith("Windows", true)) return@withContext null
        val process =
            ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath")
                .start()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@withContext null
        }
        val text = process.inputStream.bufferedReader().use { it.readText() }
        val steam =
            Regex("SteamPath\\s+REG_SZ\\s+(.*)").find(text)?.groupValues?.get(1)?.trim()?.let {
                Path.of(it)
            } ?: return@withContext null
        val vdf =
            listOf(
                    steam.resolve("steamapps/libraryfolders.vdf"),
                    steam.resolve("config/libraryfolders.vdf"),
                )
                .firstOrNull { it.isRegularFile() }
        val libs =
            vdf?.readText()
                ?.let {
                    Regex("\"path\"\\s+\"(.+?)\"")
                        .findAll(it)
                        .map { m -> Path.of(m.groupValues[1].replace("\\\\", "/")) }
                        .toList()
                }
                .orEmpty() + steam
        libs
            .map { it.resolve("steamapps/common/Nuclear Option") }
            .firstOrNull { it.resolve("NuclearOption.exe").isRegularFile() }
            ?.toString()
    }

class RealGameLauncher(
    private val home: Path,
    private val scope: CoroutineScope,
    private val fakeCommand: List<String>? = null,
    private val detect: () -> Boolean = ::gameProcessRunning,
    private val notice: (String) -> Unit = {},
    private val readDoorstop: (Path) -> ByteArray = { Files.readAllBytes(it) },
    private val writeDoorstop: (Path, ByteArray) -> Unit = { path, bytes ->
        atomic(path, String(bytes, DoorstopIni.charset), DoorstopIni.charset, createParents = false)
    },
) : GameLauncher {
    private val flow = MutableStateFlow(detect())
    override val running = flow.asStateFlow()
    private val mutex = Mutex()
    private var launched: Process? = null
    private var pending = false
    // Folders whose failed restore was announced; a folder leaves the set when it restores.
    private val announcedRestoreFailures = mutableSetOf<String>()

    init {
        if (!flow.value) tryRestoreDoorstop()
        scope.launch {
            while (isActive) {
                mutex.withLock {
                    if (!pending) {
                        val now = detect() || launched?.isAlive == true
                        if (!now) tryRestoreDoorstop()
                        flow.value = now
                    }
                }
                delay(3000)
            }
        }
    }

    private val restores
        get() = DoorstopRestores(home)

    // Each folder can recover even if another library is absent or locked.
    private fun tryRestoreDoorstop(launchGame: Path? = null): Exception? {
        var failure: Exception? = null
        val failedFolders = mutableListOf<Path>()
        try {
            for (record in restores.records()) {
                val game = Path.of(record.getValue("path")).parent
                DoorstopLocks.locked(game) {
                    val current = restores.read(game)
                    if (current.isNotEmpty()) {
                        try {
                            restoreDoorstop(game, current)
                        } catch (e: Exception) {
                            if (
                                launchGame == null ||
                                    DoorstopLocks.key(game) == DoorstopLocks.key(launchGame)
                            )
                                failure = e
                            // A refused atomic edit may not even allow a comparison read.
                            // Do not claim mods were disabled without evidence of a change.
                            if (restores.read(game)["changed"] != "false")
                                failedFolders += listOf(game)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            failure = e
        }
        // Keys come from the recorded path, so a folder that is absent for a while keeps its key.
        fun noticeKey(game: Path) = game.toAbsolutePath().normalize().toString()
        val failedKeys = failedFolders.associateBy { noticeKey(it) }
        // A folder stays announced until its restore record is gone, also while it is absent.
        val pendingKeys =
            runCatching {
                    restores
                        .records()
                        .map { noticeKey(Path.of(it.getValue("path")).parent) }
                        .toSet()
                }
                .getOrElse { announcedRestoreFailures.toSet() }
        announcedRestoreFailures.retainAll(pendingKeys)
        val newlyFailed = failedKeys.filterKeys { it !in announcedRestoreFailures }.values
        if (newlyFailed.isNotEmpty()) {
            announcedRestoreFailures += failedKeys.keys
            val folders = if (newlyFailed.size == 1) "that game folder" else "those game folders"
            notice(
                "NNOMM could not turn the mods back on in doorstop_config.ini in ${newlyFailed.joinToString()}. Close programs that use $folders; NNOMM tries again."
            )
        }
        return failure
    }

    private fun restoreDoorstop(game: Path, restore: Map<String, String>) {
        val path = Path.of(restore.getValue("path"))
        if (Files.notExists(game)) return
        val bytes =
            restore["bytes"]?.let { java.util.Base64.getDecoder().decode(it) }
                ?: restore.getValue("text").toByteArray(DoorstopIni.charset)
        if (!Files.exists(path) || !readDoorstop(path).contentEquals(bytes)) {
            if (restore["changed"] == "false") restores.write(game, restore + ("changed" to "true"))
            writeDoorstop(path, bytes)
        }
        restores.clear(game)
    }

    override suspend fun stop(): Boolean {
        val stopped = stopGameProcesses(launched?.toHandle())
        mutex.withLock { if (!pending) flow.value = detect() || launched?.isAlive == true }
        return stopped
    }

    override suspend fun start(state: AppState, withoutMods: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(!detect() && !flow.value) { "Nuclear Option is already running." }
                val game = Path.of(requireNotNull(state.game.folder)).toRealPath()
                safe(game, "NuclearOption.exe")
                // Recover an interrupted vanilla launch before deriving this launch mode.
                tryRestoreDoorstop(game)?.let { throw DoorstopSettingsFailure(it) }
                val ini = safe(game, "doorstop_config.ini")
                try {
                    DoorstopLocks.locked(game) {
                        if (withoutMods && ini.exists()) {
                            try {
                                val bytes =
                                    try {
                                        readDoorstop(ini)
                                    } catch (e: java.io.IOException) {
                                        throw DoorstopSettingsFailure(e)
                                    }
                                val disabled =
                                    DoorstopIni.read(bytes).setEnabled(false, includeLegacy = true)
                                restores.write(
                                    game,
                                    mapOf(
                                        "path" to ini.toString(),
                                        "bytes" to
                                            java.util.Base64.getEncoder().encodeToString(bytes),
                                        "changed" to "false",
                                    ),
                                )
                                writeDoorstop(ini, disabled)
                                restores.write(
                                    game,
                                    restores.read(game) +
                                        ("changed" to (!bytes.contentEquals(disabled)).toString()),
                                )
                            } catch (e: Exception) {
                                if (e is DoorstopSettingsFailure) throw e
                                throw DoorstopSettingsFailure(e)
                            }
                        }
                    }
                    val args =
                        state.settings.value("launchArgs", state.profileId)?.content.orEmpty()
                    val uri =
                        if (args.isBlank()) "steam://rungameid/2168680"
                        else
                            "steam://run/2168680//" +
                                URLEncoder.encode(args, Charsets.UTF_8).replace("+", "%20")
                    val os = System.getProperty("os.name")
                    val command =
                        fakeCommand
                            ?: when {
                                os.startsWith("Windows", true) ->
                                    listOf("cmd.exe", "/d", "/c", "start", "", uri)
                                os.startsWith("Mac", true) -> listOf("open", uri)
                                else -> listOf("xdg-open", uri)
                            }
                    launched = ProcessBuilder(command).directory(game.toFile()).start()
                    flow.value = fakeCommand != null
                    pending = true
                    scope.launch {
                        var exited = false
                        var seen = fakeCommand != null
                        try {
                            val deadline = System.nanoTime() + 60_000_000_000L
                            while (!seen && System.nanoTime() < deadline) {
                                seen = detect()
                                if (!seen) delay(500)
                            }
                            if (seen) flow.value = true
                            if (seen)
                                while (
                                    detect() || (fakeCommand != null && launched?.isAlive == true)
                                ) delay(500)
                            exited = seen
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) {
                                if (
                                    withoutMods &&
                                        (exited || !seen) &&
                                        !detect() &&
                                        (fakeCommand == null || launched?.isAlive != true)
                                )
                                    tryRestoreDoorstop()
                                pending = false
                                flow.value = false
                                launched = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (withoutMods) tryRestoreDoorstop()?.let { e.addSuppressed(it) }
                    pending = false
                    flow.value = false
                    throw e
                }
                Unit
            }
        }
}
