package nnomm.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** Registry versions remain strings, including pre-release suffixes. */
@Serializable data class PackageReference(val id: String, val version: String? = null)

@Serializable
enum class ReleaseKind {
    RELEASE,
    PRE_RELEASE,
}

@Serializable
enum class ModType {
    PLUGIN,
    ADDON,
}

@Serializable
enum class Side {
    CLIENT,
    SERVER,
    BOTH,
}

@Serializable
data class ModVersion(
    val version: String,
    val category: ReleaseKind = ReleaseKind.RELEASE,
    val type: ModType = ModType.PLUGIN,
    val gameVersion: String? = null,
    val downloadUrl: String? = null,
    val hash: String? = null,
    val dependencies: List<PackageReference> = emptyList(),
    val incompatibilities: List<PackageReference> = emptyList(),
    val extends: PackageReference? = null,
    val sizeKb: Long? = null,
    val dateMillis: Long? = null,
    val fileName: String? = null,
    val releaseTagPrefix: String? = null,
    val releaseAssetName: String? = null,
) {
    val unavailableReason: String?
        get() {
            val name = (fileName ?: downloadUrl?.substringBefore('?')).orEmpty().lowercase()
            return if (name.endsWith(".rar"))
                "This version uses an archive format NNOMM cannot install yet."
            else null
        }
}

@Serializable
data class CatalogMod(
    val id: String,
    val name: String,
    val authors: List<String> = emptyList(),
    val description: String = "",
    val tags: List<String> = emptyList(),
    val downloadCount: Long = 0,
    val imageUrl: String? = null,
    val demoPicture: String? = null,
    val githubOwner: String? = null,
    val githubRepository: String? = null,
    val side: Side = Side.CLIENT,
    val versions: List<ModVersion> = emptyList(),
    val localFile: String? = null,
    val urls: List<RegistryUrl> = emptyList(),
    val imageHash: String? = null,
    val firstReleaseMillis: Long? = null,
) {
    @kotlinx.serialization.Transient
    val newestRelease =
        versions
            .filter { it.category == ReleaseKind.RELEASE }
            .maxWithOrNull { a, b -> compareVersionValues(a.version, b.version) }
    @kotlinx.serialization.Transient
    val newerPreRelease =
        versions
            .filter {
                it.category == ReleaseKind.PRE_RELEASE &&
                    (newestRelease == null ||
                        compareVersionValues(it.version, newestRelease.version) > 0)
            }
            .maxWithOrNull { a, b -> compareVersionValues(a.version, b.version) }
    val installable
        get() = newestRelease ?: newerPreRelease

    val updated
        get() = versions.mapNotNull { it.dateMillis }.maxOrNull()

    val added
        get() = firstReleaseMillis ?: versions.mapNotNull { it.dateMillis }.minOrNull()
}

@Serializable
enum class ModSource {
    REGISTRY,
    LOCAL,
}

@Serializable data class ProfileMod(val on: Boolean = false, val version: String)

@Serializable
data class InstalledMod(
    val id: String,
    val source: ModSource = ModSource.REGISTRY,
    val fileName: String? = null,
    val versions: Set<String>,
    val defaultVersion: String,
    val profiles: Map<String, ProfileMod> = emptyMap(),
    val fileMissing: Boolean = false,
)

@Serializable
data class PackInfo(
    val by: String,
    val version: String,
    val nextVersion: String? = null,
    val mods: List<String> = emptyList(),
    val followAddress: String? = null,
    val memberVersions: Map<String, String> = emptyMap(),
)

@Serializable data class Profile(val id: String, val name: String, val pack: PackInfo? = null)

@Serializable
data class PackItem(
    val kind: PackKind,
    val id: String,
    val version: String? = null,
    val nextVersion: String? = null,
    val sizeKb: Long? = null,
)

@Serializable
data class Pack(
    val name: String,
    val by: String,
    val version: String,
    val items: List<PackItem>,
    val fileName: String? = null,
    val followAddress: String? = null,
    val includeSettings: Boolean = false,
)

@Serializable
data class GameInfo(
    val folder: String? = null,
    val version: String? = null,
    val loaderPresent: Boolean = false,
    val loaderVersion: String? = null,
    val folderMissing: Boolean = false,
)

@Serializable
enum class ConnectionKind {
    LOADING,
    READY,
    OFFLINE,
}

@Serializable
data class CatalogConnection(
    val kind: ConnectionKind = ConnectionKind.LOADING,
    val readAtMillis: Long? = null,
)

@Serializable
data class SettingsValues(
    val general: Map<String, JsonPrimitive> = emptyMap(),
    val overrides: Map<String, Map<String, JsonPrimitive>> = emptyMap(),
) {
    fun value(key: String, profile: String) = overrides[profile]?.get(key) ?: general[key]

    fun bool(key: String, profile: String = "default") = value(key, profile)?.content == "true"
}

@Serializable
data class Download(
    val id: String,
    val kind: DownloadKind,
    val profile: String,
    val version: String,
    val percent: Int = 0,
    val quiet: Boolean = false,
    val withDependencies: List<String> = emptyList(),
    val error: String? = null,
) {
    val status: DownloadStatus
        get() =
            when {
                error != null -> DownloadStatus.FAILED
                percent >= 100 -> DownloadStatus.COMPLETE
                else -> DownloadStatus.ACTIVE
            }
}

@Serializable
data class UiAction(val kind: ActionKind, val id: String? = null, val value: String? = null) {
    constructor(
        kind: String,
        id: String? = null,
        value: String? = null,
    ) : this(ActionKind.valueOf(kind.uppercase()), id, value)
}

data class Toast(
    val id: Long,
    val text: String,
    val action: String? = null,
    val target: UiAction? = null,
    val durationMillis: Long = 7000,
    val callback: (() -> Unit)? = null,
)

@Serializable
data class TroubleSession(
    val profile: String,
    val saved: List<String>,
    val suspects: List<String>,
    val phase: TroublePhase = TroublePhase.BASE,
    val round: Int = 0,
    val rounds: Int,
    val on: List<String> = emptyList(),
    val off: List<String> = saved,
    val found: String? = null,
    val culprit: String? = null,
)

@Serializable
data class FirstStart(
    val complete: Boolean = true,
    val existingOn: Int = 0,
    val existingOff: Int = 0,
)

data class AppState(
    val catalog: List<CatalogMod> = emptyList(),
    val connection: CatalogConnection = CatalogConnection(),
    val game: GameInfo = GameInfo(),
    val installed: Map<String, InstalledMod> = emptyMap(),
    val profiles: List<Profile> = listOf(Profile("default", "Default")),
    val profileId: String = "default",
    val settings: SettingsValues = SettingsValues(),
    val downloads: List<Download> = emptyList(),
    val toasts: List<Toast> = emptyList(),
    val gameRunning: Boolean = false,
    val gameStarting: Boolean = false,
    val gameIoPending: Boolean = false,
    val deferredModChange: Boolean = false,
    val applyProblems: Map<String, String> = emptyMap(),
    val releaseNotes: Map<String, List<ReleaseNote>> = emptyMap(),
    val notesStatus: Map<String, LoadStatus> = emptyMap(),
    val metadataStatus: Map<String, LoadStatus> = emptyMap(),
    val storeUnusedMb: Long? = null,
    val trouble: TroubleSession? = null,
    val firstStart: FirstStart = FirstStart(),
    val nowMillis: Long = 0,
    val appVersion: String = "0.1.0",
    val storeUsedMb: Long = 0,
    val storeUsedKb: Long? = null,
    val packSource: nnomm.core.io.PackSource? = null,
) {
    private val index by lazy { catalog.associateBy { it.id } }
    val reverseIncompatibilities by lazy {
        installed.keys
            .flatMap { id -> artifact(id)?.incompatibilities.orEmpty().map { it.id to id } }
            .groupBy({ it.first }, { it.second })
    }
    val registryGameVersion by lazy {
        catalog
            .flatMap { it.versions }
            .mapNotNull { it.gameVersion }
            .maxWithOrNull({ a, b -> compareVersionValues(a, b) })
    }

    fun mod(id: String) = index[id] ?: CatalogMod(id, id, description = "Not in the registry")

    fun artifact(id: String) =
        mod(id).versions.firstOrNull {
            it.version ==
                (installed[id]?.profiles?.get(profileId)?.version ?: installed[id]?.defaultVersion)
        } ?: mod(id).installable

    fun setting(key: String) = settings.value(key, profileId)

    fun profile(id: String = profileId) = profiles.first { it.id == id }
}

@Serializable
data class ReleaseNote(val version: String, val date: String, val lines: List<String>)
