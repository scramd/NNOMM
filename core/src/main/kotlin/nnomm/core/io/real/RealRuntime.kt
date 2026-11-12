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
) {
    suspend fun shutdown() {
        try {
            store.shutdown()
        } finally {
            scope.cancel()
            client.close()
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
                recoverTransactions(home.resolve("transactions"))
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
                                                        "sources" to JsonPrimitive(DEFAULT_REGISTRY),
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
                val launcher = RealGameLauncher(home, scope, fakeLauncher)
                val game =
                    RealGameFolder(
                        home,
                        mods,
                        downloader,
                        { current().catalog },
                        { launcher.running.value || gameProcessRunning() },
                        notice,
                    )
                val folder =
                    initial.settings.general["gamePath"]?.content?.takeIf { it.isNotBlank() }
                        ?: initial.game.folder
                        ?: findGameFolder(initial.settings)
                val adopted =
                    if (
                        initial.firstStart.complete &&
                            initial.installed.isEmpty() &&
                            folder != null &&
                            Path.of(folder).resolve("NuclearOption.exe").isRegularFile()
                    )
                        game.takeOver(folder)
                    else emptyList()
                val loaded =
                    initial.copy(
                        catalog =
                            (saved?.first.orEmpty() + initial.catalog + adopted.map { it.first })
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
                    )
                if (System.getProperty("os.name").startsWith("Linux") && loaded.game.loaderPresent)
                    notice(
                        "For mods under Proton, set WINEDLLOVERRIDES=\"winhttp.dll=n,b\" %command% in this game's Steam launch options. Process detection under Proton needs verification."
                    )
                val packs = RealPacks(home, mods, client)
                app =
                    AppStore(
                        loaded,
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
                app.startRegistrySchedule()
                RealRuntime(app, mods, packs, scope, client)
            }
    }
}
