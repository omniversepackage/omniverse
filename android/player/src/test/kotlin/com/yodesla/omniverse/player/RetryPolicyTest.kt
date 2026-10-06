package com.yodesla.omniverse.player

import kotlin.test.Test
import kotlin.test.assertEquals

class RetryPolicyTest {
    private var now = 0L
    private val policy = RetryPolicy { now }

    // ---- classification table ----------------------------------------------------------------

    @Test fun nonHttpFailuresMapToTheirBuckets() {
        assertEquals(StreamErrorKind.TRANSIENT_NETWORK, policy.classify(StreamFailure.Network))
        assertEquals(StreamErrorKind.BEHIND_LIVE_WINDOW, policy.classify(StreamFailure.BehindLiveWindow))
        assertEquals(StreamErrorKind.DECODER, policy.classify(StreamFailure.Decoder))
        assertEquals(StreamErrorKind.OTHER, policy.classify(StreamFailure.Other))
    }

    @Test fun authAndLimitStatusesAreOneBucket() {
        for (code in listOf(401, 403, 429, 509)) {
            assertEquals(StreamErrorKind.AUTH_OR_LIMIT, policy.classify(StreamFailure.HttpStatus(code)), "HTTP $code")
        }
    }

    @Test fun goneIsGone() {
        for (code in listOf(404, 410)) {
            assertEquals(StreamErrorKind.NOT_FOUND, policy.classify(StreamFailure.HttpStatus(code)), "HTTP $code")
        }
    }

    @Test fun serverHiccupsCountAsTransient() {
        for (code in listOf(408, 500, 502, 503, 504)) {
            assertEquals(StreamErrorKind.TRANSIENT_NETWORK, policy.classify(StreamFailure.HttpStatus(code)), "HTTP $code")
        }
    }

    // ---- transient backoff ----------------------------------------------------------------------

    @Test fun transientBackoffIsOneTwoFourEightFifteenThenGiveUp() {
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        expected.forEachIndexed { i, delay ->
            assertEquals(
                RetryDecision.RetryAfter(delay, i + 1),
                policy.onStreamFailure(StreamFailure.Network),
                "try ${i + 1}",
            )
            now += delay // the retry opens the stream and the provider drops it again at once
        }
        assertEquals(RetryDecision.GiveUp(GiveUpReason.EXHAUSTED), policy.onStreamFailure(StreamFailure.Network))
        // and it stays given up — no silent infinite loop
        assertEquals(RetryDecision.GiveUp(GiveUpReason.EXHAUSTED), policy.onStreamFailure(StreamFailure.Network))
    }

    @Test fun sixtySecondsOfGoodPlaybackRestartsTheBackoff() {
        assertEquals(RetryDecision.RetryAfter(1_000L, 1), policy.onStreamFailure(StreamFailure.Network))
        now = 1_000
        policy.onHealthyPlayback() // the retry landed, the stream is playing
        now = 61_000 // 60 s of good playback later
        assertEquals(RetryDecision.RetryAfter(1_000L, 1), policy.onStreamFailure(StreamFailure.Network))
    }

    @Test fun justUnderSixtySecondsKeepsTheSequenceGoing() {
        assertEquals(RetryDecision.RetryAfter(1_000L, 1), policy.onStreamFailure(StreamFailure.Network))
        now = 1_000
        policy.onHealthyPlayback()
        now = 60_999 // 59.999 s of playback — not enough to reset
        assertEquals(RetryDecision.RetryAfter(2_000L, 2), policy.onStreamFailure(StreamFailure.Network))
    }

    @Test fun repeatedHealthyTicksDoNotExtendTheStreakStart() {
        policy.onStreamFailure(StreamFailure.Network) // try 1
        now = 1_000
        policy.onHealthyPlayback() // streak starts here
        now = 30_000
        policy.onHealthyPlayback() // periodic call must not move the streak start
        now = 61_000 // 60 s measured from the real start (1 s)
        assertEquals(RetryDecision.RetryAfter(1_000L, 1), policy.onStreamFailure(StreamFailure.Network))
    }

    // ---- behind live window ---------------------------------------------------------------------

    @Test fun behindLiveWindowRejoinsTheLiveEdgeWithoutBurningTries() {
        repeat(10) {
            assertEquals(
                RetryDecision.RetryAfter(0L, 1, seekToLive = true),
                policy.onStreamFailure(StreamFailure.BehindLiveWindow),
            )
        }
        // A real drop after ten DVR slips still gets the full first retry.
        assertEquals(RetryDecision.RetryAfter(1_000L, 1), policy.onStreamFailure(StreamFailure.Network))
    }

    // ---- auth / max-connections -----------------------------------------------------------------

    @Test fun providerRefusalGetsOneRetryAfterThreeSecondsThenGiveUp() {
        assertEquals(RetryDecision.RetryAfter(3_000L, 1), policy.onStreamFailure(StreamFailure.HttpStatus(403)))
        now = 3_000
        assertEquals(RetryDecision.GiveUp(GiveUpReason.PROVIDER_REFUSED), policy.onStreamFailure(StreamFailure.HttpStatus(509)))
    }

    @Test fun providerRefusalCounterResetsAfterGoodPlayback() {
        assertEquals(RetryDecision.RetryAfter(3_000L, 1), policy.onStreamFailure(StreamFailure.HttpStatus(401)))
        now = 3_000
        policy.onHealthyPlayback() // the slot freed up and playback resumed
        now = 64_000 // 61 s of good playback later
        assertEquals(RetryDecision.RetryAfter(3_000L, 1), policy.onStreamFailure(StreamFailure.HttpStatus(403)))
    }

    // ---- instant give-ups -----------------------------------------------------------------------

    @Test fun notFoundAndDecoderGiveUpAtOnce() {
        assertEquals(RetryDecision.GiveUp(GiveUpReason.NOT_FOUND), policy.onStreamFailure(StreamFailure.HttpStatus(404)))
        assertEquals(RetryDecision.GiveUp(GiveUpReason.NOT_FOUND), policy.onStreamFailure(StreamFailure.HttpStatus(410)))
        assertEquals(RetryDecision.GiveUp(GiveUpReason.DECODER), policy.onStreamFailure(StreamFailure.Decoder))
        assertEquals(RetryDecision.GiveUp(GiveUpReason.OTHER), policy.onStreamFailure(StreamFailure.Other))
    }

    @Test fun instantGiveUpsDoNotBurnTransientTries() {
        policy.onStreamFailure(StreamFailure.HttpStatus(404))
        policy.onStreamFailure(StreamFailure.Decoder)
        assertEquals(RetryDecision.RetryAfter(1_000L, 1), policy.onStreamFailure(StreamFailure.Network))
    }

    // ---- "Try again" ------------------------------------------------------------------------------

    @Test fun resetClearsEveryCounter() {
        repeat(5) { now += 1_000; policy.onStreamFailure(StreamFailure.Network) }
        assertEquals(RetryDecision.GiveUp(GiveUpReason.EXHAUSTED), policy.onStreamFailure(StreamFailure.Network))
        policy.onStreamFailure(StreamFailure.HttpStatus(403)) // one auth try
        policy.onStreamFailure(StreamFailure.HttpStatus(403))
        assertEquals(RetryDecision.GiveUp(GiveUpReason.PROVIDER_REFUSED), policy.onStreamFailure(StreamFailure.HttpStatus(403)))

        policy.reset() // the "Try again" button
        assertEquals(RetryDecision.RetryAfter(1_000L, 1), policy.onStreamFailure(StreamFailure.Network))
        policy.reset()
        assertEquals(RetryDecision.RetryAfter(3_000L, 1), policy.onStreamFailure(StreamFailure.HttpStatus(403)))
    }

    @Test fun maxTriesMatchesTheBackoffTable() {
        assertEquals(RetryPolicy.BACKOFF_MS.size, RetryPolicy.MAX_TRIES)
        assertEquals(5, RetryPolicy.MAX_TRIES)
    }
}
