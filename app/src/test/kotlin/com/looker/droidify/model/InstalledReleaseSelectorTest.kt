package com.looker.droidify.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InstalledReleaseSelectorTest {

    private fun createRelease(
        versionCode: Long,
        version: String = "1.0",
        signature: String = "test_sig",
        identifier: String = "rel_$versionCode",
    ) = Release(
        selected = false,
        version = version,
        versionCode = versionCode,
        added = 0L,
        size = 1000L,
        minSdkVersion = 21,
        targetSdkVersion = 33,
        maxSdkVersion = 0,
        source = "",
        release = "$identifier.apk",
        hash = "hash_$versionCode",
        hashType = "sha256",
        signature = signature,
        obbMain = "",
        obbMainHash = "",
        obbMainHashType = "",
        obbPatch = "",
        obbPatchHash = "",
        obbPatchHashType = "",
        permissions = emptyList(),
        features = emptyList(),
        platforms = emptyList(),
        incompatibilities = emptyList(),
    )

    private fun createRepo(id: Long, name: String) = Repository(
        id = id,
        address = "https://example.com/repo$id",
        mirrors = emptyList(),
        name = name,
        description = "",
        version = 1,
        enabled = true,
        fingerprint = "",
        lastModified = "",
        entityTag = "",
        updated = 0L,
        timestamp = 0L,
        authentication = "",
    )

    @Test
    fun `single release matching installed versionCode and signature is selected`() {
        val installed = InstalledItem(
            packageName = "com.example.app",
            version = "1.0",
            versionCode = 100L,
            signature = "sig_valid",
            signatures = listOf("sig_valid"),
        )
        val repo = createRepo(1L, "F-Droid")
        val items = listOf(
            Pair(createRelease(200L, "2.0", "sig_valid"), repo),
            Pair(createRelease(100L, "1.0", "sig_valid"), repo),
        )

        val selected = InstalledReleaseSelector.findInstalledRelease(
            items = items,
            installedItem = installed,
            releaseOf = { it.first },
            repositoryIdOf = { it.second.id },
        )

        assertNotNull(selected)
        assertEquals(100L, selected?.first?.versionCode)
        assertEquals(1L, selected?.second?.id)
    }

    @Test
    fun `same versionCode in two repos prefers preferred repository`() {
        // Issue #785: NewPipe / Breezy Weather in both F-Droid and upstream repo with same version
        val installed = InstalledItem(
            packageName = "org.schabi.newpipe",
            version = "0.27.0",
            versionCode = 990L,
            signature = "newpipe_sig",
            signatures = listOf("newpipe_sig"),
        )
        val repoFdroid = createRepo(1L, "F-Droid")
        val repoNewPipe = createRepo(2L, "NewPipe Official")

        val relFdroid = createRelease(990L, "0.27.0", "newpipe_sig", "newpipe_fdroid")
        val relNewPipe = createRelease(990L, "0.27.0", "newpipe_sig", "newpipe_official")

        val items = listOf(
            Pair(relFdroid, repoFdroid),
            Pair(relNewPipe, repoNewPipe),
        )

        val selectedWithPreferred = InstalledReleaseSelector.findInstalledRelease(
            items = items,
            installedItem = installed,
            preferredRepositoryId = 2L,
            selectedRepositoryId = 1L,
            releaseOf = { it.first },
            repositoryIdOf = { it.second.id },
        )

        assertNotNull(selectedWithPreferred)
        assertEquals(2L, selectedWithPreferred?.second?.id, "Should select release from preferred repo 2")
        assertEquals("newpipe_official", selectedWithPreferred?.first?.identifier)
    }

    @Test
    fun `same versionCode in two repos falls back to selected repo when preferred is not set`() {
        val installed = InstalledItem(
            packageName = "org.breezyweather",
            version = "5.0.0",
            versionCode = 500L,
            signature = "breezy_sig",
            signatures = listOf("breezy_sig"),
        )
        val repoFdroid = createRepo(10L, "F-Droid")
        val repoIzzy = createRepo(20L, "IzzyOnDroid")

        val items = listOf(
            Pair(createRelease(500L, "5.0.0", "breezy_sig", "bw_fdroid"), repoFdroid),
            Pair(createRelease(500L, "5.0.0", "breezy_sig", "bw_izzy"), repoIzzy),
        )

        val selected = InstalledReleaseSelector.findInstalledRelease(
            items = items,
            installedItem = installed,
            preferredRepositoryId = 0L,
            selectedRepositoryId = 20L,
            releaseOf = { it.first },
            repositoryIdOf = { it.second.id },
        )

        assertNotNull(selected)
        assertEquals(20L, selected?.second?.id, "Should select release from currently selected repository")
    }

    @Test
    fun `same versionCode in two repos falls back to first in display order`() {
        val installed = InstalledItem(
            packageName = "com.test.app",
            version = "1.0",
            versionCode = 10L,
            signature = "sig_common",
            signatures = listOf("sig_common"),
        )
        val repo1 = createRepo(1L, "Repo 1")
        val repo2 = createRepo(2L, "Repo 2")

        val items = listOf(
            Pair(createRelease(10L, "1.0", "sig_common", "rel_1"), repo1),
            Pair(createRelease(10L, "1.0", "sig_common", "rel_2"), repo2),
        )

        val selected = InstalledReleaseSelector.findInstalledRelease(
            items = items,
            installedItem = installed,
            preferredRepositoryId = 0L,
            selectedRepositoryId = 0L,
            releaseOf = { it.first },
            repositoryIdOf = { it.second.id },
        )

        assertNotNull(selected)
        assertEquals(1L, selected?.second?.id, "Should fall back to first item in display order")
    }

    @Test
    fun `incompatible signature is never marked installed`() {
        val installed = InstalledItem(
            packageName = "com.test.app",
            version = "1.0",
            versionCode = 10L,
            signature = "installed_sig",
            signatures = listOf("installed_sig"),
        )
        val repo = createRepo(1L, "Repo")
        val items = listOf(
            Pair(createRelease(10L, "1.0", "different_sig", "rel_diff"), repo),
        )

        val selected = InstalledReleaseSelector.findInstalledRelease(
            items = items,
            installedItem = installed,
            releaseOf = { it.first },
            repositoryIdOf = { it.second.id },
        )

        assertNull(selected, "Release with different signature should not be considered installed")
    }
}
