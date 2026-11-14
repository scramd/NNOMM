package nnomm.core.demo

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import nnomm.core.io.*
import nnomm.core.model.*
import nnomm.core.rules.hash
import nnomm.core.settings.*
import nnomm.core.store.AppStore

object Demo {
    const val NOW = 1780315200000L
    val clock = Clock { NOW }
    private val data by lazy {
        Json.parseToJsonElement(
                Demo::class.java.getResourceAsStream("/nnomm/demo.json")!!.bufferedReader().use {
                    it.readText()
                }
            )
            .jsonObject
    }

    private fun JsonObject.str(k: String, default: String = "") =
        get(k)?.jsonPrimitive?.content ?: default

    private fun JsonObject.strings(k: String) =
        get(k)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    fun state(): AppState = cachedState

    private val cachedState: AppState by lazy {
        val catalog =
            data.getValue("mods").jsonArray.map { node ->
                val x = node.jsonObject
                val deps = x.strings("deps").map { PackageReference(it) }
                val type = if (x.str("type") == "addon") ModType.ADDON else ModType.PLUGIN
                val upd = x.str("upd").toLong()
                val added = x.str("added").toLong()
                val v =
                    ModVersion(
                        x.str("v"),
                        type = type,
                        gameVersion = x.str("game"),
                        dependencies = deps,
                        incompatibilities = x.strings("conflicts").map { PackageReference(it) },
                        extends = if (type == ModType.ADDON) deps.firstOrNull() else null,
                        sizeKb = x.str("size").toLong(),
                        dateMillis = NOW - upd * 86400000,
                    )
                val installedVersion =
                    data.getValue("installed").jsonObject[x.str("id")]?.jsonObject?.str("v")
                val olderInstalled = installedVersion != null && installedVersion != v.version
                val versions = buildList {
                    add(v)
                    if (added != upd)
                        add(
                            v.copy(
                                version = x.str("prev", prevVersion(v.version)),
                                dateMillis =
                                    NOW - (if (olderInstalled) upd + 38 else added) * 86400000,
                            )
                        )
                    if (x["pre"] !is JsonNull && x["pre"] != null)
                        add(v.copy(version = x.str("pre"), category = ReleaseKind.PRE_RELEASE))
                }
                CatalogMod(
                    x.str("id"),
                    x.str("name"),
                    listOf(x.str("author")),
                    x.str("desc"),
                    x.strings("tags"),
                    x.str("dl").toLong(),
                    demoPicture = x["img"]?.jsonPrimitive?.content?.takeUnless { it == "false" },
                    side = if (x.str("side") == "server") Side.SERVER else Side.CLIENT,
                    versions = versions,
                    githubOwner = x.str("author"),
                    githubRepository = x.str("id"),
                    firstReleaseMillis = NOW - added * 86400000,
                    localFile = if (x.str("local") == "true") x.str("file") else null,
                )
            }
        val installed =
            data.getValue("installed").jsonObject.mapValues { (id, node) ->
                val x = node.jsonObject
                val v = x.str("v")
                InstalledMod(
                    id,
                    if (x.str("source") == "local") ModSource.LOCAL else ModSource.REGISTRY,
                    catalog.first { it.id == id }.localFile,
                    setOf(v),
                    v,
                    x.getValue("on").jsonObject.mapValues {
                        ProfileMod(it.value.jsonPrimitive.boolean, v)
                    },
                    x.str("problem") == "missing",
                )
            }
        val profiles =
            data.getValue("profiles").jsonArray.map { node ->
                val x = node.jsonObject
                val p = x["pack"]?.jsonObject
                Profile(
                    x.str("id"),
                    x.str("name"),
                    p?.let {
                        PackInfo(
                            it.str("by"),
                            it.str("v"),
                            it.str("next").ifEmpty { null },
                            it.strings("mods"),
                            if (it.str("followed") == "true") "demo:friday" else null,
                        )
                    },
                )
            }
        AppState(
            catalog,
            CatalogConnection(ConnectionKind.READY, NOW - 720000),
            GameInfo("sandbox/game", "0.34.6", true, "5.4.23.4"),
            installed,
            profiles,
            settings =
                defaultSettings()
                    .copy(
                        general =
                            defaultSettings().general +
                                ("gamePath" to JsonPrimitive("sandbox/game")),
                        overrides =
                            mapOf(
                                "hardcore" to
                                    mapOf(
                                        "ownSettingsP" to JsonPrimitive(true),
                                        "console" to JsonPrimitive(true),
                                    ),
                                "friday" to emptyMap(),
                                "default" to emptyMap(),
                            ),
                    ),
            nowMillis = NOW,
            storeUsedMb = 438,
            storeUnusedMb = 212,
            firstStart = FirstStart(true, 17, 8),
        )
    }
    val PACK_IMPORT by lazy {
        val initial = state()
        pack(data.getValue("packImport").jsonObject).let { pack ->
            pack.copy(
                items =
                    pack.items.map { item ->
                        if (item.version == null && item.kind != PackKind.LOCAL)
                            item.copy(
                                version =
                                    nnomm.core.rules.verOf(initial, item.id)
                                        ?: initial.catalog
                                            .firstOrNull { it.id == item.id }
                                            ?.installable
                                            ?.version
                            )
                        else item
                    }
            )
        }
    }
    val PACK_UPDATE by lazy { pack(data.getValue("packUpdate").jsonObject) }

    private fun pack(x: JsonObject) =
        Pack(
            x.str("name"),
            x.str("by"),
            x.str("v"),
            x.getValue("items")
                .jsonArray
                .map { node ->
                    val a = node.jsonArray
                    PackItem(
                        PackKind.valueOf(a[0].jsonPrimitive.content.uppercase()),
                        a[1].jsonPrimitive.content,
                        a.getOrNull(2)?.jsonPrimitive?.content?.takeUnless {
                            a[0].jsonPrimitive.content == "local"
                        },
                        a.getOrNull(3)?.jsonPrimitive?.content,
                        if (a[0].jsonPrimitive.content == "local") a[2].jsonPrimitive.long else null,
                    )
                }
                .map { item ->
                    if (item.kind == PackKind.SAME && item.version == null)
                        item.copy(version = state().installed[item.id]?.defaultVersion)
                    else item
                },
            x.str("file").ifEmpty { null },
        )

    fun notes(id: String): List<ReleaseNote> {
        val explicit = data.getValue("notes").jsonObject[id]?.jsonArray
        if (explicit != null)
            return explicit.map {
                it.jsonArray.let { a ->
                    ReleaseNote(
                        a[0].jsonPrimitive.content,
                        a[1].jsonPrimitive.content,
                        a[2].jsonArray.map { it.jsonPrimitive.content },
                    )
                }
            }
        val m = state().mod(id)
        val v = m.newestRelease!!
        return listOf(
            ReleaseNote(
                v.version,
                nnomm.core.rules.ago(nnomm.core.rules.daysAgo(state(), m.updated)),
                emptyList(),
            ),
            ReleaseNote(
                prevVersion(v.version),
                nnomm.core.rules.ago(nnomm.core.rules.daysAgo(state(), m.updated) + 40),
                emptyList(),
            ),
        )
    }

    fun io(): AppIo {
        val launcher = DemoLauncher()
        return AppIo(
            object : Registry {
                override suspend fun read() = state().catalog

                override suspend fun saved() = state().catalog to NOW

                override suspend fun save(mods: List<CatalogMod>, readAtMillis: Long) {}
            },
            object : MetaSource {
                override suspend fun metadata(mod: CatalogMod, version: ModVersion) =
                    VersionMetadata(version.sizeKb, version.dateMillis)
            },
            object : ReleaseNotesSource {
                override suspend fun releases(mod: CatalogMod) = notes(mod.id)
            },
            object : Downloader {
                override suspend fun download(
                    mod: CatalogMod,
                    version: ModVersion,
                    progress: (Int) -> Unit,
                ): DownloadedFile {
                    var pct = 0
                    while (pct < 100) {
                        delay(260)
                        pct = (pct + 6 + (hash(mod.id) % 14).toInt()).coerceAtMost(100)
                        progress(pct)
                    }
                    return DownloadedFile("demo:${mod.id}/${version.version}")
                }
            },
            object : ModStore {
                override suspend fun put(id: String, version: String, file: DownloadedFile) {}

                override suspend fun remove(ids: List<String>) {}

                override suspend fun restore(ids: List<String>) {}

                override suspend fun addLocal(path: String): Pair<CatalogMod, InstalledMod> {
                    val name = path.substringAfterLast('/').substringAfterLast('\\')
                    val id = "local-" + name.substringBeforeLast('.').lowercase()
                    return CatalogMod(
                        id,
                        name.substringBeforeLast('.'),
                        listOf(name),
                        "You added this mod from a file. It is not in the registry, so NNOMM cannot look for updates.",
                        versions = listOf(ModVersion("1.0", gameVersion = "0.34.6")),
                        localFile = name,
                    ) to InstalledMod(id, ModSource.LOCAL, name, setOf("1.0"), "1.0")
                }
            },
            object : GameFolder {
                override suspend fun inspect(folder: String) = state().game.copy(folder = folder)

                override suspend fun apply(state: AppState, profileId: String) {}

                override suspend fun takeOver(folder: String) =
                    state().installed.values.map { state().mod(it.id) to it }

                override suspend fun installLoader(folder: String) {}

                override suspend fun repairLoader(folder: String) {}
            },
            launcher,
            object : SettingsFile {
                private var values: SettingsValues? = null

                override suspend fun read() = values

                override suspend fun write(values: SettingsValues) {
                    this.values = values
                }
            },
            clock,
            readme =
                object : ReadmeSource {
                    override suspend fun readme(mod: CatalogMod) = readme(mod.id)

                    override suspend fun image(url: String): ByteArray =
                        throw java.io.IOException("The demo has no network")
                },
        )
    }

    /** An invented readme in the shape that real mod readmes have. */
    fun readme(id: String): Readme {
        val m = state().mod(id)
        val needs = m.newestRelease?.dependencies.orEmpty().map { state().mod(it.id).name }
        val text = buildString {
            appendLine("# ${m.name}")
            appendLine()
            appendLine("${m.description} Made by **${m.authors.joinToString(", ")}**.")
            appendLine()
            appendLine("## Features")
            appendLine()
            appendLine("- Works in single player and in multiplayer")
            appendLine("- Settings in the game with `F1` (BepInEx Configuration Manager)")
            appendLine("- No changes to your saved missions")
            appendLine()
            appendLine("## Installation")
            appendLine()
            appendLine("1. Install [BepInEx 5](https://github.com/BepInEx/BepInEx/releases).")
            if (needs.isNotEmpty()) appendLine("2. Install ${needs.joinToString(" and ")}.")
            appendLine("${if (needs.isNotEmpty()) 3 else 2}. Put the mod in `BepInEx/plugins`.")
            appendLine()
            appendLine("> [!NOTE]")
            appendLine("> Turn the mod off before you report a bug in the game itself.")
            appendLine()
            appendLine("## Keys")
            appendLine()
            appendLine("| Key | Action |")
            appendLine("| --- | --- |")
            appendLine("| `F1` | Open the settings |")
            appendLine("| `Ctrl` + `M` | Show or hide the mod |")
            appendLine()
            appendLine("<details>")
            appendLine("<summary>Settings file</summary>")
            appendLine()
            appendLine("```ini")
            appendLine("[General]")
            appendLine("Enabled = true")
            appendLine("```")
            appendLine()
            appendLine("</details>")
        }
        return nnomm.core.rules.parseReadme(text, "nnomm-demo", m.id, m.name)
    }
}

class DemoLauncher(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : GameLauncher {
    private val flow = MutableStateFlow(false)
    private var end: Job? = null
    override val running = flow.asStateFlow()

    override suspend fun start(state: AppState, withoutMods: Boolean) {
        flow.value = true
        end?.cancel()
        end =
            scope.launch {
                delay(20_000)
                stop()
            }
    }

    override suspend fun stop(): Boolean {
        end?.cancel()
        flow.value = false
        return true
    }
}

fun prevVersion(v: String): String {
    val p = v.split('.').map { Regex("^\\d+").find(it)?.value?.toIntOrNull() ?: 0 }.toMutableList()
    for (i in p.indices.reversed()) {
        if (p[i] > 0) {
            p[i]--
            return p.joinToString(".")
        }
        p[i] = 9
    }
    return "0.$v"
}

fun demoStore(scope: CoroutineScope? = null) = AppStore(Demo.state(), Demo.io(), scope)
