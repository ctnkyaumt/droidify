package com.looker.droidify.github

import android.content.Context
import android.util.Log
import com.looker.droidify.index.RepositoryUpdater
import com.looker.droidify.index.RepositoryUpdater.ErrorType
import com.looker.droidify.index.RepositoryUpdater.Stage
import com.looker.droidify.index.RepositoryUpdater.UpdateException
import com.looker.droidify.model.Repository
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.network.header.addIfNotBlank
import com.looker.droidify.sync.JsonParser
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.result.Result
import com.looker.droidify.utility.extension.android.Android
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/**
 * Syncs a "GitHub source" repository (see [Repository.gitHubSlug]) from the GitHub releases API.
 */
object GitHubRepositoryUpdater {

    private const val TAG = "GitHubRepositoryUpdater"

    private const val API_URL = "https://api.github.com"
    private const val ACCEPT = "application/vnd.github+json"
    private const val API_VERSION = "2022-11-28"
    private const val RELEASES_PER_PAGE = 15

    private const val PROBE_CACHE_DIR = "github"

    private val probeCacheSerializer = MapSerializer(String.serializer(), ApkManifest.serializer())

    suspend fun update(
        context: Context,
        downloader: Downloader,
        repository: Repository,
        unstable: Boolean,
        callback: (Stage, Long, Long?) -> Unit,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val (owner, name) = repository.gitHubSlug
            ?: return@withContext Result.Error(
                UpdateException(ErrorType.VALIDATION, "Not a GitHub repository address"),
            )
        try {
            callback(Stage.DOWNLOAD, 0, null)
            // Never send the repository credentials to GitHub
            val releasesResponse = getApi(
                context = context,
                downloader = downloader,
                url = "$API_URL/repos/$owner/$name/releases?per_page=$RELEASES_PER_PAGE",
                entityTag = repository.entityTag,
            ) ?: return@withContext Result.Success(false)
            val releases = GitHubReleaseMapper.parseReleases(releasesResponse.body)

            val info: GitHubRepoInfo? = try {
                getApi(context, downloader, "$API_URL/repos/$owner/$name", "")
                    ?.let { GitHubReleaseMapper.parseRepoInfo(it.body) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not get details of $owner/$name", e)
                null
            }
            val repoInfo = GitHubRepoInfo(
                name = info?.name?.ifBlank { null } ?: name,
                description = info?.description,
                htmlUrl = info?.htmlUrl?.ifBlank { null } ?: "https://github.com/$owner/$name",
                owner = GitHubOwner(info?.owner?.login?.ifBlank { null } ?: owner),
            )

            val deviceAbis = Android.platforms.toList()
            val assets = GitHubReleaseMapper.assetsToProbe(releases, deviceAbis)
            val cacheFile = probeCacheFile(context, repository.id)
            val cache = readProbeCache(cacheFile)
            val probed = mutableMapOf<String, ApkManifest>()
            var failures = 0
            assets.forEachIndexed { index, asset ->
                callback(Stage.PROCESS, index.toLong(), assets.size.toLong())
                val key = asset.cacheKey
                val manifest = cache[key] ?: try {
                    ApkManifestReader.read(HttpRangeReader(context, downloader, asset))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    Log.w(TAG, "Could not read manifest of ${asset.name}", e)
                    null
                }
                if (manifest != null) probed[key] = manifest
            }
            if (assets.isNotEmpty() && probed.isEmpty()) {
                throw UpdateException(
                    ErrorType.PARSING,
                    "Could not read any APK of $owner/$name",
                )
            }
            writeProbeCache(cacheFile, probed)

            val products = GitHubReleaseMapper.map(
                repositoryId = repository.id,
                repo = repoInfo,
                releases = releases,
                deviceAbis = deviceAbis,
            ) { asset -> probed[asset.cacheKey] }

            callback(Stage.COMMIT, 0, null)
            val changedRepository = repository.update(
                mirrors = emptyList(),
                name = repoInfo.name.replaceFirstChar { it.uppercaseChar() },
                description = repoInfo.description ?: repository.description,
                version = -1,
                lastModified = "",
                // Retry the failed APKs on next sync instead of getting "not modified"
                entityTag = if (failures == 0) releasesResponse.entityTag else "",
                timestamp = products.maxOfOrNull { it.updated } ?: repository.timestamp,
            )
            RepositoryUpdater.commitProducts(context, changedRepository, products, unstable)
            Result.Success(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UpdateException) {
            Result.Error(e)
        } catch (e: Exception) {
            Result.Error(
                UpdateException(ErrorType.PARSING, "Error reading GitHub releases of $owner/$name", e),
            )
        }
    }

    private class ApiResponse(val body: String, val entityTag: String)

    /**
     * GET a GitHub API url, returns `null` when not modified since [entityTag].
     */
    private suspend fun getApi(
        context: Context,
        downloader: Downloader,
        url: String,
        entityTag: String,
    ): ApiResponse? {
        val file = Cache.getTemporaryFile(context)
        try {
            val response = downloader.downloadToFile(
                url = url,
                target = file,
                headers = {
                    add("Accept", ACCEPT)
                    add("X-GitHub-Api-Version", API_VERSION)
                    addIfNotBlank("If-None-Match", entityTag)
                },
            )
            return when (response) {
                is NetworkResponse.Success -> if (response.statusCode == 304) {
                    null
                } else {
                    ApiResponse(file.readText(), response.etag.orEmpty())
                }

                is NetworkResponse.Error.Http -> throw httpException(
                    context,
                    downloader,
                    response.statusCode,
                )

                is NetworkResponse.Error.ConnectionTimeout -> throw networkException(response.exception)
                is NetworkResponse.Error.SocketTimeout -> throw networkException(response.exception)
                is NetworkResponse.Error.IO -> throw networkException(response.exception)
                is NetworkResponse.Error.Unknown -> throw networkException(response.exception)
            }
        } finally {
            file.delete()
        }
    }

    private fun networkException(cause: Exception) =
        UpdateException(ErrorType.NETWORK, "Could not reach GitHub", cause)

    private suspend fun httpException(
        context: Context,
        downloader: Downloader,
        statusCode: Int,
    ): UpdateException {
        val message = when (statusCode) {
            403, 429 -> {
                val reset = rateLimitReset(context, downloader)
                when {
                    reset == null && statusCode == 429 -> "GitHub API rate limit exceeded, try again later"
                    reset == null -> "GitHub denied access (HTTP 403)"
                    else -> "GitHub API rate limit exceeded, try again after " +
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(reset * 1000))
                }
            }

            404 -> "GitHub repository not found (HTTP 404)"
            else -> "GitHub API error (HTTP $statusCode)"
        }
        return UpdateException(ErrorType.HTTP, message)
    }

    /**
     * Epoch seconds when the exhausted API rate limit resets, `null` when not limited.
     * Querying the rate limit does not count against it.
     */
    private suspend fun rateLimitReset(context: Context, downloader: Downloader): Long? {
        val file = Cache.getTemporaryFile(context)
        return try {
            val response = downloader.downloadToFile(
                url = "$API_URL/rate_limit",
                target = file,
                headers = { add("Accept", ACCEPT) },
            )
            if (response !is NetworkResponse.Success) return null
            val rate = JsonParser.parseToJsonElement(file.readText())
                .jsonObject["rate"]?.jsonObject ?: return null
            val remaining = rate["remaining"]?.jsonPrimitive?.longOrNull
            if (remaining != null && remaining > 0) null else rate["reset"]?.jsonPrimitive?.longOrNull
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    private val GitHubAsset.cacheKey: String
        get() = "$id:${updatedAt.orEmpty()}:$size"

    private fun probeCacheFile(context: Context, repositoryId: Long): File =
        File(File(context.filesDir, PROBE_CACHE_DIR), "probe_$repositoryId.json")

    private fun readProbeCache(file: File): Map<String, ApkManifest> = try {
        if (file.exists()) {
            JsonParser.decodeFromString(probeCacheSerializer, file.readText())
        } else {
            emptyMap()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Ignoring unreadable probe cache", e)
        emptyMap()
    }

    // Only keeps the current assets, so the cache does not grow forever
    private fun writeProbeCache(file: File, entries: Map<String, ApkManifest>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(JsonParser.encodeToString(probeCacheSerializer, entries))
        } catch (e: IOException) {
            Log.w(TAG, "Could not write probe cache", e)
        }
    }

    /**
     * Reads parts of a release asset with HTTP range requests (GitHub redirects downloads to
     * a CDN supporting them).
     */
    private class HttpRangeReader(
        private val context: Context,
        private val downloader: Downloader,
        private val asset: GitHubAsset,
    ) : RangeReader {
        override val size: Long = asset.size

        override suspend fun read(offset: Long, length: Int): ByteArray {
            val file = Cache.getTemporaryFile(context)
            try {
                val response = downloader.downloadToFile(
                    url = asset.downloadUrl,
                    target = file,
                    headers = { add("Range", "bytes=$offset-${offset + length - 1}") },
                ) { read, _ ->
                    // Do not download whole APKs from servers ignoring the range
                    if (read.value > length) throw IOException("Range request not honored")
                }
                if (response !is NetworkResponse.Success || response.statusCode != 206) {
                    throw ApkProbeException("Range request for ${asset.name} failed: $response")
                }
                return file.readBytes()
            } finally {
                file.delete()
            }
        }
    }
}
