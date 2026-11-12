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
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
                addInterceptor { chain ->
                    try {
                        checkedUrl(chain.request().url.toString())
                    } catch (e: IllegalArgumentException) {
                        throw java.io.IOException(e.message, e)
                    }
                    val response = chain.proceed(chain.request())
                    response.header("Location")?.let { location ->
                        try {
                            checkedUrl(
                                response.request.url.resolve(location)?.toString()
                                    ?: error("Invalid redirect")
                            )
                        } catch (e: IllegalArgumentException) {
                            response.close()
                            throw java.io.IOException(e.message, e)
                        }
                    }
                    response
                }
            }
        }
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
    private val notice: (String) -> Unit = {},
) : Registry {
    private val file = JsonFile(home.resolve("registry.json"), SavedRegistry.serializer(), notice)

    private val official =
        JsonFile(home.resolve("official-registry.json"), SavedRegistry.serializer(), notice)

    override suspend fun read(): List<CatalogMod> = readSnapshot().mods

    override suspend fun readSnapshot(): RegistrySnapshot =
        withContext(Dispatchers.IO) {
            val urls = addresses()
            require(urls.isNotEmpty()) { "No registry addresses are configured." }
            var liveSources = 0
            var savedTime: Long? = null
            val loaded =
                urls
                    .sortedBy { if (it == DEFAULT_REGISTRY) 0 else 1 }
                    .mapNotNull { url ->
                        try {
                            decodeRegistry(client.get(checkedUrl(url)).boundedText()).also {
                                liveSources++
                                if (url == DEFAULT_REGISTRY)
                                    official.write(SavedRegistry(it, System.currentTimeMillis()))
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            notice("Could not load registry $url; other sources remain available.")
                            if (url == DEFAULT_REGISTRY)
                                official.read()?.let {
                                    savedTime = it.readAtMillis
                                    it.mods
                                }
                            else null
                        }
                    }
            require(loaded.isNotEmpty()) { "No registry source could be loaded" }
            RegistrySnapshot(
                loaded.flatten().distinctBy { it.id },
                savedTime.takeIf { liveSources == 0 },
            )
        }

    override suspend fun saved() =
        withContext(Dispatchers.IO) { file.read()?.let { it.mods to it.readAtMillis } }

    override suspend fun save(mods: List<CatalogMod>, readAtMillis: Long) =
        withContext(Dispatchers.IO) { file.write(SavedRegistry(mods, readAtMillis)) }
}

fun decodeRegistry(text: String): List<CatalogMod> =
    json.parseToJsonElement(text).jsonArray.mapNotNull { node ->
        runCatching {
                val obj = node.jsonObject
                val normalized =
                    JsonObject(
                        obj.mapValues { (key, value) ->
                            if (key == "isAiAssisted")
                                JsonPrimitive(
                                    (value as? JsonPrimitive)?.content.equals("true", true)
                                )
                            else if (key == "artifacts")
                                JsonArray(
                                    value.jsonArray.filter { artifact ->
                                        runCatching {
                                                json.decodeFromJsonElement<RegistryArtifact>(
                                                    artifact
                                                )
                                            }
                                            .isSuccess
                                    }
                                )
                            else value
                        }
                    )
                json.decodeFromJsonElement<RegistryEntry>(normalized).toCatalogMod().let { mod ->
                    mod.copy(
                        githubOwner =
                            (obj["githubOwner"] as? JsonPrimitive)?.contentOrNull
                                ?: mod.githubOwner,
                        githubRepository =
                            (obj["githubRepoName"] as? JsonPrimitive)?.contentOrNull
                                ?: mod.githubRepository,
                    )
                }
            }
            .getOrNull()
    }

@Serializable
private data class CachedMeta(
    val sizeKb: Long? = null,
    val dateMillis: Long? = null,
    val retryAfter: Long = 0,
)

class RealMetaSource(private val client: HttpClient, home: Path) : MetaSource {
    private val file =
        JsonFile(
            home.resolve("head.json"),
            MapSerializer(String.serializer(), CachedMeta.serializer()),
        )
    private val mutex = Mutex()
    private val slots = Semaphore(3)
    private val keys = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private var cache: Map<String, CachedMeta>? = null
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null

    suspend fun flush() {
        mutex.withLock {
            cache?.let(file::write)
            flushJob = null
        }
    }

    suspend fun shutdown() {
        cacheScope.cancel()
        flush()
    }

    override suspend fun metadata(mod: CatalogMod, version: ModVersion): VersionMetadata =
        withContext(Dispatchers.IO) {
            val key = "${mod.id}/${version.version}/${version.downloadUrl}"
            keys
                .computeIfAbsent(key) { Mutex() }
                .withLock {
                    mutex
                        .withLock {
                            if (cache == null) cache = file.read().orEmpty()
                            cache!![key]
                        }
                        ?.takeIf {
                            it.retryAfter == 0L || System.currentTimeMillis() < it.retryAfter
                        }
                        ?.let {
                            return@withLock VersionMetadata(it.sizeKb, it.dateMillis)
                        }
                    val value =
                        try {
                            slots.withPermit {
                                val response =
                                    client.head(checkedUrl(requireNotNull(version.downloadUrl)))
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
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            CachedMeta(retryAfter = System.currentTimeMillis() + 300_000)
                        }
                    mutex.withLock {
                        cache = cache.orEmpty() + (key to value)
                        if (flushJob?.isActive != true)
                            flushJob =
                                cacheScope.launch {
                                    delay(1000)
                                    flush()
                                }
                    }
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
                    val text = client.get("https://github.com/$key/releases.atom").boundedText()
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
        url?.let {
            Regex("/releases/(?:download|tag)/([^/?#]+)").find(it)?.groupValues?.get(1)?.let { value
                ->
                java.net.URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
            }
        }
    val entries = doc.getElementsByTagName("entry")
    return (0 until entries.length).mapNotNull { index ->
        val entry = entries.item(index) as Element
        val links = entry.getElementsByTagName("link")
        val releaseTag =
            (0 until links.length).firstNotNullOfOrNull {
                tag((links.item(it) as Element).getAttribute("href"))
            } ?: return@mapNotNull null
        val version =
            mod.versions
                .firstOrNull {
                    tag(it.downloadUrl) == releaseTag ||
                        (it.releaseTagPrefix != null &&
                            releaseTag == it.releaseTagPrefix + it.version)
                }
                ?.version ?: return@mapNotNull null
        fun value(name: String) = entry.getElementsByTagName(name).item(0)?.textContent.orEmpty()
        val html = Jsoup.parseBodyFragment(value("content"))
        html
            .select("h1,h2,h3,h4,h5,h6")
            .filter { it.text().equals("New Contributors", true) }
            .forEach { heading ->
                var next = heading.nextElementSibling()
                while (next != null && !next.tagName().matches(Regex("h[1-6]"))) {
                    val following = next.nextElementSibling()
                    next.remove()
                    next = following
                }
                heading.remove()
            }
        html
            .select("p,li")
            .filter { it.text().startsWith("Full Changelog", true) }
            .forEach { it.remove() }
        html.select("h1,h2,h3,h4,h5,h6").remove()
        html
            .select("a")
            .filter { it.text().startsWith("Full Changelog", true) }
            .forEach { it.remove() }
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
                version.fileName?.trim()
                    ?: java.net.URI(url).path.substringAfterLast('/').trim().ifEmpty { "download" }
            try {
                val path = safe(dir, name)
                var lastPercent = -1
                retryTransfer {
                    client
                        .prepareGet(checkedUrl(url)) {
                            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
                        }
                        .execute { response ->
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
                                    val percent =
                                        if (total != null && total > 0)
                                            (received * 100 / total).toInt().coerceIn(0, 99)
                                        else 0
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        progress(percent)
                                    }
                                }
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
                DownloadedFile(
                    path.toString(),
                    PayloadOrigin.REGISTRY_DOWNLOAD,
                    normalizedHash(version.hash),
                ) {
                    deleteTree(dir)
                }
            } catch (e: Exception) {
                deleteTree(dir)
                throw e
            }
        }
}

internal fun checkedUrl(url: String): String {
    val uri = java.net.URI(url)
    require(
        uri.scheme == "https" ||
            (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "::1"))
    ) {
        "Use an HTTPS address"
    }
    return url
}

internal suspend fun HttpResponse.boundedText(): String {
    val bytes = java.io.ByteArrayOutputStream()
    val channel = bodyAsChannel()
    val buffer = ByteArray(65536)
    while (true) {
        val n = channel.readAvailable(buffer)
        if (n < 0) break
        require(bytes.size() + n <= 16 * 1024 * 1024) { "Response exceeds 16 MiB" }
        bytes.write(buffer, 0, n)
    }
    return bytes.toString(Charsets.UTF_8)
}

private suspend fun retryTransfer(block: suspend () -> Unit) {
    repeat(3) { attempt ->
        try {
            block()
            return
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ClientRequestException || attempt == 2) throw e
            delay(250L * (attempt + 1))
        }
    }
}
