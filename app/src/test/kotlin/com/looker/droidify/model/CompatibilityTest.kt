package com.looker.droidify.model

import com.fasterxml.jackson.core.JsonFactory
import com.looker.droidify.utility.serialization.release
import com.looker.droidify.utility.serialization.serialize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class CompatibilityTest {

    private fun createRelease(
        minSdkVersion: Int = 0,
        targetSdkVersion: Int = 0,
        maxSdkVersion: Int = 0,
        platforms: List<String> = emptyList(),
        features: List<String> = emptyList(),
    ): Release = Release(
        selected = false,
        version = "1.0.0",
        versionCode = 100,
        added = 1000L,
        size = 1024L,
        minSdkVersion = minSdkVersion,
        targetSdkVersion = targetSdkVersion,
        maxSdkVersion = maxSdkVersion,
        source = "",
        release = "",
        hash = "",
        hashType = "",
        signature = "",
        obbMain = "",
        obbMainHash = "",
        obbMainHashType = "",
        obbPatch = "",
        obbPatchHash = "",
        obbPatchHashType = "",
        permissions = emptyList(),
        features = features,
        platforms = platforms,
        incompatibilities = emptyList(),
    )

    @Test
    fun `minInstallableTargetSdk returns correct values per Android version`() {
        assertEquals(0, Compatibility.minInstallableTargetSdk(33))
        assertEquals(23, Compatibility.minInstallableTargetSdk(34))
        assertEquals(24, Compatibility.minInstallableTargetSdk(35))
        assertEquals(25, Compatibility.minInstallableTargetSdk(36))
        assertEquals(25, Compatibility.minInstallableTargetSdk(37))
    }

    @Test
    fun `isTargetSdkIncompatible detects low targetSdkVersion on Android 14+`() {
        // Android 13 (API 33): no restriction
        assertFalse(Compatibility.isTargetSdkIncompatible(22, 33))
        assertFalse(Compatibility.isTargetSdkIncompatible(21, 33))

        // Android 14 (API 34): min targetSdk is 23
        assertTrue(Compatibility.isTargetSdkIncompatible(22, 34))
        assertTrue(Compatibility.isTargetSdkIncompatible(21, 34))
        assertFalse(Compatibility.isTargetSdkIncompatible(23, 34))
        assertFalse(Compatibility.isTargetSdkIncompatible(24, 34))

        // Android 15 (API 35): min targetSdk is 24
        assertTrue(Compatibility.isTargetSdkIncompatible(23, 35))
        assertFalse(Compatibility.isTargetSdkIncompatible(24, 35))
        assertFalse(Compatibility.isTargetSdkIncompatible(25, 35))

        // Android 16 (API 36): min targetSdk is 25
        assertTrue(Compatibility.isTargetSdkIncompatible(24, 36))
        assertFalse(Compatibility.isTargetSdkIncompatible(25, 36))

        // Unspecified targetSdk (0) is never marked incompatible
        assertFalse(Compatibility.isTargetSdkIncompatible(0, 34))
        assertFalse(Compatibility.isTargetSdkIncompatible(0, 35))
    }

    @Test
    fun `computeIncompatibilities marks low targetSdk release as TargetSdk incompatible`() {
        val releaseLow = createRelease(targetSdkVersion = 22)
        val incompatibilities34 = Compatibility.computeIncompatibilities(
            release = releaseLow,
            deviceSdk = 34,
            devicePlatforms = setOf("arm64-v8a"),
            deviceFeatures = emptySet(),
        )
        assertEquals(listOf(Release.Incompatibility.TargetSdk), incompatibilities34)

        val releaseOk = createRelease(targetSdkVersion = 23)
        val incompatibilitiesOk = Compatibility.computeIncompatibilities(
            release = releaseOk,
            deviceSdk = 34,
            devicePlatforms = setOf("arm64-v8a"),
            deviceFeatures = emptySet(),
        )
        assertTrue(incompatibilitiesOk.isEmpty())
    }

    @Test
    fun `gamepad feature requirement is treated as optional`() {
        val release = createRelease(
            features = listOf("android.hardware.gamepad"),
        )
        val deviceFeatures = setOf("android.hardware.touchscreen")

        val incompatibilities = Compatibility.computeIncompatibilities(
            release = release,
            deviceSdk = 34,
            devicePlatforms = emptySet(),
            deviceFeatures = deviceFeatures,
        )
        assertTrue(incompatibilities.isEmpty())
    }

    @Test
    fun `non-optional missing feature produces Feature incompatibility`() {
        val release = createRelease(
            features = listOf("android.hardware.camera", "android.hardware.gamepad"),
        )
        val deviceFeatures = setOf("android.hardware.touchscreen")

        val incompatibilities = Compatibility.computeIncompatibilities(
            release = release,
            deviceSdk = 34,
            devicePlatforms = emptySet(),
            deviceFeatures = deviceFeatures,
        )
        assertEquals(1, incompatibilities.size)
        val featureIncomp = incompatibilities.first()
        assertTrue(featureIncomp is Release.Incompatibility.Feature)
        assertEquals("android.hardware.camera", (featureIncomp as Release.Incompatibility.Feature).feature)
    }

    @Test
    fun `release serialization roundtrip preserves TargetSdk incompatibility`() {
        val release = createRelease(
            targetSdkVersion = 22,
        ).copy(
            incompatibilities = listOf(Release.Incompatibility.TargetSdk),
        )

        val factory = JsonFactory()
        val stream = ByteArrayOutputStream()
        factory.createGenerator(stream).use { generator ->
            release.serialize(generator)
        }

        val deserialized = factory.createParser(stream.toByteArray()).use { parser ->
            parser.nextToken()
            parser.release()
        }

        assertEquals(listOf(Release.Incompatibility.TargetSdk), deserialized.incompatibilities)
    }
}
