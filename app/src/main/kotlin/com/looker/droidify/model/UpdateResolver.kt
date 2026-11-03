package com.looker.droidify.model

object UpdateResolver {

    fun isSignatureCompatible(
        installedSignatures: Collection<String>,
        releaseSignature: String,
    ): Boolean {
        // Old behaviour: only matches if releaseSignature exactly equals first installed signature
        return releaseSignature == installedSignatures.firstOrNull()
    }

    fun findSuggestedRelease(
        releases: List<Release>,
        installedItem: InstalledItem?,
        primaryPlatform: String? = null,
    ): Release? {
        // Old behaviour: only checks the first release (highest version code)
        val latest = releases.firstOrNull { it.incompatibilities.isEmpty() } ?: return null
        if (installedItem != null && installedItem.signature != latest.signature) {
            return null
        }
        return latest
    }

    fun canUpdate(installedItem: InstalledItem?, releases: List<Release>): Boolean {
        val suggested = findSuggestedRelease(releases, installedItem) ?: return false
        return installedItem != null && suggested.versionCode > installedItem.versionCode
    }
}
