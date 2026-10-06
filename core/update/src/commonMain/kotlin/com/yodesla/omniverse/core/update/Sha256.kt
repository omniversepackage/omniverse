package com.yodesla.omniverse.core.update

import okio.Buffer
import okio.HashingSource
import okio.Source

/** SHA-256 helpers for verifying the downloaded APK against the manifest. */
object Sha256 {
    /** Consumes [source] and returns the digest as a 64-char lowercase hex string. */
    fun hex(source: Source): String {
        val hashing = HashingSource.sha256(source)
        hashing.use {
            val sink = Buffer()
            while (true) {
                if (hashing.read(sink, 8_192L) == -1L) break
            }
            return hashing.hash.hex()
        }
    }

    /** Case-insensitive comparison; [expectedHex] must be a full 64-char digest. */
    fun matches(expectedHex: String, actualHex: String): Boolean =
        expectedHex.length == 64 && expectedHex.equals(actualHex, ignoreCase = true)
}
