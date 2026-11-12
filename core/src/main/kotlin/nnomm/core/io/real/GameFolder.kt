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
    val unplaced: Map<String, ManagedMod> = emptyMap(),
    val entryProfiles: Map<String, String> = emptyMap(),
    val epoch: String? = null,
)

@Serializable
internal data class PendingLoaderOwnership(
    val gameRoot: String,
    val hashes: Map<String, String>,
    // Verify rewritten player files too, but never turn a repair into ownership.
    val unowned: Set<String> = emptySet(),
    val archiveFiles: Set<String> = emptySet(),
    val operationId: String? = null,
)

@Serializable
internal data class PendingLoaderRemoval(
    val gameRoot: String,
    val names: Set<String>,
    val absent: Set<String> = emptySet(),
    val operationId: String? = null,
)

@Serializable
internal data class LoaderOwnership(
    val gameRoot: String,
    val hashes: Map<String, String>,
    val relinquished: Set<String> = emptySet(),
)

class RealGameFolder(
    private val home: Path,
    private val store: RealModStore,
    private val downloader: Downloader,
    private val catalog: () -> List<CatalogMod> = { emptyList() },
    private val running: () -> Boolean = { gameProcessRunning() },
    private val notice: (String) -> Unit = {},
    private val backgroundScope: CoroutineScope? = null,
    private val preferredVersions: () -> Map<String, String> = { emptyMap() },
    private val backgroundReady: () -> Boolean = { true },
    private val readDoorstop: (Path) -> ByteArray = { Files.readAllBytes(it) },
    private val readLoaderHash: (Path) -> String = ::digest,
) : GameFolder {
    private val mutex = Mutex()
    private val learningSlot = Semaphore(1)
    private val learning = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun displayName(id: String) = catalog().find { it.id == id }?.name ?: "this mod"

    override var applySucceeded = false
        private set

    private val conflictsFlow =
        kotlinx.coroutines.flow.MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    override val placementConflicts = conflictsFlow
    private val problemFlow =
        kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    override val applyProblems: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = problemFlow

    private suspend fun <T> guarded(block: suspend () -> T): T =
        mutex.withLock { HomeLocks.operation(home, block) }

    private fun problem(id: String, text: String) {
        problemFlow.value = problemFlow.value + (id to text)
        notice(text)
    }

    override suspend fun setConsole(folder: String, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (running()) return@withLock
                val game = root(folder)
                val config = safe(game, "BepInEx/config/BepInEx.cfg")
                Files.createDirectories(config.parent)
                val text = if (config.exists()) config.readText() else ""
                val updated = consoleConfig(text, enabled)
                if (updated != text) {
                    if (config.exists()) {
                        val backup = home.resolve("console-backups/${System.nanoTime()}.cfg")
                        Files.createDirectories(backup.parent)
                        Files.copy(config, backup)
                    }
                    val temporary = Files.createTempFile(config.parent, ".console-", ".tmp")
                    try {
                        temporary.writeText(updated)
                        Files.move(temporary, config, StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                }
            }
        }

    override val recoveryPending: Boolean
        get() =
            pendingTransactions(home.resolve("transactions")) ||
                pendingTransactions(home.resolve("game-transactions")) ||
                pendingLoaderRecords().isNotEmpty() ||
                pendingRemovalRecords().isNotEmpty()

    override suspend fun recoverPending() =
        withContext(Dispatchers.IO) {
            guarded {
                if (!running()) {
                    recoverTransactions(home.resolve("transactions"), notice = notice)
                    recoverTransactions(home.resolve("game-transactions"), notice = notice)
                    if (home.isDirectory())
                        home.listDirectoryEntries("loader-*.json.hashes").forEach { path ->
                            val ownership =
                                JsonFile(path, LoaderOwnership.serializer(), notice).read()
                                    ?: return@forEach
                            val game =
                                runCatching { root(ownership.gameRoot) }.getOrNull()
                                    ?: return@forEach
                            runCatching {
                                checkLoaderRecovery(game)
                                writeOwnership(game, verifiedOwnership(game))
                            }
                        }
                    pendingRemovalRecords().forEach { path ->
                        try {
                            finishLoaderRemoval(path)
                        } catch (e: Exception) {
                            notice("Mod loader removal recovery is pending. Its record is kept.")
                        }
                    }
                    pendingLoaderRecords().forEach { path ->
                        try {
                            val pending =
                                json.decodeFromString<PendingLoaderOwnership>(path.readText())
                            val game = root(pending.gameRoot)
                            check(pendingLoaderRecord(game) == path) {
                                "Loader recovery folder mismatch"
                            }
                            finishLoaderOwnership(game)
                            if (path.exists())
                                notice(
                                    "Mod loader ownership verification is pending. Close programs using the game folder, then Retry."
                                )
                        } catch (e: Exception) {
                            notice(
                                "Mod loader ownership recovery is pending. Restore access to the game folder, then Retry."
                            )
                        }
                    }
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
            val archive = loaderArchive(game).read().orEmpty()
            val hashes = ownedHashes(game)
            val complete = hashes.isEmpty() || archive.all { safe(game, it).isRegularFile() }
            val resumeAllowed =
                hashes.isNotEmpty() &&
                    loaderVersion == "5.4.23.4" &&
                    !hasPresentRelinquished(game) &&
                    hashes.all { (name, hash) ->
                        !safe(game, name).exists() ||
                            runCatching { readLoaderHash(safe(game, name)) == hash }
                                .getOrDefault(false)
                    }
            GameInfo(
                game.toString(),
                version ?: "unknown",
                present,
                loaderVersion,
                loaderComplete = complete,
                loaderResumeAllowed = resumeAllowed,
            )
        }

    override suspend fun takeOver(folder: String): List<Pair<CatalogMod, InstalledMod>> =
        withContext(Dispatchers.IO) {
            guarded {
                require(!running()) { "Close Nuclear Option before taking over mods." }
                val game = root(folder)
                recoverTransactions(home.resolve("transactions"), notice = notice)
                recoverTransactions(home.resolve("game-transactions"), notice = notice)
                check(!pendingTransactions(home.resolve("game-transactions"))) {
                    "Some game files still need recovery. Their journals and files have been kept."
                }
                val record = record(game)
                val previousAdoption = adopted(game)
                val staleRecord = record.read()
                val trusted =
                    staleRecord?.takeIf {
                        previousAdoption?.applied == true && it.epoch == previousAdoption.epoch
                    }
                val owned = trusted?.entries.orEmpty()
                val matchVersion = store.versionMatcher(preferredVersions())
                val found = mutableListOf<Pair<CatalogMod, InstalledMod>>()
                val entries = mutableMapOf<String, ManagedMod>()
                val unplaced = mutableMapOf<String, ManagedMod>()
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
                                if (on && path.name in owned) continue
                                rejectLinks(path)
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
                                val stale = staleRecord?.entries?.get(path.name)
                                val match =
                                    matchVersion(
                                        path,
                                        path.isRegularFile(),
                                        stale?.let { it.id to it.version },
                                    )
                                val oldId = match?.first ?: meta?.get("id")?.jsonPrimitive?.content
                                val oldVersion =
                                    match?.second
                                        ?: meta
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
                                                localFile =
                                                    if (id.startsWith("local-")) path.name else null,
                                            )
                                    }
                                val pair =
                                    if (recognized != null && oldVersion != null) {
                                        val v = component(oldVersion)
                                        if (!store.hasVersion(recognized.id, v))
                                            store.put(
                                                recognized.id,
                                                v,
                                                DownloadedFile(
                                                    path.toString(),
                                                    PayloadOrigin.TAKEOVER,
                                                ),
                                            )
                                        else
                                            require(
                                                treeHash(
                                                    if (path.isRegularFile())
                                                        store
                                                            .version(recognized.id, v)
                                                            .resolve(path.name)
                                                    else store.version(recognized.id, v)
                                                ) == treeHash(path)
                                            ) {
                                                "Files for ${recognized.name} differ from the stored version. The game files and stored files are unchanged."
                                            }
                                        recognized to
                                            InstalledMod(
                                                recognized.id,
                                                source =
                                                    if (recognized.localFile != null)
                                                        ModSource.LOCAL
                                                    else ModSource.REGISTRY,
                                                fileName = recognized.localFile,
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
                                                store.put(
                                                    id,
                                                    v,
                                                    DownloadedFile(
                                                        raw.toString(),
                                                        PayloadOrigin.TAKEOVER,
                                                    ),
                                                )
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
                                (if (on) entries else unplaced)[path.name] =
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
                        owned + entries,
                        trusted?.profileId ?: "default",
                        ownSettings = trusted?.ownSettings ?: false,
                        directories = trusted?.directories.orEmpty(),
                        entryProfiles =
                            trusted?.entryProfiles.orEmpty() +
                                entries.keys.associateWith { "default" },
                        unplaced = previousAdoption?.unplaced.orEmpty() + unplaced,
                        epoch = java.util.UUID.randomUUID().toString(),
                        generations =
                            trusted?.generations.orEmpty() +
                                entries.mapValues { (_, m) ->
                                    "|" + store.generation(m.id, m.version)
                                },
                    ),
                )
                var announcedLearning = false
                found.forEach { (mod, installed) ->
                    val artifact = mod.versions.find { it.version == installed.defaultVersion }
                    if (
                        artifact != null &&
                            !artifact.hash.isNullOrBlank() &&
                            store.unprovenTakeover(mod.id, artifact.version) &&
                            learning.add("${mod.id}/${artifact.version}")
                    ) {
                        if (backgroundScope != null && !announcedLearning) {
                            notice(
                                "Checking original mod files in the background. Your copies stay unchanged."
                            )
                            announcedLearning = true
                        }
                        backgroundScope?.launch {
                            try {
                                yield()
                                learningSlot.withPermit {
                                    while (!backgroundReady()) delay(250)
                                    store.learnShipped(mod, artifact, downloader)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                /* Offline: same-version-only restoration remains safe. */
                            }
                        }
                    }
                }
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
            guarded {
                applySucceeded = false
                if (running()) return@guarded
                val game = root(plan.folder ?: return@guarded)
                recoverTransactions(home.resolve("game-transactions"), notice = notice)
                recoverTransactions(home.resolve("transactions"), notice = notice)
                val plugin = safe(game, "BepInEx/plugins")
                val recorded = record(game).read()
                val adoption = adopted(game)
                val old =
                    if (
                        adoption != null &&
                            !adoption.applied &&
                            (recorded == null || recorded.epoch != adoption.epoch)
                    )
                        adoption
                    else recorded ?: adoption ?: Placement()
                problemFlow.value = problemFlow.value.filterKeys { it in plan.versions }
                // A fresh home cannot use an old ownership record as permission to remove files.
                if (
                    (adoption == null && recorded != null) ||
                        (adoption?.applied == true &&
                            recorded != null &&
                            recorded.epoch != adoption.epoch)
                ) {
                    notice(
                        "Existing mods need takeover before changes can be applied. They were left unchanged."
                    )
                    return@guarded
                }
                if (recorded == null && adopted(game) != null && old.applied) {
                    notice("Ownership is missing. Take over existing mods before applying changes.")
                    return@guarded
                }
                if (adopted(game) == null) saveAdoption(game, old)
                val names =
                    (adopted(game)?.unplaced.orEmpty().entries +
                            adopted(game)?.entries.orEmpty().entries)
                        .associate { it.value.id to it.key } +
                        old.entries.entries.associate { it.value.id to it.key }
                fun parent(id: String, v: String) =
                    catalog().find { it.id == id }?.versions?.find { it.version == v }?.extends?.id
                fun placement(id: String, v: String): String =
                    names[id]
                        ?: parent(id, v)?.let {
                            "${names[it] ?: component(it)}/addons/${component(id)}"
                        }
                        ?: component(id)
                val wantedPairs =
                    plan.versions.map { (id, v) ->
                        placement(id, v) to
                            ManagedMod(
                                id,
                                v,
                                (old.entries.values.find { it.id == id }
                                        ?: adopted(game)?.entries?.values?.find { it.id == id }
                                        ?: adopted(game)?.unplaced?.values?.find { it.id == id })
                                    ?.loose ?: false,
                            )
                    }
                val collisions =
                    wantedPairs.groupBy { it.first.lowercase() }.values.filter { it.size > 1 }
                if (collisions.isNotEmpty()) {
                    collisions.flatten().forEach { (_, m) ->
                        problem(
                            m.id,
                            "Two enabled mods use the same game folder name. Turn off one of them and Retry.",
                        )
                    }
                }
                conflictsFlow.value = buildMap {
                    collisions.forEach { group ->
                        val ids = group.map { it.second.id }.toSet()
                        ids.forEach { put(it, ids - it) }
                        old.entries
                            .filterKeys { name ->
                                name.equals(group.first().first, ignoreCase = true)
                            }
                            .values
                            .forEach { put(it.id, ids - it.id) }
                    }
                }
                val collisionNames = collisions.flatten().map { it.first.lowercase() }.toSet()
                old.entries
                    .filterKeys { it.lowercase() in collisionNames }
                    .values
                    .forEach { m ->
                        if (m.id !in problemFlow.value)
                            problem(
                                m.id,
                                "This mod is still in the game because other mods use the same game folder name. Turn off one of them and Retry.",
                            )
                    }
                // Preserve existing colliding placements; unrelated entries can still proceed.
                val wanted =
                    wantedPairs.filter { it.first.lowercase() !in collisionNames }.toMap() +
                        old.entries.filterKeys { it.lowercase() in collisionNames }
                val result = old.entries.toMutableMap()
                val owners =
                    old.entries.keys
                        .associateWith { old.entryProfiles[it] ?: old.profileId ?: "default" }
                        .toMutableMap()
                val directories = old.directories.toMutableSet()
                val generations = old.generations.toMutableMap()
                val directChanges =
                    (old.entries.keys + wanted.keys)
                        .filter { name ->
                            val m = wanted[name]
                            m != old.entries[name] ||
                                (m != null &&
                                    runCatching { !safe(plugin, name).exists() }
                                        .getOrDefault(true)) ||
                                (m != null && (owners[name] ?: old.profileId) != plan.profileId) ||
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
                val blocked =
                    old.entries.keys.filter { it.lowercase() in collisionNames }.toMutableSet()
                for (name in changed) {
                    try {
                        val target = safe(plugin, name)
                        require(
                            !pendingTouches(
                                home.resolve("game-transactions"),
                                target,
                                unknownBlocks = true,
                            ) &&
                                !pendingTouches(
                                    home.resolve("game-transactions"),
                                    record(game).path,
                                ) &&
                                !pendingTouches(home.resolve("transactions"), record(game).path) &&
                                !pendingTouches(home.resolve("transactions"), target) &&
                                listOfNotNull(old.entries[name], wanted[name]).all { entry ->
                                    listOf(
                                            store.version(entry.id, entry.version),
                                            home.resolve(
                                                "player-files/${component(plan.profileId)}/${component(entry.id)}"
                                            ),
                                            home.resolve(
                                                "player-edits/${component(plan.profileId)}/${component(entry.id)}"
                                            ),
                                        )
                                        .none { pendingTouches(home.resolve("transactions"), it) }
                                }
                        ) {
                            "These files need recovery"
                        }
                        rejectLinks(target)
                        for ((childName, child) in
                            wanted.filterKeys { it == name || it.startsWith("$name/") }) {
                            require(store.version(child.id, child.version).isDirectory()) {
                                "Stored files are missing"
                            }
                        }
                    } catch (e: Exception) {
                        blocked += name
                        (old.entries[name] ?: wanted[name])?.let {
                            problem(
                                it.id,
                                placementMessage(
                                    catalog().find { mod -> mod.id == it.id }?.name ?: "this mod",
                                    e,
                                ),
                            )
                        }
                    }
                }
                fun blocked(name: String) = blocked.any { name == it || name.startsWith("$it/") }
                // Snapshot deepest entries first, before a parent can move its children.
                val captured = mutableMapOf<String, Path>()
                for (name in changed.sortedByDescending { it.count { c -> c == '/' } }) {
                    val m = old.entries[name] ?: continue
                    if (blocked(name)) continue
                    try {
                        val dest = safe(plugin, name)
                        val payload = store.version(m.id, m.version)
                        val kept = extras(m, owners[name] ?: "default")
                        val baseline =
                            if (store.unprovenTakeover(m.id, m.version))
                                store.hashes(m.id, m.version)
                            else store.shippedHashes(m.id, m.version)
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
                                            val originalHash = baseline[rel]
                                            if (
                                                originalHash == null || originalHash != digest(file)
                                            )
                                                copyTree(file, safe(snapshot, rel))
                                        }
                                    }
                            }
                        if (dest.isRegularFile()) {
                            val rel = dest.fileName.toString()
                            if (baseline[rel] != digest(dest)) copyTree(dest, safe(snapshot, rel))
                        }
                        // Shipped-file edits belong to this exact version/generation. A later
                        // version's capture cannot overwrite edits that it never put back.
                        val editedStage =
                            home.resolve("staging/edits-${java.util.UUID.randomUUID()}")
                        Files.createDirectories(editedStage)
                        try {
                            val shipped = store.shippedHashes(m.id, m.version)
                            Files.walk(snapshot).use { files ->
                                files
                                    .filter { it.isRegularFile() }
                                    .forEach { file ->
                                        val relative =
                                            snapshot.relativize(file).toString().replace('\\', '/')
                                        if (
                                            relative in shipped ||
                                                store.unprovenTakeover(m.id, m.version)
                                        )
                                            copyTree(file, safe(editedStage, relative))
                                    }
                            }
                            replaceTree(
                                editedFiles(
                                    m,
                                    owners[name] ?: "default",
                                    old.generations[name].orEmpty(),
                                ),
                                editedStage,
                                home.resolve("transactions"),
                            )
                        } finally {
                            deleteTree(editedStage)
                        }
                        // The saved extra set mirrors removal, including player deletions.
                        replaceTree(kept, snapshot, home.resolve("transactions"))
                        atomic(
                            kept.resolveSibling(kept.fileName.toString() + ".generation"),
                            old.generations[name].orEmpty(),
                        )
                        atomic(
                            kept.resolveSibling(kept.fileName.toString() + ".version"),
                            m.version,
                        )
                        captured[name] = snapshot
                        if (
                            !store.unprovenTakeover(m.id, m.version) &&
                                Files.walk(snapshot).use { it.anyMatch { p -> p.isRegularFile() } }
                        )
                            notice(
                                "Kept changed files for ${displayName(m.id)} in recovery storage. They return with this profile."
                            )
                    } catch (e: Exception) {
                        problem(
                            m.id,
                            placementMessage(
                                catalog().find { it.id == m.id }?.name ?: "this mod",
                                e,
                            ),
                        )
                    }
                }
                // Removals first, deepest first. Missing wanted payloads never authorize removal.
                for (name in changed.sortedByDescending { it.count { c -> c == '/' } }) {
                    if (name in wanted) continue
                    val m = old.entries[name] ?: continue
                    if (blocked(name)) continue
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
                            beforeMutation = {
                                check(!running()) { "Game started; changes are pending" }
                            },
                            afterCommit =
                                record(game).path to
                                    json.encodeToString(
                                        Placement(
                                            result - name,
                                            old.profileId,
                                            old.ownSettings,
                                            generations - name,
                                            directories,
                                            applied = true,
                                            entryProfiles = owners - name,
                                            epoch = old.epoch,
                                        )
                                    ),
                        )
                        result.keys
                            .filter { it == name || it.startsWith("$name/") }
                            .toList()
                            .forEach {
                                result.remove(it)
                                generations.remove(it)
                            }
                    } catch (e: Exception) {
                        problem(
                            m.id,
                            placementMessage(
                                catalog().find { it.id == m.id }?.name ?: "this mod",
                                e,
                            ),
                        )
                    }
                }
                for (name in
                    changed.filter { it in wanted }.sortedBy { it.count { c -> c == '/' } }) {
                    val m = wanted.getValue(name)
                    if (blocked(name)) continue
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
                            val versionSidecar =
                                kept.resolveSibling(kept.fileName.toString() + ".version")
                            val originalVersion =
                                if (versionSidecar.isRegularFile()) versionSidecar.readText()
                                else
                                    kept
                                        .resolveSibling(kept.fileName.toString() + ".generation")
                                        .let {
                                            if (it.isRegularFile())
                                                store.versionForGeneration(m.id, it.readText())
                                            else null
                                        }
                            if (
                                originalVersion != null &&
                                    !versionSidecar.exists() &&
                                    kept.isDirectory()
                            )
                                atomic(versionSidecar, originalVersion)
                            if (
                                kept.isDirectory() &&
                                    (originalVersion == null ||
                                        (originalVersion != m.version &&
                                            store.unprovenTakeover(m.id, originalVersion)))
                            ) {
                                val saved =
                                    home.resolve(
                                        "player-edits/${component(plan.profileId)}/${component(m.id)}/" +
                                            (originalVersion?.let(::component)
                                                ?: "unknown-version") +
                                            "-" +
                                            java.util.UUID.randomUUID()
                                    )
                                val recoveryStage =
                                    Files.createTempDirectory(
                                        home.resolve("staging"),
                                        "unknown-files-",
                                    )
                                try {
                                    val storedHashes =
                                        originalVersion?.let { store.hashes(m.id, it) }.orEmpty()
                                    Files.walk(kept).use { files ->
                                        files
                                            .filter { it.isRegularFile() }
                                            .forEach { file ->
                                                val to =
                                                    safe(
                                                        recoveryStage,
                                                        kept
                                                            .relativize(file)
                                                            .toString()
                                                            .replace('\\', '/'),
                                                    )
                                                val relative =
                                                    kept
                                                        .relativize(file)
                                                        .toString()
                                                        .replace('\\', '/')
                                                if (storedHashes[relative] != digest(file))
                                                    copyTree(file, to)
                                            }
                                    }
                                    val hasFiles =
                                        Files.walk(recoveryStage).use { files ->
                                            files.anyMatch { it.isRegularFile() }
                                        }
                                    if (hasFiles) {
                                        val hash = treeHash(recoveryStage)
                                        val existing =
                                            if (saved.parent.isDirectory())
                                                Files.list(saved.parent).use { paths ->
                                                    paths.anyMatch {
                                                        it.isDirectory() &&
                                                            it.fileName.toString().dropLast(37) ==
                                                                (originalVersion?.let(::component)
                                                                    ?: "unknown-version") &&
                                                            treeHash(it) == hash
                                                    }
                                                }
                                            else false
                                        if (!existing) {
                                            replaceTree(
                                                saved,
                                                recoveryStage,
                                                home.resolve("transactions"),
                                            )
                                            notice(
                                                "Saved files for ${displayName(m.id)} were set aside in recovery storage. The selected version uses its own files."
                                            )
                                        }
                                    }
                                } finally {
                                    deleteTree(recoveryStage)
                                }
                                deleteTree(kept)
                            }
                            var collisionNoticed = false
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
                                            val savedGeneration =
                                                kept
                                                    .resolveSibling(
                                                        kept.fileName.toString() + ".generation"
                                                    )
                                                    .let {
                                                        if (it.isRegularFile()) it.readText()
                                                        else ""
                                                    }
                                            if (
                                                !to.exists() ||
                                                    savedGeneration ==
                                                        "|" + store.generation(m.id, m.version)
                                            ) {
                                                if (to.exists()) deleteTree(to)
                                                copyTree(file, to)
                                            } else {
                                                val originalVersion =
                                                    kept
                                                        .resolveSibling(
                                                            kept.fileName.toString() + ".version"
                                                        )
                                                        .readText()
                                                val saved =
                                                    editedFiles(
                                                        m.copy(version = originalVersion),
                                                        plan.profileId,
                                                        savedGeneration,
                                                    )
                                                val collision =
                                                    safe(
                                                        saved,
                                                        kept
                                                            .relativize(file)
                                                            .toString()
                                                            .replace('\\', '/'),
                                                    )
                                                if (
                                                    !collision.exists() ||
                                                        digest(collision) != digest(file)
                                                )
                                                    replaceTree(
                                                        collision,
                                                        file,
                                                        home.resolve("transactions"),
                                                    )
                                                if (!collisionNoticed)
                                                    notice(
                                                        "Shipped files were placed for ${displayName(m.id)}. Its edits stay saved for their original version in recovery storage."
                                                    )
                                                collisionNoticed = true
                                            }
                                        }
                                }
                            if (
                                m.loose &&
                                    kept.resolve(Path.of(name).fileName).isRegularFile() &&
                                    kept
                                        .resolveSibling(kept.fileName.toString() + ".generation")
                                        .readText() == "|" + store.generation(m.id, m.version)
                            ) {
                                deleteTree(next)
                                copyTree(kept.resolve(Path.of(name).fileName), next)
                            }
                            val edited =
                                editedFiles(
                                    m,
                                    plan.profileId,
                                    "|" + store.generation(m.id, m.version),
                                )
                            if (edited.isDirectory()) {
                                if (m.loose) {
                                    val file = edited.resolve(Path.of(name).fileName)
                                    if (file.isRegularFile()) {
                                        deleteTree(next)
                                        copyTree(file, next)
                                    }
                                } else
                                    Files.walk(edited).use { files ->
                                        files
                                            .filter { it.isRegularFile() }
                                            .forEach { file ->
                                                val to =
                                                    safe(
                                                        next,
                                                        edited
                                                            .relativize(file)
                                                            .toString()
                                                            .replace('\\', '/'),
                                                    )
                                                if (to.exists()) deleteTree(to)
                                                copyTree(file, to)
                                            }
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
                                beforeMutation = {
                                    check(!running()) { "Game started; changes are pending" }
                                },
                                afterCommit =
                                    record(game).path to
                                        json.encodeToString(
                                            Placement(
                                                result + (name to m),
                                                old.profileId,
                                                old.ownSettings,
                                                generations +
                                                    (name to
                                                        ("|" + store.generation(m.id, m.version))),
                                                directories,
                                                applied = true,
                                                entryProfiles = owners + (name to plan.profileId),
                                                epoch = old.epoch,
                                            )
                                        ),
                            )
                            problemFlow.value = problemFlow.value - m.id
                            owners[name] = plan.profileId
                            result[name] = m
                            generations[name] = "|" + store.generation(m.id, m.version)
                        } finally {
                            deleteTree(stage)
                        }
                    } catch (e: Exception) {
                        problem(
                            m.id,
                            placementMessage(
                                catalog().find { it.id == m.id }?.name ?: "this mod",
                                e,
                            ),
                        )
                    }
                }
                captured.values.forEach(::deleteTree)
                // A promoted entry with pending metadata must be recovered before publishing any
                // aggregate record; otherwise the old in-memory plan could erase its ownership.
                if (pendingTransactions(home.resolve("game-transactions"))) return@guarded
                if (store.recoveryOverLimit())
                    notice(
                        "Recovery copies are kept. Use Clear in Settings to remove copies whose bytes exist elsewhere."
                    )
                val beforeConfig =
                    Placement(
                        result,
                        old.profileId,
                        old.ownSettings,
                        generations,
                        directories,
                        applied = true,
                        entryProfiles = owners,
                        epoch = old.epoch,
                    )
                swapConfig(game, beforeConfig, plan.ownSettings, plan.profileId)
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
                        entryProfiles = owners,
                        epoch = old.epoch,
                    )
                if (updated != old) {
                    record(game).write(updated)
                    saveAdoption(
                        game,
                        updated.copy(
                            entries = adopted(game)?.entries.orEmpty() + updated.entries,
                            unplaced = adopted(game)?.unplaced.orEmpty(),
                        ),
                    )
                }
                applySucceeded = problemFlow.value.isEmpty()
            }
        }

    fun canAutomaticallyTakeOver(folder: String): Boolean {
        val game = root(folder)
        return adopted(game) == null && record(game).read() == null
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
        if (!old.ownSettings && ownSettings && !next.exists()) {
            record(game).write(old.copy(profileId = p, ownSettings = ownSettings))
            return
        }
        val empty = home.resolve("staging/config-${java.util.UUID.randomUUID()}")
        Files.createDirectories(empty)
        try {
            replaceTree(
                config,
                if (next.exists()) next else empty,
                home.resolve("game-transactions"),
                afterCommit =
                    record(game).path to
                        json.encodeToString(old.copy(profileId = p, ownSettings = ownSettings)),
                beforeMutation = {
                    check(!running()) { "Game started; config changes are pending" }
                },
            )
        } finally {
            deleteTree(empty)
        }
    }

    private fun editedFiles(m: ManagedMod, profile: String, generation: String): Path {
        val key =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(generation.toByteArray())
                .joinToString("") { "%02x".format(it) }
        return home.resolve(
            "player-edits/${component(profile)}/${component(m.id)}/${component(m.version)}/$key"
        )
    }

    private fun loaderRecord(game: Path) =
        JsonFile(
            home.resolve("loader-${Integer.toHexString(game.toString().hashCode())}.json"),
            kotlinx.serialization.builtins.ListSerializer(String.serializer()),
            notice,
        )

    // The legacy name list is an index only. Names without durable hashes grant no authority.
    private fun loaderOwnership(game: Path) =
        JsonFile(
            loaderRecordPath(game)
                .resolveSibling(loaderRecordPath(game).fileName.toString() + ".hashes"),
            LoaderOwnership.serializer(),
            notice,
        )

    private fun ownedHashes(game: Path): Map<String, String> =
        loaderOwnership(game).read()?.takeIf { it.gameRoot == game.toString() }?.hashes.orEmpty()

    private fun verifiedOwnership(game: Path): Map<String, String> {
        val hashes = ownedHashes(game)
        return hashes.filter { (name, hash) ->
            val target = safe(game, name)
            if (name == "doorstop_config.ini" && DoorstopRestores(home).read(game).isNotEmpty())
                true // Our temporary disabled bytes are not a player edit.
            else target.exists() && readLoaderHash(target) == hash
        }
    }

    private fun hasPresentRelinquished(game: Path): Boolean =
        loaderOwnership(game)
            .read()
            ?.takeIf { it.gameRoot == game.toString() }
            ?.relinquished
            .orEmpty()
            .any { safe(game, it).exists() }

    private fun writeOwnership(game: Path, hashes: Map<String, String>) {
        val old = loaderOwnership(game).read()?.takeIf { it.gameRoot == game.toString() }
        if (old == null && hashes.isEmpty() && !loaderRecord(game).path.exists()) return
        val relinquished =
            (old?.relinquished.orEmpty() + (old?.hashes.orEmpty().keys - hashes.keys)) - hashes.keys
        loaderOwnership(game).write(LoaderOwnership(game.toString(), hashes, relinquished))
        loaderRecord(game).write(hashes.keys.toList())
    }

    private fun promoted(report: Path, operationId: String?): Set<String> {
        if (operationId == null || !report.exists()) return emptySet()
        val evidence =
            runCatching { json.decodeFromString<PromotionReport>(report.readText()) }.getOrNull()
        return evidence?.takeIf { it.operationId == operationId }?.targets.orEmpty().toSet()
    }

    private fun loaderArchive(game: Path) =
        JsonFile(
            loaderRecordPath(game)
                .resolveSibling(loaderRecordPath(game).fileName.toString() + ".archive"),
            kotlinx.serialization.builtins.ListSerializer(String.serializer()),
            notice,
        )

    private fun loaderRecordPath(game: Path) =
        home.resolve("loader-${Integer.toHexString(game.toString().hashCode())}.json")

    private fun promotionReport(game: Path) =
        pendingLoaderRecord(game)
            .resolveSibling(pendingLoaderRecord(game).fileName.toString() + ".promoted")

    private fun pendingRemovalRecords(): List<Path> =
        home.resolve("loader-removals").let {
            if (it.isDirectory()) it.listDirectoryEntries("*.json") else emptyList()
        }

    private fun removalRecord(game: Path) =
        home.resolve("loader-removals").resolve(pendingLoaderRecord(game).fileName)

    private fun finishLoaderRemoval(path: Path) {
        val pending = json.decodeFromString<PendingLoaderRemoval>(path.readText())
        val game = root(pending.gameRoot)
        check(removalRecord(game) == path)
        checkLoaderRecovery(game)
        val record = loaderRecord(game)
        val report = path.resolveSibling(path.fileName.toString() + ".promoted")
        val promoted = promoted(report, pending.operationId)
        val hashes =
            verifiedOwnership(game).filter { (name, _) ->
                name !in pending.absent && safe(game, name).toString() !in promoted
            }
        writeOwnership(game, hashes)
        Files.deleteIfExists(report)
        DiskFaults.step("loader-removal-report-deleted")
        Files.delete(path)
        DiskFaults.step("probe-removal-pending-deleted")
        forceDirectory(path.parent)
    }

    private fun checkLoaderRecovery(game: Path) {
        if (
            pendingTouches(home.resolve("transactions"), game) ||
                pendingTouches(home.resolve("game-transactions"), game, unknownBlocks = true)
        )
            throw LoaderRecoveryPending()
    }

    private fun pendingLoaderRecord(game: Path): Path {
        val key =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(game.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return home.resolve("loader-pending/$key.json")
    }

    private fun pendingLoaderRecords(): List<Path> {
        val directory = home.resolve("loader-pending")
        return if (directory.isDirectory()) directory.listDirectoryEntries("*.json")
        else emptyList()
    }

    // Transactions must settle before matching bytes: recovery can still promote a target.
    private fun recoverLoaderOwnership(game: Path) {
        recoverTransactions(home.resolve("transactions"), notice = notice)
        recoverTransactions(home.resolve("game-transactions"), notice = notice)
        checkLoaderRecovery(game)
        removalRecord(game).takeIf { it.exists() }?.let(::finishLoaderRemoval)
        finishLoaderOwnership(game)?.let { throw it }
    }

    private fun finishLoaderOwnership(
        game: Path,
        requireAll: Boolean = false,
    ): java.io.IOException? {
        val path = pendingLoaderRecord(game)
        if (!path.exists()) return null
        checkLoaderRecovery(game)
        val pending = json.decodeFromString<PendingLoaderOwnership>(path.readText())
        check(pending.gameRoot == game.toString()) { "Loader recovery folder mismatch" }
        val report = promotionReport(game)
        val promoted = promoted(report, pending.operationId)
        // Only recovery's confirmed hand replacements relinquish matching old bytes.
        // A refused eligibility check is unmoved, not preserved; it still needs verification.
        val preserved =
            runCatching { json.decodeFromString<PromotionReport>(report.readText()) }
                .getOrNull()
                ?.takeIf { pending.operationId != null && it.operationId == pending.operationId }
                ?.preserved
                .orEmpty()
                .toSet()
        val names =
            verifiedOwnership(game)
                .filterKeys { safe(game, it).toString() !in preserved }
                .toMutableMap()
        val unreadable = mutableMapOf<String, String>()
        var failure: java.io.IOException? = null
        pending.hashes.forEach { (relative, hash) ->
            val target = safe(game, relative)
            if (target.toString() !in promoted) {
                if (requireAll && failure == null)
                    failure = AccessDeniedException(target.toString())
                return@forEach
            }
            try {
                val actual =
                    if (relative == "doorstop_config.ini") {
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(readDoorstop(target))
                            .joinToString("") { "%02x".format(it) }
                    } else readLoaderHash(target)
                if (actual == hash) {
                    if (relative !in pending.unowned) names[relative] = hash
                } else {
                    if (failure == null)
                        failure =
                            if (relative == "doorstop_config.ini")
                                DoorstopSettingsFailure(AccessDeniedException(target.toString()))
                            else AccessDeniedException(target.toString())
                }
            } catch (e: java.nio.file.NoSuchFileException) {
                if (failure == null) failure = AccessDeniedException(target.toString())
            } catch (e: java.io.IOException) {
                unreadable[relative] = hash
                val problem =
                    if (relative == "doorstop_config.ini") DoorstopSettingsFailure(e)
                    else AccessDeniedException(target.toString()).apply { initCause(e) }
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
        }
        if (names.isNotEmpty() && pending.archiveFiles.isNotEmpty())
            loaderArchive(game).write(pending.archiveFiles.toList())
        writeOwnership(game, names)
        if (unreadable.isEmpty()) {
            Files.deleteIfExists(report)
            DiskFaults.step("loader-ownership-report-deleted")
            Files.delete(path)
            DiskFaults.step("probe-install-pending-deleted")
        } else {
            atomic(path, json.encodeToString(pending.copy(hashes = unreadable)))
        }
        forceDirectory(path.parent)
        return failure
    }

    override suspend fun removeLoader(folder: String) =
        withContext(Dispatchers.IO) {
            guarded {
                require(!running()) { "Close Nuclear Option before removing the mod loader." }
                val game = root(folder)
                recoverLoaderOwnership(game)
                val hashes = ownedHashes(game)
                val absent = hashes.keys.filter { !safe(game, it).exists() }.toSet()
                val verified = verifiedOwnership(game)
                writeOwnership(game, verified)
                val removals = verified.keys.map { safe(game, it) to null }
                val presentRemovals = removals.map { it.first }.toSet()
                val removal = removalRecord(game)
                val operationId = java.util.UUID.randomUUID().toString()
                Files.deleteIfExists(
                    removal.resolveSibling(removal.fileName.toString() + ".promoted")
                )
                atomic(
                    removal,
                    json.encodeToString(
                        PendingLoaderRemoval(game.toString(), hashes.keys, absent, operationId)
                    ),
                )
                DiskFaults.step("loader-removal-prepared")
                replaceTrees(
                    removals,
                    home.resolve("transactions"),
                    promotionReport =
                        removal.resolveSibling(removal.fileName.toString() + ".promoted"),
                    commitRemovals = true,
                    operationId = operationId,
                    beforeMutation = {
                        check(!running()) { "Game started; loader changes are pending" }
                    },
                    eligible = { target ->
                        val name = game.relativize(target).toString().replace('\\', '/')
                        val allowed =
                            try {
                                target.exists() &&
                                    readLoaderHash(target) == verified[name] &&
                                    !(name == "doorstop_config.ini" &&
                                        DoorstopRestores(home).read(game).isNotEmpty())
                            } catch (_: java.io.IOException) {
                                false
                            }
                        if (!allowed)
                            notice(
                                "A loader file changed or still needs restoration. Its current bytes were kept. Retry."
                            )
                        allowed
                    },
                )
                val report = removal.resolveSibling(removal.fileName.toString() + ".promoted")
                val removedCount =
                    if (report.exists())
                        promoted(report, operationId).count { name ->
                            presentRemovals.any { it.toString() == name }
                        }
                    else 0
                finishLoaderRemoval(removal)
                if (loaderRecord(game).read().orEmpty().isNotEmpty())
                    throw AccessDeniedException(game.toString())
                DiskFaults.step("loader-removal-published")
                LoaderRemoval(
                    removedCount,
                    listOf("doorstop_config.ini", "winhttp.dll", "BepInEx/core").any {
                        safe(game, it).let { path ->
                            if (path.isDirectory())
                                Files.walk(path).use { files ->
                                    files.anyMatch { it.isRegularFile() }
                                }
                            else path.exists()
                        }
                    },
                )
            }
        }

    override suspend fun installLoader(folder: String) = loader(folder, false)

    override suspend fun repairLoader(folder: String) = loader(folder, true)

    private suspend fun loader(folder: String, repair: Boolean) =
        withContext(Dispatchers.IO) {
            guarded {
                require(!running()) { "Close Nuclear Option before changing the mod loader." }
                val game = root(folder)
                recoverLoaderOwnership(game)
                val info = inspect(folder)
                val recorded = ownedHashes(game)
                val verified = verifiedOwnership(game)
                writeOwnership(game, verified)
                // Only resume the pinned loader while every surviving owned file is unchanged.
                if (
                    !repair &&
                        (hasPresentRelinquished(game) ||
                            (info.loaderPresent &&
                                (info.loaderVersion != "5.4.23.4" ||
                                    verified.isEmpty() ||
                                    recorded.any { (name, hash) ->
                                        safe(game, name).exists() && verified[name] != hash
                                    })))
                )
                    return@guarded
                val existingVersion = info.loaderVersion
                val changedOwned =
                    (recorded.keys - verified.keys) +
                        loaderOwnership(game).read()?.relinquished.orEmpty()
                if (repair && existingVersion != "5.4.23.4") {
                    val message =
                        if (existingVersion == null)
                            "The installed BepInEx version could not be identified. Its files were left unchanged."
                        else
                            "This folder has BepInEx $existingVersion. Its files were left unchanged."
                    notice(message)
                    throw LoaderRepairRefused()
                }
                val v =
                    ModVersion(
                        "5.4.23.4",
                        hash = "f881201b79da03e513bf97cdf39607ffa7f9e0d31a519b1aeeca8eb60f8309e7",
                        downloadUrl =
                            "https://github.com/BepInEx/BepInEx/releases/download/v5.4.23.4/BepInEx_win_x64_5.4.23.4.zip",
                    )
                val transfer =
                    try {
                        downloader.download(CatalogMod("bepinex", "BepInEx"), v) {}
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw LoaderDownloadFailure(e)
                    }
                val downloaded = Path.of(transfer.path)
                val staging = Files.createTempDirectory(home.resolve("staging"), "loader-")
                try {
                    unpack(downloaded, staging)
                    require(staging.resolve("BepInEx/core/BepInEx.dll").isRegularFile())
                    DoorstopLocks.locked(game) {
                        val pendingRestore = DoorstopRestores(home).read(game).isNotEmpty()
                        val replacements = mutableListOf<Pair<Path, Path>>()
                        val archiveFiles = mutableSetOf<String>()
                        val owned = loaderRecord(game)
                        val ownedNames = owned.read().orEmpty().toMutableList()
                        Files.walk(staging).use { paths ->
                            paths
                                .filter { it.isRegularFile() }
                                .sorted()
                                .forEach { src ->
                                    val relative =
                                        staging.relativize(src).toString().replace('\\', '/')
                                    require(
                                        !relative.startsWith("BepInEx/plugins/") &&
                                            !relative.startsWith("BepInEx/config/")
                                    )
                                    archiveFiles += relative
                                    val dest = safe(game, relative)
                                    val wasOwned = relative in ownedNames
                                    if (
                                        (!dest.exists() || (repair && wasOwned)) &&
                                            !(relative == "doorstop_config.ini" &&
                                                (dest.exists() || pendingRestore))
                                    )
                                        replacements += dest to src
                                }
                        }
                        val doorstop = safe(game, "doorstop_config.ini")
                        val unowned =
                            if (doorstop.exists() && "doorstop_config.ini" !in ownedNames)
                                setOf("doorstop_config.ini")
                            else emptySet()
                        val originalDoorstopHash =
                            if (
                                repair &&
                                    doorstop.exists() &&
                                    !pendingRestore &&
                                    "doorstop_config.ini" !in changedOwned
                            ) {
                                try {
                                    readLoaderHash(doorstop)
                                } catch (e: java.io.IOException) {
                                    throw DoorstopSettingsFailure(e)
                                }
                            } else null
                        var replacedDoorstop = false
                        if (
                            repair &&
                                !pendingRestore &&
                                doorstop.exists() &&
                                "doorstop_config.ini" !in changedOwned
                        ) {
                            val config =
                                try {
                                    // Strict decoding happens before any loader replacement is
                                    // committed.
                                    DoorstopIni.read(readDoorstop(doorstop))
                                } catch (e: java.io.IOException) {
                                    throw DoorstopSettingsFailure(e)
                                }
                            if (!config.healthy || config.explicitlyDisabled) {
                                val restored = staging.resolve("restored-doorstop.ini")
                                if (!config.healthy) {
                                    Files.copy(
                                        staging.resolve("doorstop_config.ini"),
                                        restored,
                                        StandardCopyOption.REPLACE_EXISTING,
                                    )
                                    replacedDoorstop = true
                                } else Files.write(restored, config.setEnabled(true))
                                replacements.add(0, doorstop to restored)
                            }
                        }
                        // Keep the INI first so a refused settings move cannot change
                        // loader binaries.
                        // This also covers creating a missing INI, which the archive walk adds
                        // in file-system order.
                        val doorstopIndex = replacements.indexOfFirst { it.first == doorstop }
                        if (doorstopIndex > 0)
                            replacements.add(0, replacements.removeAt(doorstopIndex))
                        val hashes =
                            replacements.associate { (target, source) ->
                                game.relativize(target).toString().replace('\\', '/') to
                                    digest(source)
                            }
                        val originalHashes =
                            replacements.associate { (target, _) ->
                                val name = game.relativize(target).toString().replace('\\', '/')
                                target to
                                    (verified[name]
                                        ?: if (name in unowned) originalDoorstopHash else null)
                            }
                        val operationId = java.util.UUID.randomUUID().toString()
                        Files.deleteIfExists(promotionReport(game))
                        atomic(
                            pendingLoaderRecord(game),
                            json.encodeToString(
                                PendingLoaderOwnership(
                                    game.toString(),
                                    hashes,
                                    unowned,
                                    archiveFiles,
                                    operationId,
                                )
                            ),
                        )
                        DiskFaults.step("probe-install-pending-written")
                        try {
                            replaceTrees(
                                replacements,
                                home.resolve("transactions"),
                                promotionReport = promotionReport(game),
                                operationId = operationId,
                                beforeMutation = {
                                    check(!running()) { "Game started; loader changes are pending" }
                                },
                                eligible = { target ->
                                    val expected = originalHashes[target]
                                    val allowed =
                                        try {
                                            if (expected == null) !target.exists()
                                            else
                                                target.exists() &&
                                                    readLoaderHash(target) == expected
                                        } catch (_: java.io.IOException) {
                                            false
                                        }
                                    if (!allowed)
                                        notice(
                                            "A loader file changed while preparing the change. Its current bytes were kept. Retry."
                                        )
                                    allowed
                                },
                            )
                        } catch (e: Exception) {
                            try {
                                finishLoaderOwnership(game)?.let { e.addSuppressed(it) }
                            } catch (publication: Exception) {
                                e.addSuppressed(publication)
                            }
                            throw e
                        }
                        // A recovered batch may contain promoted and rolled-back files.
                        finishLoaderOwnership(game, requireAll = true)?.let { throw it }
                        if (replacedDoorstop)
                            notice(
                                "Replaced the damaged mod loader settings with the shipped defaults. Your previous doorstop_config.ini was kept in recovery in the game folder (.nnomm/recovery)."
                            )
                        if (repair && pendingRestore)
                            notice(
                                "NNOMM is still putting back doorstop_config.ini from Play without mods. Close programs that use the game folder, then use Repair again."
                            )
                    }
                } finally {
                    deleteTree(staging)
                    transfer.close()
                }
            }
        }
}
