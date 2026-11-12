package nnomm.core.io.real

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import java.nio.file.*
import java.util.zip.*
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
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
            if (path.name.endsWith(".nomm.json", true))
                return@withContext legacy(path.readText(), path.name, null)
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
        return contentPreview(
            Pack(
                name.substringBeforeLast('.'),
                "",
                "1.0",
                refs.map { PackItem(PackKind.VER, it.id, it.version) } +
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
                                        if (pack.includeSettings) packSettings(state.settings)
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
                                        if (payload?.isDirectory() == true)
                                            Files.walk(payload).use { files ->
                                                files
                                                    .filter {
                                                        it.isRegularFile() && it.extension == "cfg"
                                                    }
                                                    .map { it.fileName.toString() }
                                                    .toList()
                                            }
                                        else emptyList()
                                    }
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
                    mods.put(localId, v, nnomm.core.io.DownloadedFile(source.toString()))
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
            val body = client.get(checkedUrl(url)).boundedText()
            val document = json.decodeFromString<PackDocument>(body)
            require(document.format == "NNOMM/1")
            document.pack.takeIf { compareVersions(it.version, info.version) > 0 }
        }
}

/** Only mod behavior can be shared. Machine paths, sources and launch commands are private. */
internal fun packSettings(settings: SettingsValues): SettingsValues {
    val allowed =
        setOf("ownSettingsP", "ownSettings", "prerelease", "autoDeps", "warnOld", "console")
    return SettingsValues(
        settings.general.filterKeys { it in allowed },
        settings.overrides.mapValues { (_, values) -> values.filterKeys { it in allowed } },
    )
}
