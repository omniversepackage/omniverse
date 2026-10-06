package com.yodesla.omniverse.player

import android.view.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameRateMatchTest {
    private val hd = DisplayModeInfo("1", 1920, 1080, 59.94f) // the current mode
    private fun mode(id: String, hz: Float, w: Int = 1920, h: Int = 1080) = DisplayModeInfo(id, w, h, hz)

    @Test fun prefersExactRefreshRateOverMultiples() {
        val modes = listOf(mode("a", 23.976f), mode("b", 24.0f), mode("c", 47.95f), mode("d", 48.0f), mode("e", 60.0f))
        assertEquals("a", chooseDisplayMode(23.976f, modes, hd))
    }

    @Test fun acceptsWholeHzMultiplesWithinTolerance() {
        // 23.976 → 24.000 (±0.05) and 48.000 (47.952 rounded to whole Hz).
        val modes = listOf(mode("a", 24.0f), mode("b", 48.0f), mode("c", 60.0f))
        assertEquals("a", chooseDisplayMode(23.976f, modes, hd))
        val only48 = listOf(mode("b", 48.0f), mode("c", 60.0f))
        assertEquals("b", chooseDisplayMode(23.976f, only48, hd))
    }

    @Test fun prefersExactMultipleOverRoundedMultiple() {
        val modes = listOf(mode("a", 48.0f), mode("b", 47.95f))
        assertEquals("b", chooseDisplayMode(23.976f, modes, hd))
    }

    @Test fun twentyFiveMatchesTwentyFiveAndFiftyNotSixty() {
        val modes = listOf(mode("a", 50.0f), mode("b", 60.0f), mode("c", 25.0f))
        assertEquals("c", chooseDisplayMode(25.0f, modes, hd))
        assertEquals("a", chooseDisplayMode(25.0f, listOf(mode("a", 50.0f), mode("b", 60.0f)), hd))
    }

    @Test fun twentyNineNineSevenMatchesFiftyNineNineFourNotSixty() {
        val modes = listOf(mode("a", 59.94f), mode("b", 60.0f))
        assertEquals("a", chooseDisplayMode(29.97f, modes, hd))
    }

    @Test fun thirtyMatchesSixtyNotFiftyNineNineFour() {
        val modes = listOf(mode("a", 59.94f), mode("b", 60.0f))
        assertEquals("b", chooseDisplayMode(30.0f, modes, hd))
    }

    @Test fun neverChangesResolution() {
        val modes = listOf(mode("a", 23.976f, 1280, 720), mode("b", 50.0f))
        assertNull(chooseDisplayMode(23.976f, modes, hd)) // only a 720p 24 Hz mode: rejected
        assertEquals("b", chooseDisplayMode(25.0f, modes, hd))
    }

    @Test fun unknownFpsOrNoModesOrUnknownCurrentReturnsNull() {
        val modes = listOf(mode("a", 24.0f))
        assertNull(chooseDisplayMode(0f, modes, hd))
        assertNull(chooseDisplayMode(-1f, modes, hd))
        assertNull(chooseDisplayMode(23.976f, emptyList(), hd))
        assertNull(chooseDisplayMode(23.976f, modes, null))
    }

    @Test fun nothingFitsReturnsNull() {
        val modes = listOf(mode("a", 50.0f), mode("b", 60.0f))
        assertNull(chooseDisplayMode(23.976f, modes, hd))
    }

    @Test fun modeParsesStoredValuesAndDefaultsToSeamless() {
        assertEquals(FrameRateMatchMode.OFF, FrameRateMatchMode.of(FRAME_RATE_MATCH_OFF))
        assertEquals(FrameRateMatchMode.SEAMLESS, FrameRateMatchMode.of(FRAME_RATE_MATCH_SEAMLESS))
        assertEquals(FrameRateMatchMode.ALWAYS, FrameRateMatchMode.of(FRAME_RATE_MATCH_ALWAYS))
        assertEquals(FrameRateMatchMode.SEAMLESS, FrameRateMatchMode.of(null))
        assertEquals(FrameRateMatchMode.SEAMLESS, FrameRateMatchMode.of("garbage"))
        // Task 77's boolean values migrate faithfully: on was VOD-only seamless, off stays off.
        assertEquals(FrameRateMatchMode.SEAMLESS, FrameRateMatchMode.of("true"))
        assertEquals(FrameRateMatchMode.OFF, FrameRateMatchMode.of("false"))
    }

    @Test fun storedValuesRoundTrip() {
        for (m in FrameRateMatchMode.values()) assertEquals(m, FrameRateMatchMode.of(m.storedValue))
    }

    @Test fun surfaceRequestsNeedApi30AndNeverForOff() {
        assertFalse(canRequestSurfaceFrameRate(FrameRateMatchMode.OFF, 36))
        assertTrue(canRequestSurfaceFrameRate(FrameRateMatchMode.SEAMLESS, 30))
        assertTrue(canRequestSurfaceFrameRate(FrameRateMatchMode.ALWAYS, 30))
        assertFalse(canRequestSurfaceFrameRate(FrameRateMatchMode.SEAMLESS, 29))
        assertFalse(canRequestSurfaceFrameRate(FrameRateMatchMode.ALWAYS, 23))
    }

    @Test fun displayModeFallbackOnlyForAlwaysBelowApi30() {
        assertTrue(shouldUseDisplayModeFallback(FrameRateMatchMode.ALWAYS, 23))
        assertTrue(shouldUseDisplayModeFallback(FrameRateMatchMode.ALWAYS, 29))
        assertFalse(shouldUseDisplayModeFallback(FrameRateMatchMode.SEAMLESS, 29))
        assertFalse(shouldUseDisplayModeFallback(FrameRateMatchMode.OFF, 29))
        assertFalse(shouldUseDisplayModeFallback(FrameRateMatchMode.ALWAYS, 30))
    }

    @Test fun backgroundingPinIsTheWindowFallbackThatMustBeClearedOnStop() {
        // Task 111 M2: the only pin that survives backgrounding is the API 23-29 window
        // preferredDisplayModeId, used for "Always" below API 30 (API 30+ pins the surface, which the
        // OS already drops when the surface is destroyed). EngineSurface's LifecycleStartEffect must
        // clear this pin on ON_STOP and re-request it on ON_START.
        assertTrue(shouldUseDisplayModeFallback(FrameRateMatchMode.ALWAYS, 29), "the window pin is the backgrounding hazard")
        assertFalse(shouldUseDisplayModeFallback(FrameRateMatchMode.ALWAYS, 30), "API 30+ pins the surface, not the window")
    }

    @Test fun onlyRealVideoRatesAreRequested() {
        assertTrue(isRequestableFrameRate(23.976f))
        assertTrue(isRequestableFrameRate(120f))
        assertFalse(isRequestableFrameRate(0f))
        assertFalse(isRequestableFrameRate(-1f))
        assertFalse(isRequestableFrameRate(1000f))
    }

    @Test fun strategyFollowsMode() {
        assertEquals(Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS, surfaceChangeFrameRateStrategy(FrameRateMatchMode.SEAMLESS))
        assertEquals(Surface.CHANGE_FRAME_RATE_ALWAYS, surfaceChangeFrameRateStrategy(FrameRateMatchMode.ALWAYS))
    }
}
