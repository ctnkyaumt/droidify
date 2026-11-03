package com.looker.droidify.github

import com.looker.droidify.assets
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApkManifestReaderTest {

    /**
     * In-memory stand-in for the HTTP range requests (the network is the system boundary).
     * Records how much of the file was requested.
     */
    private class InMemoryRangeReader(private val bytes: ByteArray) : RangeReader {
        var reads = 0
            private set
        var bytesRead = 0L
            private set

        override val size: Long = bytes.size.toLong()

        override suspend fun read(offset: Long, length: Int): ByteArray {
            reads++
            bytesRead += length
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
    }

    // Fixture: org.billthefarmer.tuner_158.apk from https://f-droid.org/repo
    // Expected values from the F-Droid website/API: version 1.58 (158), requires Android 6.0 (API 23),
    // permissions: read shared storage, record audio.
    private val apk: ByteArray
        get() = assets("github/tuner_158.apk")!!.readBytes()

    @Test
    fun `manifest of a real apk is read from ranges`() = runTest {
        val reader = InMemoryRangeReader(apk)

        val manifest = ApkManifestReader.read(reader)

        assertEquals("org.billthefarmer.tuner", manifest.packageName)
        assertEquals(158L, manifest.versionCode)
        assertEquals("1.58", manifest.versionName)
        assertEquals(23, manifest.minSdkVersion)
        assertTrue(
            manifest.permissions.containsAll(
                listOf(
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.RECORD_AUDIO",
                ),
            ),
            "permissions were ${manifest.permissions}",
        )
    }

    @Test
    fun `only a small part of the apk is requested`() = runTest {
        val bytes = apk
        val reader = InMemoryRangeReader(bytes)

        ApkManifestReader.read(reader)

        assertTrue(reader.reads <= 4, "made ${reader.reads} range requests")
        assertTrue(
            reader.bytesRead < bytes.size / 2,
            "read ${reader.bytesRead} of ${bytes.size} bytes",
        )
    }

    @Test
    fun `data that is not a zip archive is rejected`() = runTest {
        val reader = InMemoryRangeReader(ByteArray(4096) { 7 })

        assertFailsWith<ApkProbeException> { ApkManifestReader.read(reader) }
    }
}
