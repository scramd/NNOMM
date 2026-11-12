package nnomm.core.io.real

import java.nio.file.*
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import nnomm.core.io.*
import nnomm.core.model.*

@Serializable internal data class ManagedMod(val id: String, val version: String)

@Serializable
internal data class Placement(
    val entries: Map<String, ManagedMod> = emptyMap(),
    val profileId: String? = null,
    val ownSettings: Boolean = false,
)

class RealGameFolder(
    private val home: Path,
    private val store: RealModStore,
    private val downloader: Downloader,
    private val catalog: () -> List<CatalogMod> = { emptyList() },
    private val running: () -> Boolean = { gameProcessRunning() },
    private val notice: (String) -> Unit = {},
) : GameFolder {
    private val mutex = Mutex()

    private fun root(folder: String): Path =
        Path.of(folder).toAbsolutePath().normalize().also {
            safe(it, "NuclearOption.exe")
            require(it.resolve("NuclearOption.exe").isRegularFile()) {
                "NuclearOption.exe was not found in $folder"
            }
        }

    private fun record(game: Path) =
        JsonFile(safe(game, "BepInEx/plugins/.nnomm-state.json"), Placement.serializer(), notice)

    override suspend fun find(settings: SettingsValues) = findGameFolder(settings)

    override suspend fun inspect(folder: String): GameInfo =
        withContext(Dispatchers.IO) {
            val game = root(folder)
            val dll = safe(game, "BepInEx/core/BepInEx.dll")
            val present = dll.isRegularFile()
            val loaderVersion =
                if (present)
                    runCatching {
                            val bytes = dll.readBytes()
                            val text = String(bytes, Charsets.UTF_16LE)
                            Regex("5\\.4\\.\\d+\\.\\d+").find(text)?.value
                        }
                        .getOrNull()
                else null
            // Only accept an explicitly named bundleVersion; guessing arbitrary numeric strings
            // confuses Unity's engine version with the game version.
            val asset = safe(game, "NuclearOption_Data/globalgamemanagers")
            val version =
                if (asset.isRegularFile())
                    runCatching {
                            Regex("bundleVersion[\\x00\\s:=]+(\\d+\\.\\d+(?:\\.\\d+){0,2})")
                                .find(String(asset.readBytes(), Charsets.ISO_8859_1))
                                ?.groupValues
                                ?.get(1)
                        }
                        .getOrNull()
                else null
            GameInfo(game.toString(), version ?: "unknown", present, loaderVersion)
        }

    override suspend fun takeOver(folder: String): List<Pair<CatalogMod, InstalledMod>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(!running()) { "Close Nuclear Option before taking over mods." }
                val game = root(folder)
                val record = record(game)
                require(record.read() == null) { "This game folder is already managed." }
                val found = mutableListOf<Pair<CatalogMod, InstalledMod>>()
                val entries = mutableMapOf<String, ManagedMod>()
                for ((location, on) in listOf("plugins" to true, "disabledPlugins" to false)) {
                    val dir = safe(game, "BepInEx/$location")
                    if (!dir.exists()) continue
                    Files.list(dir).use { paths ->
                        for (path in paths.filter { !it.name.startsWith(".") }.sorted().toList()) {
                            safe(dir, path.name)
                            val meta =
                                if (path.isDirectory() && path.resolve("meta.json").isRegularFile())
                                    runCatching {
                                            json
                                                .parseToJsonElement(
                                                    path.resolve("meta.json").readText()
                                                )
                                                .jsonObject
                                        }
                                        .getOrNull()
                                else null
                            val oldId = meta?.get("id")?.jsonPrimitive?.content
                            val oldVersion =
                                meta
                                    ?.get("artifact")
                                    ?.jsonObject
                                    ?.get("version")
                                    ?.jsonPrimitive
                                    ?.content
                            val recognized = oldId?.let { id -> catalog().find { it.id == id } }
                            val pair =
                                if (recognized != null && oldVersion != null) {
                                    val v = component(oldVersion)
                                    store.put(recognized.id, v, DownloadedFile(path.toString()))
                                    recognized to
                                        InstalledMod(
                                            recognized.id,
                                            versions = setOf(v),
                                            defaultVersion = v,
                                        )
                                } else if (path.isRegularFile()) store.addLocal(path.toString())
                                else {
                                    val id =
                                        "local-" +
                                            path.name
                                                .lowercase()
                                                .replace(Regex("[^a-z0-9_-]"), "-") +
                                            "-" +
                                            Integer.toHexString(path.toString().hashCode())
                                    val v = "takeover"
                                    store.put(id, v, DownloadedFile(path.toString()))
                                    CatalogMod(
                                        id,
                                        path.name,
                                        versions = listOf(ModVersion(v)),
                                        localFile = path.name,
                                    ) to InstalledMod(id, ModSource.LOCAL, path.name, setOf(v), v)
                                }
                            require(found.none { it.first.id == pair.first.id }) {
                                "Duplicate mod ${pair.first.name} in $location. Both copies are kept; resolve the duplicate before takeover."
                            }
                            val installed =
                                pair.second.copy(
                                    profiles =
                                        mapOf(
                                            "default" to ProfileMod(on, pair.second.defaultVersion)
                                        )
                                )
                            found += pair.first to installed
                            if (on)
                                entries[path.name] =
                                    ManagedMod(installed.id, installed.defaultVersion)
                        }
                    }
                }
                record.write(Placement(entries, "default"))
                found
            }
        }

    override suspend fun apply(state: AppState, profileId: String) =
        apply(gamePlan(state, profileId))

    override suspend fun apply(plan: GamePlan) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (running()) return@withLock
                val folder = plan.folder ?: return@withLock
                val game = root(folder)
                val plugin = safe(game, "BepInEx/plugins")
                val record = record(game)
                var old = record.read() ?: Placement()
                val profileId = plan.profileId
                val wanted = plan.versions.mapValues { (id, version) -> ManagedMod(id, version) }
                // Preflight all destinations and payloads before removing anything.
                wanted.forEach { (name, m) ->
                    require(store.version(m.id, m.version).isDirectory()) {
                        "Stored files are missing for ${m.id}"
                    }
                    val dest = safe(plugin, name)
                    require(!dest.exists() || name in old.entries) {
                        "$name is not managed by NNOMM. It was left unchanged."
                    }
                }
                for ((name, m) in old.entries) {
                    if (wanted[name] == m && safe(plugin, name).exists()) continue
                    val dest = safe(plugin, name)
                    if (dest.exists()) {
                        val recovery =
                            home.resolve("recovery/${System.nanoTime()}/${component(name)}")
                        copyTree(
                            dest,
                            recovery,
                        ) // Captures edits and extra files, even if the original store version
                        // still exists.
                        deleteTree(dest)
                    }
                    old = old.copy(entries = old.entries - name)
                    record.write(old)
                }
                Files.createDirectories(plugin)
                for ((name, m) in wanted) if (old.entries[name] != m) {
                    val dest = safe(plugin, name)
                    val staged = Files.createTempDirectory(plugin, ".nnomm-apply-")
                    try {
                        copyTreeContents(store.version(m.id, m.version), staged, link = true)
                        // Journal ownership before promotion so a crash never leaves an unrecorded
                        // payload.
                        old = old.copy(entries = old.entries + (name to m))
                        record.write(old)
                        Files.move(staged, dest)
                    } finally {
                        deleteTree(staged)
                    }
                }
                swapConfig(game, old, plan.ownSettings, profileId)
                record.write(old.copy(profileId = profileId, ownSettings = plan.ownSettings))
            }
        }

    private fun swapConfig(game: Path, old: Placement, ownSettings: Boolean, p: String) {
        if (old.profileId == p && old.ownSettings == ownSettings) return
        val config = safe(game, "BepInEx/config")
        val previous =
            if (old.ownSettings) home.resolve("profiles/${component(old.profileId!!)}/config")
            else home.resolve("profiles/shared/config")
        if (config.exists()) {
            if (previous.exists()) {
                val recovery = home.resolve("recovery/config-${System.nanoTime()}")
                copyTree(previous, recovery)
                deleteTree(previous)
            }
            copyTree(config, previous)
        }
        val next =
            if (ownSettings) home.resolve("profiles/${component(p)}/config")
            else home.resolve("profiles/shared/config")
        if (next.exists()) {
            if (config.exists()) {
                copyTree(config, home.resolve("recovery/config-${System.nanoTime()}"))
                deleteTree(config)
            }
            copyTree(next, config)
        }
    }

    private fun loaderRecord(game: Path) =
        JsonFile(
            home.resolve("loader-${Integer.toHexString(game.toString().hashCode())}.json"),
            kotlinx.serialization.builtins.ListSerializer(String.serializer()),
            notice,
        )

    override suspend fun removeLoader(folder: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(!running()) { "Close Nuclear Option before removing the mod loader." }
                val game = root(folder)
                val record = loaderRecord(game)
                for (relative in record.read().orEmpty()) {
                    val file = safe(game, relative)
                    if (file.exists()) {
                        copyTree(
                            file,
                            home.resolve("recovery/loader-remove-${System.nanoTime()}/$relative"),
                        )
                        deleteTree(file)
                    }
                }
                record.write(emptyList())
            }
        }

    override suspend fun installLoader(folder: String) {
        if (!inspect(folder).loaderPresent) loader(folder, false)
    }

    override suspend fun repairLoader(folder: String) = loader(folder, true)

    private suspend fun loader(folder: String, repair: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(!running()) { "Close Nuclear Option before changing the mod loader." }
                val game = root(folder)
                val v =
                    ModVersion(
                        "5.4.23.4",
                        downloadUrl =
                            "https://github.com/BepInEx/BepInEx/releases/download/v5.4.23.4/BepInEx_win_x64_5.4.23.4.zip",
                    )
                val downloaded =
                    Path.of(downloader.download(CatalogMod("bepinex", "BepInEx"), v) {}.path)
                val staging = Files.createTempDirectory(home.resolve("staging"), "loader-")
                try {
                    unpack(downloaded, staging)
                    require(staging.resolve("BepInEx/core/BepInEx.dll").isRegularFile())
                    Files.walk(staging).use { paths ->
                        paths
                            .filter { it.isRegularFile() }
                            .forEach { src ->
                                val relative = staging.relativize(src).toString().replace('\\', '/')
                                require(
                                    !relative.startsWith("BepInEx/plugins/") &&
                                        !relative.startsWith("BepInEx/config/")
                                )
                                val dest = safe(game, relative)
                                if (!dest.exists()) {
                                    val owned = loaderRecord(game)
                                    owned.write((owned.read().orEmpty() + relative).distinct())
                                }
                                if (!dest.exists() || repair) {
                                    if (dest.exists()) {
                                        val saved =
                                            home.resolve(
                                                "recovery/loader-${System.nanoTime()}/$relative"
                                            )
                                        copyTree(dest, saved)
                                        Files.delete(dest)
                                    }
                                    Files.createDirectories(dest.parent)
                                    Files.copy(src, dest)
                                }
                            }
                    }
                } finally {
                    deleteTree(staging)
                    deleteTree(downloaded.parent)
                }
            }
        }
}
