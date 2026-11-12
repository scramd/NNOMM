package nnomm.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * NOMNOM wire format, based on Combat787/NOMM (MIT), upstream 3dacae5. Unknown registry fields may
 * be ignored by the caller's Json decoder.
 */
@Serializable
data class RegistryEntry(
    val id: String,
    val displayName: String = id,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val urls: List<RegistryUrl> = emptyList(),
    val authors: List<String> = emptyList(),
    val artifacts: List<RegistryArtifact> = emptyList(),
    val downloadCount: Long? = null,
    val imageUrl: String? = null,
    val imageHash: String? = null,
    val githubOwner: String? = null,
    val githubRepoName: String? = null,
    val releaseTagPrefix: String? = null,
    val releaseAssetName: String? = null,
    val isClientOrServer: String? = null,
    val isAiAssisted: kotlinx.serialization.json.JsonPrimitive? = null,
) {
    fun toCatalogMod(): CatalogMod {
        val github =
            urls.firstNotNullOfOrNull { ref ->
                Regex("https?://github\\.com/([^/]+)/([^/#?]+)", RegexOption.IGNORE_CASE)
                    .find(ref.url)
            }
        return CatalogMod(
            id,
            displayName,
            authors,
            description,
            cleanTagValues(tags, authors),
            downloadCount ?: 0,
            imageUrl,
            githubOwner = githubOwner ?: github?.groupValues?.get(1),
            githubRepository = githubRepoName ?: github?.groupValues?.get(2)?.removeSuffix(".git"),
            side =
                when (isClientOrServer?.lowercase()) {
                    "server" -> Side.SERVER
                    "client" -> Side.CLIENT
                    else -> Side.BOTH
                },
            versions =
                artifacts.map {
                    it.toModVersion()
                        .copy(
                            releaseTagPrefix = releaseTagPrefix,
                            releaseAssetName = releaseAssetName,
                        )
                },
            urls = urls,
            imageHash = imageHash,
        )
    }
}

@Serializable data class RegistryUrl(val name: String = "", val url: String)

@Serializable
data class RegistryArtifact(
    val fileName: String? = null,
    val version: String,
    val category: String? = null,
    val type: String? = null,
    val gameVersion: String? = null,
    val downloadUrl: String? = null,
    @SerialName("downloadURL") val legacyDownloadUrl: String? = null,
    val hash: String? = null,
    val extends: PackageReference? = null,
    val dependencies: List<PackageReference> = emptyList(),
    val incompatibilities: List<PackageReference> = emptyList(),
) {
    fun toModVersion() =
        ModVersion(
            version,
            if (category.equals("preRelease", true)) ReleaseKind.PRE_RELEASE
            else ReleaseKind.RELEASE,
            if (type.equals("addon", true)) ModType.ADDON else ModType.PLUGIN,
            gameVersion,
            downloadUrl ?: legacyDownloadUrl,
            hash,
            dependencies,
            incompatibilities,
            extends,
            fileName = fileName,
        )
}
