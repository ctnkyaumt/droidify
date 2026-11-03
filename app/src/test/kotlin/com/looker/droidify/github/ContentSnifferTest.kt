package com.looker.droidify.github

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentSnifferTest {

    @Test
    fun `sniffs png magic bytes`() {
        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
        )
        val format = ContentSniffer.sniffRasterFormat(pngBytes)
        assertEquals(ContentSniffer.ImageFormat.PNG, format)
        assertEquals("png", format?.extension)
    }

    @Test
    fun `sniffs jpeg magic bytes`() {
        val jpegBytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte(),
        )
        val format = ContentSniffer.sniffRasterFormat(jpegBytes)
        assertEquals(ContentSniffer.ImageFormat.JPEG, format)
        assertEquals("jpg", format?.extension)
    }

    @Test
    fun `sniffs webp magic bytes`() {
        // RIFF (4 bytes) + file size (4 bytes) + WEBP (4 bytes)
        val webpBytes = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0x24.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
        )
        val format = ContentSniffer.sniffRasterFormat(webpBytes)
        assertEquals(ContentSniffer.ImageFormat.WEBP, format)
        assertEquals("webp", format?.extension)
    }

    @Test
    fun `sniffs binary xml magic bytes`() {
        val xmlBytes = byteArrayOf(0x03.toByte(), 0x00.toByte(), 0x08.toByte(), 0x00.toByte(), 0x10, 0x00, 0x00, 0x00)
        assertTrue(ContentSniffer.isBinaryXml(xmlBytes))
        assertNull(ContentSniffer.sniffRasterFormat(xmlBytes))
    }

    @Test
    fun `unknown or short bytes return null and false`() {
        assertNull(ContentSniffer.sniffRasterFormat(byteArrayOf()))
        assertNull(ContentSniffer.sniffRasterFormat(byteArrayOf(1, 2, 3)))
        assertNull(ContentSniffer.sniffRasterFormat(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)))
        assertFalse(ContentSniffer.isBinaryXml(byteArrayOf()))
        assertFalse(ContentSniffer.isBinaryXml(byteArrayOf(1, 2, 3)))
    }
}
