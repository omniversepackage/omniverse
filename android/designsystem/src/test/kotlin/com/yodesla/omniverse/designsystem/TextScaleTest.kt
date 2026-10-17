package com.yodesla.omniverse.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class TextScaleTest {
    @Test
    fun everySupportedScalePreservesUnspecifiedTypography() {
        for (scale in listOf(1f, 1.15f, 1.3f)) {
            val type = OmniType().scaled(scale)
            assertEquals(OmniType().display.letterSpacing, type.display.letterSpacing)
            assertEquals(OmniType().body.fontSize.value * scale, type.body.fontSize.value, 0.001f)
        }
    }
    @Test
    fun mapsKnownSettings() {
        assertEquals(1.0f, textScaleFor(TEXT_SIZE_NORMAL), 0.0001f)
        assertEquals(1.15f, textScaleFor(TEXT_SIZE_LARGE), 0.0001f)
        assertEquals(1.3f, textScaleFor(TEXT_SIZE_XLARGE), 0.0001f)
    }

    @Test
    fun nullAndUnknownFallBackToNormal() {
        assertEquals(1.0f, textScaleFor(null), 0.0001f)
        assertEquals(1.0f, textScaleFor(""), 0.0001f)
        assertEquals(1.0f, textScaleFor("huge"), 0.0001f)
        assertEquals(1.0f, textScaleFor("1.3"), 0.0001f)
    }
}
