package com.looker.droidify.service

import com.looker.droidify.model.Release
import com.looker.droidify.model.Repository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadQueueTest {

    private val testRepo = Repository.defaultRepositories.first()

    private fun createTask(
        packageName: String,
        versionCode: Long = 100L,
    ): DownloadTask {
        val release = Release(
            selected = false,
            version = "1.0",
            versionCode = versionCode,
            added = 0L,
            size = 1000L,
            minSdkVersion = 21,
            targetSdkVersion = 33,
            maxSdkVersion = 0,
            source = "",
            release = "$packageName.apk",
            hash = "hash_$packageName",
            hashType = "sha256",
            signature = "sig",
            obbMain = "",
            obbMainHash = "",
            obbMainHashType = "",
            obbPatch = "",
            obbPatchHash = "",
            obbPatchHashType = "",
            permissions = emptyList(),
            features = emptyList(),
            platforms = emptyList(),
            incompatibilities = emptyList(),
        )
        return DownloadTask(
            packageName = packageName,
            name = packageName,
            repository = testRepo,
            release = release,
            isUpdate = true,
        )
    }

    @Test
    fun `enqueueing same package twice does not queue duplicate tasks`() {
        val queue = DownloadQueue()
        val task1 = createTask("com.example.app1")
        val task1Duplicate = createTask("com.example.app1")

        assertTrue(queue.enqueue(task1), "First enqueue should succeed")
        assertFalse(queue.enqueue(task1Duplicate), "Second enqueue for same package should be ignored")
        assertEquals(1, queue.size, "Queue size should remain 1")
    }

    @Test
    fun `enqueueing same package while actively downloading does not queue`() {
        val queue = DownloadQueue()
        val task1 = createTask("com.example.app1")

        assertTrue(queue.enqueue(task1))
        val active = queue.poll()
        assertNotNull(active)
        assertEquals(task1.packageName, active.packageName)

        val task1Again = createTask("com.example.app1")
        assertFalse(queue.enqueue(task1Again), "Enqueue while active should be ignored")
        assertEquals(1, queue.size, "Queue size should be 1 (only active task)")
    }

    @Test
    fun `enqueueing different packages succeeds`() {
        val queue = DownloadQueue()
        val task1 = createTask("com.example.app1")
        val task2 = createTask("com.example.app2")

        assertTrue(queue.enqueue(task1))
        assertTrue(queue.enqueue(task2))
        assertEquals(2, queue.size)
        assertEquals(listOf("com.example.app1", "com.example.app2"), queue.queuedPackageNames)
    }
}
