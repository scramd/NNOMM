package nnomm.core.io

import kotlinx.coroutines.flow.StateFlow
import nnomm.core.model.*

/** Network registry plus a persisted list. Implementations do not touch Compose. */
interface Registry {
    suspend fun read(): List<CatalogMod>
    suspend fun saved(): Pair<List<CatalogMod>,Long>?
    suspend fun save(mods: List<CatalogMod>,readAtMillis: Long)
}
/** HEAD-derived metadata, cached by download URL and version. */
interface MetaSource { suspend fun metadata(mod: CatalogMod,version: ModVersion): VersionMetadata }
data class VersionMetadata(val sizeKb: Long?,val dateMillis: Long?)
/** Atom feed, newest ten releases; implementations cache results. */
interface ReleaseNotesSource { suspend fun releases(mod: CatalogMod): List<ReleaseNote> }
/** Progress is 0..100; coroutine cancellation must abort transfer and discard partial data. */
interface Downloader { suspend fun download(mod: CatalogMod,version: ModVersion,progress: (Int)->Unit): DownloadedFile }
data class DownloadedFile(val path: String)
/** Store keeps each version once. Remove must remain reversible until Undo expires. */
interface ModStore {
    suspend fun put(id: String,version: String,file: DownloadedFile)
    suspend fun remove(ids: List<String>)
    suspend fun restore(ids: List<String>)
    suspend fun addLocal(path: String): Pair<CatalogMod,InstalledMod>
}
/** Only managed files can be changed; takeover copies existing files without changing the game. */
interface GameFolder {
    suspend fun inspect(folder: String): GameInfo
    suspend fun apply(state: AppState,profileId: String)
    suspend fun takeOver(folder: String): List<Pair<CatalogMod,InstalledMod>>
    suspend fun installLoader(folder: String)
    suspend fun repairLoader(folder: String)
}
/** Launch without Steam authentication or a helper. */
interface GameLauncher {
    val running: StateFlow<Boolean>
    suspend fun start(state: AppState,withoutMods: Boolean)
}
/** NNOMM's private settings location only, never NOMM's. */
interface SettingsFile { suspend fun read(): SettingsValues?; suspend fun write(values: SettingsValues) }
/** Milliseconds since Unix epoch, injectable for deterministic rules and tests. */
fun interface Clock { fun nowMillis(): Long }
data class AppIo(val registry: Registry,val meta: MetaSource,val releaseNotes: ReleaseNotesSource,val downloader: Downloader,val modStore: ModStore,val gameFolder: GameFolder,val launcher: GameLauncher,val settingsFile: SettingsFile,val clock: Clock)
