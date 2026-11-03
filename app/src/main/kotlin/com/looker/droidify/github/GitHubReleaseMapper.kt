package com.looker.droidify.github

import com.looker.droidify.model.Product
import com.looker.droidify.model.Release
import com.looker.droidify.sync.JsonParser
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

/**
 * Turns GitHub releases into Droid-ify products, one product per app (package name)
 * found among the `.apk` assets.
 */
object GitHubReleaseMapper {

    const val MAX_RELEASES = 3

    private const val APK_EXTENSION = ".apk"
    private const val SHA256_PREFIX = "sha256:"

    private val ABI_REGEX = Regex(
        "(?<![A-Za-z0-9])(arm64-v8a|arm64_v8a|armeabi-v7a|armeabi_v7a|armeabi|x86_64|x86-64|" +
            "arm64|aarch64|x86|x64|arm|universal)(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val VERSION_REGEX = Regex(
        "(?<![A-Za-z0-9])v?\\d+(?:\\.\\d+)+(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val SEPARATOR_REGEX = Regex("[-_.\\s]+")

    fun parseReleases(json: String): List<GitHubRelease> =
        JsonParser.decodeFromString(ListSerializer(GitHubRelease.serializer()), json)

    fun parseRepoInfo(json: String): GitHubRepoInfo =
        JsonParser.decodeFromString(GitHubRepoInfo.serializer(), json)

    /**
     * Published, non-prerelease releases, newest first, at most [MAX_RELEASES].
     */
    fun stableReleases(releases: List<GitHubRelease>): List<GitHubRelease> = releases
        .filter { !it.draft && !it.prerelease }
        .sortedByDescending { it.publishedAt.toMillis() }
        .take(MAX_RELEASES)

    /**
     * The `.apk` assets of [stableReleases] which can run on a device supporting [deviceAbis].
     */
    fun assetsToProbe(
        releases: List<GitHubRelease>,
        deviceAbis: Collection<String>,
    ): List<GitHubAsset> = stableReleases(releases)
        .flatMap { it.assets }
        .filter { it.isApk && isSupported(abiOf(it.name), deviceAbis) }

    /**
     * Android ABI encoded in an asset file name, `null` for universal APKs.
     */
    fun abiOf(fileName: String): String? {
        val token = ABI_REGEX.find(fileName.removeApkExtension())?.value?.lowercase()
        return when (token) {
            "arm64-v8a", "arm64_v8a", "arm64", "aarch64" -> "arm64-v8a"
            "armeabi-v7a", "armeabi_v7a", "arm" -> "armeabi-v7a"
            "armeabi" -> "armeabi"
            "x86_64", "x86-64", "x64" -> "x86_64"
            "x86" -> "x86"
            else -> null
        }
    }

    /**
     * Name of the app variant an asset belongs to, with version and ABI removed,
     * e.g. `arm64_ChromePublic.apk` -> `ChromePublic`, `quax-v4.21.0_x86_64.apk` -> `quax`.
     */
    fun groupNameOf(fileName: String, tag: String): String {
        var name = ABI_REGEX.replace(fileName.removeApkExtension(), " ")
        if (tag.isNotBlank()) {
            name = name.replace(tag, " ", ignoreCase = true)
            name = name.replace(tag.removePrefix("v").removePrefix("V"), " ", ignoreCase = true)
        }
        name = VERSION_REGEX.replace(name, " ")
        return name.split(SEPARATOR_REGEX).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun map(
        repositoryId: Long,
        repo: GitHubRepoInfo,
        releases: List<GitHubRelease>,
        deviceAbis: List<String>,
        probe: (GitHubAsset) -> ApkManifest?,
    ): List<Product> {
        val candidates = stableReleases(releases).flatMap { release ->
            release.assets
                .filter { it.isApk }
                .mapNotNull { asset ->
                    val abi = abiOf(asset.name)
                    if (!isSupported(abi, deviceAbis)) return@mapNotNull null
                    val manifest = probe(asset) ?: return@mapNotNull null
                    Candidate(
                        release = release,
                        asset = asset,
                        abi = abi,
                        groupName = groupNameOf(asset.name, release.tagName),
                        manifest = manifest,
                    )
                }
                // Prefer the device's primary ABI, universal APKs last
                .sortedBy { candidate ->
                    candidate.abi?.let { deviceAbis.indexOf(it) } ?: deviceAbis.size
                }
        }

        val repoName = repo.name.replaceFirstChar { it.uppercaseChar() }
        val owner = repo.owner?.login.orEmpty()
        val byPackage = candidates.groupBy { it.manifest.packageName }
        return byPackage.map { (packageName, packageCandidates) ->
            val groupName = packageCandidates.first().groupName
            val name = if (byPackage.size == 1 || groupName.isBlank()) {
                repoName
            } else {
                "$repoName $groupName"
            }
            val published = packageCandidates.map { it.release.publishedAt.toMillis() }
            Product(
                repositoryId = repositoryId,
                packageName = packageName,
                name = name,
                summary = repo.description.orEmpty(),
                description = "",
                whatsNew = packageCandidates.first().release.body.orEmpty().trim(),
                icon = if (owner.isNotEmpty()) "https://github.com/$owner.png" else "",
                metadataIcon = "",
                author = Product.Author(
                    name = owner,
                    email = "",
                    web = if (owner.isNotEmpty()) "https://github.com/$owner" else "",
                ),
                source = repo.htmlUrl,
                changelog = if (repo.htmlUrl.isNotEmpty()) "${repo.htmlUrl}/releases" else "",
                web = repo.htmlUrl,
                tracker = if (repo.htmlUrl.isNotEmpty()) "${repo.htmlUrl}/issues" else "",
                added = published.min(),
                updated = published.max(),
                suggestedVersionCode = 0L,
                categories = emptyList(),
                antiFeatures = emptyList(),
                licenses = emptyList(),
                donates = emptyList(),
                screenshots = emptyList(),
                releases = packageCandidates.map { it.toRelease() },
            )
        }.sortedBy { it.name }
    }

    private class Candidate(
        val release: GitHubRelease,
        val asset: GitHubAsset,
        val abi: String?,
        val groupName: String,
        val manifest: ApkManifest,
    )

    private fun Candidate.toRelease(): Release {
        val digest = asset.digest.orEmpty()
        val hash = if (digest.startsWith(SHA256_PREFIX, ignoreCase = true)) {
            digest.substring(SHA256_PREFIX.length)
        } else {
            ""
        }
        return Release(
            selected = false,
            version = manifest.versionName.ifBlank { release.tagName.removePrefix("v") },
            versionCode = manifest.versionCode,
            added = release.publishedAt.toMillis(),
            size = asset.size,
            minSdkVersion = manifest.minSdkVersion,
            targetSdkVersion = manifest.targetSdkVersion,
            maxSdkVersion = 0,
            source = "",
            release = asset.downloadUrl,
            hash = hash,
            hashType = "sha256",
            signature = "",
            obbMain = "",
            obbMainHash = "",
            obbMainHashType = "",
            obbPatch = "",
            obbPatchHash = "",
            obbPatchHashType = "",
            permissions = manifest.permissions,
            features = emptyList(),
            platforms = listOfNotNull(abi),
            incompatibilities = emptyList(),
        )
    }

    private val GitHubAsset.isApk: Boolean
        get() = name.endsWith(APK_EXTENSION, ignoreCase = true)

    private fun isSupported(abi: String?, deviceAbis: Collection<String>): Boolean =
        abi == null || abi in deviceAbis

    private fun String.removeApkExtension(): String =
        if (endsWith(APK_EXTENSION, ignoreCase = true)) dropLast(APK_EXTENSION.length) else this

    private fun String?.toMillis(): Long = if (this.isNullOrEmpty()) {
        0L
    } else {
        try {
            Instant.parse(this).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
