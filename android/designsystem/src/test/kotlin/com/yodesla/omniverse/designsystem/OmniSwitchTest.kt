package com.yodesla.omniverse.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class OmniSwitchTest {
    @Test
    fun thumbSitsAtTheEndsOfTheTrack() {
        assertEquals(0f, switchThumbFraction(false), 0f)
        assertEquals(1f, switchThumbFraction(true), 0f)
    }

    @Test
    fun tappingFlipsTheStateTheRowWillCommit() {
        assertEquals(true, nextSwitchState(false))
        assertEquals(false, nextSwitchState(true))
    }
}
