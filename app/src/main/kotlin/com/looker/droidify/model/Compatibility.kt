package com.looker.droidify.model

object Compatibility {

    val OPTIONAL_FEATURES: Set<String> = setOf(
        "android.hardware.gamepad",
    )

    fun minInstallableTargetSdk(deviceSdk: Int): Int = when {
        deviceSdk >= 36 -> 25
        deviceSdk == 35 -> 24
        deviceSdk == 34 -> 23
        else -> 0
    }

    fun isTargetSdkIncompatible(targetSdkVersion: Int, deviceSdk: Int): Boolean {
        if (targetSdkVersion <= 0) return false
        val minTarget = minInstallableTargetSdk(deviceSdk)
        return minTarget > 0 && targetSdkVersion < minTarget
    }

    fun computeIncompatibilities(
        release: Release,
        deviceSdk: Int,
        devicePlatforms: Set<String>,
        deviceFeatures: Set<String>,
    ): List<Release.Incompatibility> {
        val incompatibilities = mutableListOf<Release.Incompatibility>()
        if (release.minSdkVersion > 0 && deviceSdk < release.minSdkVersion) {
            incompatibilities += Release.Incompatibility.MinSdk
        }
        if (release.maxSdkVersion > 0 && deviceSdk > release.maxSdkVersion) {
            incompatibilities += Release.Incompatibility.MaxSdk
        }
        if (isTargetSdkIncompatible(release.targetSdkVersion, deviceSdk)) {
            incompatibilities += Release.Incompatibility.TargetSdk
        }
        if (release.platforms.isNotEmpty() && (release.platforms intersect devicePlatforms).isEmpty()) {
            incompatibilities += Release.Incompatibility.Platform
        }
        incompatibilities += (release.features - deviceFeatures - OPTIONAL_FEATURES).sorted()
            .map { Release.Incompatibility.Feature(it) }
        return incompatibilities
    }
}
