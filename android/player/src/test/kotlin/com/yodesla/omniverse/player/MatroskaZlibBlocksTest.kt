package com.yodesla.omniverse.player

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DeflaterOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MatroskaZlibBlocksTest {
    private fun compressed(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        DeflaterOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    @Test fun pgsFramePreservesTrackTimestampAndFlags() {
        val pgs = byteArrayOf(0x16, 0, 4, 1, 2, 3, 4)
        val header = byteArrayOf(0x83.toByte(), 0xff.toByte(), 0xf0.toByte(), 0x80.toByte())
        assertContentEquals(header + pgs, MatroskaZlibBlocks.expand(header + compressed(pgs)))
    }

    @Test fun allLacingModesPreserveEveryFrame() {
        val frames = listOf(ByteArray(350) { 1 }, ByteArray(12) { 2 }, ByteArray(351) { 3 })
        val zipped = frames.map(::compressed)
        assertEquals(zipped[0].size, zipped[2].size) // Equal-sized compressed frames, differing outputs.
        // Two equal compressed lengths exercise fixed lacing with unequal inflated lengths.
        val fixed = byteArrayOf(0x83.toByte(), 0, 1, 0x84.toByte(), 1) + zipped[0] + zipped[2]
        val xiph = byteArrayOf(0x83.toByte(), 0, 1, 0x82.toByte(), 2, zipped[0].size.toByte(), zipped[1].size.toByte()) +
            zipped.reduce(ByteArray::plus)
        val delta = zipped[1].size - zipped[0].size
        val ebml = byteArrayOf(0x83.toByte(), 0, 1, 0x86.toByte(), 2,
            (0x80 or zipped[0].size).toByte(), (0x80 or (delta + 63)).toByte()) + zipped.reduce(ByteArray::plus)
        fun expected(values: List<ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0x83.toByte(), 0, 1, 0x82.toByte(), (values.size - 1).toByte()))
            values.dropLast(1).forEach { frame ->
                repeat(frame.size / 255) { out.write(255) }
                out.write(frame.size % 255)
            }
            values.forEach(out::write)
            return out.toByteArray()
        }
        assertContentEquals(expected(listOf(frames[0], frames[2])), MatroskaZlibBlocks.expand(fixed))
        assertContentEquals(expected(frames), MatroskaZlibBlocks.expand(xiph))
        assertContentEquals(expected(frames), MatroskaZlibBlocks.expand(ebml))
    }

    @Test fun malformedFramesFailWithoutHanging() {
        val header = byteArrayOf(0x83.toByte(), 0, 0, 0)
        val zipped = compressed(ByteArray(128) { 42 })
        assertFailsWith<IOException> { MatroskaZlibBlocks.expand(header + zipped.dropLast(1)) }
        assertFailsWith<IOException> { MatroskaZlibBlocks.expand(header + zipped + byteArrayOf(0)) }
        assertFailsWith<IOException> { MatroskaZlibBlocks.expand(header + byteArrayOf(1, 2, 3)) }
        assertFailsWith<IOException> { MatroskaZlibBlocks.expand(byteArrayOf(0x83.toByte())) }
        assertFailsWith<IOException> { MatroskaZlibBlocks.expand(byteArrayOf(0x83.toByte(), 0, 0, 0x82.toByte(), 1, 127)) }
    }

    @Test fun expansionHasAHardMemoryLimit() {
        val header = byteArrayOf(0x83.toByte(), 0, 0, 0)
        val bomb = compressed(ByteArray(MatroskaZlibBlocks.MAX_BLOCK_BYTES + 1))
        assertFailsWith<IOException> { MatroskaZlibBlocks.expand(header + bomb) }
    }

    @Test fun multibyteTrackNumbersAreReadWithoutChangingBytes() {
        val header = byteArrayOf(0x40, 0x81.toByte(), 0, 0, 0)
        assertEquals(129L, MatroskaZlibBlocks.trackNumber(header))
        assertContentEquals(header + byteArrayOf(7), MatroskaZlibBlocks.expand(header + compressed(byteArrayOf(7))))
    }
}
