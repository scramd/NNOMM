package nnomm.core.io.real

import java.nio.file.*
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.io.*
import nnomm.core.model.*
import nnomm.core.settings.defaultSettings
import nnomm.core.store.AppStore

/** Owns HTTP and process-watch resources. Call shutdown to flush state before closing the UI. */
class RealRuntime
private constructor(
    val store: AppStore,
    val mods: RealModStore,
    val packs: RealPacks,
    private val scope: CoroutineScope,
    private val client: io.ktor.client.HttpClient,
    private val homeLock: java.nio.channels.FileLock,
    private val lockChannel: java.nio.channels.FileChannel,
) {
    suspend fun shutdown() {
        try {
            store.shutdown()
            (store.io.meta as? RealMetaSource)?.shutdown()
        } finally {
            scope.cancel()
            client.close()
            HomeLocks.runtimeHomes.remove(mods.home.toRealPath())
            homeLock.release()
            lockChannel.close()
        }
    }

    companion object {
        suspend fun open(
            home: Path = settingsHome(),
            version: String = "0.1.0",
            fakeLauncher: List<String>? = null,
            notice: (String) -> Unit = {},
        ): RealRuntime =
            withContext(Dispatchers.IO) {
                Files.createDirectories(home)
                val lockChannel =
                    java.nio.channels.FileChannel.open(
                        home.resolve(".lock"),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE,
                    )
                val homeLock =
                    try {
                        lockChannel.tryLock()
                            ?: error("This NNOMM home is already open in another process.")
                    } catch (e: Exception) {
                        lockChannel.close()
                        throw e
                    }
                HomeLocks.runtimeHomes.add(home.toRealPath())
                try {
                    cleanHomePreparation(home)
                    recoverTransactions(
                        home.resolve("transactions"),
                        homeOnly = true,
                        notice = notice,
                    )
                    val persistence = RealPersistence(home, notice)
                    val initial =
                        persistence.load()
                            ?: AppState(
                                settings =
                                    persistence.read()
                                        ?: defaultSettings().let {
                                            it.copy(
                                                general =
                                                    it.general +
                                                        mapOf(
                                                            "gamePath" to JsonPrimitive(""),
                                                            "store" to
                                                                JsonPrimitive(
                                                                    home.resolve("mods").toString()
                                                                ),
                                                            "sources" to
                                                                JsonPrimitive(DEFAULT_REGISTRY),
                                                        )
                                            )
                                        },
                                firstStart = FirstStart(false),
                            )
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val client = networkClient(version)
                    var app: AppStore? = null
                    fun current() = app?.state?.value ?: initial
                    val registry =
                        RealRegistry(
                            client,
                            home,
                            {
                                current()
                                    .settings
                                    .general["sources"]
                                    ?.content
                                    ?.split(Regex("[\\n,;]+"))
                                    ?.map { it.trim() }
                                    ?.filter { it.isNotEmpty() } ?: listOf(DEFAULT_REGISTRY)
                            },
                            notice,
                        )
                    val saved = registry.saved()
                    val mods =
                        RealModStore(
                            home,
                            initial.settings.general["store"]?.content,
                            { current().catalog },
                        )
                    val downloader = RealDownloader(client, home)
                    val launcher = RealGameLauncher(home, scope, fakeLauncher, notice = notice)
                    val game =
                        RealGameFolder(
                            home,
                            mods,
                            downloader,
                            { current().catalog },
                            { launcher.running.value || gameProcessRunning() },
                            notice,
                            backgroundScope = scope,
                            backgroundReady = {
                                current().downloads.none { it.status == DownloadStatus.ACTIVE }
                            },
                            preferredVersions = {
                                current().installed.mapValues { (_, mod) ->
                                    mod.profiles[current().profileId]?.version ?: mod.defaultVersion
                                }
                            },
                        )
                    val folder =
                        initial.settings.general["gamePath"]?.content?.takeIf { it.isNotBlank() }
                            ?: initial.game.folder
                            ?: findGameFolder(initial.settings)
                    if (launcher.running.value)
                        notice(
                            "Close Nuclear Option to recover pending changes and take over existing mods."
                        )
                    else {
                        game.recoverPending()
                    }
                    val adopted =
                        if (
                            !launcher.running.value &&
                                initial.firstStart.complete &&
                                initial.installed.isEmpty() &&
                                folder != null &&
                                Path.of(folder).resolve("NuclearOption.exe").isRegularFile() &&
                                game.canAutomaticallyTakeOver(folder)
                        )
                            game.takeOver(folder)
                        else emptyList()
                    val loaded =
                        initial.copy(
                            downloads = emptyList(),
                            catalog =
                                (saved?.first.orEmpty() +
                                        initial.catalog +
                                        adopted.map { it.first })
                                    .distinctBy { it.id },
                            installed =
                                initial.installed + adopted.associate { it.second.id to it.second },
                            connection = CatalogConnection(ConnectionKind.OFFLINE, saved?.second),
                            game = folder?.let { game.inspect(it) } ?: GameInfo(),
                            settings =
                                initial.settings.copy(
                                    general =
                                        initial.settings.general +
                                            ("gamePath" to JsonPrimitive(folder.orEmpty()))
                                ),
                            appVersion = version,
                            nowMillis = System.currentTimeMillis(),
                            gameRunning = launcher.running.value,
                            gameIoPending = launcher.running.value || game.recoveryPending,
                        )
                    if (
                        System.getProperty("os.name").startsWith("Linux") &&
                            loaded.game.loaderPresent
                    )
                        notice(
                            "For mods under Proton, set WINEDLLOVERRIDES=\"winhttp.dll=n,b\" %command% in this game's Steam launch options."
                        )
                    val packs = RealPacks(home, mods, client)
                    app =
                        AppStore(
                            loaded.copy(
                                catalog = nnomm.core.rules.cleanCatalogTags(loaded.catalog)
                            ),
                            AppIo(
                                registry,
                                RealMetaSource(client, home),
                                RealReleaseNotes(client, home),
                                downloader,
                                mods,
                                game,
                                launcher,
                                persistence,
                                Clock { System.currentTimeMillis() },
                                persistence,
                                packs,
                            ),
                        )
                    app.checkPackUpdates()
                    app.startRegistrySchedule()
                    RealRuntime(app, mods, packs, scope, client, homeLock, lockChannel)
                } catch (e: Throwable) {
                    HomeLocks.runtimeHomes.remove(home.toRealPath())
                    homeLock.release()
                    lockChannel.close()
                    throw e
                }
            }
    }
}
