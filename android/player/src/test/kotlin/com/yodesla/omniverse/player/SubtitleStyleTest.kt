package com.yodesla.omniverse.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SubtitleStyleTest {
    @Test fun allDefaultMeansSystemStyle() {
        assertNull(resolveSubtitleStyle(null, null, null, null))
        assertNull(resolveSubtitleStyle("default", "default", "default", "default"))
        assertNull(resolveSubtitleStyle("", "  ", "", ""))
        assertNull(resolveSubtitleStyle("bogus", "nope", "x", "y"))
    }

    @Test fun explicitSizeUsesLargeAndKeepsBaselines() {
        val s = resolveSubtitleStyle("large", null, null, null)!!
        assertEquals(SubtitleSize.LARGE.fraction, s.fractionalTextSize)
        assertEquals(SubtitleTextColor.WHITE.argb, s.textColorArgb)
        assertEquals(SubtitleBackground.SEMI.argb, s.backgroundColorArgb)
        assertEquals(SubtitleBackground.SEMI.argb, s.windowColorArgb)
        assertEquals(SubtitlePosition.BOTTOM.padding, s.bottomPaddingFraction)
        assertFalse(s.applyEmbeddedStyles)
    }

    @Test fun colourBackgroundPositionMapToTheirValues() {
        val s = resolveSubtitleStyle(null, "none", "yellow", "raised")!!
        assertEquals(SubtitleTextColor.YELLOW.argb, s.textColorArgb)
        assertEquals(SubtitleBackground.NONE.argb, s.backgroundColorArgb)
        assertEquals(SubtitleBackground.NONE.argb, s.windowColorArgb)
        assertEquals(SubtitlePosition.RAISED.padding, s.bottomPaddingFraction)
        assertEquals(SubtitleSize.MEDIUM.fraction, s.fractionalTextSize)
    }

    @Test fun solidBackgroundIsOpaque() {
        val s = resolveSubtitleStyle(null, "solid", null, null)!!
        assertEquals(SubtitleBackground.SOLID.argb, s.backgroundColorArgb)
        assertEquals(0xFF000000.toInt(), s.backgroundColorArgb)
    }

    @Test fun tokensAreCaseAndWhitespaceInsensitive() {
        val s = resolveSubtitleStyle("  LARGE ", "Semi", null, null)!!
        assertEquals(SubtitleSize.LARGE.fraction, s.fractionalTextSize)
        assertEquals(SubtitleBackground.SEMI.argb, s.backgroundColorArgb)
    }

    @Test fun summaryNamesOnlyTheNonDefaultChoices() {
        assertEquals("Default", subtitleStyleSummary(null, null, null, null))
        assertEquals("Default", subtitleStyleSummary("default", "default", "default", "default"))
        assertEquals("Large", subtitleStyleSummary("large", null, null, null))
        assertEquals("Large  ·  Yellow", subtitleStyleSummary("large", null, "yellow", null))
        assertEquals("Small  ·  Solid box  ·  White  ·  Raised",
            subtitleStyleSummary("small", "solid", "white", "raised"))
    }

    @Test fun delayStepsByHundredMs() {
        assertEquals(100L, stepSubtitleDelay(0L, 1))
        assertEquals(-100L, stepSubtitleDelay(0L, -1))
        assertEquals(0L, stepSubtitleDelay(0L, 0))
        assertEquals(100L, stepSubtitleDelay(0L, 5)) // direction is coerced to ±1
        assertEquals(-100L, stepSubtitleDelay(0L, -5))
        assertEquals(900L, stepSubtitleDelay(800L, 1))
    }

    @Test fun delayClampsToPlusMinusTenSeconds() {
        assertEquals(SUBTITLE_DELAY_MAX_MS, stepSubtitleDelay(SUBTITLE_DELAY_MAX_MS, 1))
        assertEquals(SUBTITLE_DELAY_MAX_MS, stepSubtitleDelay(9_950L, 1))
        assertEquals(SUBTITLE_DELAY_MIN_MS, stepSubtitleDelay(SUBTITLE_DELAY_MIN_MS, -1))
        assertEquals(SUBTITLE_DELAY_MIN_MS, stepSubtitleDelay(-9_950L, -1))
    }

    @Test fun delayLabelIsSignedSeconds() {
        assertEquals("0 s", formatSubtitleDelay(0L))
        assertEquals("+0.1 s", formatSubtitleDelay(100L))
        assertEquals("+10.0 s", formatSubtitleDelay(10_000L))
        assertEquals("-3.2 s", formatSubtitleDelay(-3_200L))
    }
}
