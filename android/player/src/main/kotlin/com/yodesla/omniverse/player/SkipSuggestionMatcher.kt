package com.yodesla.omniverse.player

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One already-computed analysis frame (NOT raw PCM).
 *
 * Privacy contract: no title id, URL, provider data or audio samples may ever be added to this
 * type. [positionMs] is the playback position of the frame start.
 */
data class AudioFeatureFrame(
    val positionMs: Long,
    val rmsDb: Float,
    val spectralCentroidHz: Float,
    val hfRatio: Float,
)

/** Coarse per-frame label. UNKNOWN covers quiet (-45..-30 dB) or non-finite frames. */
enum class FrameClass { SILENT, MUSIC, SPEECH, UNKNOWN }

/**
 * Envelope sample stored with a confirmed point. [offsetMs] is relative to the confirmed
 * position (negative = before). Derived features only; no raw audio.
 */
data class EnvelopeFrame(
    val offsetMs: Long,
    val rmsDb: Float,
    val spectralCentroidHz: Float,
)

/**
 * Previously CONFIRMED local points (set by the user, never by this matcher). Used only to boost
 * a suggestion; unknown values are ignored.
 */
data class SkipReference(
    val confirmedIntroEndMs: Long? = null,
    val confirmedCreditsStartMs: Long? = null,
    val introEnvelope: List<EnvelopeFrame>? = null,
    val creditsEnvelope: List<EnvelopeFrame>? = null,
)

enum class SuggestionKind { INTRO_END, CREDITS_START }

/**
 * An UNCONFIRMED suggestion. It is never a SkipMarker and never a seek command: the caller must
 * confirm it before anything is applied or persisted. [confidence] is always at least
 * config.surfaceMinConfidence when a suggestion is returned.
 */
data class SkipSuggestion(
    val kind: SuggestionKind,
    val positionMs: Long,
    val confidence: Float,
    val mayBeLonger: Boolean,
    val reasons: List<String>,
)

/** Why a window or candidate was rejected. [code] is one of the REJECT_* constants. */
data class Rejection(
    val kind: SuggestionKind,
    val code: String,
    val detail: String,
)

data class SkipSuggestionResult(
    val intro: SkipSuggestion?,
    val credits: SkipSuggestion?,
    val rejections: List<Rejection>,
)

/** All matcher thresholds. Every value is a documented hypothesis (see task 41 report). */
data class SkipMatcherConfig(
    val introWindowMs: Long = 240_000,
    val creditsTailMs: Long = 180_000,
    val maxFrames: Int = 48_000,
    val maxPositionMs: Long = 6 * 3_600_000,
    val silentBelowDb: Float = -45f,
    val audibleAtDb: Float = -30f,
    val musicCentroidHz: Float = 1_800f,
    val musicHfRatio: Float = 0.35f,
    val minIntroMusicMs: Long = 5_000,
    val minIntroSpeechMs: Long = 2_000,
    val minCreditsMusicMs: Long = 5_000,
    val maxBoundaryGapMs: Long = 1_500,
    val maxDataGapMs: Long = 2_000,
    val tailEndToleranceMs: Long = 2_000,
    val coverageToleranceMs: Long = 5_000,
    val surfaceMinConfidence: Float = 0.70f,
    val maxConfidence: Float = 0.95f,
    val positionAgreementMs: Long = 2_000,
    val envelopeDbTolerance: Float = 6f,
    val envelopeCentroidTolerance: Float = 0.15f,
    val envelopeMatchMin: Float = 0.80f,
    val minEnvelopeFrames: Int = 10,
    val maxEnvelopeFrames: Int = 200,
)

/**
 * Pure, deterministic intro/credits suggestion matcher.
 *
 * Accepts already-computed timestamped feature frames and returns unconfirmed suggestions only.
 * No I/O, no network, no clock, no randomness: the same input always gives the same output.
 * One linear pass per window (O(n) time, O(window) memory); oversized or malformed input is
 * rejected, never reallocated or re-sorted.
 */
class SkipSuggestionMatcher(
    private val config: SkipMatcherConfig = SkipMatcherConfig(),
) {

    fun match(
        frames: List<AudioFeatureFrame>,
        durationMs: Long?,
        reference: SkipReference? = null,
    ): SkipSuggestionResult {
        val rejections = ArrayList<Rejection>(4)
        if (durationMs != null && (durationMs < 0L || durationMs > config.maxPositionMs)) {
            return bothRejected(rejections, REJECT_INPUT_INVALID, "duration out of range")
        }
        if (frames.size > config.maxFrames) {
            return bothRejected(
                rejections,
                REJECT_INPUT_TOO_LARGE,
                "frame count ${frames.size} exceeds ${config.maxFrames}",
            )
        }
        if (!frames.all { it.isValid() }) {
            return bothRejected(rejections, REJECT_INPUT_INVALID, "non-finite or out-of-range frame value")
        }
        if (!frames.isNonDecreasing()) {
            return bothRejected(rejections, REJECT_INPUT_NOT_SORTED, "frame positions must be non-decreasing")
        }
        val intro = matchIntro(frames, durationMs, reference, rejections)
        val credits = matchCredits(frames, durationMs, reference, rejections)
        return SkipSuggestionResult(intro, credits, rejections)
    }

    private fun bothRejected(
        rejections: MutableList<Rejection>,
        code: String,
        detail: String,
    ): SkipSuggestionResult {
        rejections += Rejection(SuggestionKind.INTRO_END, code, detail)
        rejections += Rejection(SuggestionKind.CREDITS_START, code, detail)
        return SkipSuggestionResult(null, null, rejections)
    }

    private fun matchIntro(
        frames: List<AudioFeatureFrame>,
        durationMs: Long?,
        reference: SkipReference?,
        rejections: MutableList<Rejection>,
    ): SkipSuggestion? {
        val windowEnd = if (durationMs != null) min(config.introWindowMs, durationMs) else config.introWindowMs
        val window = frames.selectWindow(0L, windowEnd)
        if (window.isEmpty()) {
            rejections += Rejection(SuggestionKind.INTRO_END, REJECT_WINDOW_INCOMPLETE, "no frames in intro window")
            return null
        }
        val lastPos = window.last().positionMs
        if (lastPos < windowEnd - config.coverageToleranceMs) {
            rejections += Rejection(
                SuggestionKind.INTRO_END,
                REJECT_WINDOW_INCOMPLETE,
                "intro frames end at ${lastPos}ms, need ${windowEnd - config.coverageToleranceMs}ms",
            )
            return null
        }
        val classes = window.map { classify(it) }.toTypedArray()
        val runs = buildRuns(classes)
        val frameMs = estimateFrameMs(window)

        for (i in runs.indices) {
            val music = runs[i]
            if (music.cls != FrameClass.MUSIC) continue
            if (runLengthMs(window, music, frameMs) < config.minIntroMusicMs) continue
            var j = i + 1
            var gapMs = 0L
            while (j < runs.size && runs[j].cls != FrameClass.SPEECH) {
                gapMs += runLengthMs(window, runs[j], frameMs)
                j++
            }
            if (j >= runs.size) continue
            val speech = runs[j]
            if (runLengthMs(window, speech, frameMs) < config.minIntroSpeechMs) continue
            if (gapMs > config.maxBoundaryGapMs) continue
            if (hasDataGap(window, music.start, speech.end - 1, frameMs)) {
                rejections += Rejection(
                    SuggestionKind.INTRO_END,
                    REJECT_GAP_IN_EVIDENCE,
                    "data gap inside the music-to-speech evidence span",
                )
                return null
            }
            val base = baseConfidence(margin(window, music, speech))
            if (base < 0.40f) {
                rejections += Rejection(SuggestionKind.INTRO_END, REJECT_LOW_CONFIDENCE, "weak spectral margin")
                return null
            }
            return finish(SuggestionKind.INTRO_END, window[speech.start].positionMs, base, false, reference, window, emptyList(), rejections)
        }
        rejections += Rejection(
            SuggestionKind.INTRO_END,
            REJECT_NO_SUSTAINED_MUSIC,
            "no sustained music-to-speech boundary in the intro window",
        )
        return null
    }

    private fun matchCredits(
        frames: List<AudioFeatureFrame>,
        durationMs: Long?,
        reference: SkipReference?,
        rejections: MutableList<Rejection>,
    ): SkipSuggestion? {
        if (durationMs == null) {
            rejections += Rejection(
                SuggestionKind.CREDITS_START,
                REJECT_DURATION_UNKNOWN,
                "duration unknown, tail window cannot be bounded",
            )
            return null
        }
        val windowStart = max(0L, durationMs - config.creditsTailMs)
        val window = frames.selectWindow(windowStart, durationMs)
        if (window.isEmpty()) {
            rejections += Rejection(
                SuggestionKind.CREDITS_START,
                REJECT_TAIL_NOT_REACHED,
                "no frames in the tail window (playback stopped before credits?)",
            )
            return null
        }
        val lastPos = window.last().positionMs
        if (lastPos < durationMs - config.coverageToleranceMs) {
            rejections += Rejection(
                SuggestionKind.CREDITS_START,
                REJECT_TAIL_NOT_REACHED,
                "tail frames end at ${lastPos}ms of ${durationMs}ms",
            )
            return null
        }
        val classes = window.map { classify(it) }.toTypedArray()
        val runs = buildRuns(classes)
        val frameMs = estimateFrameMs(window)

        var chosen = -1
        for (i in runs.indices.reversed()) {
            val music = runs[i]
            if (music.cls == FrameClass.MUSIC && runLengthMs(window, music, frameMs) >= config.minCreditsMusicMs) {
                chosen = i
                break
            }
        }
        if (chosen < 0) {
            rejections += Rejection(
                SuggestionKind.CREDITS_START,
                REJECT_SILENT_CREDITS,
                "no sustained music in the tail window",
            )
            return null
        }
        val music = runs[chosen]
        val mayBeLonger = music.start == 0
        val positionMs = if (mayBeLonger) windowStart else window[music.start].positionMs

        val after = classes.copyOfRange(music.end, classes.size)
        val sustainedToEnd = after.all { it == FrameClass.SILENT || it == FrameClass.UNKNOWN } &&
            lastPos >= durationMs - config.tailEndToleranceMs
        var trailingSpeechMs = 0L
        for (k in music.end until classes.size) {
            if (classes[k] == FrameClass.SPEECH) {
                trailingSpeechMs += window[k].positionMs - window[k - 1].positionMs + frameMs
            }
        }
        val endsCleanly = after.none { it == FrameClass.MUSIC } &&
            trailingSpeechMs <= config.maxBoundaryGapMs &&
            lastPos >= durationMs - config.tailEndToleranceMs

        if (!sustainedToEnd && !endsCleanly) {
            rejections += Rejection(
                SuggestionKind.CREDITS_START,
                REJECT_NO_SUSTAINED_MUSIC,
                "credits music does not run to the end of the file",
            )
            return null
        }
        if (hasDataGap(window, music.start, classes.size - 1, frameMs)) {
            rejections += Rejection(
                SuggestionKind.CREDITS_START,
                REJECT_GAP_IN_EVIDENCE,
                "data gap inside the credits evidence span",
            )
            return null
        }
        var base: Float
        val reasons = ArrayList<String>(3)
        when {
            mayBeLonger -> {
                base = 0.55f
                reasons += "MAY_BE_LONGER"
            }
            sustainedToEnd -> {
                base = 0.70f
                reasons += "SUSTAINED_TO_END"
            }
            else -> {
                base = 0.55f
                reasons += "FOLLOWS_SHORT_TRAIL"
            }
        }
        return finish(SuggestionKind.CREDITS_START, positionMs, base, mayBeLonger, reference, window, reasons, rejections)
    }

    private fun finish(
        kind: SuggestionKind,
        positionMs: Long,
        base: Float,
        mayBeLonger: Boolean,
        reference: SkipReference?,
        window: List<AudioFeatureFrame>,
        extraReasons: List<String>,
        rejections: MutableList<Rejection>,
    ): SkipSuggestion? {
        var confidence = base
        val reasons = ArrayList(extraReasons)
        if (kind == SuggestionKind.INTRO_END) {
            reasons += when {
                base >= 0.75f -> "BASE_MARGIN_HIGH"
                base >= 0.60f -> "BASE_MARGIN_MEDIUM"
                else -> "BASE_MARGIN_LOW"
            }
        }
        val confirmed = if (kind == SuggestionKind.INTRO_END) reference?.confirmedIntroEndMs
        else reference?.confirmedCreditsStartMs
        val envelope = if (kind == SuggestionKind.INTRO_END) reference?.introEnvelope
        else reference?.creditsEnvelope
        if (confirmed != null && abs(positionMs - confirmed) <= config.positionAgreementMs) {
            confidence += 0.15f
            reasons += "POSITION_AGREEMENT"
        }
        if (envelope != null && envelopeMatches(envelope, positionMs, window)) {
            confidence += 0.10f
            reasons += "ENVELOPE_MATCH"
        }
        confidence = min(confidence, config.maxConfidence)
        if (confidence < config.surfaceMinConfidence) {
            rejections += Rejection(
                kind,
                REJECT_LOW_CONFIDENCE,
                "confidence $confidence is below ${config.surfaceMinConfidence}",
            )
            return null
        }
        return SkipSuggestion(kind, positionMs, confidence, mayBeLonger, reasons)
    }

    private data class Margin(val drms: Float, val dCentroid: Float)

    private fun margin(
        window: List<AudioFeatureFrame>,
        music: Run,
        speech: Run,
    ): Margin {
        fun avg(from: Int, to: Int, pick: (AudioFeatureFrame) -> Float): Float {
            var sum = 0.0
            for (k in from until to) sum += pick(window[k]).toDouble()
            return (sum / (to - from)).toFloat()
        }
        return Margin(
            avg(music.start, music.end) { it.rmsDb } - avg(speech.start, speech.end) { it.rmsDb },
            avg(music.start, music.end) { it.spectralCentroidHz } - avg(speech.start, speech.end) { it.spectralCentroidHz },
        )
    }

    private fun baseConfidence(m: Margin): Float = when {
        m.drms >= 10f || m.dCentroid >= 800f -> 0.75f
        m.drms >= 6f || m.dCentroid >= 400f -> 0.60f
        else -> 0.45f
    }

    private fun envelopeMatches(
        envelope: List<EnvelopeFrame>,
        candidatePositionMs: Long,
        window: List<AudioFeatureFrame>,
    ): Boolean {
        if (envelope.size < config.minEnvelopeFrames || envelope.size > config.maxEnvelopeFrames) return false
        if (!envelope.all {
                it.offsetMs in -20_000L..20_000L &&
                    it.rmsDb.isFinite() &&
                    it.spectralCentroidHz.isFinite()
            }
        ) {
            return false
        }
        var matched = 0
        for (env in envelope) {
            val target = candidatePositionMs + env.offsetMs
            var best = -1
            var bestDist = Long.MAX_VALUE
            for (k in window.indices) {
                val d = abs(window[k].positionMs - target)
                if (d < bestDist) {
                    bestDist = d
                    best = k
                }
            }
            if (best < 0 || bestDist > 500L) continue
            val f = window[best]
            val dbOk = abs(f.rmsDb - env.rmsDb) <= config.envelopeDbTolerance
            val centroidOk = abs(f.spectralCentroidHz - env.spectralCentroidHz) <=
                max(100f, env.spectralCentroidHz * config.envelopeCentroidTolerance)
            if (dbOk && centroidOk) matched++
        }
        return matched / envelope.size.toFloat() >= config.envelopeMatchMin
    }

    private fun classify(frame: AudioFeatureFrame): FrameClass = when {
        !frame.isValid() -> FrameClass.UNKNOWN
        frame.rmsDb < config.silentBelowDb -> FrameClass.SILENT
        frame.rmsDb >= config.audibleAtDb &&
            (frame.spectralCentroidHz > config.musicCentroidHz || frame.hfRatio > config.musicHfRatio) -> FrameClass.MUSIC
        frame.rmsDb >= config.audibleAtDb -> FrameClass.SPEECH
        else -> FrameClass.UNKNOWN
    }

    private data class Run(val cls: FrameClass, val start: Int, val end: Int)

    private fun buildRuns(classes: Array<FrameClass>): List<Run> {
        val runs = ArrayList<Run>(classes.size / 8 + 1)
        var i = 0
        while (i < classes.size) {
            var j = i + 1
            while (j < classes.size && classes[j] == classes[i]) j++
            runs += Run(classes[i], i, j)
            i = j
        }
        return runs
    }

    private fun runLengthMs(window: List<AudioFeatureFrame>, run: Run, frameMs: Long): Long =
        window[run.end - 1].positionMs - window[run.start].positionMs + frameMs

    private fun estimateFrameMs(window: List<AudioFeatureFrame>): Long {
        if (window.size < 2) return 250L
        val diffs = ArrayList<Long>(window.size - 1)
        for (k in 1 until window.size) {
            val d = window[k].positionMs - window[k - 1].positionMs
            if (d in 50L..1_000L) diffs += d
        }
        if (diffs.isEmpty()) return 250L
        diffs.sort()
        return diffs[diffs.size / 2]
    }

    private fun hasDataGap(window: List<AudioFeatureFrame>, from: Int, to: Int, frameMs: Long): Boolean {
        for (k in (from + 1)..to) {
            if (window[k].positionMs - window[k - 1].positionMs > config.maxDataGapMs + frameMs) return true
        }
        return false
    }

    companion object {
        const val REJECT_INPUT_INVALID = "REJECT_INPUT_INVALID"
        const val REJECT_INPUT_TOO_LARGE = "REJECT_INPUT_TOO_LARGE"
        const val REJECT_INPUT_NOT_SORTED = "REJECT_INPUT_NOT_SORTED"
        const val REJECT_WINDOW_INCOMPLETE = "REJECT_WINDOW_INCOMPLETE"
        const val REJECT_TAIL_NOT_REACHED = "REJECT_TAIL_NOT_REACHED"
        const val REJECT_DURATION_UNKNOWN = "REJECT_DURATION_UNKNOWN"
        const val REJECT_GAP_IN_EVIDENCE = "REJECT_GAP_IN_EVIDENCE"
        const val REJECT_NO_SUSTAINED_MUSIC = "REJECT_NO_SUSTAINED_MUSIC"
        const val REJECT_SILENT_CREDITS = "REJECT_SILENT_CREDITS"
        const val REJECT_LOW_CONFIDENCE = "REJECT_LOW_CONFIDENCE"
    }
}

private fun AudioFeatureFrame.isValid(): Boolean =
    positionMs in 0L..43_200_000L &&
        rmsDb.isFinite() && spectralCentroidHz.isFinite() && hfRatio.isFinite()

private fun List<AudioFeatureFrame>.isNonDecreasing(): Boolean {
    for (k in 1 until size) if (this[k].positionMs < this[k - 1].positionMs) return false
    return true
}

private fun List<AudioFeatureFrame>.selectWindow(fromMs: Long, toMs: Long): List<AudioFeatureFrame> =
    filter { it.positionMs in fromMs until toMs }
