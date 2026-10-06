package com.yodesla.omniverse.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 101: the startup-sync gate — Home's cached rows first, sync after, and never later than the cap. */
class StartupSyncGateTest {
    private var now = 0L
    private val gate = StartupSyncGate({ now }, maxDelayMs = 2_000L)

    @Test
    fun nothingMaySyncBeforeTheFirstFrameIsDrawn() {
        now = 50
        assertEquals(2_000L, gate.syncWaitMs(), "with no frame yet the sync stays behind the gate")
    }

    @Test
    fun homeRowsVisibleReleasesTheSyncAtOnce() {
        now = 120
        gate.firstFrameDrawn()
        now = 900
        gate.homeRowsVisible()
        assertEquals(0L, gate.syncWaitMs())
    }

    @Test
    fun syncStartsNoLaterThanTheCapAfterTheFirstFrame() {
        now = 120
        gate.firstFrameDrawn()
        now = 120
        assertEquals(2_000L, gate.syncWaitMs(), "Home with nothing cached still gets its sync at the cap")
        now = 1_500
        assertEquals(620L, gate.syncWaitMs(), "the cap is absolute: 120 + 2000")
        now = 2_500
        assertEquals(0L, gate.syncWaitMs(), "past the cap the sync runs immediately")
    }

    @Test
    fun theCapIsMeasuredFromTheFirstFrameNotFromTheCall() {
        now = 100
        gate.firstFrameDrawn()
        now = 1_900
        assertEquals(200L, gate.syncWaitMs())
        now = 2_100
        assertEquals(0L, gate.syncWaitMs())
    }

    @Test
    fun milestonesAreRecordedOnceSoLateCallsCannotReopenTheGate() {
        now = 100
        gate.firstFrameDrawn()
        now = 400
        gate.homeRowsVisible()
        now = 5_000
        gate.firstFrameDrawn()
        gate.homeRowsVisible()
        assertEquals(400L, gate.homeRowsVisibleAtMs)
        assertEquals(0L, gate.syncWaitMs())
    }

    @Test
    fun aDeepLinkThatSkipsHomeStillGetsASyncAtTheCap() {
        now = 300
        gate.firstFrameDrawn()
        assertEquals(2_000L, gate.syncWaitMs(), "Home never showed rows, so the cap decides")
        now = 2_300
        assertEquals(0L, gate.syncWaitMs())
    }
}
