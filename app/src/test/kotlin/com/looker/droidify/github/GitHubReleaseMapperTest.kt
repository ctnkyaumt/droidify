package com.looker.droidify.github

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GitHubReleaseMapperTest {

    private val arm64Device = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    private fun asset(id: Long, name: String, tag: String, digest: String? = null): String {
        val digestJson = if (digest != null) "\"$digest\"" else "null"
        return """
            {
              "id": $id,
              "name": "$name",
              "size": ${id * 1000},
              "digest": $digestJson,
              "updated_at": "2026-09-15T11:29:20Z",
              "browser_download_url": "https://github.com/owner/repo/releases/download/$tag/$name"
            }
        """.trimIndent()
    }

    private fun release(
        tag: String,
        publishedAt: String?,
        assets: List<String>,
        draft: Boolean = false,
        prerelease: Boolean = false,
        body: String = "Notes for $tag",
    ): String = """
        {
          "id": ${tag.hashCode()},
          "tag_name": "$tag",
          "name": "$tag",
          "body": "$body",
          "draft": $draft,
          "prerelease": $prerelease,
          "published_at": ${if (publishedAt != null) "\"$publishedAt\"" else "null"},
          "assets": [${assets.joinToString(",")}]
        }
    """.trimIndent()

    private fun releases(vararg releases: String): List<GitHubRelease> =
        GitHubReleaseMapper.parseReleases("[${releases.joinToString(",")}]")

    private val cromiteRepo = GitHubReleaseMapper.parseRepoInfo(
        """
        {
          "name": "cromite",
          "description": "Cromite a Bromite fork with ad blocking and privacy enhancements",
          "html_url": "https://github.com/uazo/cromite",
          "owner": { "login": "uazo" }
        }
        """.trimIndent(),
    )

    private val quaxRepo = GitHubReleaseMapper.parseRepoInfo(
        """
        {
          "name": "QuaX",
          "description": "Privacy respecting X client for Android",
          "html_url": "https://github.com/Teskann/QuaX",
          "owner": { "login": "Teskann" }
        }
        """.trimIndent(),
    )

    @Test
    fun `cromite assets are grouped into one app per apk kind with device platforms`() {
        val tag = "v153.0.8010.37-11507ac1061b5ea227806f5e84db5a57df6ccf6a"
        val input = releases(
            release(
                tag = tag,
                publishedAt = "2026-09-15T11:51:14Z",
                assets = listOf(
                    asset(1, "arm64_ChromePublic.apk", tag),
                    asset(2, "arm64_ChromePublic.apk.mapping", tag),
                    asset(3, "arm_ChromePublic.apk", tag),
                    asset(4, "x64_ChromePublic.apk", tag),
                    asset(5, "arm64_SystemWebView.apk", tag),
                    asset(6, "arm64_SystemWebViewShell.apk", tag),
                    asset(7, "chrome-win.zip", tag),
                ),
            ),
        )
        val manifests = mapOf(
            "arm64_ChromePublic.apk" to
                ApkManifest("org.cromite.cromite", 801003702, "153.0.8010.37", 29, 36),
            "arm_ChromePublic.apk" to
                ApkManifest("org.cromite.cromite", 801003700, "153.0.8010.37", 29, 36),
            "x64_ChromePublic.apk" to
                ApkManifest("org.cromite.cromite", 801003706, "153.0.8010.37", 29, 36),
            "arm64_SystemWebView.apk" to
                ApkManifest("com.android.webview", 801003701, "153.0.8010.37", 29, 36),
            "arm64_SystemWebViewShell.apk" to
                ApkManifest("org.cromite.webview_shell", 1, "Developer Build", 23, 36),
        )
        val probed = mutableSetOf<String>()

        val products = GitHubReleaseMapper.map(
            repositoryId = 5,
            repo = cromiteRepo,
            releases = input,
            deviceAbis = arm64Device,
        ) { probed += it.name; manifests[it.name] }

        assertEquals(
            setOf(
                "arm64_ChromePublic.apk",
                "arm_ChromePublic.apk",
                "arm64_SystemWebView.apk",
                "arm64_SystemWebViewShell.apk",
            ),
            probed,
        )
        assertEquals(
            listOf("Cromite ChromePublic", "Cromite SystemWebView", "Cromite SystemWebViewShell"),
            products.map { it.name },
        )
        assertEquals(
            listOf("org.cromite.cromite", "com.android.webview", "org.cromite.webview_shell"),
            products.map { it.packageName },
        )
        val chrome = products.first()
        assertEquals(5L, chrome.repositoryId)
        assertEquals(
            listOf(listOf("arm64-v8a"), listOf("armeabi-v7a")),
            chrome.releases.map { it.platforms },
        )
        assertEquals(listOf(801003702L, 801003700L), chrome.releases.map { it.versionCode })
        assertEquals(
            "https://github.com/owner/repo/releases/download/$tag/arm64_ChromePublic.apk",
            chrome.releases.first().release,
        )
        assertEquals(1000L, chrome.releases.first().size)
        assertEquals(29, chrome.releases.first().minSdkVersion)
        assertEquals(36, chrome.releases.first().targetSdkVersion)
        assertEquals(
            "Cromite a Bromite fork with ad blocking and privacy enhancements",
            chrome.summary,
        )
        assertEquals("https://github.com/uazo/cromite", chrome.source)
        assertEquals("https://github.com/uazo.png", chrome.icon)
        assertEquals(listOf(listOf("arm64-v8a")), products[1].releases.map { it.platforms })
    }

    @Test
    fun `quax universal and per abi apks become one app`() {
        val tag = "v4.21.0"
        val input = releases(
            release(
                tag = tag,
                publishedAt = "2026-11-01T15:12:20Z",
                assets = listOf(
                    asset(11, "quax-v4.21.0.apk", tag),
                    asset(12, "quax-v4.21.0_arm64-v8a.apk", tag),
                    asset(13, "quax-v4.21.0_armeabi-v7a.apk", tag),
                    asset(14, "quax-v4.21.0_x86_64.apk", tag),
                ),
            ),
        )
        val manifests = mapOf(
            "quax-v4.21.0.apk" to ApkManifest("com.teskann.quax", 400001058, "4.21.0", 24, 37),
            "quax-v4.21.0_arm64-v8a.apk" to
                ApkManifest("com.teskann.quax", 400001061, "4.21.0", 24, 37),
            "quax-v4.21.0_armeabi-v7a.apk" to
                ApkManifest("com.teskann.quax", 400001060, "4.21.0", 24, 37),
            "quax-v4.21.0_x86_64.apk" to
                ApkManifest("com.teskann.quax", 400001062, "4.21.0", 24, 37),
        )

        val products = GitHubReleaseMapper.map(
            repositoryId = 7,
            repo = quaxRepo,
            releases = input,
            deviceAbis = arm64Device,
        ) { manifests[it.name] }

        assertEquals(1, products.size)
        val quax = products.single()
        assertEquals("QuaX", quax.name)
        assertEquals("com.teskann.quax", quax.packageName)
        assertEquals("Privacy respecting X client for Android", quax.summary)
        assertEquals("Notes for v4.21.0", quax.whatsNew)
        assertEquals(
            listOf(listOf("arm64-v8a"), listOf("armeabi-v7a"), emptyList()),
            quax.releases.map { it.platforms },
        )
        assertEquals(listOf("4.21.0", "4.21.0", "4.21.0"), quax.releases.map { it.version })
    }

    @Test
    fun `drafts and prereleases are ignored and only the latest three releases are kept`() {
        fun appRelease(
            tag: String,
            publishedAt: String?,
            draft: Boolean = false,
            prerelease: Boolean = false,
        ) = release(
            tag = tag,
            publishedAt = publishedAt,
            draft = draft,
            prerelease = prerelease,
            assets = listOf(asset(tag.filter(Char::isDigit).toLong(), "app-$tag.apk", tag)),
        )

        val input = releases(
            appRelease("v6.0.0", null, draft = true),
            appRelease("v5.1.0", "2026-06-01T10:00:00Z", prerelease = true),
            appRelease("v5.0.0", "2026-05-01T10:00:00Z"),
            appRelease("v4.0.0", "2026-04-01T10:00:00Z"),
            appRelease("v3.0.0", "2026-03-01T10:00:00Z"),
            appRelease("v2.0.0", "2026-02-01T10:00:00Z"),
        )

        val products = GitHubReleaseMapper.map(
            repositoryId = 1,
            repo = quaxRepo,
            releases = input,
            deviceAbis = arm64Device,
        ) { asset ->
            val version = asset.name.removePrefix("app-v").removeSuffix(".apk")
            ApkManifest("com.example.app", version.filter(Char::isDigit).toLong(), version, 21, 34)
        }

        assertEquals(
            listOf("5.0.0", "4.0.0", "3.0.0"),
            products.single().releases.map { it.version },
        )
    }

    @Test
    fun `sha256 digest becomes the release hash`() {
        val tag = "v1.0"
        val input = releases(
            release(
                tag = tag,
                publishedAt = "2026-01-01T00:00:00Z",
                assets = listOf(
                    asset(21, "app_arm64-v8a.apk", tag, digest = "sha256:abc"),
                    asset(22, "app_armeabi-v7a.apk", tag, digest = null),
                ),
            ),
        )

        val products = GitHubReleaseMapper.map(
            repositoryId = 1,
            repo = quaxRepo,
            releases = input,
            deviceAbis = arm64Device,
        ) { asset ->
            ApkManifest("com.example.app", if (asset.id == 21L) 2L else 1L, "1.0", 21, 34)
        }

        val releases = products.single().releases
        assertEquals("abc", releases[0].hash)
        assertEquals("sha256", releases[0].hashType)
        assertEquals("", releases[1].hash)
    }
}
