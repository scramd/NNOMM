package nnomm.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** Registry versions remain strings, including pre-release suffixes. */
@Serializable data class PackageReference(val id: String, val version: String? = null)
@Serializable enum class ReleaseKind { RELEASE, PRE_RELEASE }
@Serializable enum class ModType { PLUGIN, ADDON }
@Serializable enum class Side { CLIENT, SERVER, BOTH }
@Serializable data class ModVersion(
    val version: String, val category: ReleaseKind = ReleaseKind.RELEASE,
    val type: ModType = ModType.PLUGIN, val gameVersion: String? = null,
    val downloadUrl: String? = null, val hash: String? = null,
    val dependencies: List<PackageReference> = emptyList(),
    val incompatibilities: List<PackageReference> = emptyList(),
    val extends: PackageReference? = null,
    val sizeKb: Long? = null, val dateMillis: Long? = null,
)
@Serializable data class CatalogMod(
    val id: String, val name: String, val authors: List<String> = emptyList(),
    val description: String = "", val tags: List<String> = emptyList(),
    val downloadCount: Long = 0, val imageUrl: String? = null, val demoPicture: String? = null,
    val githubOwner: String? = null, val githubRepository: String? = null,
    val side: Side = Side.CLIENT, val versions: List<ModVersion> = emptyList(),
    val localFile: String? = null,
) {
    val newestRelease get() = versions.filter { it.category == ReleaseKind.RELEASE }.maxWithOrNull { a, b -> nnomm.core.rules.compareVersions(a.version, b.version) }
    val newerPreRelease get() = versions.filter { it.category == ReleaseKind.PRE_RELEASE && (newestRelease == null || nnomm.core.rules.compareVersions(it.version, newestRelease!!.version) > 0) }.maxWithOrNull { a, b -> nnomm.core.rules.compareVersions(a.version, b.version) }
    val updated get() = versions.mapNotNull { it.dateMillis }.maxOrNull()
    val added get() = versions.mapNotNull { it.dateMillis }.minOrNull()
}
@Serializable enum class ModSource { REGISTRY, LOCAL }
@Serializable data class ProfileMod(val on: Boolean = false, val version: String)
@Serializable data class InstalledMod(
    val id: String, val source: ModSource = ModSource.REGISTRY, val fileName: String? = null,
    val versions: Set<String>, val defaultVersion: String,
    val profiles: Map<String, ProfileMod> = emptyMap(), val fileMissing: Boolean = false,
)
@Serializable data class PackInfo(
    val by: String, val version: String, val nextVersion: String? = null,
    val mods: List<String> = emptyList(), val followAddress: String? = null,
)
@Serializable data class Profile(val id: String, val name: String, val pack: PackInfo? = null)
@Serializable data class PackItem(val kind: String, val id: String, val version: String? = null, val nextVersion: String? = null, val sizeKb: Long? = null)
@Serializable data class Pack(val name: String, val by: String, val version: String, val items: List<PackItem>, val fileName: String? = null, val followAddress: String? = null, val includeSettings: Boolean = false)
@Serializable data class GameInfo(val folder: String? = null, val version: String? = null, val loaderPresent: Boolean = false, val loaderVersion: String? = null)
@Serializable enum class ConnectionKind { LOADING, READY, OFFLINE }
@Serializable data class CatalogConnection(val kind: ConnectionKind = ConnectionKind.LOADING, val readAtMillis: Long? = null)
@Serializable data class SettingsValues(val general: Map<String, JsonPrimitive> = emptyMap(), val overrides: Map<String, Map<String, JsonPrimitive>> = emptyMap()) {
    fun value(key: String, profile: String) = overrides[profile]?.get(key) ?: general[key]
    fun bool(key: String, profile: String = "default") = value(key, profile)?.content == "true"
}
@Serializable data class Download(val id: String, val kind: String, val profile: String, val version: String, val percent: Int = 0, val quiet: Boolean = false, val withDependencies: List<String> = emptyList(), val error: String? = null)
@Serializable data class UiAction(val kind: String, val id: String? = null, val value: String? = null)
data class Toast(val id: Long, val text: String, val action: String? = null, val target: UiAction? = null, val durationMillis: Long = 7000, val callback: (() -> Unit)? = null)
@Serializable data class TroubleSession(val profile: String, val saved: List<String>, val suspects: List<String>, val phase: String = "base", val round: Int = 0, val rounds: Int, val on: List<String> = emptyList(), val off: List<String> = saved, val found: String? = null)
@Serializable data class FirstStart(val complete: Boolean = true, val existingOn: Int = 0, val existingOff: Int = 0)
data class AppState(
    val catalog: List<CatalogMod> = emptyList(), val connection: CatalogConnection = CatalogConnection(),
    val game: GameInfo = GameInfo(), val installed: Map<String, InstalledMod> = emptyMap(),
    val profiles: List<Profile> = listOf(Profile("default", "Default")), val profileId: String = "default",
    val settings: SettingsValues = SettingsValues(), val downloads: List<Download> = emptyList(),
    val toasts: List<Toast> = emptyList(), val gameRunning: Boolean = false,
    val trouble: TroubleSession? = null, val firstStart: FirstStart = FirstStart(),
    val nowMillis: Long = 0, val appVersion: String = "0.1.0", val storeUsedMb: Long = 0,
) {
    fun mod(id: String) = catalog.first { it.id == id }
    fun profile(id: String = profileId) = profiles.first { it.id == id }
}
@Serializable data class ReleaseNote(val version: String, val date: String, val lines: List<String>)
