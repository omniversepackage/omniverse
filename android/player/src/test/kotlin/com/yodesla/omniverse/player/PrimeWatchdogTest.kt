package com.yodesla.omniverse.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 111 L1: a primed spare engine must release its connection when it is never adopted, so a
 * provider does not see two concurrent streams for the whole time the player is open.
 */
class PrimeWatchdogTest {
    @Test fun unadoptedIdlePrimeIsReleased() {
        assertTrue(shouldReleasePrime(wantPlaying = false, isPlaying = false, primed = true))
    }

    @Test fun adoptedOrPlayingPrimeIsKept() {
        assertFalse(shouldReleasePrime(wantPlaying = true, isPlaying = false, primed = true))
        assertFalse(shouldReleasePrime(wantPlaying = false, isPlaying = true, primed = true))
    }

    @Test fun nothingPrimedIsNothingToRelease() {
        assertFalse(shouldReleasePrime(wantPlaying = false, isPlaying = false, primed = false))
    }
}
