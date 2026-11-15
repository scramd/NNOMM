package nnomm.app

import javax.xml.parsers.DocumentBuilderFactory
import java.io.StringReader
import org.xml.sax.InputSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.ExecutableType
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.nativehttp.NativeHttpClient
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.provider.UpdateProvider

internal const val NNOMM_LATEST_RELEASE_URL = "https://github.com/scramd/NNOMM/releases/latest"

private val manualWindowsUpdateTypes = setOf(ExecutableType.PORTABLE, ExecutableType.ZIP)

internal fun requiresManualWindowsUpdate(
    platform: Platform,
    executableType: ExecutableType,
): Boolean = platform == Platform.Windows && executableType in manualWindowsUpdateTypes

internal fun requiresManualWindowsUpdate(): Boolean =
    requiresManualWindowsUpdate(Platform.Current, ExecutableRuntime.type())

internal fun updaterChannel(testVersions: Boolean) = if (testVersions) "beta" else "latest"

fun createUpdater(testVersions: Boolean = false) = NucleusUpdater {
    provider =
        object : UpdateProvider {

            val owner: String = "scramd"
            val repo: String = "NNOMM"

            override fun getUpdateMetadataUrl(channel: String, platform: Platform): String {
                val suffix = platformSuffix(platform)
                fun metadata(prefix: String) = if (suffix.isEmpty()) "$prefix.yml" else "$prefix-$suffix.yml"
                if (channel == "latest") return "https://github.com/$owner/$repo/releases/latest/download/${metadata("latest")}"
                val request = HttpRequest.newBuilder(URI("https://github.com/$owner/$repo/releases.atom"))
                    .timeout(java.time.Duration.ofSeconds(20)).build()
                val response = HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
                check(response.statusCode() == 200) { "Could not read test releases" }
                val release = testReleaseTag(response.body()) ?: error("No release is available")
                // release.yaml writes beta*.yml for a test version and latest*.yml for a normal one.
                return "https://github.com/$owner/$repo/releases/download/${release.tag}/${metadata(if (release.beta != null) "beta" else "latest")}"
            }

            override fun getDownloadUrl(fileName: String, version: String): String = releaseDownloadUrl(fileName, version)

            override fun authHeaders(): Map<String, String> = emptyMap()

            private fun platformSuffix(platform: Platform): String =
                when (platform) {
                    Platform.Windows -> ""
                    Platform.MacOS -> "mac"
                    Platform.Linux -> "linux"
                    Platform.Unknown -> ""
                }
        }

    httpClient = NativeHttpClient.create()
    channel = updaterChannel(testVersions)
}

/** The update files name the version without the tag's "v" (release.yaml strips it). */
internal fun releaseDownloadUrl(fileName: String, version: String): String =
    "https://github.com/scramd/NNOMM/releases/download/v$version/$fileName"

/** A release tag as release.yaml accepts it: vMAJOR.MINOR.PATCH, or vMAJOR.MINOR.PATCH-beta.N for a test version. */
internal data class ReleaseTag(val tag: String, val numbers: List<Int>, val beta: Int?) : Comparable<ReleaseTag> {
    override fun compareTo(other: ReleaseTag): Int {
        for (i in 0..2) numbers[i].compareTo(other.numbers[i]).let { if (it != 0) return it }
        // A normal release comes after every test version of the same number.
        return (beta ?: Int.MAX_VALUE).compareTo(other.beta ?: Int.MAX_VALUE)
    }
}

private val releaseTagPattern = Regex("""v(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?""")

internal fun parseReleaseTag(tag: String): ReleaseTag? =
    releaseTagPattern.matchEntire(tag)?.let { m ->
        ReleaseTag(tag, m.groupValues.subList(1, 4).map { it.toInt() }, m.groupValues[4].takeIf { it.isNotEmpty() }?.toInt())
    }

/**
 * The newest release for a player who takes test versions: the highest version among the published
 * test and normal releases in the feed, so a normal release that is newer than the last test version
 * also reaches them. Disable external XML resolution.
 */
internal fun testReleaseTag(body: String): ReleaseTag? {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val entries = factory.newDocumentBuilder().parse(InputSource(StringReader(body)))
        .getElementsByTagNameNS("http://www.w3.org/2005/Atom", "entry")
    val prefix = "https://github.com/scramd/NNOMM/releases/tag/"
    val tags = mutableListOf<ReleaseTag>()
    for (i in 0 until entries.length) {
        val entry = entries.item(i) as org.w3c.dom.Element
        val links = entry.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "link")
        for (j in 0 until links.length) {
            val href = (links.item(j) as org.w3c.dom.Element).getAttribute("href")
            if (!href.startsWith(prefix)) continue
            parseReleaseTag(java.net.URLDecoder.decode(href.removePrefix(prefix), Charsets.UTF_8))?.let(tags::add)
        }
    }
    return tags.maxOrNull()
}
