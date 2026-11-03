package com.looker.droidify.model

object UpdateResolver {

    /**
     * Checks if [releaseSignature] is compatible with [installedSignatures].
     * Compatible if:
     * - release has no signature specified (empty/blank), or
     * - installedSignatures is empty (unknown), or
     * - release signature matches any signature in installedSignatures
     *   (which includes full signing certificate history for apps with rotated keys).
     */
    fun isSignatureCompatible(
        installedSignatures: Collection<String>,
        releaseSignature: String,
    ): Boolean {
        if (releaseSignature.isBlank()) return true
        if (installedSignatures.isEmpty()) return true
        return releaseSignature in installedSignatures
    }

    /**
     * Finds the suggested release for a product given [installedItem].
     *
     * If the app is installed:
     * 1. Looks for compatible releases with versionCode > installedItem.versionCode
     *    whose signature matches [installedItem.signatures].
     *    Picks the highest versionCode among them (preferring matching primaryPlatform).
     * 2. If no newer update with matching signature exists, looks for a release
     *    with matching signature at the installed versionCode (same version).
     * 3. If no matching signature release exists at all, falls back to the latest
     *    compatible release (which will display the signature mismatch warning).
     *
     * If not installed:
     * Picks the latest compatible release (highest versionCode, matching platform).
     */
    fun findSuggestedRelease(
        releases: List<Release>,
        installedItem: InstalledItem?,
        primaryPlatform: String? = null,
    ): Release? {
        val compatibleReleases = releases.filter { it.incompatibilities.isEmpty() }
        if (compatibleReleases.isEmpty()) return releases.firstOrNull()

        if (installedItem == null) {
            return selectBestRelease(compatibleReleases, primaryPlatform)
        }

        // 1. Look for update (versionCode > installed) with compatible signature
        val matchingUpdates = compatibleReleases.filter { release ->
            release.versionCode > installedItem.versionCode &&
                isSignatureCompatible(installedItem.signatures, release.signature)
        }
        if (matchingUpdates.isNotEmpty()) {
            return selectBestRelease(matchingUpdates, primaryPlatform)
        }

        // 2. Look for installed version with compatible signature
        val matchingCurrent = compatibleReleases.filter { release ->
            release.versionCode == installedItem.versionCode &&
                isSignatureCompatible(installedItem.signatures, release.signature)
        }
        if (matchingCurrent.isNotEmpty()) {
            return selectBestRelease(matchingCurrent, primaryPlatform)
        }

        // 3. Fallback to any release with compatible signature
        val anyMatching = compatibleReleases.filter { release ->
            isSignatureCompatible(installedItem.signatures, release.signature)
        }
        if (anyMatching.isNotEmpty()) {
            return selectBestRelease(anyMatching, primaryPlatform)
        }

        // 4. Fallback to latest compatible (will indicate signature mismatch)
        return selectBestRelease(compatibleReleases, primaryPlatform)
    }

    private fun selectBestRelease(
        releases: List<Release>,
        primaryPlatform: String?,
    ): Release? {
        if (releases.isEmpty()) return null
        val maxVersionCode = releases.maxOf { it.versionCode }
        val candidates = releases.filter { it.versionCode == maxVersionCode }
        return if (primaryPlatform != null) {
            candidates.filter { primaryPlatform in it.platforms }.minByOrNull { it.platforms.size }
                ?: candidates.minByOrNull { it.platforms.size }
                ?: candidates.firstOrNull()
        } else {
            candidates.minByOrNull { it.platforms.size } ?: candidates.firstOrNull()
        }
    }

    fun canUpdate(installedItem: InstalledItem?, releases: List<Release>): Boolean {
        if (installedItem == null) return false
        val suggested = findSuggestedRelease(releases, installedItem) ?: return false
        return suggested.versionCode > installedItem.versionCode &&
            isSignatureCompatible(installedItem.signatures, suggested.signature)
    }
}
