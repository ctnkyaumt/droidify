package com.looker.droidify.github

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubTreeIconResolverTest {

    @Test
    fun `quax-like list picks fastlane icon`() {
        val paths = listOf(
            "README.md",
            "build.gradle.kts",
            "fastlane/metadata/android/en-US/images/icon.png",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
        )
        val chosen = GitHubTreeIconResolver.findBestTreeIconPath(paths)
        assertEquals("fastlane/metadata/android/en-US/images/icon.png", chosen)
    }

    @Test
    fun `fastlane other locale picked when en-US is missing`() {
        val paths = listOf(
            "app/fastlane/metadata/android/de-DE/images/icon.jpg",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
        )
        val chosen = GitHubTreeIconResolver.findBestTreeIconPath(paths)
        assertEquals("app/fastlane/metadata/android/de-DE/images/icon.jpg", chosen)
    }

    @Test
    fun `metadata icon picked when fastlane is missing`() {
        val paths = listOf(
            "metadata/en-US/images/icon.png",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
        )
        val chosen = GitHubTreeIconResolver.findBestTreeIconPath(paths)
        assertEquals("metadata/en-US/images/icon.png", chosen)
    }

    @Test
    fun `playstore icon picked over mipmaps`() {
        val paths = listOf(
            "app/src/main/ic_launcher-playstore.png",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
        )
        val chosen = GitHubTreeIconResolver.findBestTreeIconPath(paths)
        assertEquals("app/src/main/ic_launcher-playstore.png", chosen)
    }

    @Test
    fun `web icon picked over mipmaps`() {
        val paths = listOf(
            "ic_launcher-web.png",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
        )
        val chosen = GitHubTreeIconResolver.findBestTreeIconPath(paths)
        assertEquals("ic_launcher-web.png", chosen)
    }

    @Test
    fun `app-module mipmap picked when no fastlane`() {
        val paths = listOf(
            "app/src/main/res/mipmap-mdpi/ic_launcher.png",
            "app/src/main/res/mipmap-hdpi/ic_launcher.png",
            "app/src/main/res/mipmap-xhdpi/ic_launcher.png",
            "app/src/main/res/mipmap-xxhdpi/ic_launcher.png",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
        )
        val chosen = GitHubTreeIconResolver.findBestTreeIconPath(paths)
        assertEquals("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png", chosen)
    }

    @Test
    fun `test and debug source sets are ignored when main exists`() {
        val paths = listOf(
            "app/src/test/res/mipmap-xxxhdpi/ic_launcher.png",
            "app/src/androidTest/res/mipmap-xxxhdpi/ic_launcher.png",
            "app/src/debug/res/mipmap-xxxhdpi/ic_launcher.png",
            "app/src/beta/res/mipmap-xxxhdpi/ic_launcher.png",
            "app/src/nightly/res/mipmap-xxxhdpi/ic_launcher.png",
            "app/src/main/res/mipmap-xxhdpi/ic_launcher.png",
        )
        val chosen = GitHubTreeIconResolver.findBestTreeIconPath(paths)
        assertEquals("app/src/main/res/mipmap-xxhdpi/ic_launcher.png", chosen)
    }

    @Test
    fun `cromite-like repository with no icon files returns null`() {
        val paths = listOf(
            "README.md",
            "patches/0001-privacy.patch",
            "build/config.py",
        )
        assertNull(GitHubTreeIconResolver.findBestTreeIconPath(paths))
    }

    @Test
    fun `buildTreeIconUrl encodes path segments correctly`() {
        val url = GitHubTreeIconResolver.buildTreeIconUrl(
            owner = "Teskann",
            repo = "QuaX",
            defaultBranch = "main",
            path = "fastlane/metadata/android/en-US/images/icon.png",
        )
        assertEquals(
            "https://raw.githubusercontent.com/Teskann/QuaX/main/fastlane/metadata/android/en-US/images/icon.png",
            url,
        )

        val urlWithSpaces = GitHubTreeIconResolver.buildTreeIconUrl(
            owner = "owner",
            repo = "repo",
            defaultBranch = "master",
            path = "src/main/res/icon name/ic_launcher.png",
        )
        assertEquals(
            "https://raw.githubusercontent.com/owner/repo/master/src/main/res/icon%20name/ic_launcher.png",
            urlWithSpaces,
        )
    }
}
