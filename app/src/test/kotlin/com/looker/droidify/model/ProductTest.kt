package com.looker.droidify.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProductTest {

    private fun createRelease(
        versionCode: Long,
        version: String = "1.0",
        signature: String = "default_sig",
        incompatibilities: List<Release.Incompatibility> = emptyList(),
        selected: Boolean = false,
    ) = Release(
        selected = selected,
        version = version,
        versionCode = versionCode,
        added = 0L,
        size = 1000L,
        minSdkVersion = 21,
        targetSdkVersion = 33,
        maxSdkVersion = 0,
        source = "",
        release = "test.apk",
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
        incompatibilities = incompatibilities,
    )

    private fun createProduct(
        repositoryId: Long = 1L,
        packageName: String = "org.schabi.newpipe",
        name: String = "NewPipe",
        suggestedVersionCode: Long = 0L,
        releases: List<Release> = emptyList(),
    ) = Product(
        repositoryId = repositoryId,
        packageName = packageName,
        name = name,
        summary = "Lightweight YouTube frontend",
        description = "Description",
        whatsNew = "",
        icon = "",
        metadataIcon = "",
        author = Product.Author("", "", ""),
        source = "",
        changelog = "",
        web = "",
        tracker = "",
        added = 0L,
        updated = 0L,
        suggestedVersionCode = suggestedVersionCode,
        categories = emptyList(),
        antiFeatures = emptyList(),
        licenses = emptyList(),
        donates = emptyList(),
        screenshots = emptyList(),
        releases = releases,
    )

    @Test
    fun `versionCode and compatible fall back to highest compatible release when selectedReleases is empty`() {
        val releases = listOf(
            createRelease(versionCode = 1000L, version = "0.27.0", selected = false),
            createRelease(versionCode = 900L, version = "0.26.0", selected = false),
        )
        val product = createProduct(releases = releases)

        assertTrue(product.selectedReleases.isEmpty())
        assertEquals(1000L, product.versionCode, "Should fall back to highest compatible release versionCode")
        assertTrue(product.compatible, "Should be compatible if compatible releases exist")
        assertEquals(1000L, product.displayRelease?.versionCode)
    }

    @Test
    fun `versionCode returns selected release when selectedReleases has elements`() {
        val releases = listOf(
            createRelease(versionCode = 1000L, version = "0.27.0", selected = false),
            createRelease(versionCode = 900L, version = "0.26.0", selected = true),
        )
        val product = createProduct(releases = releases)

        assertEquals(1, product.selectedReleases.size)
        assertEquals(900L, product.versionCode, "Should return selected release versionCode")
        assertTrue(product.compatible)
    }

    @Test
    fun `compatible is false when all releases are incompatible and selectedReleases is empty`() {
        val releases = listOf(
            createRelease(
                versionCode = 1000L,
                incompatibilities = listOf(Release.Incompatibility.MinSdk),
                selected = false,
            ),
        )
        val product = createProduct(releases = releases)

        assertFalse(product.compatible, "Product should not be compatible when all releases are incompatible")
    }

    private fun createRepo(id: Long, name: String, address: String = "https://example.com/repo$id") = Repository(
        id = id,
        address = address,
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
    fun `findSuggested prefers repository with compatible signature over higher version with mismatch`() {
        val installed = InstalledItem(
            packageName = "org.schabi.newpipe",
            version = "0.25.0",
            versionCode = 800L,
            signature = "sig_newpipe",
            signatures = listOf("sig_newpipe"),
        )

        // Repo 1: F-Droid (signed with sig_fdroid, higher versionCode 1000)
        val fdroidRepo = createRepo(id = 1L, name = "F-Droid", address = "https://f-droid.org/repo")
        val fdroidProduct = createProduct(
            repositoryId = 1L,
            releases = listOf(createRelease(versionCode = 1000L, signature = "sig_fdroid")),
        )

        // Repo 2: NewPipe Archive (signed with sig_newpipe, lower versionCode 950)
        val newpipeRepo = createRepo(id = 2L, name = "NewPipe", address = "https://archive.newpipe.net/fdroid/repo")
        val newpipeProduct = createProduct(
            repositoryId = 2L,
            releases = listOf(createRelease(versionCode = 950L, signature = "sig_newpipe")),
        )

        val candidates = listOf(
            Pair(fdroidProduct, fdroidRepo),
            Pair(newpipeProduct, newpipeRepo),
        )

        val suggested = candidates.findSuggested(installed)
        assertNotNull(suggested)
        assertEquals(2L, suggested.second.id, "Should select NewPipe repository with matching signature")
        assertEquals(950L, suggested.first.versionCode)
    }

    @Test
    fun `findSuggested respects preferredRepositoryId when specified`() {
        val installed = InstalledItem(
            packageName = "org.schabi.newpipe",
            version = "0.25.0",
            versionCode = 800L,
            signature = "sig_newpipe",
            signatures = listOf("sig_newpipe"),
        )

        val repo1 = createRepo(id = 1L, name = "Repo 1", address = "https://repo1.org")
        val product1 = createProduct(
            repositoryId = 1L,
            releases = listOf(createRelease(versionCode = 900L, signature = "sig_newpipe")),
        )

        val repo2 = createRepo(id = 2L, name = "Repo 2", address = "https://repo2.org")
        val product2 = createProduct(
            repositoryId = 2L,
            releases = listOf(createRelease(versionCode = 950L, signature = "sig_newpipe")),
        )

        val candidates = listOf(Pair(product1, repo1), Pair(product2, repo2))

        val suggested = candidates.findSuggested(installed, preferredRepositoryId = 1L)
        assertNotNull(suggested)
        assertEquals(1L, suggested.second.id, "Should select preferred repository 1")
    }
}
