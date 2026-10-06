package com.yodesla.omniverse.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepTimerTest {
    private var now = 0L
    private val timer = SleepTimer { now }

    @Test fun offTimerNeverFires() {
        assertFalse(timer.isArmed)
        now = 10_000_000
        assertEquals(SleepTimerEvent.None, timer.tick())
    }

    @Test fun armedTimerStaysQuietOutsideTheWarningWindow() {
        timer.armMinutes(30)
        assertTrue(timer.isArmed)
        now = 1_739_000L // 61 s remaining — just outside the 60 s window
        assertEquals(SleepTimerEvent.None, timer.tick())
    }

    @Test fun warnsInsideTheSixtySecondWindowThenFiresOnceAndDisarms() {
        timer.armMinutes(30)
        now = 30 * 60_000L - 59_000L
        assertEquals(SleepTimerEvent.Warn(59_000L), timer.tick())
        now = 30 * 60_000L - 1L
        assertEquals(SleepTimerEvent.Warn(1L), timer.tick())
        now = 30 * 60_000L
        assertEquals(SleepTimerEvent.Fire, timer.tick())
        assertFalse(timer.isArmed)
        assertEquals(SleepTimerEvent.None, timer.tick())
    }

    @Test fun cancelResetsToOff() {
        timer.armMinutes(15)
        timer.cancel()
        assertFalse(timer.isArmed)
        assertEquals(SleepTimerEvent.None, timer.tick())
    }

    @Test fun armRemainingWarnsImmediatelyForShortProgrammes() {
        timer.armRemaining(30_000L)
        assertEquals(SleepTimerEvent.Warn(30_000L), timer.tick())
    }

    @Test fun remainingMsTracksTheDeadline() {
        timer.armMinutes(60)
        assertEquals(60 * 60_000L, timer.remainingMs)
        now = 10 * 60_000L
        assertEquals(50 * 60_000L, timer.remainingMs)
    }

    @Test fun armingAgainReplacesTheDeadline() {
        timer.armMinutes(90)
        timer.armMinutes(15)
        now = 15 * 60_000L
        assertEquals(SleepTimerEvent.Fire, timer.tick())
    }
}
