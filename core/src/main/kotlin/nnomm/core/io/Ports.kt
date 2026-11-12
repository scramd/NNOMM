package nnomm.core.io

import kotlinx.coroutines.flow.StateFlow
import nnomm.core.model.*

/** Network registry plus a persisted list. Implementations do not touch Compose. */
interface Registry {
    suspend fun read(): List<CatalogMod>

    suspend fun readSnapshot(): RegistrySnapshot = RegistrySnapshot(read())

    suspend fun saved(): Pair<List<CatalogMod>, Long>?

    suspend fun save(mods: List<CatalogMod>, readAtMillis: Long)
}

data class RegistrySnapshot(val mods: List<CatalogMod>, val savedAtMillis: Long? = null)

/** HEAD-derived metadata, cached by download URL and version. */
interface MetaSource {
    suspend fun metadata(mod: CatalogMod, version: ModVersion): VersionMetadata
}

data class VersionMetadata(val sizeKb: Long?, val dateMillis: Long?)

/** Atom feed, newest ten releases; implementations cache results. */
interface ReleaseNotesSource {
    suspend fun releases(mod: CatalogMod): List<ReleaseNote>
}

/** Progress is 0..100; coroutine cancellation must abort transfer and discard partial data. */
interface Downloader {
    suspend fun download(
        mod: CatalogMod,
        version: ModVersion,
        progress: (Int) -> Unit,
    ): DownloadedFile
}

enum class PayloadOrigin {
    UNKNOWN,
    REGISTRY_DOWNLOAD,
    TAKEOVER,
    LOCAL_FILE,
    PACK,
}

data class DownloadedFile(
    val path: String,
    val origin: PayloadOrigin = PayloadOrigin.UNKNOWN,
    val verifiedHash: String? = null,
    val cleanup: () -> Unit = {},
) : AutoCloseable {
    override fun close() = cleanup()
}

/** Store keeps each version once. Remove must remain reversible until Undo expires. */
interface ModStore {
    suspend fun put(id: String, version: String, file: DownloadedFile)

    suspend fun remove(ids: List<String>)

    suspend fun restore(ids: List<String>)

    suspend fun hasVersion(id: String, version: String): Boolean = true

    suspend fun addLocal(path: String): Pair<CatalogMod, InstalledMod>

    fun protect(ids: Set<String>) {}

    suspend fun clearUnused(state: AppState) {}

    suspend fun clearUnused(current: () -> AppState) = clearUnused(current())

    suspend fun unusedBytes(state: AppState): Long = 0

    suspend fun usedBytes(): Long = 0

    suspend fun installedBytes(state: AppState): Long = usedBytes()

    /** Unknown provenance is retained: its only copy may be in this store. */
    suspend fun keepInLibrary(id: String, versions: Set<String>): Boolean = true

    suspend fun moveTo(folder: String) {
        error("Moving the store is unavailable")
    }
}

/** Only managed files can be changed; takeover copies existing files without changing the game. */
class DoorstopSettingsFailure(cause: Exception? = null) :
    java.io.IOException("The mod loader settings file could not be read.", cause)

class LoaderDownloadFailure(cause: Exception) : java.io.IOException(cause)

class LoaderRepairRefused :
    IllegalStateException(
        "Repair supports only BepInEx 5.4.23.4. Select a folder with that version, or restore the loader using its original installer. Existing files were kept."
    )

data class LoaderRemoval(val removed: Int = 0, val kept: Boolean = false)

interface GameFolder {
    val placementConflicts: StateFlow<Map<String, Set<String>>>?
        get() = null

    val applyProblems: StateFlow<Map<String, String>>?
        get() = null

    val recoveryPending: Boolean
        get() = false

    suspend fun recoverPending() {}

    suspend fun find(settings: SettingsValues): String? = null

    suspend fun inspect(folder: String): GameInfo

    suspend fun apply(state: AppState, profileId: String)

    suspend fun apply(plan: GamePlan) = apply(plan.asState(), plan.profileId)

    /** False means deferred or partial; the requested plan must be retried before Play. */
    val applySucceeded: Boolean
        get() = true

    suspend fun removeLoader(folder: String): LoaderRemoval = LoaderRemoval()

    suspend fun setConsole(folder: String, enabled: Boolean) {}

    suspend fun takeOver(folder: String): List<Pair<CatalogMod, InstalledMod>>

    suspend fun installLoader(folder: String)

    suspend fun repairLoader(folder: String)
}

/** Launch without Steam authentication or a helper. */
interface GameLauncher {
    val running: StateFlow<Boolean>

    suspend fun start(state: AppState, withoutMods: Boolean)
}

/** NNOMM's private settings location only, never NOMM's. */
interface SettingsFile {
    suspend fun read(): SettingsValues?

    suspend fun write(values: SettingsValues)
}

/** Milliseconds since Unix epoch, injectable for deterministic rules and tests. */
fun interface Clock {
    fun nowMillis(): Long
}

data class AppIo(
    val registry: Registry,
    val meta: MetaSource,
    val releaseNotes: ReleaseNotesSource,
    val downloader: Downloader,
    val modStore: ModStore,
    val gameFolder: GameFolder,
    val launcher: GameLauncher,
    val settingsFile: SettingsFile,
    val clock: Clock,
    val persistence: StatePersistence? = null,
    val packSource: PackSource? = null,
    val logger: Logger = Logger { action, error ->
        java.util.logging.Logger.getLogger("nnomm.core")
            .log(java.util.logging.Level.WARNING, action, error)
    },
)

/** Durable domain snapshot, excluding transient downloads and Undo callbacks. */
interface StatePersistence {
    suspend fun load(): AppState?

    suspend fun save(state: AppState)
}

/** Opt-in pack update address; no background subscription or publication. */
interface PackSource {
    suspend fun following(info: PackInfo): Pack?
}

fun interface Logger {
    fun error(action: String, error: Throwable)
}

/** Fully resolved active payloads; unrelated UI/catalog state cannot affect placement. */
data class GamePlan(
    val folder: String?,
    val profileId: String,
    val versions: Map<String, String>,
    val ownSettings: Boolean,
    val knownIds: Set<String> = versions.keys,
) {
    internal fun asState() =
        AppState(
            game = GameInfo(folder),
            profileId = profileId,
            profiles = listOf(Profile(profileId, profileId)),
            settings =
                SettingsValues(
                    overrides =
                        mapOf(
                            profileId to
                                mapOf(
                                    "ownSettingsP" to
                                        kotlinx.serialization.json.JsonPrimitive(ownSettings)
                                )
                        )
                ),
            installed =
                versions.mapValues { (id, v) ->
                    InstalledMod(
                        id,
                        versions = setOf(v),
                        defaultVersion = v,
                        profiles = mapOf(profileId to ProfileMod(true, v)),
                    )
                },
        )
}

fun gamePlan(state: AppState, profileId: String = state.profileId) =
    GamePlan(
        state.game.folder,
        profileId,
        state.installed.values
            .mapNotNull { m -> m.profiles[profileId]?.takeIf { it.on }?.let { m.id to it.version } }
            .toMap(),
        state.settings.bool("ownSettingsP", profileId),
        state.installed.keys,
    )
