package com.looker.droidify.github

import com.looker.droidify.assets
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApkIconResolverTest {

    private class InMemoryRangeReader(private val bytes: ByteArray) : RangeReader {
        override val size: Long = bytes.size.toLong()

        override suspend fun read(offset: Long, length: Int): ByteArray =
            bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private val apk: ByteArray
        get() = assets("github/tuner_158.apk")!!.readBytes()

    @Test
    fun `resolves highest density raster icon in real apk fixture`() = runTest {
        val reader = InMemoryRangeReader(apk)
        val manifest = ApkManifestReader.read(reader)

        assertEquals(0x7f040018, manifest.iconResId)

        val resolvedPath = ApkIconResolver.resolveIconPath(
            reader = reader,
            iconResId = manifest.iconResId,
            roundIconResId = manifest.roundIconResId,
        )
        assertEquals("res/5p.png", resolvedPath)

        val resolvedIcon = ApkIconResolver.resolveIcon(
            reader = reader,
            iconResId = manifest.iconResId,
            roundIconResId = manifest.roundIconResId,
        )
        assertNotNull(resolvedIcon)
        assertEquals("res/5p.png", resolvedIcon.path)
        assertEquals("png", resolvedIcon.extension)
        assertTrue(resolvedIcon.bytes.isNotEmpty())
        assertEquals(4534, resolvedIcon.bytes.size)
    }

    @Test
    fun `returns null when resId is zero or invalid`() = runTest {
        val reader = InMemoryRangeReader(apk)

        assertNull(ApkIconResolver.resolveIconPath(reader, 0, 0))
        assertNull(ApkIconResolver.resolveIconPath(reader, 0x7f049999, 0))
    }
}
