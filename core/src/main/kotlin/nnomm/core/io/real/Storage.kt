package nnomm.core.io.real

import java.nio.file.*
import java.util.zip.ZipInputStream
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
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
    when (file.extension.lowercase()) {
        "zip",
        "nommpack" ->
            ZipInputStream(Files.newInputStream(file)).use { zip ->
                var count = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(++count <= 100_000) { "Too many archive entries" }
                    val dest = safe(target, entry.name.replace('\\', '/'))
                    if (entry.isDirectory) Files.createDirectories(dest)
                    else {
                        Files.createDirectories(dest.parent)
                        Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW).use { out ->
                            val buf = ByteArray(65536)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = zip.read(buf)
                                if (n < 0) break
                                budget(n)
                                out.write(buf, 0, n)
                            }
                        }
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
                    val dest = safe(target, entry.name.replace('\\', '/'))
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
    private var storeRoot = Path.of(catalogStorePath ?: home.resolve("mods").toString())
    private val undoUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun version(id: String, version: String) =
        safe(safe(storeRoot, component(id)), component(version))

    override suspend fun hasVersion(id: String, version: String): Boolean =
        withContext(Dispatchers.IO) { version(id, version).isDirectory() }

    override suspend fun put(id: String, version: String, file: DownloadedFile) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val source = Path.of(file.path)
                val target = version(id, version)
                val root = home.resolve("staging")
                Files.createDirectories(root)
                val staging = Files.createTempDirectory(root, "unpack-")
                try {
                    if (source.isDirectory()) copyTreeContents(source, staging)
                    else unpack(source, staging)
                    if (target.exists()) {
                        // Repair keeps the old payload, including unexpected files, recoverable.
                        val backup =
                            home.resolve(
                                "recovery/${System.nanoTime()}/${component(id)}/${component(version)}"
                            )
                        Files.createDirectories(backup.parent)
                        Files.move(target, backup)
                    }
                    Files.createDirectories(target.parent)
                    Files.move(staging, target)
                    // Only consume files created by our downloader; never the user's import.
                    if (
                        source
                            .toAbsolutePath()
                            .normalize()
                            .startsWith(home.resolve("staging").toAbsolutePath().normalize())
                    )
                        deleteTree(source.parent)
                } finally {
                    deleteTree(staging)
                }
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
        withContext(Dispatchers.IO) { ids.forEach { component(it) } }

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
            if (!storeRoot.exists()) 0
            else
                Files.walk(storeRoot).use { files ->
                    files.filter { it.isRegularFile() }.mapToLong { it.fileSize() }.sum()
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
                    val m = state.installed[dir.name]
                    val referenced =
                        m?.profiles
                            ?.filterKeys { p -> state.profiles.any { it.id == p } }
                            ?.values
                            ?.map { it.version }
                            ?.toSet()
                            .orEmpty() + listOfNotNull(m?.defaultVersion)
                    val versions =
                        Files.list(dir)
                            .use { it.toList() }
                            .sortedWith { a, b -> compareVersions(b.name, a.name) }
                    val spare =
                        versions.filter {
                            it.name !in referenced &&
                                (undoUntil[dir.name] ?: 0) < System.currentTimeMillis()
                        }
                    spare.drop(if (m == null) 0 else keepOld)
                }
            }
        }

    override suspend fun unusedBytes(state: AppState): Long =
        withContext(Dispatchers.IO) {
            unused(state).sumOf { dir ->
                Files.walk(dir).use {
                    it.filter { p -> p.isRegularFile() }.mapToLong { p -> p.fileSize() }.sum()
                }
            }
        }

    override suspend fun clearUnused(state: AppState) =
        withContext(Dispatchers.IO) {
            require(!state.gameRunning && !gameProcessRunning()) {
                "Close the game before clearing downloaded files."
            }
            unused(state).forEach(::deleteTree)
        }
}

internal fun copyTreeContents(from: Path, to: Path, link: Boolean = false) {
    Files.list(from).use { it.forEach { p -> copyTree(p, safe(to, p.name), link) } }
}
