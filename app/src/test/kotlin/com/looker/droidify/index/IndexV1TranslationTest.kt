package com.looker.droidify.index

import com.fasterxml.jackson.core.JsonFactory
import com.looker.droidify.model.Product
import com.looker.droidify.model.Release
import com.looker.droidify.utility.serialization.product
import com.looker.droidify.utility.serialization.serialize
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class IndexV1TranslationTest {

    @Test
    fun `parse translation url from index-v1 json`() {
        val json = """
        {
            "repo": {
                "name": "Test Repo",
                "description": "Test",
                "version": 1,
                "timestamp": 123456
            },
            "apps": [
                {
                    "packageName": "com.example.app",
                    "name": "Example App",
                    "summary": "Summary",
                    "description": "Description",
                    "translation": "https://hosted.weblate.org/projects/example/app/",
                    "sourceCode": "https://github.com/example/app",
                    "added": 1600000000000,
                    "lastUpdated": 1700000000000
                }
            ],
            "packages": {}
        }
        """.trimIndent()

        val products = mutableListOf<Product>()
        IndexV1Parser.parse(
            repositoryId = 1L,
            inputStream = ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
            callback = object : IndexV1Parser.Callback {
                override fun onRepository(
                    mirrors: List<String>,
                    name: String,
                    description: String,
                    version: Int,
                    timestamp: Long,
                ) {}

                override fun onProduct(product: Product) {
                    products.add(product)
                }

                override fun onReleases(packageName: String, releases: List<Release>) {}
            },
        )

        assertEquals(1, products.size)
        val product = products.first()
        assertEquals("com.example.app", product.packageName)
        assertEquals("https://hosted.weblate.org/projects/example/app/", product.translation)
    }

    @Test
    fun `product serialization roundtrip preserves translation`() {
        val product = Product(
            repositoryId = 1L,
            packageName = "com.example.test",
            name = "Test",
            summary = "Summary",
            description = "Desc",
            whatsNew = "Changelog",
            icon = "icon.png",
            metadataIcon = "",
            author = Product.Author("Author", "author@example.com", "https://author.com"),
            source = "https://github.com/test",
            changelog = "https://github.com/test/releases",
            web = "https://test.com",
            tracker = "https://github.com/test/issues",
            added = 1000L,
            updated = 2000L,
            suggestedVersionCode = 10L,
            categories = listOf("Tools"),
            antiFeatures = emptyList(),
            licenses = listOf("GPL-3.0-only"),
            donates = emptyList(),
            screenshots = emptyList(),
            releases = emptyList(),
            translation = "https://weblate.org/project/test",
        )

        val factory = JsonFactory()
        val stream = ByteArrayOutputStream()
        factory.createGenerator(stream).use { generator ->
            product.serialize(generator)
        }

        val deserialized = factory.createParser(stream.toByteArray()).use { parser ->
            parser.nextToken()
            parser.product()
        }

        assertEquals("https://weblate.org/project/test", deserialized.translation)
        assertEquals(product.packageName, deserialized.packageName)
    }

    @Test
    fun `product serialization defaults translation to empty when missing`() {
        // Serialized JSON without the translation field (simulating existing DB record)
        val json = """
        {
            "repositoryId": 1,
            "packageName": "com.example.old",
            "name": "Old App",
            "summary": "Summary",
            "description": "Desc"
        }
        """.trimIndent()

        val factory = JsonFactory()
        val deserialized = factory.createParser(json.toByteArray(Charsets.UTF_8)).use { parser ->
            parser.nextToken()
            parser.product()
        }

        assertEquals("", deserialized.translation)
        assertEquals("com.example.old", deserialized.packageName)
    }
}
