package com.looker.droidify.github

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal parser for `resources.arsc` that resolves a resource ID to file paths
 * across device configurations (densities).
 *
 * The table is read lazily through a [RangeReader]: only the table and string pool headers,
 * the chunk headers of the package and the entry of the requested resource are fetched,
 * so a huge table does not need to be downloaded to find one icon.
 */
class ArscParser private constructor(
    private val source: RangeReader,
    private val packages: List<PackageChunk>,
    private val stringCount: Int,
    private val stringPoolHeaderSize: Int,
    private val stringsStart: Int,
    private val utf8Strings: Boolean,
) {

    private class PackageChunk(
        val id: Int,
        val offset: Long,
        val size: Long,
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
        private const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202

        // ResTable_type flags
        private const val FLAG_SPARSE = 0x01
        private const val FLAG_OFFSET16 = 0x02
        private const val NO_ENTRY_OFFSET16 = 0xFFFF

        // ResTable_entry flags
        private const val ENTRY_FLAG_COMPLEX = 0x0001
        private const val ENTRY_FLAG_COMPACT = 0x0008

        private const val TYPE_REFERENCE = 0x01
        private const val TYPE_STRING = 0x03

        private const val TABLE_HEADER_SIZE = 12L
        private const val STRING_POOL_HEADER_SIZE = 28
        private const val TYPE_CHUNK_PREFIX_SIZE = 36

        // Enough for any resource path, a longer string is treated as missing
        private const val STRING_WINDOW = 512

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

        suspend fun create(source: RangeReader): ArscParser {
            val size = source.size
            if (size < TABLE_HEADER_SIZE + STRING_POOL_HEADER_SIZE ||
                source.readBuffer(0, 4).u16(0) != RES_TABLE_TYPE
            ) {
                throw ApkProbeException("Invalid resources.arsc header")
            }
            val pool = source.readBuffer(TABLE_HEADER_SIZE, STRING_POOL_HEADER_SIZE)
            if (pool.u16(0) != RES_STRING_POOL_TYPE) {
                throw ApkProbeException("Expected string pool in resources.arsc at offset 12")
            }
            val poolHeaderSize = pool.u16(2)
            val poolChunkSize = pool.getInt(4)
            val stringCount = pool.getInt(8)
            val flags = pool.getInt(16)
            val stringsStart = pool.getInt(20)
            if (poolChunkSize < 8 || TABLE_HEADER_SIZE + poolChunkSize > size) {
                throw ApkProbeException("Invalid string pool chunk size in resources.arsc")
            }
            if (stringCount < 0 || TABLE_HEADER_SIZE + poolHeaderSize + stringCount * 4L > size) {
                throw ApkProbeException("Invalid string pool")
            }

            val packages = mutableListOf<PackageChunk>()
            var pos = TABLE_HEADER_SIZE + poolChunkSize
            while (pos + 8 <= size) {
                val header = source.readBuffer(pos, 8)
                val chunkType = header.u16(0)
                val chunkSize = header.getInt(4)
                if (chunkSize < 8 || chunkSize > size - pos) break
                if (chunkType == RES_TABLE_PACKAGE_TYPE && pos + 12 <= size) {
                    val pkgId = source.readBuffer(pos + 8, 4).getInt(0) and 0xFF
                    packages.add(PackageChunk(pkgId, pos, chunkSize.toLong()))
                }
                pos += chunkSize
            }
            return ArscParser(
                source = source,
                packages = packages,
                stringCount = stringCount,
                stringPoolHeaderSize = poolHeaderSize,
                stringsStart = stringsStart,
                utf8Strings = flags and AxmlManifestParser.UTF8_FLAG != 0,
            )
        }

        private suspend fun RangeReader.readBuffer(offset: Long, length: Int): ByteBuffer {
            val bytes = with(ApkManifestReader) { readExactly(offset, length) }
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }

        private fun ByteBuffer.u16(offset: Int): Int = getShort(offset).toInt() and 0xFFFF
    }

    suspend fun resolveResId(resId: Int, maxDepth: Int = 3): List<Candidate> {
        if (resId == 0 || maxDepth <= 0) return emptyList()
        val targetPkg = (resId ushr 24) and 0xFF
        val targetType = (resId ushr 16) and 0xFF
        val targetEntry = resId and 0xFFFF

        val candidates = mutableListOf<Candidate>()
        for (pkg in packages) {
            if (targetPkg != 0 && pkg.id != targetPkg) continue
            val headerSize = source.readBuffer(pkg.offset, 4).u16(2)
            var subPos = pkg.offset + headerSize
            val pkgEnd = pkg.offset + pkg.size
            var seenTargetType = false
            while (subPos + 8 <= pkgEnd) {
                val header = source.readBuffer(subPos, 8)
                val chunkType = header.u16(0)
                val chunkHeaderSize = header.u16(2)
                val chunkSize = header.getInt(4)
                if (chunkSize < 8 || chunkSize > pkgEnd - subPos) break
                if ((chunkType == RES_TABLE_TYPE_TYPE || chunkType == RES_TABLE_TYPE_SPEC_TYPE) && chunkSize >= 12) {
                    val prefix = source.readBuffer(subPos, minOf(chunkSize, TYPE_CHUNK_PREFIX_SIZE))
                    val typeId = prefix.get(8).toInt() and 0xFF
                    if (typeId == targetType) {
                        seenTargetType = true
                        if (chunkType == RES_TABLE_TYPE_TYPE) {
                            addEntryCandidates(subPos, chunkHeaderSize, prefix, targetEntry, resId, maxDepth, candidates)
                        }
                    } else if (seenTargetType) {
                        // Chunks are grouped by type id, nothing of the target type follows
                        break
                    }
                }
                subPos += chunkSize
            }
        }
        return candidates
    }

    private suspend fun addEntryCandidates(
        chunkPos: Long,
        chunkHeaderSize: Int,
        prefix: ByteBuffer,
        targetEntry: Int,
        resId: Int,
        maxDepth: Int,
        candidates: MutableList<Candidate>,
    ) {
        if (prefix.limit() < 20) return
        val entryCount = prefix.getInt(12)
        val entriesStart = prefix.getInt(16)
        val density = if (prefix.limit() >= 36) prefix.u16(34) else 0
        val typeFlags = prefix.get(9).toInt() and 0xFF
        val offsetsPos = chunkPos + chunkHeaderSize
        val entryOffset: Long = when (typeFlags) {
            0 -> {
                if (targetEntry !in 0 until entryCount) return
                val offsetPos = offsetsPos + targetEntry * 4L
                if (offsetPos + 4 > source.size) return
                val offset = source.readBuffer(offsetPos, 4).getInt(0)
                if (offset == -1) return
                offset.toLong()
            }
            FLAG_SPARSE -> findSparseOffset(offsetsPos, entryCount, targetEntry) ?: return
            FLAG_OFFSET16 -> {
                if (targetEntry !in 0 until entryCount) return
                val offsetPos = offsetsPos + targetEntry * 2L
                if (offsetPos + 2 > source.size) return
                val offset = source.readBuffer(offsetPos, 2).u16(0)
                if (offset == NO_ENTRY_OFFSET16) return
                offset * 4L
            }
            // Unknown layout, do not guess
            else -> return
        }

        val entryPos = chunkPos + entriesStart + entryOffset
        if (entryPos < 0 || entryPos + 8 > source.size) return
        val entry = source.readBuffer(entryPos, 8)
        val entrySize = entry.u16(0)
        val flags = entry.u16(2)
        // Simple entry only: not complex/bag, not compact
        if ((flags and (ENTRY_FLAG_COMPLEX or ENTRY_FLAG_COMPACT)) != 0) return

        val valuePos = entryPos + entrySize
        if (valuePos + 8 > source.size) return
        val value = source.readBuffer(valuePos, 8)
        val valType = value.get(3).toInt() and 0xFF
        val valData = value.getInt(4)
        if (valType == TYPE_STRING) {
            val str = readString(valData)
            if (!str.isNullOrBlank()) {
                candidates.add(Candidate(density, str))
            }
        } else if (valType == TYPE_REFERENCE && valData != 0 && valData != resId) {
            candidates.addAll(resolveResId(valData, maxDepth - 1))
        }
    }

    /**
     * Offset of [targetEntry] in a sparse type chunk: [count] sorted pairs of
     * (u16 entry index, u16 offset / 4), or null when it has no such entry.
     */
    private suspend fun findSparseOffset(pairsPos: Long, count: Int, targetEntry: Int): Long? {
        if (count <= 0 || pairsPos + count * 4L > source.size) return null
        var low = 0
        var high = count - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val pair = source.readBuffer(pairsPos + mid * 4L, 4)
            val index = pair.u16(0)
            when {
                index == targetEntry -> return pair.u16(2) * 4L
                index < targetEntry -> low = mid + 1
                else -> high = mid - 1
            }
        }
        return null
    }

    private suspend fun readString(index: Int): String? {
        if (index < 0 || index >= stringCount) return null
        val offset = source.readBuffer(TABLE_HEADER_SIZE + stringPoolHeaderSize + index * 4L, 4).getInt(0)
        val start = TABLE_HEADER_SIZE + stringsStart + offset
        if (start < 0 || start >= source.size) return null
        val window = with(ApkManifestReader) {
            source.readExactly(start, minOf(STRING_WINDOW.toLong(), source.size - start).toInt())
        }
        return try {
            AxmlManifestParser.readPoolString(window, 0, utf8Strings)
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

}

/**
 * A [RangeReader] over an in-memory byte array.
 */
class ByteArrayRangeReader(private val bytes: ByteArray) : RangeReader {
    override val size: Long = bytes.size.toLong()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > bytes.size) {
            throw ApkProbeException("Read of $length bytes at $offset out of bounds")
        }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

/**
 * The window of [size] bytes at [baseOffset] of [reader] (e.g. a stored ZIP entry), fetched in
 * blocks of [blockSize] bytes and kept in a small LRU cache. Gives up with
 * [FetchBudgetExceededException] once more than [maxFetchedBytes] have been fetched in total.
 */
class BlockCachedRangeReader(
    private val reader: RangeReader,
    private val baseOffset: Long,
    override val size: Long,
    private val maxFetchedBytes: Long,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val maxBlocks: Int = DEFAULT_MAX_BLOCKS,
) : RangeReader {

    companion object {
        const val DEFAULT_BLOCK_SIZE = 64 * 1024
        const val DEFAULT_MAX_BLOCKS = 64
    }

    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>): Boolean =
            this.size > maxBlocks
    }

    var fetchedBytes = 0L
        private set

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw ApkProbeException("Read of $length bytes at $offset out of bounds")
        }
        val result = ByteArray(length)
        var done = 0
        while (done < length) {
            val position = offset + done
            val index = position / blockSize
            val block = block(index)
            val inBlock = (position - index * blockSize).toInt()
            val count = minOf(length - done, block.size - inBlock)
            System.arraycopy(block, inBlock, result, done, count)
            done += count
        }
        return result
    }

    private suspend fun block(index: Long): ByteArray {
        blocks[index]?.let { return it }
        val start = index * blockSize
        val length = minOf(blockSize.toLong(), size - start).toInt()
        if (fetchedBytes + length > maxFetchedBytes) {
            throw FetchBudgetExceededException("Fetched more than $maxFetchedBytes bytes")
        }
        val data = with(ApkManifestReader) { reader.readExactly(baseOffset + start, length) }
        fetchedBytes += length
        blocks[index] = data
        return data
    }
}

class FetchBudgetExceededException(message: String) : Exception(message)
