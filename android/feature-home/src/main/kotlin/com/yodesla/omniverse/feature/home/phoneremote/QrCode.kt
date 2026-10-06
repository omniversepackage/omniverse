package com.yodesla.omniverse.feature.home.phoneremote

import kotlin.math.abs

class QrMatrix(val size: Int, val modules: Array<BooleanArray>) {
    operator fun get(r: Int, c: Int): Boolean = modules[r][c]
}

/**
 * Dependency-free QR Code encoder (byte mode, error level M, versions 1-10) so Settings can show a
 * scannable pairing URL without a third-party library. Follows ISO/IEC 18004: byte-mode encoding,
 * Reed-Solomon over GF(256), function patterns, zigzag placement, mask choice by the penalty rules,
 * BCH format/version info.
 */
object QrCode {
    internal val ECC_M = arrayOf(
        intArrayOf(10, 1, 16, 0, 0), intArrayOf(16, 1, 28, 0, 0), intArrayOf(26, 1, 44, 0, 0),
        intArrayOf(18, 2, 32, 0, 0), intArrayOf(24, 2, 43, 0, 0), intArrayOf(16, 4, 27, 0, 0),
        intArrayOf(16, 4, 31, 0, 0), intArrayOf(22, 2, 38, 2, 39), intArrayOf(22, 3, 36, 2, 37),
        intArrayOf(26, 4, 43, 1, 44),
    )
    internal val ALIGN = arrayOf(
        IntArray(0), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
        intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
    )
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) { EXP[i] = x; LOG[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
    internal fun capacity(v: Int): Int { val t = ECC_M[v - 1]; return t[1] * t[2] + t[3] * t[4] }
    internal fun ccBits(v: Int): Int = if (v <= 9) 8 else 16

    private fun pickVersion(byteLen: Int): Int {
        for (v in 1..10) {
            val cw = (4 + ccBits(v) + byteLen * 8 + 7) / 8
            if (cw <= capacity(v)) return v
        }
        throw IllegalArgumentException("Text too long for QR v1-10")
    }

    fun encode(text: String): QrMatrix {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val version = pickVersion(bytes.size)
        val data = encodeData(bytes, version, capacity(version))
        val codewords = interleave(makeBlocks(data, version))
        val size = 17 + 4 * version
        val reserved = reservedMask(version)
        val base = Array(size) { BooleanArray(size) }
        placeFunctionPatterns(base, version)
        placeData(base, reserved, codewords)
        var best: Array<BooleanArray>? = null
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            val m = Array(size) { base[it].copyOf() }
            applyMask(m, reserved, mask)
            writeFormatInfo(m, mask)
            if (version >= 7) writeVersionInfo(m, version)
            val p = penalty(m)
            if (p < bestPenalty) { bestPenalty = p; best = m }
        }
        return QrMatrix(size, best!!)
    }

    private fun encodeData(bytes: ByteArray, version: Int, cap: Int): ByteArray {
        val bits = ArrayList<Int>()
        bits += listOf(0, 1, 0, 0)
        val cc = ccBits(version)
        for (i in cc - 1 downTo 0) bits += (bytes.size shr i) and 1
        for (b in bytes) for (i in 7 downTo 0) bits += (b.toInt() shr i) and 1
        var term = 4
        while (term > 0 && bits.size + term > cap * 8) term--
        repeat(term) { bits += 0 }
        while (bits.size % 8 != 0) bits += 0
        val cw = ArrayList<Byte>()
        for (i in bits.indices step 8) { var v = 0; for (j in 0 until 8) v = (v shl 1) or bits[i + j]; cw.add(v.toByte()) }
        var pad = 0
        while (cw.size < cap) { cw.add(if (pad % 2 == 0) 0xEC.toByte() else 0x11.toByte()); pad++ }
        return cw.toByteArray()
    }

    private fun generatorPoly(n: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until n) {
            val f = EXP[i]
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) { next[j] = next[j] xor poly[j]; next[j + 1] = next[j + 1] xor gfMul(poly[j], f) }
            poly = next
        }
        return poly
    }

    private fun remainder(data: ByteArray, gen: IntArray): ByteArray {
        val n = gen.size - 1
        val res = IntArray(n)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor res[0]
            for (i in 0 until n - 1) res[i] = res[i + 1]
            res[n - 1] = 0
            for (i in 0 until n) res[i] = res[i] xor gfMul(gen[i + 1], factor)
        }
        return ByteArray(n) { res[it].toByte() }
    }

    private fun makeBlocks(data: ByteArray, version: Int): List<Pair<ByteArray, ByteArray>> {
        val t = ECC_M[version - 1]
        val gen = generatorPoly(t[0])
        val blocks = ArrayList<Pair<ByteArray, ByteArray>>()
        var pos = 0
        repeat(t[1]) { val d = data.copyOfRange(pos, pos + t[2]); pos += t[2]; blocks.add(d to remainder(d, gen)) }
        repeat(t[3]) { val d = data.copyOfRange(pos, pos + t[4]); pos += t[4]; blocks.add(d to remainder(d, gen)) }
        return blocks
    }

    private fun interleave(blocks: List<Pair<ByteArray, ByteArray>>): ByteArray {
        val out = ArrayList<Byte>()
        val maxData = blocks.maxOf { it.first.size }
        for (i in 0 until maxData) for (b in blocks) if (i < b.first.size) out.add(b.first[i])
        val maxEcc = blocks.maxOf { it.second.size }
        for (i in 0 until maxEcc) for (b in blocks) if (i < b.second.size) out.add(b.second[i])
        return out.toByteArray()
    }

    internal fun reservedMask(version: Int): Array<BooleanArray> {
        val size = 17 + 4 * version
        val r = Array(size) { BooleanArray(size) }
        fun mark(r0: Int, c0: Int, r1: Int, c1: Int) {
            for (i in r0..r1) for (j in c0..c1) if (i in 0 until size && j in 0 until size) r[i][j] = true
        }
        mark(0, 0, 7, 7); mark(0, size - 8, 7, size - 1); mark(size - 8, 0, size - 1, 7)
        for (i in 8 until size - 8) { r[6][i] = true; r[i][6] = true }
        val centers = ALIGN[version - 1]
        for (rr in centers) for (cc in centers) {
            if ((rr <= 8 && cc <= 8) || (rr <= 8 && cc >= size - 8) || (rr >= size - 8 && cc <= 8)) continue
            mark(rr - 2, cc - 2, rr + 2, cc + 2)
        }
        r[size - 8][8] = true
        for ((rr, cc) in formatPositions(size)) r[rr][cc] = true
        if (version >= 7) for (i in 0..17) { r[i / 3][size - 11 + i % 3] = true; r[size - 11 + i % 3][i / 3] = true }
        return r
    }

    private fun placeFunctionPatterns(m: Array<BooleanArray>, version: Int) {
        val size = m.size
        fun finder(top: Int, left: Int) {
            for (r in 0..6) for (c in 0..6) {
                val dark = (r == 0 || r == 6 || c == 0 || c == 6) || (r in 2..4 && c in 2..4)
                m[top + r][left + c] = dark
            }
        }
        finder(0, 0); finder(0, size - 7); finder(size - 7, 0)
        for (i in 8 until size - 8) { val dark = i % 2 == 0; m[6][i] = dark; m[i][6] = dark }
        val centers = ALIGN[version - 1]
        for (rr in centers) for (cc in centers) {
            if ((rr <= 8 && cc <= 8) || (rr <= 8 && cc >= size - 7) || (rr >= size - 7 && cc <= 8)) continue
            for (dr in -2..2) for (dc in -2..2) m[rr + dr][cc + dc] = (maxOf(abs(dr), abs(dc)) != 1)
        }
        m[size - 8][8] = true
    }

    private fun placeData(m: Array<BooleanArray>, reserved: Array<BooleanArray>, codewords: ByteArray) {
        val size = m.size
        val bits = ArrayList<Boolean>()
        for (cw in codewords) for (i in 7 downTo 0) bits += ((cw.toInt() shr i) and 1) == 1
        var idx = 0
        var col = size - 1
        var up = true
        while (col > 0) {
            if (col == 6) col--
            for (step in 0 until size) {
                val row = if (up) size - 1 - step else step
                for (c in intArrayOf(col, col - 1)) {
                    if (c < 0) continue
                    if (!reserved[row][c] && idx < bits.size) { m[row][c] = bits[idx]; idx++ }
                }
            }
            up = !up
            col -= 2
        }
    }

    private fun applyMask(m: Array<BooleanArray>, reserved: Array<BooleanArray>, mask: Int) {
        val size = m.size
        for (r in 0 until size) for (c in 0 until size) {
            if (reserved[r][c]) continue
            val flip = when (mask) {
                0 -> (r + c) % 2 == 0
                1 -> r % 2 == 0
                2 -> c % 3 == 0
                3 -> (r + c) % 3 == 0
                4 -> (r / 2 + c / 3) % 2 == 0
                5 -> (r * c) % 2 + (r * c) % 3 == 0
                6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
                else -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
            }
            if (flip) m[r][c] = !m[r][c]
        }
    }

    internal fun formatPositions(size: Int): List<Pair<Int, Int>> = listOf(
        8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8, 7 to 8,
        5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8,
        size - 1 to 8, size - 2 to 8, size - 3 to 8, size - 4 to 8, size - 5 to 8, size - 6 to 8, size - 7 to 8,
        8 to size - 8, 8 to size - 7, 8 to size - 6, 8 to size - 5, 8 to size - 4, 8 to size - 3, 8 to size - 2, 8 to size - 1,
    )

    private fun bch(data: Int, dataBits: Int, gen: Int, genDeg: Int): Int {
        var d = data shl genDeg
        for (dd in dataBits - 1 downTo 0) if ((d shr (dd + genDeg)) and 1 == 1) d = d xor (gen shl dd)
        return (data shl genDeg) or d
    }

    private fun writeFormatInfo(m: Array<BooleanArray>, mask: Int) {
        val value = bch(mask, 5, 0x537, 10) xor 0x5412
        val pos = formatPositions(m.size)
        for (i in 0 until 15) { val (r, c) = pos[i]; m[r][c] = ((value shr i) and 1) == 1 }
    }

    private fun writeVersionInfo(m: Array<BooleanArray>, version: Int) {
        val size = m.size
        val info = bch(version, 6, 0x1F25, 12)
        for (i in 0..17) {
            val bit = ((info shr i) and 1) == 1
            m[i / 3][size - 11 + i % 3] = bit
            m[size - 11 + i % 3][i / 3] = bit
        }
    }

    private fun penalty(m: Array<BooleanArray>): Int {
        val n = m.size
        var p = 0
        for (r in 0 until n) p += runPenalty(m[r])
        for (c in 0 until n) p += runPenalty(BooleanArray(n) { m[it][c] })
        for (r in 0 until n - 1) for (c in 0 until n - 1) {
            val v = m[r][c]
            if (m[r][c + 1] == v && m[r + 1][c] == v && m[r + 1][c + 1] == v) p += 3
        }
        for (r in 0 until n) p += finderPenalty(BooleanArray(n) { m[r][it] })
        for (c in 0 until n) p += finderPenalty(BooleanArray(n) { m[it][c] })
        var dark = 0
        for (r in 0 until n) for (c in 0 until n) if (m[r][c]) dark++
        val percent = dark * 100 / (n * n)
        p += 10 * (abs(percent - 50) / 5)
        return p
    }

    private fun runPenalty(line: BooleanArray): Int {
        var p = 0; var run = 1
        for (i in 1 until line.size) {
            if (line[i] == line[i - 1]) run++ else { if (run >= 5) p += 3 + (run - 5); run = 1 }
        }
        if (run >= 5) p += 3 + (run - 5)
        return p
    }

    private fun finderPenalty(line: BooleanArray): Int {
        val a = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val b = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
        var p = 0
        for (i in 0..line.size - 11) {
            var ma = true; var mb = true
            for (j in 0 until 11) { if (line[i + j] != a[j]) ma = false; if (line[i + j] != b[j]) mb = false }
            if (ma) p += 40
            if (mb) p += 40
        }
        return p
    }
}
