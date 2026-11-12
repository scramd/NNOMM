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
        require(!isLink(at)) { "Symbolic links are not managed: $at" }
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

internal fun atomic(
    path: Path,
    text: String,
    charset: java.nio.charset.Charset = StandardCharsets.UTF_8,
    createParents: Boolean = true,
) {
    safe(path.parent, path.fileName.toString())
    if (createParents) Files.createDirectories(path.parent)
    val tmp = Files.createTempFile(path.parent, ".nnomm-", ".tmp")
    try {
        FileChannel.open(tmp, StandardOpenOption.WRITE).use { ch ->
            val buffer = charset.encode(text)
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
    require(!isLink(from))
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

/** Refuse links, special entries, and Windows junctions that canonicalize elsewhere. */
internal fun isLink(path: Path): Boolean {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Files.isSymbolicLink(path)
    val attributes =
        Files.readAttributes(
            path,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    if (attributes.isSymbolicLink || attributes.isOther) return true
    // Junctions can report as directories rather than isOther. Resolve this entry separately
    // from its parent, so an ordinary child inside a canonical game path still compares equal.
    if (System.getProperty("os.name").startsWith("Windows", true)) {
        val absolute = path.toAbsolutePath().normalize()
        val parent = absolute.parent ?: return false
        return absolute.toRealPath() != parent.toRealPath().resolve(absolute.fileName)
    }
    return false
}

internal fun rejectLinks(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(path).use { paths ->
        paths.forEach { require(!isLink(it)) { "Links and junctions are not managed: $it" } }
    }
}

internal fun deleteTree(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    if (!isLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        Files.list(path).use { it.forEach(::deleteTree) }
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
    val trouble: TroubleSession? = null,
    val deferredModChange: Boolean = false,
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
                    trouble = it.trouble,
                    deferredModChange = it.deferredModChange,
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
                    state.trouble,
                    state.deferredModChange,
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
    val promotionReport: String? = null,
    val commitRemovals: Boolean = false,
    val operationId: String? = null,
)

@Serializable
internal data class PromotionReport(
    val operationId: String?,
    val targets: List<String>,
    val preserved: List<String> = emptyList(),
)

private fun reportPromotions(batch: TransactionBatch) {
    batch.promotionReport?.let { report ->
        atomic(
            Path.of(report),
            json.encodeToString(
                PromotionReport(
                    batch.operationId,
                    batch.changes
                        .filter { it.phase == "promoted" || it.phase == "recover-commit" }
                        .map { it.target },
                    batch.changes.filter { it.phase == "recover-preserve" }.map { it.target },
                )
            ),
        )
    }
}

internal class DiskCrash : Error("Simulated process interruption")

internal object DiskFaults {
    var step: (String) -> Unit = {}
    var beforeMove: (Path) -> Unit = {}
}

private fun isGameTarget(path: Path): Boolean =
    generateSequence(path.toAbsolutePath()) { it.parent }
        .any {
            it.fileName?.toString().equals("BepInEx", true) ||
                it.resolve("NuclearOption.exe").isRegularFile()
        }

internal fun recoveryPath(journals: Path, journal: Path, tx: DiskTransaction): Path {
    val previous = Path.of(tx.previous)
    val local =
        previous.parent.parent.resolve(
            ".nnomm/recovery/${journal.name}/${previous.name}/${Path.of(tx.target).fileName}"
        )
    val sameDrive =
        runCatching {
                Files.getFileStore(
                    generateSequence(Path.of(tx.target).parent) { it.parent }.first { it.exists() }
                ) == Files.getFileStore(journals.parent)
            }
            .getOrDefault(false)
    return if (!tx.gameTarget && sameDrive)
        journals.parent.resolve(
            "recovery/${journal.name}/${previous.name}/${Path.of(tx.target).fileName}"
        )
    else local
}

internal fun indexRecovery(journals: Path, journal: Path, changes: List<DiskTransaction>) {
    atomic(
        journals.parent.resolve("recovery/${journal.name}.json"),
        json.encodeToString(
            mapOf(
                "copies" to
                    changes.joinToString("\n") {
                        recoveryPath(journals, journal, it).parent.toString()
                    },
                "what" to changes.joinToString { it.target },
                "when" to System.currentTimeMillis().toString(),
                "why" to "Replacement or interrupted apply",
            )
        ),
    )
}

/** Recover journals independently. Strict mode is used by tests and the owning call. */
@Synchronized
internal fun recoverTransactions(
    folder: Path,
    homeOnly: Boolean = false,
    notice: ((String) -> Unit)? = null,
    only: Path? = null,
) {
    if (!folder.exists()) return
    Files.list(folder).use { paths ->
        paths
            .filter { it.name.endsWith(".txn") && (only == null || it == only) }
            .toList()
            .forEach { journal ->
                try {
                    var batch =
                        try {
                            json.decodeFromString<TransactionBatch>(journal.readText())
                        } catch (e: kotlinx.serialization.SerializationException) {
                            if (folder.fileName.toString() != "transactions" || notice == null)
                                throw e
                            val aside = folder.resolve("damaged-records")
                            Files.createDirectories(aside)
                            Files.move(
                                journal,
                                aside.resolve(
                                    journal.fileName.toString() + "-" + java.util.UUID.randomUUID()
                                ),
                            )
                            notice(
                                "A record of an unfinished change is damaged. NNOMM set it aside in recovery storage; your files have been kept."
                            )
                            return@forEach
                        }
                    if (
                        homeOnly &&
                            batch.changes.any { it.gameTarget || isGameTarget(Path.of(it.target)) }
                    )
                        return@forEach
                    // Persist the direction before rollback can remove staging or restore the
                    // target.
                    // Only a persisted rollback can explain a previous copy already moved back.
                    require(
                        batch.changes.none { tx ->
                            tx.phase == "old-moved" &&
                                tx.existed &&
                                !Path.of(tx.previous).exists() &&
                                !Path.of(tx.target).exists()
                        }
                    ) {
                        "Transaction storage is unavailable; journal kept at $journal"
                    }
                    batch =
                        batch.copy(
                            changes =
                                batch.changes.map { tx ->
                                    if (
                                        batch.operationId != null &&
                                            tx.phase == "recover-rollback" &&
                                            Path.of(tx.previous).exists() &&
                                            Path.of(tx.target).exists()
                                    )
                                        tx.copy(phase = "recover-preserve")
                                    else if (tx.phase.startsWith("recover-")) tx
                                    else
                                        tx.copy(
                                            phase =
                                                if (
                                                    tx.phase == "promoted" ||
                                                        (batch.commitRemovals &&
                                                            tx.staged == null &&
                                                            Path.of(tx.previous).exists()) ||
                                                        (tx.phase == "old-moved" &&
                                                            tx.staged != null &&
                                                            !Path.of(tx.staged).exists() &&
                                                            Path.of(tx.target).exists())
                                                )
                                                    "recover-commit"
                                                else if (
                                                    batch.operationId != null &&
                                                        Path.of(tx.previous).exists() &&
                                                        Path.of(tx.target).exists()
                                                )
                                                    "recover-preserve"
                                                else if (
                                                    tx.phase in listOf("prepared", "copying") &&
                                                        !Path.of(tx.previous).exists()
                                                )
                                                    "recover-unmoved"
                                                else "recover-rollback"
                                        )
                                }
                        )
                    atomic(journal, json.encodeToString(batch))
                    val committed = batch.changes.any { it.phase == "recover-commit" }
                    reportPromotions(batch)
                    indexRecovery(folder, journal, batch.changes)
                    batch.changes.indices.reversed().forEach { index ->
                        var tx = batch.changes[index]
                        // Recheck at the mutation too: another recovery entry may have run
                        // since the direction was saved, or this recovery may be resuming.
                        if (
                            batch.operationId != null &&
                                tx.phase == "recover-rollback" &&
                                Path.of(tx.previous).exists() &&
                                (Path.of(tx.target).exists() || !Path.of(tx.target).parent.exists())
                        ) {
                            tx = tx.copy(phase = "recover-preserve")
                            batch =
                                batch.copy(
                                    changes =
                                        batch.changes.mapIndexed { i, entry ->
                                            if (i == index) tx else entry
                                        }
                                )
                            atomic(journal, json.encodeToString(batch))
                            reportPromotions(batch)
                        }
                        val target = Path.of(tx.target)
                        val previous = Path.of(tx.previous)
                        val kept = recoveryPath(folder, journal, tx)
                        if (tx.phase == "recover-rollback-done") {
                            // A later deletion belongs to the player. Never restore it again.
                        } else if (tx.phase == "recover-commit") {
                            if (previous.exists()) {
                                Files.createDirectories(kept.parent)
                                Files.move(previous, kept)
                            }
                        } else if (tx.phase == "recover-preserve") {
                            // A hand replacement after a loader crash wins over rollback.
                            Files.createDirectories(kept.parent)
                            if (previous.exists()) Files.move(previous, kept)
                        } else if (previous.exists()) {
                            if (target.exists()) {
                                val displaced = kept.resolveSibling("displaced-${previous.name}")
                                Files.createDirectories(displaced.parent)
                                Files.move(target, displaced)
                            }
                            Files.createDirectories(target.parent)
                            Files.move(previous, target)
                            DiskFaults.step("recovery-rollback-restored")
                        } else
                            require(
                                tx.phase in listOf("recover-unmoved", "recover-rollback") ||
                                    !tx.existed ||
                                    target.exists()
                            ) {
                                "Transaction storage is unavailable; journal kept at $journal"
                            }
                        if (tx.phase == "recover-rollback") {
                            batch =
                                batch.copy(
                                    changes =
                                        batch.changes.mapIndexed { i, entry ->
                                            if (i == index)
                                                entry.copy(phase = "recover-rollback-done")
                                            else entry
                                        }
                                )
                            atomic(journal, json.encodeToString(batch))
                        }
                        tx.staged?.let { deleteTree(Path.of(it)) }
                        DiskFaults.step("recovery-staged-deleted")
                    }
                    if (committed && batch.commitPath != null && batch.commitText != null)
                        atomic(Path.of(batch.commitPath!!), batch.commitText!!)
                    Files.delete(journal)
                } catch (e: Exception) {
                    if (notice == null) throw e
                    notice(
                        "Some files still need recovery. Their recovery record and files are kept. Other files can still be changed. Restore unavailable storage, then Retry."
                    )
                }
            }
    }
}

internal fun replaceTree(
    target: Path,
    source: Path?,
    journals: Path,
    keepPrevious: Boolean = true,
    afterCommit: Pair<Path, String>? = null,
    beforeMutation: () -> Unit = {},
) {
    rejectLinks(target)
    source?.let(::rejectLinks)
    if (source != null && target.exists() && treeHash(target) == treeHash(source)) {
        afterCommit?.let { atomic(it.first, it.second) }
        return
    }
    replaceTrees(listOf(target to source), journals, keepPrevious, afterCommit, beforeMutation)
}

@Synchronized
internal fun replaceTrees(
    changes: List<Pair<Path, Path?>>,
    journals: Path,
    keepPrevious: Boolean = true,
    afterCommit: Pair<Path, String>? = null,
    beforeMutation: () -> Unit = {},
    promotionReport: Path? = null,
    commitRemovals: Boolean = false,
    operationId: String? = null,
    eligible: (Path) -> Boolean = { true },
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
            rejectLinks(target)
            val staged = source?.let { stagingRoot.resolve("$id-$index") }
            prepared +=
                DiskTransaction(
                    target.toString(),
                    staged?.toString(),
                    stagingRoot.resolve("old-$id-$index").toString(),
                    target.exists(),
                    gameTarget = isGameTarget(target),
                    phase = "copying",
                )
            atomic(
                journal,
                json.encodeToString(
                    TransactionBatch(
                        prepared,
                        afterCommit?.first?.toString(),
                        afterCommit?.second,
                        promotionReport?.toString(),
                        commitRemovals,
                        operationId,
                    )
                ),
            )
            if (source != null) copyTree(source, staged!!)
            prepared[index] = prepared[index].copy(phase = "prepared")
        }
        atomic(
            journal,
            json.encodeToString(
                TransactionBatch(
                    prepared,
                    afterCommit?.first?.toString(),
                    afterCommit?.second,
                    promotionReport?.toString(),
                    commitRemovals,
                    operationId,
                )
            ),
        )
        DiskFaults.step("prepared")
        beforeMutation()
        prepared.toList().forEachIndexed { index, tx ->
            val target = Path.of(tx.target)
            val previous = Path.of(tx.previous)
            DiskFaults.beforeMove(target)
            if (!eligible(target)) {
                prepared[index] = tx.copy(phase = "recover-preserve")
                atomic(
                    journal,
                    json.encodeToString(
                        TransactionBatch(
                            prepared,
                            afterCommit?.first?.toString(),
                            afterCommit?.second,
                            promotionReport?.toString(),
                            commitRemovals,
                            operationId,
                        )
                    ),
                )
                tx.staged?.let { deleteTree(Path.of(it)) }
                return@forEachIndexed
            }
            if (target.exists()) Files.move(target, previous)
            DiskFaults.step("old-rename-before-journal")
            prepared[index] = tx.copy(phase = "old-moved")
            atomic(
                journal,
                json.encodeToString(
                    TransactionBatch(
                        prepared,
                        afterCommit?.first?.toString(),
                        afterCommit?.second,
                        promotionReport?.toString(),
                        commitRemovals,
                        operationId,
                    )
                ),
            )
            DiskFaults.step("old-moved")
            tx.staged?.let { Files.move(Path.of(it), target) }
            DiskFaults.step("new-rename-before-journal")
            forceDirectory(target.parent)
            prepared[index] = tx.copy(phase = "promoted")
            atomic(
                journal,
                json.encodeToString(
                    TransactionBatch(
                        prepared,
                        afterCommit?.first?.toString(),
                        afterCommit?.second,
                        promotionReport?.toString(),
                        commitRemovals,
                        operationId,
                    )
                ),
            )
            DiskFaults.step("promoted")
        }
        reportPromotions(
            TransactionBatch(
                prepared,
                promotionReport = promotionReport?.toString(),
                operationId = operationId,
            )
        )
        afterCommit?.let { atomic(it.first, it.second) }
        DiskFaults.step("ownership-published")
        indexRecovery(journals, journal, prepared)
        prepared.forEachIndexed { index, tx ->
            val previous = Path.of(tx.previous)
            if (keepPrevious && previous.exists()) {
                val recovery = recoveryPath(journals, journal, tx)
                Files.createDirectories(recovery.parent)
                Files.move(previous, recovery)
            }
        }
        indexRecovery(journals, journal, prepared)
        prepared.forEach {
            val previous = Path.of(it.previous)
            deleteTree(previous)
            if (
                previous.parent.isDirectory() &&
                    Files.list(previous.parent).use { paths -> !paths.findAny().isPresent }
            )
                Files.delete(previous.parent)
        }
        Files.delete(journal)
        DiskFaults.step("journal-deleted")
        forceDirectory(journals)
    } catch (e: Exception) {
        // Ordinary IO failures roll back now; a process crash leaves the same journal for startup.
        val committed =
            prepared.any {
                it.phase == "promoted" ||
                    (it.phase == "old-moved" &&
                        it.staged != null &&
                        !Path.of(it.staged).exists() &&
                        Path.of(it.target).exists())
            }
        if (journal.exists()) {
            try {
                recoverTransactions(journals, only = journal)
            } catch (recovery: Exception) {
                e.addSuppressed(recovery)
            }
        }
        if (!journal.exists())
            prepared.forEach { it.staged?.let { staged -> deleteTree(Path.of(staged)) } }
        if (committed && !journal.exists()) return
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

internal fun pendingTransactions(folder: Path): Boolean =
    folder.isDirectory() &&
        Files.list(folder).use { paths -> paths.anyMatch { it.name.endsWith(".txn") } }

/**
 * Unknown home journals stay visible, but cannot identify an affected game entry. Unknown game
 * journals block game entries because their targets may be in this game.
 */
internal fun pendingTouches(folder: Path, target: Path, unknownBlocks: Boolean = false): Boolean {
    if (!folder.isDirectory()) return false
    val normalized = target.toAbsolutePath().normalize()
    return Files.list(folder).use { paths ->
        paths
            .filter { it.name.endsWith(".txn") }
            .anyMatch { journal ->
                val batch =
                    runCatching { json.decodeFromString<TransactionBatch>(journal.readText()) }
                        .getOrNull()
                if (batch == null) unknownBlocks
                else
                    (batch.changes.map { it.target } + listOfNotNull(batch.commitPath)).any {
                        val pending = Path.of(it).toAbsolutePath().normalize()
                        pending.startsWith(normalized) || normalized.startsWith(pending)
                    }
            }
    }
}

/** Runtime owns the lock for its lifetime; direct IO clients lock each operation. */
internal object HomeLocks {
    val runtimeHomes = java.util.concurrent.ConcurrentHashMap.newKeySet<Path>()

    suspend fun <T> operation(home: Path, block: suspend () -> T): T {
        Files.createDirectories(home)
        if (home.toRealPath() in runtimeHomes) return block()
        FileChannel.open(home.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            .use { channel ->
                val lock =
                    channel.tryLock()
                        ?: error("This NNOMM home is already open in another process.")
                try {
                    return block()
                } finally {
                    lock.release()
                }
            }
    }
}

/** These home-only snapshots are copies, never the sole committed player payload. */
internal fun cleanHomePreparation(home: Path) {
    val staging = home.resolve("staging")
    if (!staging.isDirectory() || isLink(staging)) return
    Files.list(staging).use { paths ->
        paths
            .filter { path ->
                listOf("player-", "edits-", "config-", "learn-shipped-").any {
                    path.fileName.toString().startsWith(it)
                }
            }
            .forEach(::deleteTree)
    }
}
