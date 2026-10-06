package com.yodesla.omniverse.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class TextScaleTest {
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
