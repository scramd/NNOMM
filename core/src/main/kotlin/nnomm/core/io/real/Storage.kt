package nnomm.core.io.real

import java.nio.file.*
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.builtins.serializer
import nnomm.core.io.*
import nnomm.core.model.*
import nnomm.core.rules.compareVersions
import org.apache.commons.compress.archivers.sevenz.SevenZFile

internal suspend fun unpack(file: Path, target: Path) {
    Files.createDirectories(target)
    var total = 0L
    fun budget(count: Int) {
        total += count
        require(total <= 2L * 1024 * 1024 * 1024) { "Archive exceeds the 2 GiB extraction limit." }
    }
    val magic = Files.newInputStream(file).use { it.readNBytes(6) }
    val format =
        when {
            magic.take(2) == listOf(0x50.toByte(), 0x4b.toByte()) -> "zip"
            magic.take(2) == listOf(0x1f.toByte(), 0x8b.toByte()) -> "tar.gz"
            magic.contentEquals(
                byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)
            ) -> "7z"
            magic.take(2) == listOf(0x4d.toByte(), 0x5a.toByte()) -> "dll"
            file.extension.lowercase() == "nobp" -> file.extension.lowercase()
            else -> error("Unsupported or invalid mod file: ${file.fileName}")
        }
    if (format == "dll") {
        val header = Files.newInputStream(file).use { it.readNBytes(64) }
        require(header.size == 64) { "This DLL is not a PE file." }
        val offset =
            java.nio.ByteBuffer.wrap(header, 60, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .int
                .toLong() and 0xffffffffL
        require(offset >= 64 && offset + 24 <= Files.size(file)) { "This DLL is not a PE file." }
        java.nio.channels.FileChannel.open(file).use { channel ->
            val signature = java.nio.ByteBuffer.allocate(4)
            channel.read(signature, offset)
            require(signature.array().contentEquals(byteArrayOf(80, 69, 0, 0))) {
                "This DLL is not a PE file."
            }
        }
    }
    val aliases = mutableSetOf<String>()
    val pathAliases = mutableMapOf<String, String>()
    fun destination(name: String): Path {
        require(aliases.add(name.trimEnd('/').lowercase())) { "Duplicate archive path: $name" }
        var prefix = ""
        name.trimEnd('/').split('/').forEach { part ->
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            val previous = pathAliases.putIfAbsent(prefix.lowercase(), prefix)
            require(previous == null || previous == prefix) { "Case-alias archive path: $name" }
        }
        return safe(target, name)
    }
    when (format) {
        "zip",
        "nommpack" ->
            ZipFile(file.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size <= 100_000 && entries.any { !it.isDirectory }) {
                    "Empty or oversized archive"
                }
                for (entry in entries) {
                    currentCoroutineContext().ensureActive()
                    val dest = destination(entry.name.replace('\\', '/'))
                    if (entry.isDirectory) Files.createDirectories(dest)
                    else {
                        Files.createDirectories(dest.parent)
                        zip.getInputStream(entry).use { input ->
                            Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW).use { out ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    currentCoroutineContext().ensureActive()
                                    budget(n)
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                    }
                }
            }
        "tar.gz" -> {
            org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream.builder()
                .setPath(file)
                .get()
                .use { gzip ->
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzip).use { tar
                        ->
                        var count = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = tar.nextEntry ?: break
                            require(++count <= 100_000 && !entry.isSymbolicLink && !entry.isLink) {
                                "Unsafe TAR entry"
                            }
                            val dest = destination(entry.name.trimEnd('/'))
                            if (entry.isDirectory) Files.createDirectories(dest)
                            else {
                                require(entry.isFile) { "Unsupported TAR entry" }
                                Files.createDirectories(dest.parent)
                                Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW).use { out
                                    ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val n = tar.read(buffer)
                                        if (n < 0) break
                                        budget(n)
                                        out.write(buffer, 0, n)
                                    }
                                }
                            }
                        }
                        require(count > 0) { "Empty TAR archive" }
                    }
                }
        }
        "7z" ->
            SevenZFile.builder().setPath(file).get().use { archive ->
                var count = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = archive.nextEntry ?: break
                    require(++count <= 100_000)
                    val dest = destination(entry.name.replace('\\', '/'))
                    if (entry.isDirectory) Files.createDirectories(dest)
                    else {
                        Files.createDirectories(dest.parent)
                        Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW).use { out ->
                            val buf = ByteArray(65536)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = archive.read(buf)
                                if (n < 0) break
                                budget(n)
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
        "dll",
        "nobp" -> Files.copy(file, safe(target, file.fileName.toString()))
        else -> error("Unsupported mod file: ${file.fileName}")
    }
}

class RealModStore(
    val home: Path,
    private val catalogStorePath: String? = null,
    private val catalog: () -> List<CatalogMod> = { emptyList() },
) : ModStore {
    private val mutex = Mutex()
    private var storeRoot =
        Path.of(catalogStorePath?.takeIf { it.isNotBlank() } ?: home.resolve("mods").toString())

    init {
        recoverTransactions(home.resolve("transactions"), homeOnly = true, notice = {})
    }

    private val undoUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    internal fun generation(id: String, version: String) =
        home.resolve("generations/${component(id)}-${component(version)}").let {
            if (it.exists()) it.readText() else ""
        }

    internal fun hashes(id: String, version: String): Map<String, String> {
        val file =
            JsonFile(
                home.resolve("hashes/${component(id)}/${component(version)}.json"),
                kotlinx.serialization.builtins.MapSerializer(
                    String.serializer(),
                    String.serializer(),
                ),
            )
        return file.read() ?: hashPayload(version(id, version)).also { file.write(it) }
    }

    private fun hashPayload(path: Path): Map<String, String> =
        Files.walk(path).use { files ->
            files
                .filter { it.isRegularFile() }
                .toList()
                .associate { path.relativize(it).toString().replace('\\', '/') to digest(it) }
        }

    fun version(id: String, version: String): Path {
        fun noAlias(parent: Path, name: String) {
            if (parent.isDirectory())
                Files.list(parent).use { paths ->
                    require(paths.noneMatch { it.name != name && it.name.equals(name, true) }) {
                        "Name differs only by case: $name"
                    }
                }
        }
        component(id)
        component(version)
        noAlias(storeRoot, id)
        val parent = safe(storeRoot, id)
        noAlias(parent, version)
        return safe(parent, version)
    }

    override suspend fun hasVersion(id: String, version: String): Boolean =
        withContext(Dispatchers.IO) { version(id, version).isDirectory() }

    override suspend fun put(id: String, version: String, file: DownloadedFile) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                undoUntil[id] = System.currentTimeMillis() + 9000
                val source = Path.of(file.path)
                val target = version(id, version)
                val root = home.resolve("staging")
                Files.createDirectories(root)
                val staging = Files.createTempDirectory(root, "unpack-")
                try {
                    if (source.isDirectory()) copyTreeContents(source, staging)
                    else unpack(source, staging)
                    require(Files.walk(staging).use { it.anyMatch { p -> p.isRegularFile() } }) {
                        "Empty mod payload"
                    }
                    val hashes =
                        JsonFile(
                            home.resolve("hashes/${component(id)}/${component(version)}.json"),
                            kotlinx.serialization.builtins.MapSerializer(
                                String.serializer(),
                                String.serializer(),
                            ),
                        )
                    hashes.write(hashPayload(staging))
                    val stamp = Files.createTempFile(root, "generation-", ".txt")
                    try {
                        stamp.writeText(java.util.UUID.randomUUID().toString())
                        replaceTrees(
                            listOf(
                                target to staging,
                                home.resolve(
                                    "generations/${component(id)}-${component(version)}"
                                ) to stamp,
                            ),
                            home.resolve("transactions"),
                        )
                    } finally {
                        Files.deleteIfExists(stamp)
                    }
                } finally {
                    deleteTree(staging)
                }
                undoUntil[id] = System.currentTimeMillis() + 9000
            }
        }

    override fun protect(ids: Set<String>) {
        synchronized(undoUntil) {
            ids.forEach { undoUntil[it] = System.currentTimeMillis() + 9000 }
        }
    }

    override suspend fun remove(ids: List<String>) =
        withContext(Dispatchers.IO) {
            // Logical removal only. Payloads stay available for Undo and live-game reconciliation.
            ids.forEach {
                component(it)
                undoUntil[it] = System.currentTimeMillis() + 9000
            }
        }

    override suspend fun restore(ids: List<String>) =
        withContext(Dispatchers.IO) {
            ids.forEach { id ->
                component(id)
                require(
                    Files.isDirectory(safe(storeRoot, id)) &&
                        Files.list(safe(storeRoot, id)).use { it.anyMatch { p -> p.isDirectory() } }
                ) {
                    "Stored files are missing for $id."
                }
            }
        }

    override suspend fun addLocal(path: String): Pair<CatalogMod, InstalledMod> =
        withContext(Dispatchers.IO) {
            val input = Path.of(path)
            require(input.isRegularFile()) { "Import file is missing" }
            val hash = digest(input)
            val match =
                catalog().firstNotNullOfOrNull { mod ->
                    mod.versions.firstOrNull { normalizedHash(it.hash) == hash }?.let { mod to it }
                }
            val id =
                match?.first?.id
                    ?: "local-" +
                        input.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9_-]"), "-") +
                        "-" +
                        hash.take(8)
            val v = match?.second?.version ?: hash.take(12)
            val mod =
                match?.first
                    ?: CatalogMod(
                        id,
                        input.nameWithoutExtension,
                        versions = listOf(ModVersion(v)),
                        localFile = input.fileName.toString(),
                    )
            put(id, v, DownloadedFile(path))
            mod to
                InstalledMod(
                    id,
                    if (match == null) ModSource.LOCAL else ModSource.REGISTRY,
                    input.fileName.toString(),
                    setOf(v),
                    v,
                )
        }

    override suspend fun usedBytes(): Long =
        withContext(Dispatchers.IO) {
            (listOf(storeRoot, home.resolve("player-files"), home.resolve("player-edits")) +
                    recoveryCopies())
                .let { roots ->
                    roots.filter { path -> roots.none { it != path && path.startsWith(it) } }
                }
                .filter { it.exists() }
                .sumOf { root ->
                    Files.walk(root).use { files ->
                        files.filter { it.isRegularFile() }.mapToLong { it.fileSize() }.sum()
                    }
                }
        }

    override suspend fun moveTo(folder: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val target = Path.of(folder).toAbsolutePath().normalize()
                val original = storeRoot.toAbsolutePath().normalize()
                if (target == original) return@withLock
                require(!target.startsWith(original) && !original.startsWith(target))
                require(!target.exists() || Files.list(target).use { !it.findAny().isPresent })
                if (original.exists()) copyTree(original, target)
                else Files.createDirectories(target)
                storeRoot = target
                // Keep the original copy recoverable; never delete on a store move.
            }
        }

    /** Apply retention to unreferenced versions; trash is explicit and never automatic. */
    suspend fun unused(state: AppState): List<Path> =
        withContext(Dispatchers.IO) {
            val keepOld =
                when (state.settings.general["keepOld"]?.content) {
                    "None" -> 0
                    "3 versions" -> 3
                    else -> 1
                }
            val root = storeRoot
            if (!root.exists()) return@withContext emptyList()
            Files.list(root).use { ids ->
                ids.toList().flatMap { dir ->
                    if (dir.name.startsWith(".") || !dir.isDirectory()) return@flatMap emptyList()
                    val m = state.installed[dir.name]
                    val referenced =
                        m?.profiles
                            ?.filterKeys { p -> state.profiles.any { it.id == p } }
                            ?.values
                            ?.map { it.version }
                            ?.toSet()
                            .orEmpty() +
                            listOfNotNull(m?.defaultVersion) +
                            state.downloads
                                .filter { it.id == dir.name && it.status == DownloadStatus.ACTIVE }
                                .map { it.version }
                    val versions =
                        Files.list(dir)
                            .use { paths ->
                                paths
                                    .filter { !it.name.startsWith(".") && it.isDirectory() }
                                    .toList()
                            }
                            .sortedWith { a, b -> compareVersions(b.name, a.name) }
                    // Only a version that the registry can deliver again may be cleared:
                    // local files and taken-over copies are the player's only copy.
                    val downloadable =
                        catalog()
                            .firstOrNull { it.id == dir.name }
                            ?.versions
                            ?.filter { !it.downloadUrl.isNullOrBlank() }
                            ?.map { it.version }
                            ?.toSet()
                            .orEmpty()
                    val spare =
                        versions.filter {
                            it.name !in referenced &&
                                it.name in downloadable &&
                                m?.source != ModSource.LOCAL &&
                                ((undoUntil[dir.name] ?: 0) < System.currentTimeMillis())
                        }
                    spare.drop(if (m == null) 0 else keepOld)
                }
            }
        }

    override suspend fun unusedBytes(state: AppState): Long =
        withContext(Dispatchers.IO) {
            (unused(state) + clearableRecovery(state)).sumOf { dir ->
                Files.walk(dir).use {
                    it.filter { p -> p.isRegularFile() }.mapToLong { p -> p.fileSize() }.sum()
                }
            }
        }

    private fun recoveryCopies(): List<Path> {
        val root = home.resolve("recovery")
        if (!root.isDirectory()) return emptyList()
        val copies =
            Files.list(root).use { files ->
                files
                    .toList()
                    .flatMap { file ->
                        if (file.isDirectory()) listOf(file)
                        else if (file.name.endsWith(".json"))
                            runCatching {
                                    json
                                        .decodeFromString<Map<String, String>>(file.readText())[
                                            "copies"]
                                        .orEmpty()
                                        .lines()
                                        .filter { it.isNotBlank() }
                                        .map { Path.of(it) }
                                }
                                .getOrDefault(emptyList())
                        else emptyList()
                    }
                    .filter { it.isDirectory() && !isLink(it) }
                    .distinct()
            }
        return copies.filter { path -> copies.none { it != path && path.startsWith(it) } }
    }

    internal fun recoveryOverLimit(): Boolean {
        val copies = recoveryCopies()
        if (copies.size > 100) return true
        return copies.sumOf { root ->
            Files.walk(root).use { files ->
                files.filter { it.isRegularFile() }.mapToLong { it.fileSize() }.sum()
            }
        } > 256L * 1024 * 1024
    }

    /** Only remove old recovery trees whose every byte also exists outside recovery. */
    private fun clearableRecovery(state: AppState): List<Path> {
        val copies = recoveryCopies()
        val live = mutableSetOf<String>()
        val roots =
            listOfNotNull(
                storeRoot,
                home.resolve("player-files"),
                home.resolve("player-edits"),
                home.resolve("profiles"),
                state.game.folder?.let { Path.of(it).resolve("BepInEx") },
            )
        roots
            .filter { it.exists() }
            .forEach { folder ->
                Files.walk(folder).use { paths ->
                    paths
                        .filter { file ->
                            file.isRegularFile() &&
                                !isLink(file) &&
                                copies.none { file.startsWith(it) }
                        }
                        .forEach { live += digest(it) }
                }
            }
        return copies
            .filter { it.getLastModifiedTime().toMillis() < System.currentTimeMillis() - 9000 }
            .filter { folder ->
                Files.walk(folder).use { files ->
                    files.filter { it.isRegularFile() }.allMatch { digest(it) in live }
                }
            }
    }

    override suspend fun clearUnused(state: AppState) = clearUnused { state }

    override suspend fun clearUnused(current: () -> AppState) =
        withContext(Dispatchers.IO) {
            val state = current()
            require(!state.gameRunning && !gameProcessRunning()) {
                "Close the game before clearing downloaded files."
            }
            mutex.withLock {
                val state = current()
                unused(state).forEach { path ->
                    synchronized(undoUntil) {
                        if ((undoUntil[path.parent.name] ?: 0) < System.currentTimeMillis())
                            deleteTree(path)
                    }
                }
                // Recompute after retention: a payload just deleted cannot protect recovery.
                clearableRecovery(state).forEach(::deleteTree)
            }
        }
}

internal fun copyTreeContents(from: Path, to: Path, link: Boolean = false) {
    Files.list(from).use { it.forEach { p -> copyTree(p, safe(to, p.name), link) } }
}
