package com.looker.droidify.github

object ContentSniffer {

    enum class ImageFormat(val extension: String) {
        PNG("png"),
        JPEG("jpg"),
        WEBP("webp"),
    }

    /**
     * Determines whether [bytes] represent a supported raster image (PNG, JPEG, WebP)
     * by checking its magic bytes.
     */
    fun sniffRasterFormat(bytes: ByteArray): ImageFormat? {
        // PNG: 89 50 4E 47
        if (bytes.size >= 4 &&
            (bytes[0].toInt() and 0xFF) == 0x89 &&
            (bytes[1].toInt() and 0xFF) == 0x50 &&
            (bytes[2].toInt() and 0xFF) == 0x4E &&
            (bytes[3].toInt() and 0xFF) == 0x47
        ) {
            return ImageFormat.PNG
        }
        // JPEG: FF D8 FF
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8 &&
            (bytes[2].toInt() and 0xFF) == 0xFF
        ) {
            return ImageFormat.JPEG
        }
        // WebP: RIFF at 0..3 and WEBP at 8..11
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return ImageFormat.WEBP
        }
        return null
    }

    /**
     * Checks if [bytes] start with the Android binary XML magic: 03 00 08 00
     */
    fun isBinaryXml(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
        (bytes[0].toInt() and 0xFF) == 0x03 &&
        (bytes[1].toInt() and 0xFF) == 0x00 &&
        (bytes[2].toInt() and 0xFF) == 0x08 &&
        (bytes[3].toInt() and 0xFF) == 0x00
}
