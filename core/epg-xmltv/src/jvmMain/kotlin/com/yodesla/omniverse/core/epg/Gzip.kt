package com.yodesla.omniverse.core.epg

import okio.BufferedSource
import okio.GzipSource
import okio.buffer

actual fun maybeGunzip(source: BufferedSource): BufferedSource {
    // Empty/1-byte bodies (providers do send those) must pass through, not throw EOFException.
    if (!source.request(2)) return source
    val peek = source.peek()
    val b1 = peek.readByte()
    val b2 = peek.readByte()
    peek.close()
    return if (b1 == GZIP_MAGIC_1 && b2 == GZIP_MAGIC_2) GzipSource(source).buffer() else source
}

private val GZIP_MAGIC_1: Byte = 0x1f
private val GZIP_MAGIC_2: Byte = 0x8b.toByte()
