package com.looker.droidify.github

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal decoder for Android binary XML to extract `<foreground android:drawable="...">`
 * from an adaptive icon XML drawable.
 */
object AdaptiveIconXmlParser {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102

    private const val ATTR_DRAWABLE = 0x01010199
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03

    sealed interface ForegroundResult {
        data class Reference(val resId: Int) : ForegroundResult
        data class Path(val path: String) : ForegroundResult
    }

    fun parseForeground(bytes: ByteArray): ForegroundResult? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 8 || buffer.u16(0) != RES_XML_TYPE) return null

        var strings: List<String?> = emptyList()
        var resourceIds = IntArray(0)

        var position = buffer.u16(2)
        while (position + 8 <= bytes.size) {
            val type = buffer.u16(position)
            val headerSize = buffer.u16(position + 2)
            val chunkSize = buffer.getInt(position + 4)
            if (chunkSize < 8 || chunkSize > bytes.size - position) break
            when (type) {
                RES_STRING_POOL_TYPE -> {
                    strings = AxmlManifestParser.readStringPool(buffer, bytes, position)
                }

                RES_XML_RESOURCE_MAP_TYPE -> {
                    val count = (chunkSize - headerSize) / 4
                    resourceIds = IntArray(count) { buffer.getInt(position + headerSize + it * 4) }
                }

                RES_XML_START_ELEMENT_TYPE -> {
                    val ext = position + headerSize
                    val elementName = strings.getOrNull(buffer.getInt(ext + 4))
                    if (elementName == "foreground") {
                        val attributeStart = buffer.u16(ext + 8)
                        val attributeSize = buffer.u16(ext + 10)
                        val attributeCount = buffer.u16(ext + 12)
                        for (i in 0 until attributeCount) {
                            val attrOffset = ext + attributeStart + i * attributeSize
                            val nameIndex = buffer.getInt(attrOffset + 4)
                            val dataType = bytes[attrOffset + 15].toInt() and 0xFF
                            val data = buffer.getInt(attrOffset + 16)
                            val resId = resourceIds.getOrElse(nameIndex) { 0 }
                            val attrName = strings.getOrNull(nameIndex)
                            val isDrawableAttr = if (resId != 0) resId == ATTR_DRAWABLE else attrName == "drawable"
                            if (isDrawableAttr) {
                                if (dataType == TYPE_REFERENCE && data != 0) {
                                    return ForegroundResult.Reference(data)
                                } else if (dataType == TYPE_STRING) {
                                    val str = strings.getOrNull(data)
                                    if (!str.isNullOrBlank()) {
                                        return ForegroundResult.Path(str)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            position += chunkSize
        }
        return null
    }

    private fun ByteBuffer.u16(offset: Int): Int = getShort(offset).toInt() and 0xFFFF
}
