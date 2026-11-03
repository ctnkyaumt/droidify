package com.looker.droidify.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateResolverTest {

    private fun createRelease(
        versionCode: Long,
        version: String = "1.0",
        signature: String = "default_sig",
        incompatibilities: List<Release.Incompatibility> = emptyList(),
        platforms: List<String> = emptyList(),
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
        platforms = platforms,
        incompatibilities = incompatibilities,
    )

    @Test
    fun `installed signer matches non-latest release offers that release as update`() {
        // Issue #1420: Catima switched to reproducible builds.
        // Installed version is signed by F-Droid (sig_fdroid) at versionCode 168.
        // Available releases:
        // - 1002 signed by upstream (sig_upstream)
        // - 1001 signed by F-Droid (sig_fdroid)
        // - 168 signed by F-Droid (sig_fdroid)
        val installed = InstalledItem(
            packageName = "com.catima",
            version = "2.44.0",
            versionCode = 168L,
            signature = "sig_fdroid",
            signatures = listOf("sig_fdroid"),
        )

        val releases = listOf(
            createRelease(versionCode = 1002L, version = "2.45.0", signature = "sig_upstream"),
            createRelease(versionCode = 1001L, version = "2.45.0 (F-Droid)", signature = "sig_fdroid"),
            createRelease(versionCode = 168L, version = "2.44.0", signature = "sig_fdroid"),
        )

        val suggested = UpdateResolver.findSuggestedRelease(releases, installed)
        assertNotNull(suggested, "Should find a suggested release matching installed signature")
        assertEquals(1001L, suggested.versionCode, "Should suggest version code 1001")
        assertEquals("sig_fdroid", suggested.signature)
        assertTrue(UpdateResolver.canUpdate(installed, releases))
    }

    @Test
    fun `rotated cert where installed has signer history containing release signer has no mismatch`() {
        // Issue #1425: Signal installed with rotated signing key.
        // Installed app has signing lineage: current key (sig_new) and past key (sig_original).
        // Release is signed with sig_original.
        val installed = InstalledItem(
            packageName = "org.thoughtcrime.securesms",
            version = "8.26.4",
            versionCode = 800L,
            signature = "sig_new",
            signatures = listOf("sig_new", "sig_original"),
        )

        val release = createRelease(
            versionCode = 900L,
            version = "8.27.0",
            signature = "sig_original",
        )

        assertTrue(
            UpdateResolver.isSignatureCompatible(installed.signatures, release.signature),
            "Rotated cert history containing release signature should be compatible",
        )

        val suggested = UpdateResolver.findSuggestedRelease(listOf(release), installed)
        assertNotNull(suggested)
        assertEquals(900L, suggested.versionCode)
    }

    @Test
    fun `incompatible signature with no rotated match is not offered as update`() {
        val installed = InstalledItem(
            packageName = "com.example.app",
            version = "1.0",
            versionCode = 100L,
            signature = "sig_A",
            signatures = listOf("sig_A"),
        )

        val release = createRelease(
            versionCode = 200L,
            version = "2.0",
            signature = "sig_B",
        )

        assertFalse(UpdateResolver.isSignatureCompatible(installed.signatures, release.signature))
        val suggested = UpdateResolver.findSuggestedRelease(listOf(release), installed)
        assertNotNull(suggested, "Candidate release is returned for detail view")
        assertFalse(
            UpdateResolver.isSignatureCompatible(installed.signatures, suggested.signature),
            "Suggested release should not be signature compatible",
        )
        assertFalse(
            UpdateResolver.canUpdate(installed, listOf(release)),
            "Incompatible signature must not be offered as an update",
        )
    }
}
