package nnomm.app

import kotlinx.serialization.json.*
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
            val token: String? = null

            override fun getUpdateMetadataUrl(channel: String, platform: Platform): String {
                val suffix = platformSuffix(platform)
                val fileName = if (suffix.isEmpty()) "$channel.yml" else "$channel-$suffix.yml"
                val release = if (channel == "latest") "latest/download" else {
                    val request = HttpRequest.newBuilder(URI("https://api.github.com/repos/$owner/$repo/releases?per_page=30"))
                        .timeout(java.time.Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json").build()
                    val response = HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
                    check(response.statusCode() == 200) { "Could not read test releases" }
                    val tag = testReleaseTag(response.body()) ?: error("No test release is available")
                    "download/$tag"
                }
                return "https://github.com/$owner/$repo/releases/$release/$fileName"
            }

            override fun getDownloadUrl(fileName: String, version: String): String =
                "https://github.com/$owner/$repo/releases/download/$version/$fileName"

            override fun authHeaders(): Map<String, String> =
                if (token != null) {
                    mapOf("Authorization" to "token $token")
                } else {
                    emptyMap()
                }

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

internal fun testReleaseTag(body: String): String? = Json.parseToJsonElement(body).jsonArray
    .map { it.jsonObject }
    .firstOrNull { it["draft"]?.jsonPrimitive?.booleanOrNull != true &&
        it["prerelease"]?.jsonPrimitive?.booleanOrNull == true &&
        it["tag_name"]?.jsonPrimitive?.content?.endsWith("-beta") == true }
    ?.get("tag_name")?.jsonPrimitive?.content
