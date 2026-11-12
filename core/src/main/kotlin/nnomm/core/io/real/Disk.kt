package nnomm.core.io.real

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.security.MessageDigest
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import nnomm.core.io.*
import nnomm.core.model.*

internal val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
    prettyPrint = true
}

fun settingsHome(
    env: Map<String, String> = System.getenv(),
    os: String = System.getProperty("os.name"),
    userHome: String = System.getProperty("user.home"),
): Path =
    Path.of(
            env["NNOMM_HOME"]?.takeIf { it.isNotBlank() }
                ?: if (os.startsWith("Windows", true))
                    Path.of(env["APPDATA"] ?: "$userHome/AppData/Roaming", "NNOMM").toString()
                else
                    Path.of(
                            env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }
                                ?: "$userHome/.config",
                            "NNOMM",
                        )
                        .toString()
        )
        .toAbsolutePath()
        .normalize()

/** Resolve untrusted names without permitting traversal, drive letters or symbolic links. */
internal fun safe(root: Path, name: String): Path {
    require(name.isNotBlank() && !name.contains('\\') && !name.contains(':')) {
        "Unsafe path: $name"
    }
    val base = root.toAbsolutePath().normalize()
    val result = base.resolve(name).normalize()
    require(result.startsWith(base) && result != base) { "Path leaves its folder: $name" }
    name.split('/').filter { it.isNotEmpty() }.forEach(::component)
    var at: Path? = result
    while (at != null && at != base) {
        require(!Files.isSymbolicLink(at)) { "Symbolic links are not managed: $at" }
        at = at.parent
    }
    return result
}

internal fun component(name: String): String {
    require(
        name !in listOf(".", "..") &&
            name.isNotBlank() &&
            name.none { it in "/\\:<>\"|?*" || it.code < 32 } &&
            !name.endsWith(".") &&
            !name.endsWith(" ") &&
            !name
                .substringBefore('.')
                .uppercase()
                .matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
    ) {
        "Unsafe identifier: $name"
    }
    return name
}

internal fun atomic(path: Path, text: String) {
    safe(path.parent, path.fileName.toString())
    Files.createDirectories(path.parent)
    val tmp = Files.createTempFile(path.parent, ".nnomm-", ".tmp")
    try {
        FileChannel.open(tmp, StandardOpenOption.WRITE).use { ch ->
            val buffer = StandardCharsets.UTF_8.encode(text)
            while (buffer.hasRemaining()) ch.write(buffer)
            ch.force(true)
        }
        try {
            Files.move(
                tmp,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
        forceDirectory(path.parent)
    } finally {
        Files.deleteIfExists(tmp)
    }
}

/** A malformed primary is retained verbatim. A failed backup load blocks writes. */
internal class JsonFile<T>(
    val path: Path,
    val serializer: KSerializer<T>,
    val notice: (String) -> Unit = {},
) {
    @Synchronized
    fun read(): T? {
        val backup = path.resolveSibling(path.fileName.toString() + ".bak")
        if (!path.exists() && !backup.exists()) return null
        for (p in listOf(path, backup)) if (p.exists()) {
            try {
                val value = json.decodeFromString(serializer, p.readText())
                if (p == backup)
                    notice("Recovered ${path.fileName} from its backup. The original file is kept.")
                return value
            } catch (e: Exception) {
                notice("Could not read ${p.fileName}: ${e.message}. The file is kept.")
            }
        }
        error(
            "Cannot load ${path.fileName} or its backup; the files are kept. Restore a valid backup before continuing."
        )
    }

    @Synchronized
    fun write(value: T) {
        if (path.exists()) {
            val old = path.readText()
            if (runCatching { json.decodeFromString(serializer, old) }.isSuccess)
                atomic(path.resolveSibling(path.fileName.toString() + ".bak"), old)
            else {
                val preserved =
                    path.resolveSibling(path.fileName.toString() + ".broken-${System.nanoTime()}")
                Files.copy(path, preserved)
                notice("Kept the damaged ${path.fileName} as ${preserved.fileName}.")
            }
        }
        atomic(path, json.encodeToString(serializer, value))
    }
}

internal fun digest(path: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

internal fun normalizedHash(hash: String?) =
    hash?.trim()?.replace(Regex("^sha256:", RegexOption.IGNORE_CASE), "")?.lowercase()

internal fun copyTree(from: Path, to: Path, link: Boolean = false) {
    require(!Files.isSymbolicLink(from))
    if (from.isDirectory()) {
        Files.createDirectories(to)
        Files.list(from).use {
            it.forEach { child -> copyTree(child, safe(to, child.fileName.toString()), link) }
        }
        forceDirectory(to)
    } else {
        Files.createDirectories(to.parent)
        Files.copy(from, to)
        FileChannel.open(to, StandardOpenOption.WRITE).use { it.force(true) }
    }
}

internal fun deleteTree(path: Path) {
    if (!path.exists()) return
    require(!Files.isSymbolicLink(path))
    if (path.isDirectory()) Files.list(path).use { it.forEach(::deleteTree) }
    Files.delete(path)
}

@Serializable
internal data class SavedState(
    val settings: SettingsValues,
    val installed: Map<String, InstalledMod>,
    val profiles: List<Profile>,
    val profileId: String,
    val game: GameInfo,
    val firstStart: FirstStart,
    val localCatalog: List<CatalogMod>,
)

class RealPersistence(home: Path, notice: (String) -> Unit = {}) : StatePersistence, SettingsFile {
    private val state = JsonFile(home.resolve("state.json"), SavedState.serializer(), notice)
    private val settings =
        JsonFile(home.resolve("settings.json"), SettingsValues.serializer(), notice)

    override suspend fun read() = withContext(Dispatchers.IO) { settings.read() }

    override suspend fun write(values: SettingsValues) =
        withContext(Dispatchers.IO) { settings.write(values) }

    override suspend fun load(): AppState? =
        withContext(Dispatchers.IO) {
            state.read()?.let {
                AppState(
                    settings =
                        it.settings.copy(
                            general =
                                nnomm.core.settings.defaultSettings().general + it.settings.general
                        ),
                    installed = it.installed,
                    profiles = it.profiles,
                    profileId = it.profileId,
                    game = it.game,
                    firstStart = it.firstStart,
                    catalog = it.localCatalog,
                )
            }
        }

    override suspend fun save(state: AppState) =
        withContext(Dispatchers.IO) {
            if (settings.read() != state.settings) settings.write(state.settings)
            this@RealPersistence.state.write(
                SavedState(
                    state.settings,
                    state.installed,
                    state.profiles,
                    state.profileId,
                    state.game,
                    state.firstStart,
                    state.catalog.filter { it.localFile != null || it.id in state.installed },
                )
            )
        }
}

internal fun forceDirectory(path: Path) {
    // Windows does not permit opening a directory channel. The rename remains atomic there.
    runCatching { FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) } }
}

/** Promotion is the commit point; recovery preserves targets written after promotion. */
@Serializable
internal data class DiskTransaction(
    val target: String,
    val staged: String?,
    val previous: String,
    val existed: Boolean,
    val gameTarget: Boolean = false,
    val phase: String = "prepared",
)

@Serializable
internal data class TransactionBatch(
    val changes: List<DiskTransaction>,
    val commitPath: String? = null,
    val commitText: String? = null,
)

internal class DiskCrash : Error("Simulated process interruption")

internal object DiskFaults {
    var step: (String) -> Unit = {}
}

private fun isGameTarget(path: Path): Boolean =
    generateSequence(path.toAbsolutePath()) { it.parent }
        .any {
            it.fileName?.toString().equals("BepInEx", true) ||
                it.resolve("NuclearOption.exe").isRegularFile()
        }

@Synchronized
internal fun recoverTransactions(folder: Path, homeOnly: Boolean = false) {
    if (!folder.exists()) return
    Files.list(folder).use { paths ->
        paths
            .filter { it.name.endsWith(".txn") }
            .toList()
            .forEach { journal ->
                val batch = json.decodeFromString<TransactionBatch>(journal.readText())
                if (
                    homeOnly &&
                        batch.changes.any { it.gameTarget || isGameTarget(Path.of(it.target)) }
                )
                    return@forEach
                val promotedBatch =
                    batch.changes.any {
                        it.phase == "promoted" ||
                            (it.staged != null &&
                                !Path.of(it.staged).exists() &&
                                Path.of(it.target).exists())
                    }
                batch.changes.asReversed().forEach { tx ->
                    val target = Path.of(tx.target)
                    val previous = Path.of(tx.previous)
                    require(
                        !tx.existed ||
                            target.exists() ||
                            previous.exists() ||
                            tx.staged?.let { Path.of(it).exists() } == true
                    ) {
                        "Transaction storage is unavailable; journal kept at $journal"
                    }
                    val promoted =
                        tx.phase == "promoted" ||
                            (tx.staged != null && !Path.of(tx.staged).exists() && target.exists())
                    if (promoted) {
                        // Promotion is the commit point. Keep later game writes in place.
                        if (previous.exists()) {
                            val kept =
                                folder.parent.resolve(
                                    "recovery/recovered-${journal.name}/${previous.name}"
                                )
                            Files.createDirectories(kept.parent)
                            Files.move(previous, kept)
                        }
                    } else if (previous.exists()) {
                        if (target.exists()) {
                            val kept =
                                folder.parent.resolve(
                                    "recovery/displaced-${journal.name}/${target.name}"
                                )
                            Files.createDirectories(kept.parent)
                            Files.move(target, kept)
                        }
                        Files.createDirectories(target.parent)
                        Files.move(previous, target)
                    }
                    tx.staged?.let { deleteTree(Path.of(it)) }
                    if (
                        previous.parent.isDirectory() &&
                            Files.list(previous.parent).use { !it.findAny().isPresent }
                    )
                        Files.delete(previous.parent)
                }
                if (promotedBatch && batch.commitPath != null && batch.commitText != null)
                    atomic(Path.of(batch.commitPath), batch.commitText)
                Files.delete(journal)
            }
    }
}

internal fun replaceTree(
    target: Path,
    source: Path?,
    journals: Path,
    keepPrevious: Boolean = true,
    afterCommit: Pair<Path, String>? = null,
) {
    if (source != null && target.exists() && treeHash(target) == treeHash(source)) {
        afterCommit?.let { atomic(it.first, it.second) }
        return
    }
    replaceTrees(listOf(target to source), journals, keepPrevious, afterCommit)
}

@Synchronized
internal fun replaceTrees(
    changes: List<Pair<Path, Path?>>,
    journals: Path,
    keepPrevious: Boolean = true,
    afterCommit: Pair<Path, String>? = null,
) {
    Files.createDirectories(journals)
    val id = java.util.UUID.randomUUID().toString()
    val prepared = mutableListOf<DiskTransaction>()
    val journal = journals.resolve("$id.txn")
    try {
        changes.forEachIndexed { index, (target, source) ->
            Files.createDirectories(target.parent)
            val bepinex =
                generateSequence(target.parent) { it.parent }
                    .firstOrNull { it.fileName?.toString() == "BepInEx" }
            val stagingRoot = (bepinex?.parent ?: target.parent).resolve(".nnomm-staging")
            Files.createDirectories(stagingRoot)
            val staged =
                source?.let {
                    stagingRoot.resolve("$id-$index").also { dest -> copyTree(it, dest) }
                }
            prepared +=
                DiskTransaction(
                    target.toString(),
                    staged?.toString(),
                    stagingRoot.resolve("old-$id-$index").toString(),
                    target.exists(),
                    gameTarget = isGameTarget(target),
                )
        }
        atomic(
            journal,
            json.encodeToString(
                TransactionBatch(prepared, afterCommit?.first?.toString(), afterCommit?.second)
            ),
        )
        DiskFaults.step("prepared")
        prepared.toList().forEachIndexed { index, tx ->
            val target = Path.of(tx.target)
            val previous = Path.of(tx.previous)
            if (target.exists()) Files.move(target, previous)
            prepared[index] = tx.copy(phase = "old-moved")
            atomic(
                journal,
                json.encodeToString(
                    TransactionBatch(prepared, afterCommit?.first?.toString(), afterCommit?.second)
                ),
            )
            DiskFaults.step("old-moved")
            tx.staged?.let { Files.move(Path.of(it), target) }
            forceDirectory(target.parent)
            prepared[index] = tx.copy(phase = "promoted")
            atomic(
                journal,
                json.encodeToString(
                    TransactionBatch(prepared, afterCommit?.first?.toString(), afterCommit?.second)
                ),
            )
            DiskFaults.step("promoted")
        }
        afterCommit?.let { atomic(it.first, it.second) }
        prepared.forEachIndexed { index, tx ->
            val previous = Path.of(tx.previous)
            if (keepPrevious && previous.exists()) {
                val recovery =
                    journals.parent.resolve("recovery/$id/$index/${Path.of(tx.target).fileName}")
                copyTree(previous, recovery)
            }
        }
        atomic(
            journals.parent.resolve("recovery/$id.json"),
            json.encodeToString(
                mapOf(
                    "what" to prepared.joinToString { it.target },
                    "when" to System.currentTimeMillis().toString(),
                    "why" to "Replacement",
                )
            ),
        )
        Files.delete(journal)
        forceDirectory(journals)
        prepared.forEach {
            val previous = Path.of(it.previous)
            deleteTree(previous)
            if (
                previous.parent.isDirectory() &&
                    Files.list(previous.parent).use { paths -> !paths.findAny().isPresent }
            )
                Files.delete(previous.parent)
        }
    } catch (e: Exception) {
        // Ordinary IO failures roll back now; a process crash leaves the same journal for startup.
        if (journal.exists()) {
            try {
                recoverTransactions(journals)
            } catch (recovery: Exception) {
                e.addSuppressed(recovery)
            }
        }
        if (!journal.exists())
            prepared.forEach { it.staged?.let { staged -> deleteTree(Path.of(staged)) } }
        throw e
    }
}

internal fun treeHash(root: Path): String =
    Files.walk(root).use { paths ->
        paths
            .filter { it.isRegularFile() }
            .sorted()
            .map { root.relativize(it).toString() + ":" + digest(it) }
            .toList()
            .joinToString("|")
    }
