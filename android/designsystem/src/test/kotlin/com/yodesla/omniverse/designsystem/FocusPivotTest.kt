package com.yodesla.omniverse.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FocusPivotTest {
    @Test fun visibilityOnlyDoesNotMoveAnAlreadySafeControl() {
        assertEquals(0f, focusSafeScrollDistance(180f, 72f, 480f, 0f, 12f,
            onlyWhenClipped = true), 0.001f)
    }

    @Test fun visibilityOnlyMovesJustEnoughForTheActualScale() {
        val distance = focusSafeScrollDistance(390f, 100f, 480f, 0f, 12f,
            focusedScale = 1.08f, onlyWhenClipped = true)
        assertEquals(26f, distance, 0.001f)
        assertEquals(364f, 390f - distance, 0.001f)
    }

    @Test fun ordinarySafePivotKeepsItsPosition() {
        assertEquals(52f, focusSafeScrollDistance(100f, 132f, 960f, 48f, 16f), 0.001f)
    }

    @Test fun zeroLeadingLeavesRoomForTheScaledCardAndHalo() {
        val size = 600f
        val distance = focusSafeScrollDistance(0f, size, 960f, 0f, 16f)
        val start = -distance
        assertEquals(34f, start, 0.001f)
        assertTrue(start - size * (OmniMotion.FOCUS_SCALE - 1f) / 2f >= 15.99f)
    }

    @Test fun EndPivotDoesNotClipScaledTrailingEdge() {
        val size = 200f
        val start = -focusSafeScrollDistance(0f, size, 500f, 480f, 16f)
        assertEquals(278f, start, 0.001f)
        assertTrue(start + size * (1f + (OmniMotion.FOCUS_SCALE - 1f) / 2f) <= 484.01f)
    }

    @Test fun ItemLargerThanViewportUsesFiniteCenteredFallback() {
        assertEquals(150f, focusSafeScrollDistance(100f, 300f, 200f, 0f, 16f), 0.001f)
        assertEquals(0f, focusSafeScrollDistance(0f, 0f, 0f, 0f, 16f), 0.001f)
    }
}
