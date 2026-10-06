package com.yodesla.omniverse.feature.home.phoneremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trips the dependency-free encoder through an independent decoder written here, so the test
 * proves the encoder's data placement, masking and format info are all correct — not just that it
 * produced a matrix of the right size.
 */
class QrCodeTest {
    private fun maskFlip(mask: Int, r: Int, c: Int): Boolean = when (mask) {
        0 -> (r + c) % 2 == 0
        1 -> r % 2 == 0
        2 -> c % 3 == 0
        3 -> (r + c) % 3 == 0
        4 -> (r / 2 + c / 3) % 2 == 0
        5 -> (r * c) % 2 + (r * c) % 3 == 0
        6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
        else -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
    }

    private fun decode(m: QrMatrix): String {
        val size = m.size
        val version = (size - 17) / 4
        // Format info -> mask (error level M = 00 in the top bits).
        var fmt = 0
        QrCode.formatPositions(size).forEachIndexed { i, (r, c) -> if (m[r, c]) fmt = fmt or (1 shl i) }
        val data = (fmt xor 0x5412) ushr 10
        val mask = data and 0b111
        val reserved = QrCode.reservedMask(version)
        // Undo the mask on data modules.
        val g = Array(size) { r -> BooleanArray(size) { c -> if (reserved[r][c]) m[r, c] else m[r, c] xor maskFlip(mask, r, c) } }
        // Zigzag-read codewords.
        val bits = ArrayList<Boolean>()
        var col = size - 1; var up = true
        while (col > 0) {
            if (col == 6) col--
            for (step in 0 until size) {
                val row = if (up) size - 1 - step else step
                for (c in intArrayOf(col, col - 1)) {
                    if (c < 0) continue
                    if (!reserved[row][c]) bits.add(g[row][c])
                }
            }
            up = !up; col -= 2
        }
        val cw = ArrayList<Int>()
        var i = 0
        while (i + 8 <= bits.size) { var v = 0; for (j in 0 until 8) v = (v shl 1) or (if (bits[i + j]) 1 else 0); cw.add(v); i += 8 }
        // De-interleave data codewords.
        val t = QrCode.ECC_M[version - 1]
        val blocks = ArrayList<MutableList<Int>>()
        repeat(t[1]) { blocks.add(mutableListOf()) }
        repeat(t[3]) { blocks.add(mutableListOf()) }
        val dataLen = t[1] * t[2] + t[3] * t[4]
        val maxData = maxOf(t[2], if (t[3] > 0) t[4] else 0)
        var idx = 0
        for (k in 0 until maxData) for (b in blocks.indices) {
            val len = if (b < t[1]) t[2] else t[4]
            if (k < len && idx < cw.size) { blocks[b].add(cw[idx]); idx++ }
        }
        val dataCw = ArrayList<Int>()
        for (b in blocks) dataCw.addAll(b)
        assertEquals(dataLen, dataCw.size)
        // Parse byte-mode segment.
        val stream = ArrayList<Int>()
        for (v in dataCw) for (s in 7 downTo 0) stream.add((v shr s) and 1)
        var p = 0
        var mode = 0; repeat(4) { mode = (mode shl 1) or stream[p++] }
        assertEquals(0b0100, mode)
        var len = 0; repeat(QrCode.ccBits(version)) { len = (len shl 1) or stream[p++] }
        val out = ByteArray(len)
        for (n in 0 until len) { var v = 0; repeat(8) { v = (v shl 1) or stream[p++] }; out[n] = v.toByte() }
        return String(out, Charsets.UTF_8)
    }

    @Test fun roundTripsPairingUrl() {
        val url = "http://192.168.1.42:8124/?k=9f3ac10b7e2d"
        assertEquals(url, decode(QrCode.encode(url)))
    }

    @Test fun roundTripsAcrossVersions() {
        for (text in listOf("a", "http://10.0.0.5:8124/?k=abcdef012345", "x".repeat(80), "Omniverse remote 123456")) {
            assertEquals(text, decode(QrCode.encode(text)))
        }
    }

    @Test fun finderPatternsAndDarkModule() {
        val m = QrCode.encode("hello")
        val n = m.size
        assertTrue(m[0, 0] && m[6, 0] && m[0, 6] && m[6, 6])
        assertTrue(m[0, n - 1] && m[n - 1, 0])
        assertTrue(m[n - 8, 8])
    }
}
