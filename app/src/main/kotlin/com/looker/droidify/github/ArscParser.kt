package com.looker.droidify.github

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal parser for `resources.arsc` that resolves a resource ID to file paths
 * across device configurations (densities).
 */
class ArscParser(private val bytes: ByteArray) {

    private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val globalStrings: List<String?>
    private val packages = mutableListOf<PackageChunk>()

    private class PackageChunk(
        val id: Int,
        val offset: Int,
        val size: Int,
    )

    data class Candidate(
        val density: Int,
        val path: String,
    )

    companion object {
        private const val RES_TABLE_TYPE = 0x0002
        private const val RES_STRING_POOL_TYPE = 0x0001
        private const val RES_TABLE_PACKAGE_TYPE = 0x0200
        private const val RES_TABLE_TYPE_TYPE = 0x0201

        private const val TYPE_REFERENCE = 0x01
        private const val TYPE_STRING = 0x03

        fun rasterDensityScore(density: Int): Int = when (density) {
            0xFFFE, 0xFFFF -> -1 // Exclude anydpi / nodpi from physical raster ranking
            0 -> 0              // Default / any density last
            640 -> 640          // xxxhdpi
            480 -> 480          // xxhdpi
            320 -> 320          // xhdpi
            240 -> 240          // hdpi
            213 -> 213          // tvdpi
            160 -> 160          // mdpi
            120 -> 120          // ldpi
            else -> density
        }
    }

    init {
        if (bytes.size < 12 || buffer.u16(0) != RES_TABLE_TYPE) {
            throw ApkProbeException("Invalid resources.arsc header")
        }
        val poolType = buffer.u16(12)
        if (poolType != RES_STRING_POOL_TYPE) {
            throw ApkProbeException("Expected string pool in resources.arsc at offset 12")
        }
        globalStrings = AxmlManifestParser.readStringPool(buffer, bytes, 12)
        val poolChunkSize = buffer.getInt(12 + 4)
        if (poolChunkSize < 8 || 12 + poolChunkSize > bytes.size) {
            throw ApkProbeException("Invalid string pool chunk size in resources.arsc")
        }

        var pos = 12 + poolChunkSize
        while (pos + 8 <= bytes.size) {
            val chunkType = buffer.u16(pos)
            val chunkSize = buffer.getInt(pos + 4)
            if (chunkSize < 8 || chunkSize > bytes.size - pos) break
            if (chunkType == RES_TABLE_PACKAGE_TYPE && pos + 12 <= bytes.size) {
                val pkgId = buffer.getInt(pos + 8) and 0xFF
                packages.add(PackageChunk(pkgId, pos, chunkSize))
            }
            pos += chunkSize
        }
    }

    fun resolveResId(resId: Int, maxDepth: Int = 3): List<Candidate> {
        if (resId == 0 || maxDepth <= 0) return emptyList()
        val targetPkg = (resId ushr 24) and 0xFF
        val targetType = (resId ushr 16) and 0xFF
        val targetEntry = resId and 0xFFFF

        val candidates = mutableListOf<Candidate>()
        for (pkg in packages) {
            if (targetPkg != 0 && pkg.id != targetPkg) continue
            val headerSize = buffer.u16(pkg.offset + 2)
            var subPos = pkg.offset + headerSize
            val pkgEnd = pkg.offset + pkg.size
            while (subPos + 8 <= pkgEnd && subPos + 8 <= bytes.size) {
                val chunkType = buffer.u16(subPos)
                val chunkHeaderSize = buffer.u16(subPos + 2)
                val chunkSize = buffer.getInt(subPos + 4)
                if (chunkSize < 8 || chunkSize > pkgEnd - subPos) break
                if (chunkType == RES_TABLE_TYPE_TYPE && subPos + 20 <= bytes.size) {
                    val typeId = bytes[subPos + 8].toInt() and 0xFF
                    if (typeId == targetType) {
                        val entryCount = buffer.getInt(subPos + 12)
                        val entriesStart = buffer.getInt(subPos + 16)
                        val density = if (subPos + 36 <= bytes.size) {
                            buffer.u16(subPos + 34)
                        } else {
                            0
                        }
                        if (targetEntry in 0 until entryCount) {
                            val offsetPos = subPos + chunkHeaderSize + targetEntry * 4
                            if (offsetPos + 4 <= bytes.size) {
                                val entryOffset = buffer.getInt(offsetPos)
                                if (entryOffset != -1) {
                                    val entryPos = subPos + entriesStart + entryOffset
                                    if (entryPos + 8 <= bytes.size) {
                                        val entrySize = buffer.u16(entryPos)
                                        val flags = buffer.u16(entryPos + 2)
                                        // Simple entry (not complex/bag)
                                        if ((flags and 0x0001) == 0 && entryPos + entrySize + 8 <= bytes.size) {
                                            val valType = bytes[entryPos + entrySize + 3].toInt() and 0xFF
                                            val valData = buffer.getInt(entryPos + entrySize + 4)
                                            if (valType == TYPE_STRING) {
                                                val str = globalStrings.getOrNull(valData)
                                                if (!str.isNullOrBlank()) {
                                                    candidates.add(Candidate(density, str))
                                                }
                                            } else if (valType == TYPE_REFERENCE && valData != 0 && valData != resId) {
                                                candidates.addAll(resolveResId(valData, maxDepth - 1))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                subPos += chunkSize
            }
        }
        return candidates
    }

    private fun ByteBuffer.u16(offset: Int): Int = getShort(offset).toInt() and 0xFFFF
}
