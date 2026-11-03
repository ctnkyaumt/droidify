package com.looker.droidify.index

import com.looker.droidify.assets
import com.looker.droidify.model.Product
import com.looker.droidify.model.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.InputStream

class KaliIndexV1ParserTest {

    @Test
    fun parseKaliIndexV1Successfully() {
        val stream: InputStream = assets("index-v1.json")?.inputStream()
            ?: javaClass.classLoader?.getResourceAsStream("index-v1.json")
            ?: error("index-v1.json not found in test resources")

        var repoName: String? = null
        var repoDescription: String? = null
        var repoVersion = 0
        val products = mutableListOf<Product>()
        val releasesMap = mutableMapOf<String, List<Release>>()

        stream.use {
            IndexV1Parser.parse(
                repositoryId = 1L,
                inputStream = it,
                callback = object : IndexV1Parser.Callback {
                    override fun onRepository(
                        mirrors: List<String>,
                        name: String,
                        description: String,
                        version: Int,
                        timestamp: Long,
                    ) {
                        repoName = name
                        repoDescription = description
                        repoVersion = version
                    }

                    override fun onProduct(product: Product) {
                        products.add(product)
                    }

                    override fun onReleases(packageName: String, releases: List<Release>) {
                        releasesMap[packageName] = releases
                    }
                },
            )
        }

        assertEquals("Kali NetHunter App Store", repoName)
        assertEquals(30000, repoVersion)
        assertEquals(39, products.size)
        assertEquals(39, releasesMap.size)
    }
}
