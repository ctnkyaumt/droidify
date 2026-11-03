package com.looker.droidify.github

import com.looker.droidify.assets
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArscParserTest {

    private class CountingRangeReader(private val bytes: ByteArray) : RangeReader {
        val reads = mutableListOf<LongRange>()
        override val size: Long = bytes.size.toLong()

        override suspend fun read(offset: Long, length: Int): ByteArray {
            reads += offset until offset + length
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
    }

    private val apk: ByteArray
        get() = assets("github/tuner_158.apk")!!.readBytes()

    private suspend fun zipEntryBytes(zip: ByteArray, name: String): ByteArray {
        val reader = ByteArrayRangeReader(zip)
        val entry = assertNotNull(ApkManifestReader.findEntry(ApkManifestReader.readCentralDirectory(reader), name))
        return ApkManifestReader.readEntry(reader, entry, 1024L * 1024)
    }

    private fun le(size: Int, block: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(block).array()

    /** A simple string entry (16 bytes) pointing at string [stringIndex], with the given entry flags. */
    private fun stringEntry(stringIndex: Int, entryFlags: Int = 0): ByteArray = le(16) {
        putShort(8)
        putShort(entryFlags.toShort())
        putInt(0)
        putShort(8)
        put(0)
        put(0x03)
        putInt(stringIndex)
    }

    private fun typeChunk(
        typeFlags: Int,
        density: Int,
        entryCount: Int,
        offsets: ByteArray,
        entries: ByteArray,
    ): ByteArray {
        val entriesStart = 84 + offsets.size
        return le(entriesStart + entries.size) {
            putShort(0x0201)
            putShort(84)
            putInt(entriesStart + entries.size)
            put(4) // type id
            put(typeFlags.toByte())
            putShort(0)
            putInt(entryCount)
            putInt(entriesStart)
            putInt(64) // config size
            position(34) // config density
            putShort(density.toShort())
            position(84)
            put(offsets)
            put(entries)
        }
    }

    /** Package 0x7f with several type 4 chunks, strings res/a.png, res/b.png, res/c.png. */
    private fun syntheticArsc(): ByteArray {
        val names = listOf("res/a.png", "res/b.png", "res/c.png")
        val stringSize = 12 // 2 length bytes, 9 characters, terminator
        val pool = le(28 + names.size * 4 + names.size * stringSize) {
            putShort(0x0001)
            putShort(28)
            putInt(28 + names.size * 4 + names.size * stringSize)
            putInt(names.size)
            putInt(0)
            putInt(0x100) // UTF-8
            putInt(28 + names.size * 4)
            putInt(0)
            names.indices.forEach { putInt(it * stringSize) }
            names.forEach {
                put(it.length.toByte())
                put(it.length.toByte())
                put(it.toByteArray())
                put(0)
            }
        }
        val none16 = 0xFFFF.toShort()
        val chunks = listOf(
            // Sparse, 160 dpi: entry 3 -> a, entry 7 -> b
            typeChunk(
                typeFlags = 0x01,
                density = 160,
                entryCount = 2,
                offsets = le(8) {
                    putShort(3)
                    putShort(0)
                    putShort(7)
                    putShort(4) // 16 / 4
                },
                entries = stringEntry(0) + stringEntry(1),
            ),
            // Offset16, 320 dpi: entry 7 -> c
            typeChunk(
                typeFlags = 0x02,
                density = 320,
                entryCount = 8,
                offsets = le(16) {
                    repeat(7) { putShort(none16) }
                    putShort(0)
                },
                entries = stringEntry(2),
            ),
            // Unknown flags, 480 dpi: must be skipped
            typeChunk(
                typeFlags = 0x04,
                density = 480,
                entryCount = 8,
                offsets = le(16) {
                    repeat(7) { putShort(none16) }
                    putShort(0)
                },
                entries = stringEntry(0),
            ),
            // Dense, 240 dpi, compact entry flag: must be skipped
            typeChunk(
                typeFlags = 0x00,
                density = 240,
                entryCount = 8,
                offsets = le(32) {
                    repeat(7) { putInt(-1) }
                    putInt(0)
                },
                entries = stringEntry(0, entryFlags = 0x0008),
            ),
        )
        val packageSize = 288 + chunks.sumOf { it.size }
        val pkg = le(288) {
            putShort(0x0200)
            putShort(288)
            putInt(packageSize)
            putInt(0x7f)
        } + chunks.reduce { a, b -> a + b }
        val total = 12 + pool.size + pkg.size
        val header = le(12) {
            putShort(0x0002)
            putShort(12)
            putInt(total)
            putInt(1)
        }
        return header + pool + pkg
    }

    @Test
    fun `resolves sparse and offset16 type chunks and skips unknown layouts`() = runTest {
        val parser = ArscParser.create(ByteArrayRangeReader(syntheticArsc()))

        assertEquals(
            listOf(ArscParser.Candidate(160, "res/b.png"), ArscParser.Candidate(320, "res/c.png")),
            parser.resolveResId(0x7f040007),
        )
        // Only present in the sparse chunk
        assertEquals(
            listOf(ArscParser.Candidate(160, "res/a.png")),
            parser.resolveResId(0x7f040003),
        )
        // In no chunk
        assertEquals(emptyList(), parser.resolveResId(0x7f040005))
    }

    @Test
    fun `resolves all densities of the launcher icon from a byte array`() = runTest {
        val arsc = zipEntryBytes(apk, "resources.arsc")
        val parser = ArscParser.create(ByteArrayRangeReader(arsc))

        val candidates = parser.resolveResId(0x7f040018)

        assertEquals(setOf(160, 240, 320, 480, 640), candidates.map { it.density }.toSet())
        assertEquals("res/5p.png", candidates.single { it.density == 640 }.path)
    }

    @Test
    fun `block cached reader gives the same candidates as the byte array`() = runTest {
        val arsc = zipEntryBytes(apk, "resources.arsc")
        val expected = ArscParser.create(ByteArrayRangeReader(arsc)).resolveResId(0x7f040018)

        val blocks = BlockCachedRangeReader(
            reader = ByteArrayRangeReader(arsc),
            baseOffset = 0,
            size = arsc.size.toLong(),
            maxFetchedBytes = arsc.size.toLong(),
            blockSize = 1000,
        )
        val actual = ArscParser.create(blocks).resolveResId(0x7f040018)

        assertEquals(expected, actual)
    }

    @Test
    fun `block cached reader gives up after the fetch budget`() = runTest {
        val data = ByteArray(10_000) { it.toByte() }
        val blocks = BlockCachedRangeReader(
            reader = ByteArrayRangeReader(data),
            baseOffset = 0,
            size = data.size.toLong(),
            maxFetchedBytes = 2_000,
            blockSize = 1_000,
        )

        // Spans blocks 0 and 1: exactly the budget
        assertEquals(data.copyOfRange(900, 1_100).toList(), blocks.read(900, 200).toList())
        // Cached blocks are free
        assertEquals(data.copyOfRange(0, 10).toList(), blocks.read(0, 10).toList())
        assertEquals(2_000L, blocks.fetchedBytes)

        assertFailsWith<FetchBudgetExceededException> { blocks.read(5_000, 10) }
    }

    @Test
    fun `reads only a fraction of a stored resources arsc`() = runTest {
        val reader = CountingRangeReader(apk)
        val cd = ApkManifestReader.readCentralDirectory(reader)
        val entry = assertNotNull(ApkManifestReader.findEntry(cd, "resources.arsc"))
        assertEquals(ApkManifestReader.METHOD_STORED, entry.method)
        assertEquals(64_676L, entry.uncompressedSize)
        val arscStart = ApkManifestReader.readDataOffset(reader, entry)
        val arscEnd = arscStart + entry.uncompressedSize
        reader.reads.clear()

        val icon = ApkIconResolver.resolveIcon(
            reader = reader,
            iconResId = 0x7f040018,
            arscBlockSize = 2048,
        )

        assertEquals("res/5p.png", icon?.path)
        // Reads fully inside the table, the central directory tail and the icon lie outside of it
        val arscBytesRead = reader.reads
            .filter { it.first >= arscStart && it.last < arscEnd }
            .sumOf { it.last - it.first + 1 }
        // Derived once with a script replaying the walk on this table: the table and string pool
        // headers, the 20 chunk headers of the package, the 5 densities of the icon entry and
        // their strings touch 10 distinct 2 KB blocks = 20,480 bytes. The ceiling leaves some
        // slack and is still about half of the 64,676 bytes of the table
        assertTrue(arscBytesRead in 1L until 32_000L, "Read $arscBytesRead bytes of resources.arsc")
    }

    @Test
    fun `resolves the icon of an apk with deflated resources arsc`() = runTest {
        val source = apk
        val arsc = zipEntryBytes(source, "resources.arsc")
        val png = zipEntryBytes(source, "res/5p.png")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("resources.arsc"))
            zip.write(arsc)
            zip.closeEntry()
            val stored = ZipEntry("res/5p.png").apply {
                method = ZipEntry.STORED
                size = png.size.toLong()
                compressedSize = png.size.toLong()
                crc = CRC32().apply { update(png) }.value
            }
            zip.putNextEntry(stored)
            zip.write(png)
            zip.closeEntry()
        }
        val reader = ByteArrayRangeReader(out.toByteArray())
        val cd = ApkManifestReader.readCentralDirectory(reader)
        assertEquals(ApkManifestReader.METHOD_DEFLATED, ApkManifestReader.findEntry(cd, "resources.arsc")?.method)

        val icon = ApkIconResolver.resolveIcon(reader, 0x7f040018)

        assertEquals("res/5p.png", icon?.path)
        assertEquals(png.size, icon?.bytes?.size)
    }
}
