package com.yodesla.omniverse.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Buffer
import okio.HashingSource

class Sha256Test {
    @Test
    fun digestOfAbcIsTheKnownVector() {
        val hex = Sha256.hex(Buffer().apply { writeUtf8("abc") })
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }

    @Test
    fun emptyInputDigest() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(Buffer()))
    }

    @Test
    fun matchesIgnoresCase() {
        val expected = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        val actual = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(Sha256.matches(expected, actual))
        assertTrue(Sha256.matches(actual, expected))
    }

    @Test
    fun mismatchedDigestDoesNotMatch() {
        val actual = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertFalse(Sha256.matches("0".repeat(64), actual))
    }

    @Test
    fun shortExpectedDigestNeverMatches() {
        assertFalse(Sha256.matches("abc", "abc"))
    }

    @Test
    fun hashingSourceRoundTrip() {
        val hashing = HashingSource.sha256(Buffer().apply { writeUtf8("abc") })
        hashing.use {
            val sink = Buffer()
            assertEquals(3L, it.read(sink, 8_192L))
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hashing.hash.hex())
    }
}
