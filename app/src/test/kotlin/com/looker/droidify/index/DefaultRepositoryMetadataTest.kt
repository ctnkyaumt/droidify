package com.looker.droidify.index

import com.looker.droidify.model.Repository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DefaultRepositoryMetadataTest {

    @Test
    fun defaultRepositoriesIncludeCalyxWithoutTestingTag() {
        val calyx = Repository.defaultRepositories.find {
            it.address == "https://calyxos.gitlab.io/calyx-fdroid-repo/fdroid/repo"
        }
        assertNotNull(calyx)
        assertEquals("CalyxOS repository", calyx!!.name)
        assertEquals("The official Calyx Labs F-Droid repository.", calyx.description)
    }

    @Test
    fun defaultRepositoryPreservesCuratedMetadataWhenIndexNameHasTesting() {
        val calyxTemplate = Repository.defaultRepositories.first {
            it.address == "https://calyxos.gitlab.io/calyx-fdroid-repo/fdroid/repo"
        }

        val incomingIndexName = "The Calyx Institute F-Droid repo (testing)"
        val incomingIndexDesc = "This is a copy of the Calyx Institute F-Droid repository for test purposes."

        val defaultRepo = (Repository.defaultRepositories + Repository.newRepos())
            .find { it.address == calyxTemplate.address }

        val resolvedName = defaultRepo?.name ?: incomingIndexName
        val resolvedDesc = defaultRepo?.description ?: incomingIndexDesc

        assertEquals("CalyxOS repository", resolvedName)
        assertEquals("The official Calyx Labs F-Droid repository.", resolvedDesc)
    }
}
