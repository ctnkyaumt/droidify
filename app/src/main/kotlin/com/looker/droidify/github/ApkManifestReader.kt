package com.looker.droidify.github

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Random access to a (remote) file, e.g. through HTTP range requests.
 */
interface RangeReader {
    val size: Long

    suspend fun read(offset: Long, length: Int): ByteArray
}

class ApkProbeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The parts of an APK's `AndroidManifest.xml` needed to list it as a release.
 */
@Serializable
data class ApkManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
    val permissions: List<String> = emptyList(),
    val iconResId: Int = 0,
    val roundIconResId: Int = 0,
)

/**
 * Reads `AndroidManifest.xml` out of an APK without downloading the whole file:
 * the ZIP end of central directory, the central directory and the manifest entry are
 * fetched through [RangeReader] and the binary XML is decoded by [AxmlManifestParser].
 */
object ApkManifestReader {

    const val MANIFEST_NAME = "AndroidManifest.xml"

    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val EOCD_SIZE = 22
    private const val MAX_COMMENT_SIZE = 0xFFFF
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
    private const val ZIP64_LOCATOR_SIZE = 20
    private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L
    private const val ZIP64_EOCD_SIZE = 56
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
    private const val CENTRAL_HEADER_SIZE = 46
    private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
    private const val LOCAL_HEADER_SIZE = 30
    private const val ZIP64_EXTRA_ID = 0x0001
    private const val MASK_32 = 0xFFFFFFFFL

    internal const val METHOD_STORED = 0
    internal const val METHOD_DEFLATED = 8

    private const val MAX_CENTRAL_DIRECTORY_SIZE = 32L * 1024 * 1024
    const val MAX_MANIFEST_SIZE = 8L * 1024 * 1024

    // Slack read after the local header in the same request, local extra fields may differ
    // from the central directory ones
    private const val LOCAL_HEADER_SLACK = 512

    data class ZipEntry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val nameLength: Int,
        val extraLength: Int,
    )

    suspend fun read(reader: RangeReader): ApkManifest {
        val cd = readCentralDirectory(reader)
        val entry = findEntry(cd, MANIFEST_NAME)
            ?: throw ApkProbeException("$MANIFEST_NAME not found in APK")
        val manifest = readEntry(reader, entry, MAX_MANIFEST_SIZE)
        return try {
            AxmlManifestParser.parse(manifest)
        } catch (e: ApkProbeException) {
            throw e
        } catch (e: Exception) {
            throw ApkProbeException("Invalid binary AndroidManifest.xml", e)
        }
    }

    suspend fun readCentralDirectory(reader: RangeReader): ByteArray {
        val size = reader.size
        if (size < EOCD_SIZE) throw ApkProbeException("File too small to be an APK")

        val tailLength = minOf(size, (EOCD_SIZE + MAX_COMMENT_SIZE).toLong()).toInt()
        val tailOffset = size - tailLength
        val tail = reader.readExactly(tailOffset, tailLength)
        val eocd = findEndOfCentralDirectory(tail)
            ?: throw ApkProbeException("No ZIP end of central directory found")

        var cdSize = tail.u32(eocd + 12)
        var cdOffset = tail.u32(eocd + 16)
        if (cdSize == MASK_32 || cdOffset == MASK_32) {
            val locatorPosition = tailOffset + eocd - ZIP64_LOCATOR_SIZE
            if (locatorPosition < 0) throw ApkProbeException("Missing ZIP64 locator")
            val locator = if (eocd >= ZIP64_LOCATOR_SIZE) {
                tail.copyOfRange(eocd - ZIP64_LOCATOR_SIZE, eocd)
            } else {
                reader.readExactly(locatorPosition, ZIP64_LOCATOR_SIZE)
            }
            if (locator.u32(0) != ZIP64_LOCATOR_SIGNATURE) {
                throw ApkProbeException("Invalid ZIP64 locator")
            }
            val zip64EocdOffset = locator.u64(8)
            if (zip64EocdOffset < 0 || zip64EocdOffset + ZIP64_EOCD_SIZE > size) {
                throw ApkProbeException("Invalid ZIP64 end of central directory offset")
            }
            val zip64Eocd = reader.readExactly(zip64EocdOffset, ZIP64_EOCD_SIZE)
            if (zip64Eocd.u32(0) != ZIP64_EOCD_SIGNATURE) {
                throw ApkProbeException("Invalid ZIP64 end of central directory")
            }
            cdSize = zip64Eocd.u64(40)
            cdOffset = zip64Eocd.u64(48)
        }
        if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > size) {
            throw ApkProbeException("Invalid central directory location")
        }
        if (cdSize > MAX_CENTRAL_DIRECTORY_SIZE) {
            throw ApkProbeException("Central directory too large: $cdSize")
        }

        return if (cdOffset >= tailOffset) {
            val start = (cdOffset - tailOffset).toInt()
            tail.copyOfRange(start, start + cdSize.toInt())
        } else {
            reader.readExactly(cdOffset, cdSize.toInt())
        }
    }

    private fun findEndOfCentralDirectory(tail: ByteArray): Int? {
        var position = tail.size - EOCD_SIZE
        while (position >= 0) {
            if (tail.u32(position) == EOCD_SIGNATURE) {
                val commentLength = tail.u16(position + 20)
                if (position + EOCD_SIZE + commentLength <= tail.size) return position
            }
            position--
        }
        return null
    }

    fun findEntry(cd: ByteArray, name: String): ZipEntry? {
        val targetName = name.toByteArray(Charsets.UTF_8)
        var position = 0
        while (position + CENTRAL_HEADER_SIZE <= cd.size) {
            if (cd.u32(position) != CENTRAL_HEADER_SIGNATURE) {
                throw ApkProbeException("Corrupted central directory")
            }
            val method = cd.u16(position + 10)
            var compressedSize = cd.u32(position + 20)
            var uncompressedSize = cd.u32(position + 24)
            val nameLength = cd.u16(position + 28)
            val extraLength = cd.u16(position + 30)
            val commentLength = cd.u16(position + 32)
            var localHeaderOffset = cd.u32(position + 42)
            val nameStart = position + CENTRAL_HEADER_SIZE
            val next = nameStart + nameLength + extraLength + commentLength
            if (next > cd.size) throw ApkProbeException("Corrupted central directory")

            if (nameLength == targetName.size && cd.regionEquals(nameStart, targetName)) {
                var extra = nameStart + nameLength
                val extraEnd = extra + extraLength
                while (extra + 4 <= extraEnd) {
                    val id = cd.u16(extra)
                    val dataSize = cd.u16(extra + 2)
                    if (id == ZIP64_EXTRA_ID) {
                        var field = extra + 4
                        val fieldEnd = minOf(field + dataSize, extraEnd)
                        if (uncompressedSize == MASK_32 && field + 8 <= fieldEnd) {
                            uncompressedSize = cd.u64(field)
                            field += 8
                        }
                        if (compressedSize == MASK_32 && field + 8 <= fieldEnd) {
                            compressedSize = cd.u64(field)
                            field += 8
                        }
                        if (localHeaderOffset == MASK_32 && field + 8 <= fieldEnd) {
                            localHeaderOffset = cd.u64(field)
                        }
                        break
                    }
                    extra += 4 + dataSize
                }
                return ZipEntry(
                    name = name,
                    method = method,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localHeaderOffset = localHeaderOffset,
                    nameLength = nameLength,
                    extraLength = extraLength,
                )
            }
            position = next
        }
        return null
    }

    suspend fun readEntry(
        reader: RangeReader,
        entry: ZipEntry,
        maxUncompressedSize: Long,
    ): ByteArray {
        if (entry.compressedSize > maxUncompressedSize || entry.uncompressedSize > maxUncompressedSize) {
            throw ApkProbeException("${entry.name} too large")
        }
        if (entry.localHeaderOffset < 0 || entry.localHeaderOffset >= reader.size) {
            throw ApkProbeException("Invalid local header offset")
        }
        val wanted = LOCAL_HEADER_SIZE.toLong() + entry.nameLength + entry.extraLength +
            entry.compressedSize + LOCAL_HEADER_SLACK
        val length = minOf(wanted, reader.size - entry.localHeaderOffset).toInt()
        if (length < LOCAL_HEADER_SIZE) throw ApkProbeException("Truncated local header")
        val local = reader.readExactly(entry.localHeaderOffset, length)
        if (local.u32(0) != LOCAL_HEADER_SIGNATURE) {
            throw ApkProbeException("Invalid local file header")
        }
        val dataStart = LOCAL_HEADER_SIZE + local.u16(26) + local.u16(28)
        val compressedSize = entry.compressedSize.toInt()
        val compressed = if (dataStart + compressedSize <= local.size) {
            local.copyOfRange(dataStart, dataStart + compressedSize)
        } else {
            reader.readExactly(entry.localHeaderOffset + dataStart, compressedSize)
        }
        return when (entry.method) {
            METHOD_STORED -> compressed
            METHOD_DEFLATED -> inflate(compressed, entry.uncompressedSize.toInt(), entry.name)
            else -> throw ApkProbeException("Unsupported compression method ${entry.method}")
        }
    }

    /**
     * Absolute offset of the data of [entry] in the file. Reads the local file header, since
     * its extra field can differ from the central directory one.
     */
    suspend fun readDataOffset(reader: RangeReader, entry: ZipEntry): Long {
        if (entry.localHeaderOffset < 0 || entry.localHeaderOffset + LOCAL_HEADER_SIZE > reader.size) {
            throw ApkProbeException("Invalid local header offset")
        }
        val local = reader.readExactly(entry.localHeaderOffset, LOCAL_HEADER_SIZE)
        if (local.u32(0) != LOCAL_HEADER_SIGNATURE) {
            throw ApkProbeException("Invalid local file header")
        }
        return entry.localHeaderOffset + LOCAL_HEADER_SIZE + local.u16(26) + local.u16(28)
    }

    fun inflate(data: ByteArray, size: Int, entryName: String = "entry"): ByteArray {
        val inflater = Inflater(true)
        try {
            // Raw inflate may need one extra dummy byte at the end of input
            inflater.setInput(data.copyOf(data.size + 1))
            val output = ByteArray(size)
            var count = 0
            while (count < size) {
                val inflated = inflater.inflate(output, count, size - count)
                if (inflated == 0 &&
                    (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())
                ) {
                    break
                }
                count += inflated
            }
            if (count != size) throw ApkProbeException("Truncated $entryName")
            return output
        } catch (e: DataFormatException) {
            throw ApkProbeException("Corrupted $entryName", e)
        } finally {
            inflater.end()
        }
    }

    suspend fun RangeReader.readExactly(offset: Long, length: Int): ByteArray {
        val bytes = read(offset, length)
        if (bytes.size != length) {
            throw ApkProbeException("Expected $length bytes at $offset, got ${bytes.size}")
        }
        return bytes
    }

    fun ByteArray.regionEquals(offset: Int, other: ByteArray): Boolean {
        if (offset + other.size > size) return false
        for (i in other.indices) {
            if (this[offset + i] != other[i]) return false
        }
        return true
    }

    fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    fun ByteArray.u32(offset: Int): Long =
        (u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)) and MASK_32

    fun ByteArray.u64(offset: Int): Long = u32(offset) or (u32(offset + 4) shl 32)
}

/**
 * Minimal decoder for Android binary XML (AXML), only extracting what [ApkManifest] needs.
 */
internal object AxmlManifestParser {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103

    internal const val UTF8_FLAG = 0x100

    private const val TYPE_STRING = 0x03
    private const val TYPE_FIRST_INT = 0x10
    private const val TYPE_LAST_INT = 0x1f

    private const val ATTR_ICON = 0x01010002
    private const val ATTR_NAME = 0x01010003
    private const val ATTR_MIN_SDK_VERSION = 0x0101020c
    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_NAME = 0x0101021c
    private const val ATTR_TARGET_SDK_VERSION = 0x01010270
    private const val ATTR_ROUND_ICON = 0x0101052c
    private const val ATTR_VERSION_CODE_MAJOR = 0x01010576

    private const val NO_INDEX = -1

    private class Attribute(
        val nameIndex: Int,
        val rawValue: Int,
        val dataType: Int,
        val data: Int,
    )

    fun parse(bytes: ByteArray): ApkManifest {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 8 || buffer.u16(0) != RES_XML_TYPE) {
            throw ApkProbeException("Not a binary XML document")
        }
        var strings: List<String?> = emptyList()
        var resourceIds = IntArray(0)

        var packageName: String? = null
        var versionCode = 0L
        var versionCodeMajor = 0L
        var versionName: String? = null
        var minSdk = 0
        var targetSdk = 0
        var iconResId = 0
        var roundIconResId = 0
        val permissions = mutableListOf<String>()

        fun Attribute.matches(resourceId: Int, name: String): Boolean {
            val id = resourceIds.getOrElse(nameIndex) { 0 }
            return if (id != 0) id == resourceId else strings.getOrNull(nameIndex) == name
        }

        fun Attribute.stringValue(): String? = when {
            rawValue != NO_INDEX -> strings.getOrNull(rawValue)
            dataType == TYPE_STRING -> strings.getOrNull(data)
            dataType in TYPE_FIRST_INT..TYPE_LAST_INT -> data.toString()
            else -> null
        }

        fun Attribute.intValue(): Int? = if (dataType in TYPE_FIRST_INT..TYPE_LAST_INT) {
            data
        } else {
            stringValue()?.trim()?.toIntOrNull()
        }

        var depth = 0
        var position = buffer.u16(2)
        while (position + 8 <= bytes.size) {
            val type = buffer.u16(position)
            val headerSize = buffer.u16(position + 2)
            val chunkSize = buffer.getInt(position + 4)
            if (chunkSize < 8 || chunkSize > bytes.size - position) break
            when (type) {
                RES_STRING_POOL_TYPE -> strings = readStringPool(buffer, bytes, position)
                RES_XML_RESOURCE_MAP_TYPE -> {
                    val count = (chunkSize - headerSize) / 4
                    resourceIds = IntArray(count) { buffer.getInt(position + headerSize + it * 4) }
                }

                RES_XML_START_ELEMENT_TYPE -> {
                    depth++
                    val ext = position + headerSize
                    val elementName = strings.getOrNull(buffer.getInt(ext + 4))
                    val attributeStart = buffer.u16(ext + 8)
                    val attributeSize = buffer.u16(ext + 10)
                    val attributeCount = buffer.u16(ext + 12)
                    val attributes = (0 until attributeCount).map { index ->
                        val attribute = ext + attributeStart + index * attributeSize
                        Attribute(
                            nameIndex = buffer.getInt(attribute + 4),
                            rawValue = buffer.getInt(attribute + 8),
                            dataType = bytes[attribute + 15].toInt() and 0xFF,
                            data = buffer.getInt(attribute + 16),
                        )
                    }
                    if (depth == 1 && elementName == "manifest") {
                        for (attribute in attributes) {
                            if (strings.getOrNull(attribute.nameIndex) == "package") {
                                packageName = attribute.stringValue()
                            } else if (attribute.matches(ATTR_VERSION_CODE, "versionCode")) {
                                versionCode = (attribute.intValue() ?: 0).toLong() and 0xFFFFFFFFL
                            } else if (attribute.matches(ATTR_VERSION_CODE_MAJOR, "versionCodeMajor")) {
                                versionCodeMajor = (attribute.intValue() ?: 0).toLong() and 0xFFFFFFFFL
                            } else if (attribute.matches(ATTR_VERSION_NAME, "versionName")) {
                                versionName = attribute.stringValue()
                            }
                        }
                    } else if (depth == 2 && elementName == "uses-sdk") {
                        for (attribute in attributes) {
                            if (attribute.matches(ATTR_MIN_SDK_VERSION, "minSdkVersion")) {
                                minSdk = attribute.intValue() ?: 0
                            } else if (attribute.matches(ATTR_TARGET_SDK_VERSION, "targetSdkVersion")) {
                                targetSdk = attribute.intValue() ?: 0
                            }
                        }
                    } else if (depth == 2 &&
                        (elementName == "uses-permission" || elementName == "uses-permission-sdk-23")
                    ) {
                        val permission = attributes
                            .firstOrNull { it.matches(ATTR_NAME, "name") }
                            ?.stringValue()
                        if (!permission.isNullOrBlank()) permissions.add(permission)
                    } else if (depth == 2 && elementName == "application") {
                        for (attribute in attributes) {
                            if (attribute.matches(ATTR_ICON, "icon")) {
                                iconResId = attribute.data
                            } else if (attribute.matches(ATTR_ROUND_ICON, "roundIcon")) {
                                roundIconResId = attribute.data
                            }
                        }
                    }
                }

                RES_XML_END_ELEMENT_TYPE -> depth--
            }
            position += chunkSize
        }

        val name = packageName?.takeIf { it.isNotBlank() }
            ?: throw ApkProbeException("No package name in manifest")
        return ApkManifest(
            packageName = name,
            versionCode = (versionCodeMajor shl 32) or versionCode,
            versionName = versionName.orEmpty(),
            minSdkVersion = minSdk,
            targetSdkVersion = if (targetSdk > 0) targetSdk else minSdk,
            permissions = permissions.distinct(),
            iconResId = iconResId,
            roundIconResId = roundIconResId,
        )
    }

    internal fun readStringPool(buffer: ByteBuffer, bytes: ByteArray, chunk: Int): List<String?> {
        val headerSize = buffer.u16(chunk + 2)
        val stringCount = buffer.getInt(chunk + 8)
        val flags = buffer.getInt(chunk + 16)
        val stringsStart = buffer.getInt(chunk + 20)
        val utf8 = flags and UTF8_FLAG != 0
        if (stringCount < 0 || chunk + headerSize + stringCount.toLong() * 4 > bytes.size) {
            throw ApkProbeException("Invalid string pool")
        }
        return List(stringCount) { index ->
            try {
                val offset = buffer.getInt(chunk + headerSize + index * 4)
                val start = chunk + stringsStart + offset
                if (utf8) readUtf8(bytes, start) else readUtf16(buffer, bytes, start)
            } catch (_: IndexOutOfBoundsException) {
                null
            }
        }
    }

    /** Reads the string at [start] of [bytes], a window into the data of a string pool. */
    internal fun readPoolString(bytes: ByteArray, start: Int, utf8: Boolean): String =
        if (utf8) {
            readUtf8(bytes, start)
        } else {
            readUtf16(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), bytes, start)
        }

    private fun readUtf8(bytes: ByteArray, start: Int): String {
        var position = start
        // Length in characters, then length in bytes, each 1 or 2 bytes long
        position += if (bytes[position].toInt() and 0x80 != 0) 2 else 1
        val first = bytes[position].toInt() and 0xFF
        val length = if (first and 0x80 != 0) {
            position += 2
            ((first and 0x7F) shl 8) or (bytes[position - 1].toInt() and 0xFF)
        } else {
            position += 1
            first
        }
        if (position + length > bytes.size) throw IndexOutOfBoundsException()
        return String(bytes, position, length, Charsets.UTF_8)
    }

    private fun readUtf16(buffer: ByteBuffer, bytes: ByteArray, start: Int): String {
        var position = start
        val first = buffer.u16(position)
        val length = if (first and 0x8000 != 0) {
            position += 4
            ((first and 0x7FFF) shl 16) or buffer.u16(position - 2)
        } else {
            position += 2
            first
        }
        if (position + length * 2 > bytes.size) throw IndexOutOfBoundsException()
        return String(bytes, position, length * 2, Charsets.UTF_16LE)
    }

    private fun ByteBuffer.u16(offset: Int): Int = getShort(offset).toInt() and 0xFFFF
}
