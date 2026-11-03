package com.looker.droidify.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Subsets of the GitHub REST API responses, see https://docs.github.com/en/rest/releases

@Serializable
data class GitHubRelease(
    val id: Long = 0,
    @SerialName("tag_name")
    val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at")
    val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val id: Long = 0,
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url")
    val downloadUrl: String = "",
    val digest: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
)

@Serializable
data class GitHubRepoInfo(
    val name: String = "",
    val description: String? = null,
    @SerialName("html_url")
    val htmlUrl: String = "",
    val owner: GitHubOwner? = null,
)

@Serializable
data class GitHubOwner(
    val login: String = "",
)
