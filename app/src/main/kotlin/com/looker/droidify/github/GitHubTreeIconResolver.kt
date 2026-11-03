package com.looker.droidify.github

import java.net.URLEncoder

object GitHubTreeIconResolver {

    private val EXCLUDED_SOURCE_SETS = Regex("(^|/)src/(test|debug|androidTest|beta|nightly)(/|$)")

    private val PRIORITY_PATTERNS = listOf(
        // Fastlane en-US exact root
        Regex("^fastlane/metadata/android/en-US/images/icon\\.(png|jpg|jpeg)$", RegexOption.IGNORE_CASE),
        // Fastlane en-US in subfolder
        Regex("(^|.*/)fastlane/metadata/android/en-US/images/icon\\.(png|jpg|jpeg)$", RegexOption.IGNORE_CASE),
        // Fastlane other locales
        Regex("(^|.*/)fastlane/metadata/android/[^/]+/images/icon\\.(png|jpg|jpeg)$", RegexOption.IGNORE_CASE),
        // Metadata en-US exact root
        Regex("^metadata/en-US/images/icon\\.(png|jpg|jpeg)$", RegexOption.IGNORE_CASE),
        // Metadata en-US in subfolder
        Regex("(^|.*/)metadata/en-US/images/icon\\.(png|jpg|jpeg)$", RegexOption.IGNORE_CASE),
        // Metadata other locales
        Regex("(^|.*/)metadata/[^/]+/images/icon\\.(png|jpg|jpeg)$", RegexOption.IGNORE_CASE),
        // Play store and web icons
        Regex("(^|.*/)src/main/ic_launcher-playstore\\.png$", RegexOption.IGNORE_CASE),
        Regex("(^|.*/)ic_launcher-playstore\\.png$", RegexOption.IGNORE_CASE),
        Regex("(^|.*/)ic_launcher-web\\.png$", RegexOption.IGNORE_CASE),
        // Main mipmap density hierarchy
        Regex("(^|.*/)src/main/res/mipmap-xxxhdpi/ic_launcher\\.(png|webp)$", RegexOption.IGNORE_CASE),
        Regex("(^|.*/)src/main/res/mipmap-xxxhdpi/ic_launcher_round\\.(png|webp)$", RegexOption.IGNORE_CASE),
        Regex("(^|.*/)src/main/res/mipmap-xxhdpi/ic_launcher\\.(png|webp)$", RegexOption.IGNORE_CASE),
        Regex("(^|.*/)src/main/res/mipmap-xxhdpi/ic_launcher_round\\.(png|webp)$", RegexOption.IGNORE_CASE),
        Regex("(^|.*/)src/main/res/mipmap-xhdpi/ic_launcher\\.(png|webp)$", RegexOption.IGNORE_CASE),
        Regex("(^|.*/)src/main/res/mipmap-xhdpi/ic_launcher_round\\.(png|webp)$", RegexOption.IGNORE_CASE),
        // General mipmaps when no src/main structure
        Regex("(^|.*/)res/mipmap-xxxhdpi/ic_launcher\\.(png|webp)$", RegexOption.IGNORE_CASE),
        Regex("(^|.*/)res/mipmap-xxxhdpi/ic_launcher_round\\.(png|webp)$", RegexOption.IGNORE_CASE),
        Regex("(^|.*/)res/mipmap-xxhdpi/ic_launcher\\.(png|webp)$", RegexOption.IGNORE_CASE),
        Regex("(^|.*/)res/mipmap-xxhdpi/ic_launcher_round\\.(png|webp)$", RegexOption.IGNORE_CASE),
        Regex("(^|.*/)res/mipmap-xhdpi/ic_launcher\\.(png|webp)$", RegexOption.IGNORE_CASE),
        Regex("(^|.*/)res/mipmap-xhdpi/ic_launcher_round\\.(png|webp)$", RegexOption.IGNORE_CASE),
    )

    fun findBestTreeIconPath(paths: Collection<String>): String? {
        val hasMain = paths.any { it.startsWith("src/main/") || it.contains("/src/main/") }
        val candidatePaths = if (hasMain) {
            paths.filterNot { EXCLUDED_SOURCE_SETS.containsMatchIn(it) }
        } else {
            paths.toList()
        }

        for (pattern in PRIORITY_PATTERNS) {
            val match = candidatePaths.firstOrNull { pattern.matches(it) }
            if (match != null) return match
        }
        return null
    }

    fun buildTreeIconUrl(owner: String, repo: String, defaultBranch: String, path: String): String {
        val encodedPath = path.split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return "https://raw.githubusercontent.com/$owner/$repo/$defaultBranch/$encodedPath"
    }
}
