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

@Serializable
internal data class ManagedMod(val id: String, val version: String, val loose: Boolean = false)

@Serializable
internal data class Placement(
    val entries: Map<String, ManagedMod> = emptyMap(),
    val profileId: String? = null,
    val ownSettings: Boolean = false,
    val generations: Map<String, String> = emptyMap(),
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
        Path.of(folder).toRealPath().also {
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
            if (!Path.of(folder).resolve("NuclearOption.exe").isRegularFile())
                return@withContext GameInfo(folder = folder, folderMissing = true)
            val game = root(folder)
            recoverTransactions(home.resolve("transactions"))
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
                recoverTransactions(home.resolve("transactions"))
                val found = mutableListOf<Pair<CatalogMod, InstalledMod>>()
                val entries = mutableMapOf<String, ManagedMod>()
                Files.createDirectories(home.resolve("staging"))
                for ((location, on) in listOf("plugins" to true, "disabledPlugins" to false)) {
                    val dir = safe(game, "BepInEx/$location")
                    if (!dir.exists()) continue
                    Files.list(dir).use { paths ->
                        for (path in paths.filter { !it.name.startsWith(".") }.sorted().toList()) {
                            try {
                                if (!on && path.name in entries) {
                                    notice(
                                        "Duplicate ${path.name} in disabledPlugins was left unchanged; the enabled copy takes priority."
                                    )
                                    continue
                                }
                                safe(dir, path.name)
                                val meta =
                                    if (
                                        path.isDirectory() &&
                                            path.resolve("meta.json").isRegularFile()
                                    )
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
                                val recognized =
                                    oldId?.let { id ->
                                        catalog().find { it.id == id }
                                            ?: CatalogMod(
                                                id,
                                                path.name,
                                                versions =
                                                    listOf(ModVersion(oldVersion ?: "takeover")),
                                            )
                                    }
                                val pair =
                                    if (recognized != null && oldVersion != null) {
                                        val v = component(oldVersion)
                                        if (!store.hasVersion(recognized.id, v))
                                            store.put(
                                                recognized.id,
                                                v,
                                                DownloadedFile(path.toString()),
                                            )
                                        else
                                            require(
                                                treeHash(store.version(recognized.id, v)) ==
                                                    treeHash(path)
                                            ) {
                                                "${path.name} has different files from the stored version. Both copies have been kept."
                                            }
                                        recognized to
                                            InstalledMod(
                                                recognized.id,
                                                versions = setOf(v),
                                                defaultVersion = v,
                                            )
                                    } else {
                                        var id =
                                            "local-" +
                                                path.name
                                                    .lowercase()
                                                    .replace(Regex("[^a-z0-9_-]"), "-") +
                                                "-" +
                                                Integer.toHexString(
                                                    path.name.lowercase().hashCode()
                                                )
                                        val v = "takeover"
                                        val raw =
                                            Files.createTempDirectory(
                                                home.resolve("staging"),
                                                "adopt-",
                                            )
                                        try {
                                            if (path.isDirectory()) copyTreeContents(path, raw)
                                            else copyTree(path, raw.resolve(path.name))
                                            // Different games can contain different local bytes
                                            // under the same name.
                                            if (
                                                store.hasVersion(id, v) &&
                                                    treeHash(store.version(id, v)) != treeHash(raw)
                                            ) {
                                                val suffix =
                                                    java.security.MessageDigest.getInstance(
                                                            "SHA-256"
                                                        )
                                                        .digest(treeHash(raw).toByteArray())
                                                        .joinToString("") { "%02x".format(it) }
                                                        .take(12)
                                                id += "-$suffix"
                                            }
                                            if (!store.hasVersion(id, v))
                                                store.put(id, v, DownloadedFile(raw.toString()))
                                        } finally {
                                            deleteTree(raw)
                                        }
                                        CatalogMod(
                                            id,
                                            path.name,
                                            versions = listOf(ModVersion(v)),
                                            localFile = path.name,
                                        ) to
                                            InstalledMod(
                                                id,
                                                ModSource.LOCAL,
                                                path.name,
                                                setOf(v),
                                                v,
                                            )
                                    }
                                if (found.any { it.first.id == pair.first.id }) {
                                    notice(
                                        "Duplicate ${pair.first.name} in $location was left unchanged; the enabled copy takes priority."
                                    )
                                    continue
                                }
                                val installed =
                                    pair.second.copy(
                                        profiles =
                                            mapOf(
                                                "default" to
                                                    ProfileMod(on, pair.second.defaultVersion)
                                            )
                                    )
                                found += pair.first to installed
                                entries[path.name] =
                                    ManagedMod(
                                        installed.id,
                                        installed.defaultVersion,
                                        path.isRegularFile(),
                                    )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                notice("${path.name} was left unchanged: ${e.message}")
                            }
                        }
                    }
                }
                // Adoption metadata stays outside the game; rerunning after a crash is safe.
                saveAdoption(
                    game,
                    Placement(
                        entries,
                        "default",
                        generations =
                            entries.mapValues { (_, m) ->
                                treeHash(store.version(m.id, m.version)) +
                                    store.generation(m.id, m.version)
                            },
                    ),
                )
                found
            }
        }

    private fun adoption(game: Path) =
        JsonFile(
            home.resolve("adoption-${Integer.toHexString(game.toString().hashCode())}.json"),
            Placement.serializer(),
            notice,
        )

    private fun saveAdoption(game: Path, value: Placement) {
        Files.createDirectories(home.resolve("staging"))
        val staged = Files.createTempFile(home.resolve("staging"), "adoption-", ".json")
        try {
            staged.writeText(json.encodeToString(value))
            replaceTree(adoption(game).path, staged, home.resolve("transactions"))
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    override suspend fun apply(state: AppState, profileId: String) =
        apply(gamePlan(state, profileId))

    override suspend fun apply(plan: GamePlan) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (running()) return@withLock
                val game = root(plan.folder ?: return@withLock)
                recoverTransactions(home.resolve("transactions"))
                val plugin = safe(game, "BepInEx/plugins")
                val recorded = record(game).read()
                val old = recorded ?: adoption(game).read() ?: Placement()
                // A fresh home cannot use an old ownership record as permission to remove files.
                if (
                    adoption(game).read() == null &&
                        !home.resolve("state.json").exists() &&
                        old.entries.values.any { it.id !in plan.knownIds }
                ) {
                    notice(
                        "Existing mods need takeover before changes can be applied. They were left unchanged."
                    )
                    return@withLock
                }
                val names =
                    adoption(game).read()?.entries.orEmpty().entries.associate {
                        it.value.id to it.key
                    } + old.entries.entries.associate { it.value.id to it.key }
                fun placement(id: String, version: String): String {
                    names[id]?.let {
                        return it
                    }
                    val parent =
                        catalog()
                            .find { it.id == id }
                            ?.versions
                            ?.find { it.version == version }
                            ?.extends
                            ?.id
                    return if (parent != null)
                        "${names[parent] ?: component(parent)}/addons/${component(id)}"
                    else component(id)
                }
                val wanted = linkedMapOf<String, ManagedMod>()
                plan.versions.forEach { (id, v) ->
                    val name = placement(id, v)
                    var alias = false
                    var parent = plugin
                    name.split('/').forEach { part ->
                        if (parent.isDirectory())
                            Files.list(parent).use { paths ->
                                if (
                                    paths.anyMatch { it.name != part && it.name.equals(part, true) }
                                )
                                    alias = true
                            }
                        parent = parent.resolve(part)
                    }
                    if (
                        alias ||
                            wanted.keys.any { it.equals(name, true) } ||
                            (safe(plugin, name).exists() && name !in old.entries)
                    ) {
                        notice(
                            "$id could not be placed because $name is already in use. Other mods can still change."
                        )
                    } else if (!store.version(id, v).isDirectory())
                        notice("Stored files are missing for $id.")
                    else
                        wanted[name] =
                            ManagedMod(
                                id,
                                v,
                                (old.entries[name] ?: adoption(game).read()?.entries?.get(name))
                                    ?.loose ?: false,
                            )
                }
                val generations =
                    wanted.mapValues { (_, m) ->
                        treeHash(store.version(m.id, m.version)) + store.generation(m.id, m.version)
                    }
                if (
                    recorded == null &&
                        wanted == old.entries &&
                        generations == old.generations &&
                        plan.profileId == "default" &&
                        !plan.ownSettings
                )
                    return@withLock
                if (
                    wanted == old.entries &&
                        generations == old.generations &&
                        old.profileId == plan.profileId &&
                        old.ownSettings == plan.ownSettings
                )
                    return@withLock
                Files.createDirectories(home.resolve("staging"))
                val stagedGame = Files.createTempDirectory(home.resolve("staging"), "apply-")
                val staged = stagedGame.resolve("BepInEx/plugins")
                try {
                    if (game.resolve("BepInEx").exists())
                        copyTree(game.resolve("BepInEx"), stagedGame.resolve("BepInEx"))
                    Files.createDirectories(staged)
                    // Capture changes per mod and profile; recovery never supplies a different
                    // profile's files.
                    for ((name, m) in old.entries) {
                        val dest = safe(staged, name)
                        if (!dest.exists()) continue
                        val payload = store.version(m.id, m.version)
                        val extras =
                            home.resolve(
                                "player-files/${component(old.profileId ?: "default")}/${component(m.id)}"
                            )
                        if (dest.isDirectory())
                            Files.walk(dest).use { files ->
                                files
                                    .filter { it.isRegularFile() }
                                    .toList()
                                    .forEach { file ->
                                        val relative =
                                            dest.relativize(file).toString().replace('\\', '/')
                                        val original = safe(payload, relative)
                                        val placedPath = "$name/$relative"
                                        val ownedByChild =
                                            old.entries.keys.any { child ->
                                                child != name &&
                                                    child.startsWith("$name/") &&
                                                    (placedPath == child ||
                                                        placedPath.startsWith("$child/"))
                                            }
                                        if (!original.isRegularFile() && !ownedByChild) {
                                            val saved = safe(extras, relative)
                                            replaceTree(saved, file, home.resolve("transactions"))
                                            notice(
                                                "Kept files for ${m.id} in $extras. They return with this profile."
                                            )
                                        }
                                    }
                            }
                        deleteTree(dest)
                    }
                    for ((name, m) in
                        wanted.toSortedMap(
                            compareBy<String> { it.count { c -> c == '/' } }.thenBy { it }
                        )) {
                        val dest = safe(staged, name)
                        val payload = store.version(m.id, m.version)
                        if (dest.exists()) deleteTree(dest)
                        // A taken-over loose file stays a loose file.
                        if (m.loose && payload.resolve(name).isRegularFile())
                            copyTree(payload.resolve(name), dest)
                        else copyTree(payload, dest)
                        val extras =
                            home.resolve(
                                "player-files/${component(plan.profileId)}/${component(m.id)}"
                            )
                        if (extras.exists() && dest.isDirectory())
                            Files.walk(extras).use { files ->
                                files
                                    .filter { it.isRegularFile() }
                                    .toList()
                                    .forEach { file ->
                                        val to =
                                            safe(
                                                dest,
                                                extras
                                                    .relativize(file)
                                                    .toString()
                                                    .replace('\\', '/'),
                                            )
                                        if (to.exists()) Files.delete(to)
                                        copyTree(file, to)
                                    }
                            }
                    }
                    swapConfig(stagedGame, old, plan.ownSettings, plan.profileId)
                    JsonFile(staged.resolve(".nnomm-state.json"), Placement.serializer())
                        .write(Placement(wanted, plan.profileId, plan.ownSettings, generations))
                    replaceTree(
                        game.resolve("BepInEx"),
                        stagedGame.resolve("BepInEx"),
                        home.resolve("transactions"),
                    )
                    saveAdoption(
                        game,
                        Placement(
                            (adoption(game).read()?.entries.orEmpty() + wanted),
                            plan.profileId,
                        ),
                    )
                } finally {
                    deleteTree(stagedGame)
                }
            }
        }

    private fun swapConfig(game: Path, old: Placement, ownSettings: Boolean, p: String) {
        if (
            (!old.ownSettings && !ownSettings) ||
                (old.profileId == p && old.ownSettings == ownSettings)
        )
            return
        val config = safe(game, "BepInEx/config")
        val previous =
            home.resolve(
                "profiles/${if (old.ownSettings) component(old.profileId!!) else "shared"}/config"
            )
        val next = home.resolve("profiles/${if (ownSettings) component(p) else "shared"}/config")
        if (config.exists()) replaceTree(previous, config, home.resolve("transactions"))
        if (next.exists()) replaceTree(config, next, home.resolve("transactions"))
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
                val removals =
                    record.read().orEmpty().map { relative -> safe(game, relative) to null }
                replaceTrees(removals, home.resolve("transactions"))
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
                val existingVersion = inspect(folder).loaderVersion
                require(!repair || existingVersion == null || existingVersion == "5.4.23.4") {
                    "This folder has BepInEx $existingVersion. Its files were left unchanged."
                }
                val v =
                    ModVersion(
                        "5.4.23.4",
                        hash = "f881201b79da03e513bf97cdf39607ffa7f9e0d31a519b1aeeca8eb60f8309e7",
                        downloadUrl =
                            "https://github.com/BepInEx/BepInEx/releases/download/v5.4.23.4/BepInEx_win_x64_5.4.23.4.zip",
                    )
                val transfer = downloader.download(CatalogMod("bepinex", "BepInEx"), v) {}
                val downloaded = Path.of(transfer.path)
                val staging = Files.createTempDirectory(home.resolve("staging"), "loader-")
                try {
                    unpack(downloaded, staging)
                    require(staging.resolve("BepInEx/core/BepInEx.dll").isRegularFile())
                    val replacements = mutableListOf<Pair<Path, Path?>>()
                    val owned = loaderRecord(game)
                    val ownedNames = owned.read().orEmpty().toMutableList()
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
                                if (!dest.exists()) ownedNames += relative
                                if (
                                    !relative.startsWith("BepInEx/core/") &&
                                        (!dest.exists() || repair) &&
                                        !(relative == "doorstop_config.ini" && dest.exists())
                                )
                                    replacements += dest to src
                            }
                    }
                    val coreTarget = safe(game, "BepInEx/core")
                    if (repair || !coreTarget.exists())
                        replacements += coreTarget to staging.resolve("BepInEx/core")
                    val ownedStaged = staging.resolve("ownership.json")
                    ownedStaged.writeText(json.encodeToString(ownedNames.distinct()))
                    replacements +=
                        home.resolve(
                            "loader-${Integer.toHexString(game.toString().hashCode())}.json"
                        ) to ownedStaged
                    replaceTrees(replacements, home.resolve("transactions"))
                } finally {
                    deleteTree(staging)
                    transfer.close()
                }
            }
        }
}
