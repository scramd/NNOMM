package nnomm.core.io.real

import java.net.URLEncoder
import java.nio.file.*
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.builtins.*
import nnomm.core.io.*
import nnomm.core.model.*

internal fun gameProcessRunning(): Boolean =
    ProcessHandle.allProcesses().use { all ->
        all.anyMatch { p ->
            p.info().command().orElse("").substringAfterLast('/').substringAfterLast('\\').let {
                it.equals("NuclearOption.exe", true) ||
                    it == "NuclearOption" ||
                    p.info().arguments().orElse(emptyArray()).any { arg ->
                        arg.substringAfterLast('/')
                            .substringAfterLast('\\')
                            .equals("NuclearOption.exe", true)
                    }
            }
        }
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
) : GameLauncher {
    private val flow = MutableStateFlow(detect())
    override val running = flow.asStateFlow()
    private val mutex = Mutex()
    private var launched: Process? = null
    private var pending = false

    init {
        if (!detect()) restoreDoorstop()
        scope.launch {
            while (isActive) {
                if (!pending) {
                    val now = detect() || launched?.isAlive == true
                    if (flow.value && !now) mutex.withLock { restoreDoorstop() }
                    flow.value = now
                }
                delay(3000)
            }
        }
    }

    private fun restoreDoorstop() {
        val saved =
            JsonFile(
                home.resolve("doorstop-restore.json"),
                MapSerializer(String.serializer(), String.serializer()),
            )
        val restore = saved.read() ?: return
        if (restore.isEmpty()) return
        val path = Path.of(restore.getValue("path"))
        atomic(path, restore.getValue("text"), DoorstopIni.charset)
        saved.write(emptyMap())
    }

    override suspend fun start(state: AppState, withoutMods: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(!detect() && !flow.value) { "Nuclear Option is already running." }
                val game = Path.of(requireNotNull(state.game.folder))
                safe(game, "NuclearOption.exe")
                // Recover an interrupted vanilla launch before deriving this launch mode.
                val restoreFile = home.resolve("doorstop-restore.json")
                if (restoreFile.exists()) {
                    val saved =
                        JsonFile(
                                restoreFile,
                                MapSerializer(String.serializer(), String.serializer()),
                            )
                            .read()
                    if (!saved.isNullOrEmpty()) restoreDoorstop()
                }
                val ini = safe(game, "doorstop_config.ini")
                if (withoutMods && ini.exists()) {
                    val text =
                        try {
                            String(Files.readAllBytes(ini), DoorstopIni.charset)
                        } catch (e: java.io.IOException) {
                            throw DoorstopSettingsFailure(e)
                        }
                    if (!DoorstopIni.hasEnabled(text)) throw DoorstopSettingsFailure()
                    JsonFile(restoreFile, MapSerializer(String.serializer(), String.serializer()))
                        .write(mapOf("path" to ini.toString(), "text" to text))
                    atomic(ini, DoorstopIni.setEnabled(text, false), DoorstopIni.charset)
                }
                try {
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
                                    restoreDoorstop()
                                pending = false
                                flow.value = false
                                launched = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (withoutMods) restoreDoorstop()
                    pending = false
                    flow.value = false
                    throw e
                }
                Unit
            }
        }
}
