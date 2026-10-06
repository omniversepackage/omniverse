package com.yodesla.omniverse.player

import android.view.Surface

/**
 * One physical display mode of the TV (Task 77). [id] is `Display.Mode.uniqueId`; [refreshRateHz]
 * is `Display.Mode.refreshRate` (23.976, 50.0, 59.94 …).
 */
data class DisplayModeInfo(val id: String, val width: Int, val height: Int, val refreshRateHz: Float)

/** Settings › Playback › "Match content frame rate" (Task 100), stored per profile. */
const val FRAME_RATE_MATCH_SETTING = "match_frame_rate"
const val FRAME_RATE_MATCH_OFF = "off"
const val FRAME_RATE_MATCH_SEAMLESS = "seamless"
const val FRAME_RATE_MATCH_ALWAYS = "always"

/** The stored values the picker may write; anything else reads back as [FRAME_RATE_MATCH_SEAMLESS]. */
val FRAME_RATE_MATCH_MODES = setOf(FRAME_RATE_MATCH_OFF, FRAME_RATE_MATCH_SEAMLESS, FRAME_RATE_MATCH_ALWAYS)

/**
 * Task 100: how hard the player may push the display onto the video's frame rate.
 * [OFF] never asks. [SEAMLESS] asks, but only for a switch the display can do without a blanking
 * gap (API 30+). [ALWAYS] asks unconditionally and, below API 30, also pins a matching display
 * mode — the only way to switch there, and the one that can flicker.
 */
enum class FrameRateMatchMode(val storedValue: String) {
    OFF(FRAME_RATE_MATCH_OFF),
    SEAMLESS(FRAME_RATE_MATCH_SEAMLESS),
    ALWAYS(FRAME_RATE_MATCH_ALWAYS);

    companion object {
        /**
         * Absent or unknown stored value = Seamless only (the product default). The old Task 77
         * boolean values read back faithfully: "true" was VOD-only seamless switching, "false" was off.
         */
        fun of(saved: String?): FrameRateMatchMode = when (saved) {
            FRAME_RATE_MATCH_OFF, "false" -> OFF
            FRAME_RATE_MATCH_ALWAYS -> ALWAYS
            else -> SEAMLESS
        }
    }
}

/** `Surface.setFrameRate`'s change strategy for a mode (API 31+ three-argument overload). */
@androidx.annotation.RequiresApi(31)
fun surfaceChangeFrameRateStrategy(mode: FrameRateMatchMode): Int =
    if (mode == FrameRateMatchMode.ALWAYS) Surface.CHANGE_FRAME_RATE_ALWAYS else Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS

/** API 30+ can request the rate on the video surface itself; below that only "Always" has a path. */
fun canRequestSurfaceFrameRate(mode: FrameRateMatchMode, sdkInt: Int): Boolean =
    mode != FrameRateMatchMode.OFF && sdkInt >= 30

/**
 * API 23-29 has no `Surface.setFrameRate`: only "Always" may switch, and only through
 * `WindowManager.LayoutParams.preferredDisplayModeId`. On API 30+ the surface request covers both
 * modes, so the window fallback is never used there.
 */
fun shouldUseDisplayModeFallback(mode: FrameRateMatchMode, sdkInt: Int): Boolean =
    mode == FrameRateMatchMode.ALWAYS && sdkInt < 30

/** A rate worth asking for: real video is 23.976-120 fps; anything else is a broken format value. */
fun isRequestableFrameRate(fps: Float): Boolean = fps > 0f && fps <= 120f

/**
 * Picks the display mode to pin while a VOD video plays: the same physical resolution as the
 * current mode, with a refresh rate equal to the video frame rate or an integer multiple of it
 * (23.976 → 23.976/24/47.95/48; 25 → 25/50; 29.97 → 29.97/59.94; 30 → 30/60). Exact matches win
 * over multiples, and within the same multiple the closest refresh rate wins. A ±0.05 Hz tolerance
 * is what lets 23.976 accept 24.000 while 29.97 rejects 60.000. Returns null when the video rate
 * is unknown, no same-resolution mode fits, or the current mode is unknown (resolution can't be
 * pinned then). Never changes the resolution.
 */
fun chooseDisplayMode(videoFps: Float, modes: List<DisplayModeInfo>, current: DisplayModeInfo?): String? {
    if (videoFps <= 0f || current == null || modes.isEmpty()) return null
    var bestId: String? = null
    var bestMultiple = Int.MAX_VALUE
    var bestDeltaHz = Double.MAX_VALUE
    for (mode in modes) {
        if (mode.width != current.width || mode.height != current.height) continue
        val multiple = Math.round(mode.refreshRateHz.toDouble() / videoFps.toDouble()).toInt()
        if (multiple < 1) continue
        val deltaHz = Math.abs(mode.refreshRateHz.toDouble() - videoFps.toDouble() * multiple)
        if (deltaHz > FRAME_RATE_MATCH_TOLERANCE_HZ) continue
        if (multiple < bestMultiple || (multiple == bestMultiple && deltaHz < bestDeltaHz)) {
            bestId = mode.id
            bestMultiple = multiple
            bestDeltaHz = deltaHz
        }
    }
    return bestId
}

/** Wide enough for 23.976↔24.000 and 47.952↔48.000, tight enough to reject 29.97↔60.000. */
const val FRAME_RATE_MATCH_TOLERANCE_HZ = 0.05
