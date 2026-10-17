package com.yodesla.omniverse.app

import kotlin.test.Test
import kotlin.test.assertEquals

class HiddenNavTest {
    @Test fun parsesAndNeverHidesHomeOrSettings() {
        assertEquals(setOf("sports", "anime"), parseHiddenNav(" sports, anime ,,home,settings"))
        assertEquals(emptySet(), parseHiddenNav(null))
    }
}
