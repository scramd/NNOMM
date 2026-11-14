package nnomm.core.io.real

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import java.nio.file.*
import java.util.zip.*
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import nnomm.core.config.modConfigFiles
import nnomm.core.config.modConfigFilesIn
import nnomm.core.model.*
import nnomm.core.rules.compareVersions

@Serializable
private data class PackDocument(
    val format: String = "NNOMM/1",
    val pack: Pack,
    val settings: SettingsValues? = null,
)

data class PackPreview(
    val pack: Pack,
    val localFiles: Map<String, String>,
    val settings: SettingsValues?,
    val stagingFolder: Path?,
) : AutoCloseable {
    val modSettingsFolder: Path?
        get() = stagingFolder?.resolve("config")?.takeIf { it.isDirectory() }

    override fun close() {
        stagingFolder?.let(::deleteTree)
    }
}

/** Reading is a preview only: bundled local mods are never enabled or copied into the game. */
class RealPacks(
    private val home: Path,
    private val mods: RealModStore,
    private val client: HttpClient,
) : nnomm.core.io.PackSource {
    private fun localHash(source: Path) =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(treeHash(source).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun contentPreview(
        pack: Pack,
        files: Map<String, String>,
        settings: SettingsValues?,
        staging: Path?,
    ): PackPreview {
        val identities = files.mapValues { (_, path) -> localHash(Path.of(path)) }
        return PackPreview(
            pack.copy(
                items =
                    pack.items
                        .map { item ->
                            identities[item.id]?.let { hash ->
                                item.copy(id = "local-$hash", version = hash.take(12))
                            } ?: item
                        }
                        .distinctBy { it.id }
            ),
            files.entries.associate { (id, path) -> "local-${identities.getValue(id)}" to path },
            settings,
            staging,
        )
    }

    suspend fun read(path: Path): PackPreview =
        withContext(Dispatchers.IO) {
            require(path.fileSize() <= 256L * 1024 * 1024) { "Pack exceeds 256 MiB" }
            val first = Files.newInputStream(path).use { it.readNBytes(2) }
            if (!first.contentEquals(byteArrayOf(80, 75))) {
                require(path.fileSize() <= 16L * 1024 * 1024) { "Mod list exceeds 16 MiB" }
                val text = path.readText()
                if (text.trimStart().startsWith("["))
                    return@withContext legacy(text, path.name, null)
                val document = json.decodeFromString<PackDocument>(text)
                require(document.format == "NNOMM/1")
                return@withContext contentPreview(
                    document.pack,
                    emptyMap(),
                    document.settings?.let(::packSettings),
                    null,
                )
            }
            Files.createDirectories(home.resolve("staging"))
            val staging = Files.createTempDirectory(home.resolve("staging"), "pack-")
            try {
                unpack(path, staging)
                val doc = staging.resolve("pack.json")
                if (doc.exists()) {
                    val document = json.decodeFromString<PackDocument>(doc.readText())
                    require(document.format == "NNOMM/1") { "Unsupported NNOMM pack format" }
                    val locals =
                        document.pack.items
                            .filter { it.kind == PackKind.LOCAL }
                            .associate { item ->
                                item.id to safe(staging, "mods/${component(item.id)}").toString()
                            }
                    contentPreview(
                        document.pack,
                        locals,
                        document.settings?.let(::packSettings),
                        staging,
                    )
                } else {
                    val list = staging.resolve("modlist.nomm.json")
                    require(list.isRegularFile()) { "Pack has no mod list" }
                    legacy(list.readText(), path.name, staging)
                }
            } catch (e: Exception) {
                deleteTree(staging)
                throw e
            }
        }

    private fun legacy(text: String, name: String, staging: Path?): PackPreview {
        val refs = json.decodeFromString<List<PackageReference>>(text)
        val localRoot = staging?.resolve("mods")
        val locals =
            if (localRoot?.exists() == true)
                Files.list(localRoot).use {
                    it.toList().associate { p -> "local-" + p.name to p.toString() }
                }
            else emptyMap()
        // NOMM also lists a bundled mod in its mod list, by its folder or file name or the id in
        // its meta.json. Such a mod comes from the file in the pack, not from the registry.
        val bundled = locals.values.flatMap { bundledIds(Path.of(it)) }.toSet()
        return contentPreview(
            Pack(
                name.substringBeforeLast('.'),
                "",
                "1.0",
                refs.filter { it.id !in bundled }.map { PackItem(PackKind.VER, it.id, it.version) } +
                    locals.keys.map { PackItem(PackKind.LOCAL, it) },
            ),
            locals,
            null,
            staging,
        )
    }

    suspend fun write(path: Path, pack: Pack, state: AppState) =
        withContext(Dispatchers.IO) {
            Files.createDirectories(path.toAbsolutePath().parent)
            val temp = Files.createTempFile(path.toAbsolutePath().parent, ".nnomm-pack-", ".tmp")
            try {
                ZipOutputStream(Files.newOutputStream(temp)).use { zip ->
                    zip.putNextEntry(ZipEntry("pack.json"))
                    zip.write(
                        json
                            .encodeToString(
                                PackDocument(
                                    pack = pack,
                                    settings =
                                        if (pack.includeSettings)
                                            packSettings(
                                                SettingsValues(
                                                    state.settings.general +
                                                        state.settings.overrides[state.profileId]
                                                            .orEmpty()
                                                )
                                            )
                                        else null,
                                )
                            )
                            .toByteArray()
                    )
                    zip.closeEntry()
                    fun add(src: Path, prefix: String) {
                        require(!Files.isSymbolicLink(src))
                        if (src.isDirectory())
                            Files.list(src).use { it.forEach { p -> add(p, "$prefix/${p.name}") } }
                        else {
                            zip.putNextEntry(ZipEntry(prefix))
                            Files.copy(src, zip)
                            zip.closeEntry()
                        }
                    }
                    if (pack.includeSettings) {
                        val snapshot = home.resolve("profiles/${component(state.profileId)}/config")
                        val config =
                            state.game.folder
                                ?.let { safe(Path.of(it), "BepInEx/config") }
                                ?.takeIf { it.isDirectory() }
                                ?: snapshot.takeIf { it.isDirectory() }
                        config?.let { folder ->
                            val names =
                                pack.items
                                    .flatMap { item ->
                                        val payload =
                                            item.version?.let { mods.version(item.id, it) }
                                        if (payload == null) emptyList()
                                        else {
                                            if (folder == snapshot) {
                                                modConfigFilesIn(
                                                    folder,
                                                    payload,
                                                    item.id,
                                                    state.mod(item.id).name,
                                                )
                                            } else
                                                modConfigFiles(
                                                    Path.of(requireNotNull(state.game.folder)),
                                                    payload,
                                                    item.id,
                                                    state.mod(item.id).name,
                                                )
                                        }
                                    }
                                    .map { it.fileName.toString() }
                                    .toSet()
                            names.forEach { name ->
                                val file = safe(folder, name)
                                if (file.isRegularFile()) add(file, "config/$name")
                            }
                        }
                    }
                    pack.items
                        .filter { it.kind == PackKind.LOCAL }
                        .forEach { item ->
                            add(
                                mods.version(item.id, requireNotNull(item.version)),
                                "mods/${component(item.id)}",
                            )
                        }
                }
                try {
                    Files.move(
                        temp,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }

    /** Copies selected settings only into a private destination-profile snapshot. */
    suspend fun importSettings(preview: PackPreview, profileId: String) =
        withContext(Dispatchers.IO) {
            preview.modSettingsFolder?.let { source ->
                val destination = home.resolve("profiles/${component(profileId)}/config")
                Files.createDirectories(destination)
                Files.list(source).use { files ->
                    files.forEach { file ->
                        require(
                            file.isRegularFile() &&
                                !Files.isSymbolicLink(file) &&
                                file.extension.equals("cfg", true)
                        )
                        Files.copy(
                            file,
                            safe(destination, file.name),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                }
            }
        }

    /** Explicit user selection required by the caller. Materialized local payloads remain OFF. */
    suspend fun selectedLocals(
        preview: PackPreview,
        ids: Set<String>,
    ): List<Pair<CatalogMod, InstalledMod>> =
        withContext(Dispatchers.IO) {
            ids.map { id ->
                val source = Path.of(preview.localFiles[id] ?: error("No local payload for $id"))
                val hash = localHash(source)
                val localId = "local-" + hash
                val v = hash.take(12)
                if (!mods.hasVersion(localId, v))
                    mods.put(
                        localId,
                        v,
                        nnomm.core.io.DownloadedFile(
                            source.toString(),
                            nnomm.core.io.PayloadOrigin.PACK,
                        ),
                    )
                CatalogMod(
                    localId,
                    source.name,
                    versions = listOf(ModVersion(v)),
                    localFile = source.name,
                ) to InstalledMod(localId, ModSource.LOCAL, source.name, setOf(v), v)
            }
        }

    override suspend fun following(info: PackInfo): Pack? =
        withContext(Dispatchers.IO) {
            val url = info.followAddress ?: return@withContext null
            Files.createDirectories(home.resolve("staging"))
            val file = Files.createTempFile(home.resolve("staging"), "follow-", ".pack")
            try {
                val channel = client.get(checkedUrl(url)).bodyAsChannel()
                Files.newOutputStream(file).use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val count = channel.readAvailable(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 256L * 1024 * 1024) { "Pack exceeds 256 MiB" }
                        output.write(buffer, 0, count)
                    }
                }
                read(file).use { preview ->
                    preview.pack.takeIf { compareVersions(it.version, info.version) > 0 }
                }
            } finally {
                Files.deleteIfExists(file)
            }
        }
}

/** Only mod behavior can be shared. Machine paths, sources and launch commands are private. */
private fun bundledIds(path: Path): List<String> {
    val meta = path.resolve("meta.json").takeIf { path.isDirectory() && it.isRegularFile() }
    val metaId =
        meta?.let {
            runCatching { Json.parseToJsonElement(it.readText()).jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()
        }
    return listOfNotNull(path.name, path.nameWithoutExtension, metaId)
}

/** A short name for a mod that a pack bundles: its only DLL, else its folder or file name. */
fun bundledModName(path: Path): String {
    if (!path.isDirectory()) return path.nameWithoutExtension
    val dlls = Files.walk(path).use { s -> s.filter { it.isRegularFile() && it.name.endsWith(".dll", true) }.toList() }
    return when {
        dlls.size == 1 -> dlls.single().nameWithoutExtension
        !path.name.startsWith("local-") -> path.name
        else -> dlls.firstOrNull()?.nameWithoutExtension ?: path.name
    }
}

/** The size of a mod that a pack bundles, in KB. */
fun bundledSizeKb(path: Path): Long =
    if (!path.isDirectory()) path.fileSize() / 1024
    else Files.walk(path).use { s -> s.filter { it.isRegularFile() }.mapToLong { it.fileSize() }.sum() } / 1024

internal fun packSettings(settings: SettingsValues): SettingsValues {
    val allowed =
        setOf("ownSettingsP", "ownSettings", "prerelease", "autoDeps", "warnOld", "console")
    return SettingsValues(
        settings.general.filterKeys { it in allowed },
        settings.overrides.mapValues { (_, values) -> values.filterKeys { it in allowed } },
    )
}
