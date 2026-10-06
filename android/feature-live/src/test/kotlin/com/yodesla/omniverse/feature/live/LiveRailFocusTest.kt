package com.yodesla.omniverse.feature.live

import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 107: rail item index of the selected category (header chips come before the categories). */
class LiveRailFocusTest {
    private val ids = listOf("all", "kids", "sports")

    @Test fun selectedCategorySitsBehindTheHeaderChips() {
        assertEquals(2 + 0, liveRailFocusIndex(true, true, ids, "all"))
        assertEquals(2 + 2, liveRailFocusIndex(true, true, ids, "sports"))
    }

    @Test fun headerCountFollowsTheRailLayout() {
        assertEquals(1 + 1, liveRailFocusIndex(true, false, ids, "kids"))
        assertEquals(0 + 1, liveRailFocusIndex(false, false, ids, "kids"))
    }

    @Test fun unknownSelectionIsMinusOne() {
        assertEquals(-1, liveRailFocusIndex(true, true, ids, "zz"))
        assertEquals(-1, liveRailFocusIndex(true, true, ids, null))
        assertEquals(-1, liveRailFocusIndex(true, true, emptyList(), "all"))
    }
}
