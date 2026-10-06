package com.yodesla.omniverse.feature.live.guide

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 111 M6: a filter chip that yields no channels must move focus to the chip row, not to the
 * grid that is no longer rendered (otherwise Down does nothing and the empty message is unreachable).
 */
class GuideFocusTest {
    @Test fun nonEmptyGridKeepsFocusOnTheGrid() {
        assertEquals(GuideFocusTarget.Grid, guideFocusTarget(channelCount = 5, loading = false))
    }

    @Test fun emptyGridMovesFocusToTheChips() {
        assertEquals(GuideFocusTarget.Chips, guideFocusTarget(channelCount = 0, loading = false))
    }

    @Test fun loadingGridTakesNoFocus() {
        assertEquals(GuideFocusTarget.None, guideFocusTarget(channelCount = 0, loading = true))
        assertEquals(GuideFocusTarget.None, guideFocusTarget(channelCount = 3, loading = true))
    }
}
