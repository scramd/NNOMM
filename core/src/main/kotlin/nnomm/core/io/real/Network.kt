package nnomm.core.io.real

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import java.nio.file.*
import java.time.*
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.json.*
import nnomm.core.io.*
import nnomm.core.model.*
import org.jsoup.Jsoup
import org.w3c.dom.Element

const val DEFAULT_REGISTRY = "https://kopterbuzz.github.io/NOMNOM/manifest/manifest.json"

fun networkClient(version: String) =
    HttpClient(OkHttp) {
        expectSuccess = true
        followRedirects = true
        install(UserAgent) { agent = "NNOMM/$version" }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 30_000
        }
    }

@Serializable internal data class SavedRegistry(val mods: List<CatalogMod>, val readAtMillis: Long)

class RealRegistry(
    private val client: HttpClient,
    home: Path,
    private val addresses: () -> List<String> = { listOf(DEFAULT_REGISTRY) },
    notice: (String) -> Unit = {},
) : Registry {
    private val file = JsonFile(home.resolve("registry.json"), SavedRegistry.serializer(), notice)

    override suspend fun read(): List<CatalogMod> =
        withContext(Dispatchers.IO) {
            val urls = addresses()
            require(urls.isNotEmpty()) { "No registry addresses are configured." }
            urls.flatMap { decodeRegistry(client.get(it).bodyAsText()) }.distinctBy { it.id }
        }

    override suspend fun saved() =
        withContext(Dispatchers.IO) { file.read()?.let { it.mods to it.readAtMillis } }

    override suspend fun save(mods: List<CatalogMod>, readAtMillis: Long) =
        withContext(Dispatchers.IO) { file.write(SavedRegistry(mods, readAtMillis)) }
}

fun decodeRegistry(text: String): List<CatalogMod> =
    json.parseToJsonElement(text).jsonArray.map { node ->
        val obj = node.jsonObject
        val normalized =
            JsonObject(
                obj.mapValues { (key, value) ->
                    if (key == "isAiAssisted")
                        JsonPrimitive((value as? JsonPrimitive)?.content.equals("true", true))
                    else value
                }
            )
        json.decodeFromJsonElement<RegistryEntry>(normalized).toCatalogMod().let { mod ->
            mod.copy(
                githubOwner =
                    (obj["githubOwner"] as? JsonPrimitive)?.contentOrNull ?: mod.githubOwner,
                githubRepository =
                    (obj["githubRepoName"] as? JsonPrimitive)?.contentOrNull ?: mod.githubRepository,
            )
        }
    }

@Serializable private data class CachedMeta(val sizeKb: Long? = null, val dateMillis: Long? = null)

class RealMetaSource(private val client: HttpClient, home: Path) : MetaSource {
    private val file =
        JsonFile(
            home.resolve("head.json"),
            MapSerializer(String.serializer(), CachedMeta.serializer()),
        )
    private val mutex = Mutex()
    private val slots = Semaphore(3)
    private val keys = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    override suspend fun metadata(mod: CatalogMod, version: ModVersion): VersionMetadata =
        withContext(Dispatchers.IO) {
            val key = "${mod.id}/${version.version}/${version.downloadUrl}"
            keys
                .computeIfAbsent(key) { Mutex() }
                .withLock {
                    mutex
                        .withLock { file.read().orEmpty()[key] }
                        ?.let {
                            return@withLock VersionMetadata(it.sizeKb, it.dateMillis)
                        }
                    val value =
                        slots.withPermit {
                            val response = client.head(requireNotNull(version.downloadUrl))
                            CachedMeta(
                                response.headers["Content-Length"]?.toLongOrNull()?.let {
                                    (it + 1023) / 1024
                                },
                                response.headers["Last-Modified"]?.let {
                                    runCatching {
                                            ZonedDateTime.parse(
                                                    it,
                                                    DateTimeFormatter.RFC_1123_DATE_TIME,
                                                )
                                                .toInstant()
                                                .toEpochMilli()
                                        }
                                        .getOrNull()
                                },
                            )
                        }
                    mutex.withLock { file.write(file.read().orEmpty() + (key to value)) }
                    VersionMetadata(value.sizeKb, value.dateMillis)
                }
        }
}

class RealReleaseNotes(private val client: HttpClient, home: Path) : ReleaseNotesSource {
    private val file =
        JsonFile(
            home.resolve("release-notes.json"),
            MapSerializer(String.serializer(), String.serializer()),
        )
    private val mutex = Mutex()
    private val loaded = mutableSetOf<String>()

    override suspend fun releases(mod: CatalogMod): List<ReleaseNote> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val owner = mod.githubOwner ?: return@withLock emptyList()
                val repo = mod.githubRepository ?: return@withLock emptyList()
                component(owner)
                component(repo)
                val key = "$owner/$repo"
                val cache = file.read().orEmpty()
                if (key in loaded) return@withLock parseAtom(cache.getValue(key), mod)
                try {
                    val text = client.get("https://github.com/$key/releases.atom").bodyAsText()
                    val notes =
                        parseAtom(text, mod) // Validate before replacing a usable cached feed.
                    file.write(cache + (key to text))
                    loaded += key
                    notes
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cache[key]?.let { parseAtom(it, mod) } ?: throw e
                }
            }
        }
}

fun parseAtom(text: String, mod: CatalogMod): List<ReleaseNote> {
    val factory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
    val doc = factory.newDocumentBuilder().parse(text.byteInputStream())
    fun tag(url: String?) =
        url?.let { Regex("/releases/(?:download|tag)/([^/?#]+)").find(it)?.groupValues?.get(1) }
    val entries = doc.getElementsByTagName("entry")
    return (0 until entries.length).mapNotNull { index ->
        val entry = entries.item(index) as Element
        val links = entry.getElementsByTagName("link")
        val releaseTag =
            (0 until links.length).firstNotNullOfOrNull {
                tag((links.item(it) as Element).getAttribute("href"))
            } ?: return@mapNotNull null
        val version =
            mod.versions.firstOrNull { tag(it.downloadUrl) == releaseTag }?.version
                ?: return@mapNotNull null
        fun value(name: String) = entry.getElementsByTagName(name).item(0)?.textContent.orEmpty()
        val html = Jsoup.parseBodyFragment(value("content"))
        html.select("br").append("\n")
        html.select("p,li,h1,h2,h3,pre").prepend("\n")
        ReleaseNote(
            version,
            value("updated").take(10),
            html.wholeText().lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
}

class RealDownloader(private val client: HttpClient, private val home: Path) : Downloader {
    override suspend fun download(
        mod: CatalogMod,
        version: ModVersion,
        progress: (Int) -> Unit,
    ): DownloadedFile =
        withContext(Dispatchers.IO) {
            val staging = home.resolve("staging")
            Files.createDirectories(staging)
            val dir = Files.createTempDirectory(staging, "download-")
            val url = requireNotNull(version.downloadUrl)
            val name =
                java.net.URI(url).path.substringAfterLast('/').trim().ifEmpty { "download.dll" }
            val path = safe(dir, name)
            try {
                client.prepareGet(url).execute { response ->
                    val channel = response.bodyAsChannel()
                    val total = response.headers["Content-Length"]?.toLongOrNull()
                    var received = 0L
                    Files.newOutputStream(path).use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            ensureActive()
                            val n = channel.readAvailable(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            received += n
                            progress(
                                if (total != null && total > 0)
                                    (received * 100 / total).toInt().coerceIn(0, 99)
                                else 0
                            )
                        }
                    }
                }
                normalizedHash(version.hash)?.let { expected ->
                    require(expected.matches(Regex("[0-9a-f]{64}"))) {
                        "Invalid SHA-256 for ${mod.name}"
                    }
                    require(digest(path) == expected) { "SHA-256 mismatch for ${mod.name}" }
                }
                ensureActive()
                progress(100)
                DownloadedFile(path.toString())
            } catch (e: Exception) {
                deleteTree(dir)
                throw e
            }
        }
}
