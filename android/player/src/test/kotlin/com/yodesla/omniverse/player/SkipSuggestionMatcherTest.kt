package com.yodesla.omniverse.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipSuggestionMatcherTest {

    private val matcher = SkipSuggestionMatcher()
    private val episodeMs = 2_700_000L

    // 45:00 episode grid, 250 ms frames. Only the intro window and the tail window are built;
    // the matcher filters by position, so the middle of the episode is irrelevant.
    private class Script {
        val frames = mutableListOf<AudioFeatureFrame>()
        var pos = 0L
            private set

        fun segment(ms: Long, make: (Long) -> AudioFeatureFrame) {
            val end = pos + ms
            var p = pos
            while (p < end) {
                frames += make(p)
                p += 250L
            }
            pos = end
        }

        fun gap(ms: Long) {
            pos += ms
        }
    }

    private fun silent(p: Long) = AudioFeatureFrame(p, -60f, 500f, 0.05f)
    private fun music(p: Long) = AudioFeatureFrame(p, -18f, 3000f, 0.5f)
    private fun altMusic(p: Long) = AudioFeatureFrame(p, -18f, 2000f, 0.1f)
    private fun speech(p: Long) = AudioFeatureFrame(p, -22f, 1200f, 0.1f)

    private fun introFrames(): List<AudioFeatureFrame> {
        val s = Script()
        s.segment(90_000, ::silent)
        s.segment(60_000, ::music)
        s.segment(90_000, ::speech)
        return s.frames
    }

    private fun tailFrames(start: Long, make: (Long) -> AudioFeatureFrame, end: Long = 2_700_000L): List<AudioFeatureFrame> {
        val out = mutableListOf<AudioFeatureFrame>()
        var p = start
        while (p < end) {
            out += make(p)
            p += 250L
        }
        return out
    }

    private fun silentTail(): List<AudioFeatureFrame> = tailFrames(2_520_000L, ::silent)

    private fun envelopeAround(frames: List<AudioFeatureFrame>, centerMs: Long): List<EnvelopeFrame> {
        val out = mutableListOf<EnvelopeFrame>()
        var off = -10_000L
        while (off <= 10_000L) {
            val f = frames.first { it.positionMs == centerMs + off }
            out += EnvelopeFrame(off, f.rmsDb, f.spectralCentroidHz)
            off += 2_000L
        }
        return out
    }

    private fun codes(result: SkipSuggestionResult, kind: SuggestionKind): Set<String> =
        result.rejections.filter { it.kind == kind }.map { it.code }.toSet()

    @Test
    fun introMusicToSpeechProducesUnconfirmedSuggestion() {
        val result = matcher.match(introFrames() + silentTail(), episodeMs)
        val intro = result.intro
        assertNotNull(intro)
        assertEquals(SuggestionKind.INTRO_END, intro.kind)
        assertEquals(150_000L, intro.positionMs)
        assertTrue(abs(intro.confidence - 0.75f) < 0.001f)
        assertFalse(intro.mayBeLonger)
        assertTrue("BASE_MARGIN_HIGH" in intro.reasons)
        // silence-only tail: credits must be rejected, not guessed
        assertNull(result.credits)
        assertTrue(SkipSuggestionMatcher.REJECT_SILENT_CREDITS in codes(result, SuggestionKind.CREDITS_START))
    }

    @Test
    fun coldOpenWithoutMusicMakesNoIntroSuggestion() {
        val s = Script()
        s.segment(240_000, ::speech)
        val result = matcher.match(s.frames + silentTail(), episodeMs)
        assertNull(result.intro)
        assertTrue(SkipSuggestionMatcher.REJECT_NO_SUSTAINED_MUSIC in codes(result, SuggestionKind.INTRO_END))
    }

    @Test
    fun isolatedMusicStingIsNotSustained() {
        val s = Script()
        s.segment(120_000, ::speech)
        s.segment(3_000, ::music)
        s.segment(117_000, ::speech)
        val result = matcher.match(s.frames + silentTail(), episodeMs)
        assertNull(result.intro)
        assertTrue(SkipSuggestionMatcher.REJECT_NO_SUSTAINED_MUSIC in codes(result, SuggestionKind.INTRO_END))
    }

    @Test
    fun incompleteIntroWindowIsRejected() {
        val s = Script()
        s.segment(30_000, ::speech)
        s.segment(30_000, ::music)
        s.segment(40_000, ::speech) // ends at 100 s, duration unknown: window is [0, 4 min]
        val result = matcher.match(s.frames, null)
        assertNull(result.intro)
        assertTrue(SkipSuggestionMatcher.REJECT_WINDOW_INCOMPLETE in codes(result, SuggestionKind.INTRO_END))
        assertTrue(SkipSuggestionMatcher.REJECT_DURATION_UNKNOWN in codes(result, SuggestionKind.CREDITS_START))
    }

    @Test
    fun dataGapInsideEvidenceSpanIsRejected() {
        val s = Script()
        s.segment(90_000, ::silent)
        s.segment(30_000, ::music)
        s.gap(10_000)
        s.segment(110_000, ::speech)
        val result = matcher.match(s.frames + silentTail(), episodeMs)
        assertNull(result.intro)
        assertTrue(SkipSuggestionMatcher.REJECT_GAP_IN_EVIDENCE in codes(result, SuggestionKind.INTRO_END))
    }

    @Test
    fun silentCreditsProduceNoCreditsSuggestion() {
        val s = Script()
        s.segment(90_000, ::silent)
        s.segment(60_000, ::music)
        s.segment(90_000, ::speech)
        s.gap(2_280_000)
        s.segment(120_000, ::silent)
        s.segment(60_000, ::speech)
        val result = matcher.match(s.frames, episodeMs)
        assertNotNull(result.intro)
        assertNull(result.credits)
        assertTrue(SkipSuggestionMatcher.REJECT_SILENT_CREDITS in codes(result, SuggestionKind.CREDITS_START))
    }

    @Test
    fun sustainedCreditsMusicToTheEndIsSuggested() {
        val s = Script()
        s.segment(90_000, ::silent)
        s.segment(60_000, ::music)
        s.segment(90_000, ::speech)
        s.gap(2_280_000)
        s.segment(30_000, ::silent)
        s.segment(150_000, ::music)
        val result = matcher.match(s.frames, episodeMs)
        val credits = result.credits
        assertNotNull(credits)
        assertEquals(SuggestionKind.CREDITS_START, credits.kind)
        assertEquals(2_550_000L, credits.positionMs)
        assertTrue(abs(credits.confidence - 0.70f) < 0.001f)
        assertFalse(credits.mayBeLonger)
        assertTrue("SUSTAINED_TO_END" in credits.reasons)
    }

    @Test
    fun creditsMusicOngoingAtWindowStartIsMayBeLonger() {
        val base = introFrames() + tailFrames(2_520_000L, ::music)

        // No confirmed reference: 0.55 base is below the 0.70 surface threshold.
        val plain = matcher.match(base, episodeMs)
        assertNull(plain.credits)
        assertTrue(SkipSuggestionMatcher.REJECT_LOW_CONFIDENCE in codes(plain, SuggestionKind.CREDITS_START))

        // A confirmed credits start at the window start is strong agreement: boosted to 0.70.
        val withRef = matcher.match(base, episodeMs, SkipReference(confirmedCreditsStartMs = 2_520_000L))
        val credits = withRef.credits
        assertNotNull(credits)
        assertTrue(credits.mayBeLonger)
        assertEquals(2_520_000L, credits.positionMs)
        assertTrue(abs(credits.confidence - 0.70f) < 0.001f)
        assertTrue("POSITION_AGREEMENT" in credits.reasons)
    }

    @Test
    fun unknownDurationRejectsCreditsButNotIntro() {
        val result = matcher.match(introFrames(), null)
        val intro = result.intro
        assertNotNull(intro)
        assertEquals(150_000L, intro.positionMs)
        assertNull(result.credits)
        assertTrue(SkipSuggestionMatcher.REJECT_DURATION_UNKNOWN in codes(result, SuggestionKind.CREDITS_START))
    }

    @Test
    fun positionAgreementWithConfirmedPointBoostsIntro() {
        val result = matcher.match(
            introFrames() + silentTail(),
            episodeMs,
            SkipReference(confirmedIntroEndMs = 151_000L),
        )
        val intro = result.intro
        assertNotNull(intro)
        assertTrue(abs(intro.confidence - 0.90f) < 0.001f)
        assertTrue("POSITION_AGREEMENT" in intro.reasons)
    }

    @Test
    fun shiftedRecapIsBoostedByEnvelopeNotPosition() {
        // Episode A: theme 90-150 s. Confirmed intro end 150 s, envelope sampled around it.
        val a = introFrames()
        val reference = SkipReference(
            confirmedIntroEndMs = 150_000L,
            introEnvelope = envelopeAround(a, 150_000L),
        )

        // Episode B: 30 s longer recap shifts the theme to 120-180 s. Position disagrees by 30 s,
        // so only the envelope may boost: 0.75 + 0.10 = 0.85.
        val s = Script()
        s.segment(120_000, ::silent)
        s.segment(60_000, ::music)
        s.segment(60_000, ::speech)
        val result = matcher.match(s.frames + silentTail(), episodeMs, reference)
        val intro = result.intro
        assertNotNull(intro)
        assertEquals(180_000L, intro.positionMs)
        assertTrue(abs(intro.confidence - 0.85f) < 0.001f)
        assertTrue("ENVELOPE_MATCH" in intro.reasons)
        assertFalse("POSITION_AGREEMENT" in intro.reasons)
    }

    @Test
    fun matchingEnvelopeAndPositionGiveCappedBoost() {
        val frames = introFrames() + silentTail()
        val reference = SkipReference(
            confirmedIntroEndMs = 150_000L,
            introEnvelope = envelopeAround(introFrames(), 150_000L),
        )
        val intro = matcher.match(frames, episodeMs, reference).intro
        assertNotNull(intro)
        assertTrue(abs(intro.confidence - 0.95f) < 0.001f)
        assertTrue("POSITION_AGREEMENT" in intro.reasons)
        assertTrue("ENVELOPE_MATCH" in intro.reasons)
    }

    @Test
    fun differentThemeEnvelopeDoesNotBoost() {
        // Same layout as episode A but a different theme (lower centroid).
        val s = Script()
        s.segment(90_000, ::silent)
        s.segment(60_000, ::altMusic)
        s.segment(90_000, ::speech)
        val reference = SkipReference(
            confirmedIntroEndMs = 150_000L,
            introEnvelope = envelopeAround(introFrames(), 150_000L),
        )
        val intro = matcher.match(s.frames + silentTail(), episodeMs, reference).intro
        assertNotNull(intro)
        // Position agrees (+0.15) but the envelope does not match the different theme.
        assertTrue(abs(intro.confidence - 0.90f) < 0.001f)
        assertTrue("POSITION_AGREEMENT" in intro.reasons)
        assertFalse("ENVELOPE_MATCH" in intro.reasons)
    }

    @Test
    fun oversizedInputIsRejectedWithoutCrash() {
        val frames = (0 until 48_001).map { silent(it * 250L) }
        val result = matcher.match(frames, null)
        assertNull(result.intro)
        assertNull(result.credits)
        assertTrue(SkipSuggestionMatcher.REJECT_INPUT_TOO_LARGE in codes(result, SuggestionKind.INTRO_END))
        assertTrue(SkipSuggestionMatcher.REJECT_INPUT_TOO_LARGE in codes(result, SuggestionKind.CREDITS_START))
    }

    @Test
    fun unsortedInputIsRejected() {
        val frames = introFrames().toMutableList()
        val tmp = frames[100]
        frames[100] = frames[101]
        frames[101] = tmp
        val result = matcher.match(frames, episodeMs)
        assertNull(result.intro)
        assertTrue(SkipSuggestionMatcher.REJECT_INPUT_NOT_SORTED in codes(result, SuggestionKind.INTRO_END))
    }
}
