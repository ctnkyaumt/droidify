package com.looker.droidify.model

object InstalledReleaseSelector {

    /**
     * Finds the single item from [items] that should display the "Installed" badge.
     *
     * Selection criteria:
     * 1. Candidate release must match [installedItem.versionCode].
     * 2. Candidate release must have matching signature with [installedItem.signatures]
     *    (or either signature is empty/blank).
     * 3. If multiple candidates qualify:
     *    - Prefer the release from [preferredRepositoryId] (if > 0L).
     *    - Otherwise, prefer the release from [selectedRepositoryId] (if > 0L).
     *    - Otherwise, pick the first candidate in the given display order.
     */
    fun <T> findInstalledRelease(
        items: List<T>,
        installedItem: InstalledItem?,
        preferredRepositoryId: Long = 0L,
        selectedRepositoryId: Long = 0L,
        releaseOf: (T) -> Release,
        repositoryIdOf: (T) -> Long,
    ): T? {
        if (installedItem == null || items.isEmpty()) return null

        val candidates = items.filter { item ->
            val release = releaseOf(item)
            val matchesVersion = release.versionCode == installedItem.versionCode
            val matchesSignature = release.signature.isBlank() ||
                installedItem.signatures.isEmpty() ||
                UpdateResolver.isSignatureCompatible(installedItem.signatures, release.signature)
            matchesVersion && matchesSignature
        }

        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        if (preferredRepositoryId > 0L) {
            val preferred = candidates.find { repositoryIdOf(it) == preferredRepositoryId }
            if (preferred != null) return preferred
        }

        if (selectedRepositoryId > 0L) {
            val selected = candidates.find { repositoryIdOf(it) == selectedRepositoryId }
            if (selected != null) return selected
        }

        return candidates.first()
    }
}
