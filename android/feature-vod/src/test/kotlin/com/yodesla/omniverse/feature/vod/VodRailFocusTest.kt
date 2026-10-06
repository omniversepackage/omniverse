package com.yodesla.omniverse.feature.vod

import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 107: rail item index of the selected category (header items come before the chips). */
class VodRailFocusTest {
    private val cats = listOf("a", "b", "c").map { VodCategoryUi(it, it.uppercase()) }

    @Test fun selectedChipSitsBehindTheHeaderItems() {
        assertEquals(3 + 0, vodRailFocusIndex(3, cats, "a"))
        assertEquals(3 + 2, vodRailFocusIndex(3, cats, "c"))
    }

    @Test fun headerCountFollowsTheRailLayout() {
        // search + sort + filter = 3; no search = 2; branded page (no filter) with search = 2.
        assertEquals(1 + 1, vodRailFocusIndex(2, cats, "a"))
        assertEquals(0 + 1, vodRailFocusIndex(1, cats, "a"))
    }

    @Test fun unknownSelectionScrollsToTheTop() {
        assertEquals(0, vodRailFocusIndex(3, cats, "zz"))
        assertEquals(0, vodRailFocusIndex(3, emptyList(), "a"))
    }

    @Test fun allCategoryIsTheNullIdChip() {
        val withAll = listOf(VodCategoryUi(null, "All")) + cats
        assertEquals(3, vodRailFocusIndex(3, withAll, null))
        assertEquals(6, vodRailFocusIndex(3, withAll, "c"))
    }
}
