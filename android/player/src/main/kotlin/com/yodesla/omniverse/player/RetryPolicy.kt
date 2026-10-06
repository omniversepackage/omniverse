package com.yodesla.omniverse.player

/**
 * Task 81: one decision table for dropped IPTV streams, shared by every engine so Live, VOD and
 * Multiview all recover the same way. Pure Kotlin (no media3/Android types) so the whole table is
 * unit-testable; time comes from an injected [RetryClock].
 *
 * Rules:
 * - Transient network / 5xx hiccups: backoff 1 s, 2 s, 4 s, 8 s, 15 s — max [MAX_TRIES] tries.
 *   [MAX_TRIES] further failures give up. A [GOOD_PLAYBACK_RESET_MS] stretch of healthy playback
 *   ([onHealthyPlayback]) resets the counters, so a drop hours into the evening gets the full run.
 * - BehindLiveWindow: immediate seek-to-live + prepare, no counter (it is a DVR-window hiccup,
 *   not a dead stream — retrying is always right and must never burn a try).
 * - 401/403/429/509 (auth or connection/device limit): ONE retry after [AUTH_RETRY_MS] — the slot
 *   may have just been released by another device — then give up with [GiveUpReason.PROVIDER_REFUSED].
 * - 404/410 and decoder failures: give up at once; retrying can't fix them.
 */

/** Monotonic time source injected into [RetryPolicy] so tests can drive the clock. */
fun interface RetryClock {
    fun nowMs(): Long
}

/** What went wrong, reduced to the few signals the policy needs (media3 errors map onto this). */
sealed interface StreamFailure {
    /** The provider answered with this HTTP status code. */
    data class HttpStatus(val code: Int) : StreamFailure

    /** Connection failed / timed out / stalled — no usable server answer at all. */
    data object Network : StreamFailure

    /** Live: the play head fell behind the DVR window. */
    data object BehindLiveWindow : StreamFailure

    /** The codec/decoder can't handle this stream. */
    data object Decoder : StreamFailure

    /** Anything else. */
    data object Other : StreamFailure
}

/** The five buckets [RetryPolicy.classify] sorts [StreamFailure]s into. */
enum class StreamErrorKind { TRANSIENT_NETWORK, BEHIND_LIVE_WINDOW, AUTH_OR_LIMIT, NOT_FOUND, DECODER, OTHER }

/** Why [RetryDecision.GiveUp] stopped retrying; the UI turns these into friendly messages. */
enum class GiveUpReason { PROVIDER_REFUSED, NOT_FOUND, DECODER, EXHAUSTED, OTHER }

sealed interface RetryDecision {
    /**
     * Wait [delayMs], then re-open the stream. [attempt] is the 1-based retry number (drives the
     * "Reconnecting… (n/5)" chip). [seekToLive] = rejoin the live edge (seekToDefaultPosition +
     * prepare) instead of re-opening the URL.
     */
    data class RetryAfter(val delayMs: Long, val attempt: Int, val seekToLive: Boolean = false) : RetryDecision

    /** Stop retrying; show the error UI. */
    data class GiveUp(val reason: GiveUpReason) : RetryDecision
}

/** Per-stream retry decisions. Not thread-safe: the engine calls it from the main thread only. */
class RetryPolicy(private val clock: RetryClock) {

    /** Retry number of the last transient/auth decision, 0 = none in flight (for tests + UI). */
    var transientTries: Int = 0
        private set
    var authTries: Int = 0
        private set

    // Start of the current healthy-playback streak / of the last failure. Both "long ago" so a
    // session that never played yet trivially has no streak to count.
    private var lastHealthyMs = Long.MIN_VALUE / 2
    private var lastFailureMs = Long.MIN_VALUE / 2

    fun classify(failure: StreamFailure): StreamErrorKind = when (failure) {
        StreamFailure.Network -> StreamErrorKind.TRANSIENT_NETWORK
        StreamFailure.BehindLiveWindow -> StreamErrorKind.BEHIND_LIVE_WINDOW
        StreamFailure.Decoder -> StreamErrorKind.DECODER
        StreamFailure.Other -> StreamErrorKind.OTHER
        is StreamFailure.HttpStatus -> when (failure.code) {
            401, 403, 429, 509 -> StreamErrorKind.AUTH_OR_LIMIT
            404, 410 -> StreamErrorKind.NOT_FOUND
            else -> StreamErrorKind.TRANSIENT_NETWORK
        }
    }

    /** Classify [failure] and decide what to do about it. */
    fun onStreamFailure(failure: StreamFailure): RetryDecision {
        maybeReset()
        return when (classify(failure)) {
            StreamErrorKind.TRANSIENT_NETWORK ->
                if (transientTries >= BACKOFF_MS.size) RetryDecision.GiveUp(GiveUpReason.EXHAUSTED)
                else RetryDecision.RetryAfter(BACKOFF_MS[transientTries++], transientTries)
            // No counter: a DVR-window slip is routine on live TV and always recoverable.
            StreamErrorKind.BEHIND_LIVE_WINDOW ->
                RetryDecision.RetryAfter(0L, transientTries.coerceAtLeast(1), seekToLive = true)
            StreamErrorKind.AUTH_OR_LIMIT ->
                if (authTries >= 1) RetryDecision.GiveUp(GiveUpReason.PROVIDER_REFUSED)
                else RetryDecision.RetryAfter(AUTH_RETRY_MS, ++authTries)
            StreamErrorKind.NOT_FOUND -> RetryDecision.GiveUp(GiveUpReason.NOT_FOUND)
            StreamErrorKind.DECODER -> RetryDecision.GiveUp(GiveUpReason.DECODER)
            StreamErrorKind.OTHER -> RetryDecision.GiveUp(GiveUpReason.OTHER)
        }
    }

    /**
     * Call whenever the stream is actually playing. Repeated calls are fine: only the first one
     * after a failure starts the streak that [GOOD_PLAYBACK_RESET_MS] of must clear the counters.
     */
    fun onHealthyPlayback() {
        val now = clock.nowMs()
        if (lastHealthyMs <= lastFailureMs) lastHealthyMs = now
    }

    /** Full reset — what the "Try again" button does before re-opening the stream. */
    fun reset() {
        transientTries = 0
        authTries = 0
        val now = clock.nowMs()
        lastHealthyMs = now
        lastFailureMs = now
    }

    private fun maybeReset() {
        val now = clock.nowMs()
        if (lastHealthyMs > lastFailureMs && now - lastHealthyMs >= GOOD_PLAYBACK_RESET_MS) {
            transientTries = 0
            authTries = 0
        }
        lastFailureMs = now
    }

    companion object {
        /** Transient backoff steps; its size is the max number of tries (5). */
        val BACKOFF_MS: LongArray = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        const val MAX_TRIES = 5
        const val AUTH_RETRY_MS = 3_000L
        const val GOOD_PLAYBACK_RESET_MS = 60_000L
    }
}
