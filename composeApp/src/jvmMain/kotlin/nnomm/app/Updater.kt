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
                val fileName = if (suffix.isEmpty()) "$channel.yml" else "$channel-$suffix.yml"
                val release = if (channel == "latest") "latest/download" else {
                    val request = HttpRequest.newBuilder(URI("https://github.com/$owner/$repo/releases.atom"))
                        .timeout(java.time.Duration.ofSeconds(20)).build()
                    val response = HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
                    check(response.statusCode() == 200) { "Could not read test releases" }
                    val tag = testReleaseTag(response.body()) ?: error("No test release is available")
                    "download/$tag"
                }
                return "https://github.com/$owner/$repo/releases/$release/$fileName"
            }

            override fun getDownloadUrl(fileName: String, version: String): String =
                "https://github.com/$owner/$repo/releases/download/$version/$fileName"

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

/** Atom entries are published releases, newest first. Disable external XML resolution. */
internal fun testReleaseTag(body: String): String? {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val entries = factory.newDocumentBuilder().parse(InputSource(StringReader(body)))
        .getElementsByTagNameNS("http://www.w3.org/2005/Atom", "entry")
    for (i in 0 until entries.length) {
        val entry = entries.item(i) as org.w3c.dom.Element
        val links = entry.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "link")
        for (j in 0 until links.length) {
            val link = links.item(j) as org.w3c.dom.Element
            val href = link.getAttribute("href")
            val prefix = "https://github.com/scramd/NNOMM/releases/tag/"
            if (!href.startsWith(prefix)) continue
            val tag = java.net.URLDecoder.decode(href.removePrefix(prefix), Charsets.UTF_8)
            // release.yaml assigns beta metadata to tags containing -beta (except alpha).
            if ("-beta" in tag && "-alpha" !in tag && '/' !in tag) return tag
        }
    }
    return null
}
