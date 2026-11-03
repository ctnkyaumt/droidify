package com.looker.droidify.model

data class Product(
    var repositoryId: Long,
    val packageName: String,
    val name: String,
    val summary: String,
    var description: String,
    val whatsNew: String,
    val icon: String,
    val metadataIcon: String,
    val author: Author,
    val source: String,
    val changelog: String,
    val web: String,
    val tracker: String,
    val added: Long,
    val updated: Long,
    val suggestedVersionCode: Long,
    val categories: List<String>,
    val antiFeatures: List<String>,
    val licenses: List<String>,
    val donates: List<Donate>,
    val screenshots: List<Screenshot>,
    val releases: List<Release>,
    // Anti-feature name to reason, only when the index provides one
    val antiFeatureReasons: Map<String, String> = emptyMap(),
    val translation: String = "",
) {
    data class Author(val name: String, val email: String, val web: String)

    sealed class Donate {
        data class Regular(val url: String) : Donate()
        data class Bitcoin(val address: String) : Donate()
        data class Litecoin(val address: String) : Donate()
        data class Liberapay(val id: String) : Donate()
        data class OpenCollective(val id: String) : Donate()
    }

    class Screenshot(val locale: String, val type: Type, val path: String) {
        enum class Type(val jsonName: String) {
            VIDEO("video"),
            PHONE("phone"),
            SMALL_TABLET("smallTablet"),
            LARGE_TABLET("largeTablet"),
            WEAR("wear"),
            TV("tv"),
        }

        val identifier: String
            get() = "$locale.${type.name}.$path"

        fun url(
            repository: Repository,
            packageName: String,
        ): String {
            val phoneType = when (type) {
                Type.PHONE -> "phoneScreenshots"
                Type.SMALL_TABLET -> "sevenInchScreenshots"
                Type.LARGE_TABLET -> "tenInchScreenshots"
                Type.WEAR -> "wearScreenshots"
                Type.TV -> "tvScreenshots"
                Type.VIDEO -> return path
            }
            return "${repository.address}/$packageName/$locale/$phoneType/$path"
        }
    }

    // Same releases with different signatures
    val selectedReleases: List<Release>
        get() = releases.filter { it.selected }

    val displayRelease: Release?
        get() = selectedReleases.firstOrNull()
            ?: releases.firstOrNull { it.incompatibilities.isEmpty() }
            ?: releases.firstOrNull()

    val version: String
        get() = displayRelease?.version.orEmpty()

    val versionCode: Long
        get() = selectedReleases.firstOrNull()?.versionCode
            ?: releases.filter { it.incompatibilities.isEmpty() }.maxOfOrNull { it.versionCode }
            ?: releases.firstOrNull()?.versionCode
            ?: 0L

    val compatible: Boolean
        get() = selectedReleases.firstOrNull()?.incompatibilities?.isEmpty()
            ?: releases.any { it.incompatibilities.isEmpty() }

    val signatures: List<String>
        get() = releases.filter { it.incompatibilities.isEmpty() }
            .mapNotNull { it.signature.ifBlank { null } }
            .distinct()
            .ifEmpty { selectedReleases.mapNotNull { it.signature.ifBlank { null } }.distinct().toList() }

    fun item(): ProductItem {
        return ProductItem(
            repositoryId = repositoryId,
            packageName = packageName,
            name = name,
            summary = summary,
            icon = icon,
            metadataIcon = metadataIcon,
            version = version,
            installedVersion = "",
            compatible = compatible,
            canUpdate = false,
            matchRank = 0,
        )
    }

    fun canUpdate(installedItem: InstalledItem?): Boolean {
        return UpdateResolver.canUpdate(installedItem, releases)
    }
}

/**
 * Picks the product whose releases are suggested. When [preferredRepositoryId] is set and
 * that repository provides the app, only its product is considered.
 */
fun List<Pair<Product, Repository>>.findSuggested(
    installedItem: InstalledItem?,
    preferredRepositoryId: Long = 0L,
): Pair<Product, Repository>? {
    val candidates = if (preferredRepositoryId > 0L) {
        filter { (_, repository) -> repository.id == preferredRepositoryId }.ifEmpty { this }
    } else {
        this
    }
    return candidates.maxWithOrNull(
        compareBy(
            { (product, _) ->
                val suggested = UpdateResolver.findSuggestedRelease(product.releases, installedItem)
                val isSameSignature = installedItem == null ||
                    (suggested != null && UpdateResolver.isSignatureCompatible(installedItem.signatures, suggested.signature))
                product.compatible && isSameSignature
            },
            { (product, _) ->
                product.versionCode
            },
        ),
    )
}
