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
    val directories: Set<String> = emptySet(),
    val applied: Boolean = false,
    val gameRoot: String? = null,
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
    private val problemFlow =
        kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    override val applyProblems: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = problemFlow

    private fun problem(id: String, text: String) {
        problemFlow.value = problemFlow.value + (id to text)
        notice(text)
    }

    override suspend fun recoverPending() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!running()) {
                    recoverTransactions(home.resolve("transactions"))
                    recoverTransactions(home.resolve("game-transactions"))
                }
            }
        }

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
                            entries.mapValues { (_, m) -> "|" + store.generation(m.id, m.version) },
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

    private fun adopted(game: Path): Placement? =
        adoption(game).read()?.takeIf { it.gameRoot == game.toString() }

    private fun saveAdoption(game: Path, value: Placement) {
        Files.createDirectories(home.resolve("staging"))
        val staged = Files.createTempFile(home.resolve("staging"), "adoption-", ".json")
        try {
            staged.writeText(json.encodeToString(value.copy(gameRoot = game.toString())))
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
                recoverTransactions(home.resolve("game-transactions"))
                recoverTransactions(home.resolve("transactions"))
                val plugin = safe(game, "BepInEx/plugins")
                val recorded = record(game).read()
                val old = recorded ?: adopted(game) ?: Placement()
                // A fresh home cannot use an old ownership record as permission to remove files.
                if (adopted(game) == null && recorded != null) {
                    notice(
                        "Existing mods need takeover before changes can be applied. They were left unchanged."
                    )
                    return@withLock
                }
                if (recorded == null && adopted(game) != null && old.applied) {
                    notice("Ownership is missing. Take over existing mods before applying changes.")
                    return@withLock
                }
                if (adopted(game) == null) saveAdoption(game, old)
                val names =
                    adopted(game)?.entries.orEmpty().entries.associate { it.value.id to it.key } +
                        old.entries.entries.associate { it.value.id to it.key }
                fun parent(id: String, v: String) =
                    catalog().find { it.id == id }?.versions?.find { it.version == v }?.extends?.id
                fun placement(id: String, v: String): String =
                    names[id]
                        ?: parent(id, v)?.let {
                            "${names[it] ?: component(it)}/addons/${component(id)}"
                        }
                        ?: component(id)
                val wanted =
                    plan.versions
                        .map { (id, v) ->
                            placement(id, v) to
                                ManagedMod(
                                    id,
                                    v,
                                    (old.entries.values.find { it.id == id }
                                            ?: adopted(game)?.entries?.values?.find { it.id == id })
                                        ?.loose ?: false,
                                )
                        }
                        .toMap()
                val result = old.entries.toMutableMap()
                val directories = old.directories.toMutableSet()
                val generations = old.generations.toMutableMap()
                val directChanges =
                    (old.entries.keys + wanted.keys)
                        .filter { name ->
                            val m = wanted[name]
                            m != old.entries[name] ||
                                (m != null && !store.version(m.id, m.version).isDirectory()) ||
                                (m != null &&
                                    store.generation(m.id, m.version) !=
                                        old.generations[name]?.substringAfterLast('|'))
                        }
                        .toSet()
                val changed =
                    (directChanges +
                            (old.entries.keys + wanted.keys).filter { name ->
                                directChanges.any { name.startsWith("$it/") }
                            })
                        .toSet()
                fun extras(m: ManagedMod, profile: String) =
                    home.resolve("player-files/${component(profile)}/${component(m.id)}")
                // Snapshot deepest entries first, before a parent can move its children.
                val captured = mutableMapOf<String, Path>()
                for (name in changed.sortedByDescending { it.count { c -> c == '/' } }) {
                    val m = old.entries[name] ?: continue
                    try {
                        val dest = safe(plugin, name)
                        if (!dest.exists()) {
                            captured[name] =
                                home.resolve("staging/absent-${java.util.UUID.randomUUID()}")
                            continue
                        }
                        val payload = store.version(m.id, m.version)
                        val kept = extras(m, old.profileId ?: "default")
                        val snapshot = home.resolve("staging/player-${java.util.UUID.randomUUID()}")
                        Files.createDirectories(snapshot)
                        if (dest.isDirectory())
                            Files.walk(dest).use { files ->
                                files
                                    .filter { it.isRegularFile() }
                                    .forEach { file ->
                                        val rel =
                                            dest.relativize(file).toString().replace('\\', '/')
                                        if (
                                            old.entries.keys.none {
                                                it != name &&
                                                    it.startsWith("$name/") &&
                                                    ("$name/$rel" == it ||
                                                        "$name/$rel".startsWith("$it/"))
                                            }
                                        ) {
                                            val originalHash = store.hashes(m.id, m.version)[rel]
                                            if (
                                                originalHash == null || originalHash != digest(file)
                                            )
                                                copyTree(file, safe(snapshot, rel))
                                        }
                                    }
                            }
                        // The saved set mirrors the last removal, including player deletions.
                        replaceTree(kept, snapshot, home.resolve("transactions"))
                        captured[name] = snapshot
                        if (Files.walk(snapshot).use { it.anyMatch { p -> p.isRegularFile() } })
                            notice(
                                "Kept changed files for ${m.id} in $kept. They return with this profile."
                            )
                    } catch (e: Exception) {
                        problem(m.id, "${m.id} was left unchanged: ${e.message}")
                    }
                }
                // Removals first, deepest first. Missing wanted payloads never authorize removal.
                for (name in changed.sortedByDescending { it.count { c -> c == '/' } }) {
                    if (name in wanted) continue
                    val m = old.entries[name] ?: continue
                    try {
                        require(
                            name in captured &&
                                old.entries.keys
                                    .filter { it.startsWith("$name/") }
                                    .all { it in captured }
                        ) {
                            "Could not keep player files"
                        }
                        replaceTree(
                            safe(plugin, name),
                            null,
                            home.resolve("game-transactions"),
                            keepPrevious = false,
                            afterCommit =
                                record(game).path to
                                    json.encodeToString(
                                        Placement(
                                            result - name,
                                            plan.profileId,
                                            plan.ownSettings,
                                            generations - name,
                                            directories,
                                            applied = true,
                                        )
                                    ),
                        )
                        result.remove(name)
                        generations.remove(name)
                    } catch (e: Exception) {
                        problem(m.id, "${m.id} was left unchanged: ${e.message}")
                    }
                }
                for (name in
                    changed.filter { it in wanted }.sortedBy { it.count { c -> c == '/' } }) {
                    val m = wanted.getValue(name)
                    try {
                        val payload = store.version(m.id, m.version)
                        require(payload.isDirectory()) { "Stored files are missing" }
                        val parentId = parent(m.id, m.version)
                        require(parentId == null || result.values.any { it.id == parentId }) {
                            "Its parent must be placed first"
                        }
                        val dest = safe(plugin, name)
                        require(dest.toString().length < 240) { "Game path is too long" }
                        var at = plugin
                        name.split('/').forEach { part ->
                            if (at.isDirectory())
                                Files.list(at).use { paths ->
                                    require(
                                        paths.noneMatch {
                                            it.name != part && it.name.equals(part, true)
                                        }
                                    ) {
                                        "$name is already in use"
                                    }
                                }
                            at = at.resolve(part)
                        }
                        require(!dest.exists() || name in old.entries) { "$name is already in use" }
                        require(
                            name !in old.entries ||
                                (name in captured &&
                                    old.entries.keys
                                        .filter { it.startsWith("$name/") }
                                        .all { it in captured })
                        ) {
                            "Could not keep player files"
                        }
                        Files.createDirectories(home.resolve("staging"))
                        val stage = Files.createTempDirectory(home.resolve("staging"), "entry-")
                        try {
                            val next = stage.resolve("entry")
                            if (m.loose && payload.resolve(Path.of(name).fileName).isRegularFile())
                                copyTree(payload.resolve(Path.of(name).fileName), next)
                            else copyTree(payload, next)
                            val kept = extras(m, plan.profileId)
                            if (next.isDirectory() && kept.isDirectory())
                                Files.walk(kept).use { files ->
                                    files
                                        .filter { it.isRegularFile() }
                                        .forEach { file ->
                                            val to =
                                                safe(
                                                    next,
                                                    kept
                                                        .relativize(file)
                                                        .toString()
                                                        .replace('\\', '/'),
                                                )
                                            if (!to.exists()) copyTree(file, to)
                                        }
                                }
                            // Validate actual game paths, not long temporary path prefixes.
                            if (next.isDirectory())
                                Files.walk(next).use { files ->
                                    files
                                        .filter { it.isRegularFile() }
                                        .forEach {
                                            require(
                                                safe(
                                                        dest,
                                                        next
                                                            .relativize(it)
                                                            .toString()
                                                            .replace('\\', '/'),
                                                    )
                                                    .toString()
                                                    .length < 240
                                            ) {
                                                "Game path is too long"
                                            }
                                        }
                                }
                            var ancestor = dest.parent
                            while (ancestor != plugin && ancestor.startsWith(plugin)) {
                                if (!ancestor.exists())
                                    directories +=
                                        plugin.relativize(ancestor).toString().replace('\\', '/')
                                ancestor = ancestor.parent
                            }
                            replaceTree(
                                dest,
                                next,
                                home.resolve("game-transactions"),
                                keepPrevious = false,
                                afterCommit =
                                    record(game).path to
                                        json.encodeToString(
                                            Placement(
                                                result + (name to m),
                                                plan.profileId,
                                                plan.ownSettings,
                                                generations +
                                                    (name to
                                                        ("|" + store.generation(m.id, m.version))),
                                                directories,
                                                applied = true,
                                            )
                                        ),
                            )
                            problemFlow.value = problemFlow.value - m.id
                            result[name] = m
                            generations[name] = "|" + store.generation(m.id, m.version)
                        } finally {
                            deleteTree(stage)
                        }
                    } catch (e: Exception) {
                        problem(m.id, "${m.id} was left unchanged: ${e.message}")
                    }
                }
                captured.values.forEach(::deleteTree)
                val recovery = home.resolve("recovery")
                if (recovery.isDirectory()) {
                    val copies =
                        Files.list(recovery)
                            .use {
                                it.filter { p -> p.isDirectory() }
                                    .toList()
                                    .sortedBy { p -> p.getLastModifiedTime().toMillis() }
                            }
                            .toMutableList()
                    fun bytes() =
                        copies.sumOf { p ->
                            Files.walk(p).use { files ->
                                files
                                    .filter { it.isRegularFile() }
                                    .mapToLong { it.fileSize() }
                                    .sum()
                            }
                        }
                    while (copies.size > 100 || bytes() > 256L * 1024 * 1024) {
                        val oldest = copies.removeAt(0)
                        notice(
                            "Recovery storage is full. Removing the oldest copy at $oldest. Current player files remain in player-files."
                        )
                        deleteTree(oldest)
                        Files.deleteIfExists(recovery.resolve("${oldest.name}.json"))
                    }
                }
                swapConfig(game, old, plan.ownSettings, plan.profileId)
                directories
                    .sortedByDescending { it.count { c -> c == '/' } }
                    .forEach { name ->
                        val dir = safe(plugin, name)
                        if (dir.isDirectory() && Files.list(dir).use { !it.findAny().isPresent }) {
                            Files.delete(dir)
                            directories.remove(name)
                        }
                    }
                val updated =
                    Placement(
                        result,
                        plan.profileId,
                        plan.ownSettings,
                        generations,
                        directories,
                        applied = true,
                    )
                if (updated != old) {
                    record(game).write(updated)
                    saveAdoption(
                        game,
                        updated.copy(entries = adopted(game)?.entries.orEmpty() + updated.entries),
                    )
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
                if (repair && existingVersion != "5.4.23.4") {
                    val message =
                        if (existingVersion == null)
                            "The installed BepInEx version could not be identified. Its files were left unchanged."
                        else
                            "This folder has BepInEx $existingVersion. Its files were left unchanged."
                    notice(message)
                    error(message)
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
