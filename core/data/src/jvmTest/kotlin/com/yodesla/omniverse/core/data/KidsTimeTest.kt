package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.ProfileRepositoryImpl
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 106: the Kids daily limit and bedtime clock. 2026-01-15T00:00:00Z is used as local midnight
 * (offset 0) so every boundary in these tests is an exact epoch value.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KidsTimeTest {
    private val midnight = 1_768_435_200_000L
    private val second = 1_000L
    private val minute = 60_000L
    private val hour = 3_600_000L

    private fun at(hourOfDay: Int, minuteOfHour: Int = 0, dayOffset: Int = 0) =
        midnight + dayOffset * 24L * hour + hourOfDay * hour + minuteOfHour * minute

    // ---- wall-clock math ----

    @Test
    fun minutesOfDayAndDayKeyRollOverAtLocalMidnight() {
        assertEquals(0, kidsMinutesOfDay(midnight, 0))
        assertEquals(1439, kidsMinutesOfDay(midnight + 23 * hour + 59 * minute, 0))
        assertEquals("2026-01-15", kidsDayKey(midnight, 0))
        assertEquals("2026-01-15", kidsDayKey(midnight + 24 * hour - 1, 0))
        assertEquals("2026-01-16", kidsDayKey(midnight + 24 * hour, 0))
        assertEquals("2026-01-14", kidsDayKey(midnight - 1, 0))
    }

    @Test
    fun localOffsetMovesBothMidnightAndTheDayKey() {
        // UTC+1: local midnight arrives an hour earlier, so 23:00 UTC is already the next local day.
        assertEquals(60, kidsMinutesOfDay(midnight, 60))
        assertEquals(0, kidsMinutesOfDay(midnight - hour, 60))
        assertEquals("2026-01-15", kidsDayKey(midnight - hour, 60))
        assertEquals("2026-01-16", kidsDayKey(midnight + 23 * hour, 60))
        // UTC-5: 19:00 UTC is still 14:00 local on the same day.
        assertEquals(14 * 60, kidsMinutesOfDay(midnight + 19 * hour, -5 * 60))
        assertEquals("2026-01-15", kidsDayKey(midnight + 19 * hour, -5 * 60))
    }

    @Test
    fun dayKeyMatchesCalendarDaysAcrossYears() {
        assertEquals("2025-12-31", kidsDayKey(epochOf(2026, 1, 1) - 1, 0))
        assertEquals("2026-03-01", kidsDayKey(epochOf(2026, 3, 1), 0))
        assertEquals("2024-02-29", kidsDayKey(epochOf(2024, 2, 29), 0))
        assertEquals("2026-01-01", kidsDayKey(epochOf(2026, 1, 1), 0))
    }

    /** Days from the epoch, so a date can be named instead of trusting a hand-computed ms value. */
    private fun epochOf(year: Int, month: Int, day: Int): Long {
        var days = 0L
        var y = 1970
        while (y < year) { days += if (isLeap(y)) 366 else 365; y++ }
        var m = 1
        while (m < month) { days += daysInMonth(y, m); m++ }
        return (days + day - 1) * 86_400_000L
    }

    private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    private fun daysInMonth(y: Int, m: Int) = when (m) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if (isLeap(y)) 29 else 28
    }

    // ---- bedtime windows ----

    @Test
    fun overnightBedtimeWrapsMidnight() {
        val bedtime = BedtimeWindow(20 * 60, 7 * 60)
        assertTrue(bedtime.isOvernight)
        assertTrue(bedtime.contains(20 * 60))
        assertTrue(bedtime.contains(23 * 60 + 30))
        assertTrue(bedtime.contains(0))
        assertTrue(bedtime.contains(2 * 60))
        assertTrue(bedtime.contains(7 * 60 - 1))
        assertFalse(bedtime.contains(7 * 60))
        assertFalse(bedtime.contains(19 * 60 + 59))
        assertFalse(bedtime.contains(12 * 60))
    }

    @Test
    fun daytimeBedtimeDoesNotWrap() {
        val bedtime = BedtimeWindow(13 * 60, 14 * 60)
        assertFalse(bedtime.isOvernight)
        assertTrue(bedtime.contains(13 * 60))
        assertTrue(bedtime.contains(13 * 60 + 59))
        assertFalse(bedtime.contains(14 * 60))
        assertFalse(bedtime.contains(23 * 60))
    }

    @Test
    fun minutesUntilBedtimeCrossesMidnight() {
        val bedtime = BedtimeWindow(20 * 60, 7 * 60)
        assertEquals(0, bedtime.minutesUntilStart(23 * 60))
        assertEquals(0, bedtime.minutesUntilStart(3 * 60))
        assertEquals(60, bedtime.minutesUntilStart(19 * 60))
        assertEquals(1, bedtime.minutesUntilStart(19 * 60 + 59))
        assertEquals(13 * 60, bedtime.minutesUntilStart(7 * 60))
        assertEquals(59, bedtime.minutesUntilStart(19 * 60 + 1))
    }

    @Test
    fun bedtimeEncodesAndDecodes() {
        val bedtime = BedtimeWindow(20 * 60, 7 * 60)
        assertEquals("20:00-07:00", bedtime.encode())
        assertEquals(bedtime, BedtimeWindow.decode("20:00-07:00"))
        assertEquals(BedtimeWindow(9 * 60 + 5, 10 * 60), BedtimeWindow.decode("09:05-10:00"))
        assertNull(BedtimeWindow.decode(KidsLimits.OFF))
        assertNull(BedtimeWindow.decode(""))
        assertNull(BedtimeWindow.decode(null))
        assertNull(BedtimeWindow.decode("20:00"))
        assertNull(BedtimeWindow.decode("24:00-07:00"))
        assertNull(BedtimeWindow.decode("20:00-07"))
        assertNull(BedtimeWindow.decode("nonsense"))
    }

    // ---- the watching clock ----

    @Test
    fun dailyLimitWarnsFiveMinutesBeforeItBites() {
        val guard = KidsTimeGuard(KidsLimits(dailyLimitMinutes = 30), 25 * minute, 0L, clock = { midnight })
        assertEquals(5 * minute, guard.remainingMs)
        assertEquals(KidsTimeEvent.Warn(5 * minute, KidsTimeReason.DAILY_LIMIT), guard.evaluate())
        val almost = KidsTimeGuard(KidsLimits(dailyLimitMinutes = 30), 24 * minute, 0L, clock = { midnight })
        assertEquals(KidsTimeEvent.None, almost.evaluate())
        val spent = KidsTimeGuard(KidsLimits(dailyLimitMinutes = 30), 30 * minute, 0L, clock = { midnight })
        assertEquals(0L, spent.remainingMs)
        assertEquals(KidsTimeEvent.Blocked(KidsTimeReason.DAILY_LIMIT), spent.evaluate())
    }

    @Test
    fun noDailyLimitMeansNoDailyLimitBlock() {
        val guard = KidsTimeGuard(KidsLimits(), 10 * hour, 0L, clock = { midnight })
        assertNull(guard.remainingMs)
        assertEquals(KidsTimeEvent.None, guard.evaluate())
    }

    @Test
    fun onlyPlayingTimeIsCountedAndLongGapsAreNot() {
        var now = midnight
        val guard = KidsTimeGuard(KidsLimits(dailyLimitMinutes = 30), 0L, 0L, clock = { now })
        now += 10 * minute
        assertEquals(KidsTimeEvent.None, guard.tick(playing = false))
        assertEquals(0L, guard.usedMs)
        now += 1_000L
        guard.tick(playing = true)
        assertEquals(1_000L, guard.usedMs)
        assertEquals(1_000L, guard.pendingMs)
        // The app was backgrounded for an hour: at most one tick's worth is charged.
        now += 60 * minute
        guard.tick(playing = true)
        assertEquals(3_000L, guard.usedMs)
        assertEquals(3_000L, guard.takePendingMs())
        assertEquals(0L, guard.pendingMs)
    }

    @Test
    fun bedtimeBlocksEvenWithAllowanceLeft() {
        val limits = KidsLimits(dailyLimitMinutes = 180, bedtime = BedtimeWindow(20 * 60, 7 * 60))
        assertEquals(
            KidsTimeEvent.Blocked(KidsTimeReason.BEDTIME),
            KidsTimeGuard(limits, 0L, 0L, clock = { at(21) }).evaluate(),
        )
        assertEquals(
            KidsTimeEvent.Warn(5 * minute, KidsTimeReason.BEDTIME),
            KidsTimeGuard(limits, 0L, 0L, clock = { at(19, 55) }).evaluate(),
        )
        assertEquals(
            KidsTimeEvent.None,
            KidsTimeGuard(limits, 0L, 0L, clock = { at(19, 54) }).evaluate(),
        )
    }

    @Test
    fun bedtimeEndsAtMidnightRollover() {
        val limits = KidsLimits(dailyLimitMinutes = 180, bedtime = BedtimeWindow(20 * 60, 7 * 60))
        val guard = KidsTimeGuard(limits, 0L, 0L, clock = { at(23, 30) })
        assertEquals(KidsTimeEvent.Blocked(KidsTimeReason.BEDTIME), guard.evaluate())
        assertEquals(KidsTimeEvent.Blocked(KidsTimeReason.BEDTIME), guard.evaluate(at(6, 59)))
        assertEquals(KidsTimeEvent.None, guard.evaluate(at(7, 0)))
        // The daily allowance resets with the local day, not with the bedtime window.
        assertEquals("2026-01-15", kidsDayKey(at(23, 30), 0))
        assertEquals("2026-01-16", kidsDayKey(at(7, 0, dayOffset = 1), 0))
    }

    @Test
    fun bedtimeWinsOverTheDailyLimitNotice() {
        val limits = KidsLimits(dailyLimitMinutes = 30, bedtime = BedtimeWindow(20 * 60, 7 * 60))
        val guard = KidsTimeGuard(limits, 25 * minute, 0L, clock = { at(19, 57) })
        assertEquals(KidsTimeEvent.Warn(3 * minute, KidsTimeReason.BEDTIME), guard.evaluate())
    }

    @Test
    fun grantedMinutesExtendTodayOnly() {
        val guard = KidsTimeGuard(KidsLimits(dailyLimitMinutes = 30), 30 * minute, 0L, clock = { midnight })
        assertEquals(KidsTimeEvent.Blocked(KidsTimeReason.DAILY_LIMIT), guard.evaluate())
        guard.grantExtraMinutes(30)
        assertEquals(30 * minute, guard.remainingMs)
        assertEquals(KidsTimeEvent.None, guard.evaluate())
        guard.grantExtraMinutes(30)
        assertEquals(60 * minute, guard.remainingMs)
        assertEquals(60 * minute, guard.grantedMs)
    }

    // ---- storage ----

    private val io = UnconfinedTestDispatcher()
    private var now = midnight
    private val clock = Clock { now }
    private var currentId = ProfileRepository.DEFAULT_ID
    private val activeIds = MutableStateFlow(currentId)

    private fun fresh(): Pair<UserDataRepositoryImpl, KidsUsageStore> {
        val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
        val user = UserDataRepositoryImpl(db, io, clock, profileId = { currentId }, profileIds = activeIds)
        return user to KidsUsageStore(user, clock, offsetMinutes = { 0 })
    }

    @Test
    fun limitsAndUsageFollowTheActiveProfile() = runTest {
        val (user, store) = fresh()
        assertEquals(KidsLimits(), store.limitsNow())
        user.putSetting(KidsLimits.DAILY_KEY, "60")
        user.putSetting(KidsLimits.BEDTIME_KEY, "20:00-07:00")
        assertEquals(KidsLimits(60, BedtimeWindow(20 * 60, 7 * 60)), store.limitsNow())

        store.addWatchedMs(90 * second)
        assertEquals(90 * second, store.usageToday().usedMs)
        assertEquals(60 * minute - 90 * second, store.remainingMs())

        // A second profile starts clean: usage and limits are per profile, not global.
        currentId = "kid-2"; activeIds.value = currentId
        assertEquals(KidsLimits(), store.limitsNow())
        assertEquals(0L, store.usageToday().usedMs)
        assertNull(store.remainingMs())

        currentId = ProfileRepository.DEFAULT_ID; activeIds.value = currentId
        assertEquals(90 * second, store.usageToday().usedMs)
    }

    @Test
    fun usageResetsWhenTheLocalDayRollsOver() = runTest {
        val (_, store) = fresh()
        store.addWatchedMs(20 * minute)
        assertEquals("2026-01-15", store.usageToday().day)
        now = at(0, 1, dayOffset = 1)
        val today = store.usageToday()
        assertEquals("2026-01-16", today.day)
        assertEquals(0L, today.usedMs)
        store.addWatchedMs(5 * minute)
        assertEquals(5 * minute, store.usageToday().usedMs)
    }

    @Test
    fun grantedMinutesPersistAcrossSessions() = runTest {
        val (user, store) = fresh()
        user.putSetting(KidsLimits.DAILY_KEY, "30")
        store.addWatchedMs(30 * minute)
        assertEquals(0L, store.remainingMs())
        store.grantExtraMinutes(30)
        assertEquals(30 * minute, store.remainingMs())
        assertEquals(30 * minute, store.usageToday().grantedMs)
        // A fresh store over the same rows still sees the extension.
        assertEquals(30 * minute, KidsUsageStore(user, clock).remainingMs())
    }

    @Test
    fun malformedUsageRowsAreIgnored() = runTest {
        val (user, store) = fresh()
        user.putSetting(KidsLimits.USAGE_KEY, "not json")
        assertEquals(0L, store.usageToday().usedMs)
        store.addWatchedMs(10 * second)
        assertEquals(10 * second, store.usageToday().usedMs)
    }

    // ---- task 111 M4: monotonic-anchored day rollover ----

    private suspend fun monotonicStore(offset: () -> Int, elapsed: () -> Long): KidsUsageStore {
        val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
        val user = UserDataRepositoryImpl(db, io, clock, profileId = { currentId }, profileIds = activeIds)
        user.putSetting(KidsLimits.DAILY_KEY, "60")
        return KidsUsageStore(user, clock, offsetMinutes = offset, elapsedMs = elapsed)
    }

    @Test
    fun aTimezoneJumpCarriesTheAllowanceInsteadOfResettingIt() = runTest {
        var offset = 0
        var mono = 100_000L
        val store = monotonicStore({ offset }, { mono })
        now = at(23, 0, 0)
        store.addWatchedMs(20 * minute)
        assertEquals("2026-01-15", store.usageToday().day)
        assertEquals(20 * minute, store.usageToday().usedMs)
        // Travel east: the offset jumps +5h so the day key moves to the 16th, but only a minute of
        // real (monotonic) time has passed. The allowance carries into the new day.
        now += minute
        mono += minute
        offset = 5 * 60
        val today = store.usageToday()
        assertEquals("2026-01-16", today.day)
        assertEquals(20 * minute, today.usedMs)
        assertEquals(60 * minute - 20 * minute, store.remainingMs())
    }

    @Test
    fun aManualClockJumpForwardDoesNotResetTheAllowance() = runTest {
        var mono = 100_000L
        val store = monotonicStore({ 0 }, { mono })
        now = at(12, 0, 0)
        store.addWatchedMs(20 * minute)
        // Someone sets the wall clock two days ahead while the device has only been running a minute.
        now += 2 * 24L * hour
        mono += minute
        val today = store.usageToday()
        assertEquals(20 * minute, today.usedMs)
        assertEquals(60 * minute - 20 * minute, store.remainingMs())
    }

    @Test
    fun aRealMidnightRolloverStillResetsTheAllowance() = runTest {
        var mono = 100_000L
        val store = monotonicStore({ 0 }, { mono })
        now = at(23, 59, 0)
        store.addWatchedMs(20 * minute)
        // Local time and the monotonic clock advance together across midnight: a genuine new day.
        now = at(0, 1, dayOffset = 1)
        mono += 2 * minute
        val today = store.usageToday()
        assertEquals("2026-01-16", today.day)
        assertEquals(0L, today.usedMs)
    }

    @Test
    fun aRebootAcrossMidnightResetsTheAllowance() = runTest {
        var mono = 100_000L
        val store = monotonicStore({ 0 }, { mono })
        now = at(23, 59, 0)
        store.addWatchedMs(20 * minute)
        // The device rebooted (monotonic clock reset) on a new wall day: trust the wall clock.
        now = at(9, 0, dayOffset = 1)
        mono = 5_000L
        val today = store.usageToday()
        assertEquals("2026-01-16", today.day)
        assertEquals(0L, today.usedMs)
    }

    @Test
    fun controllerFlushesPendingTimeToTheDayItWasWatchedOn() = runTest {
        val (user, store) = fresh()
        user.putSetting(KidsLimits.DAILY_KEY, "60")
        val controller = KidsTimeController(store, flushMs = 1_000_000L)
        controller.reload()
        repeat(10) {
            now += second
            controller.tick(playing = true)
        }
        // The clock jumps to a new day before the pending time is flushed.
        now = at(0, 5, dayOffset = 1)
        controller.flushPending()
        val row = KidsDayUsage.decode(user.setting(KidsLimits.USAGE_KEY).first())!!
        assertEquals("2026-01-15", row.day)
        assertEquals(10 * second, row.usedMs)
        assertEquals(0L, store.usageToday().usedMs)
    }

    @Test
    fun controllerCountsPlayingTimeAndFlushesItToStorage() = runTest {
        val (user, store) = fresh()
        user.putSetting(KidsLimits.DAILY_KEY, "1")
        val controller = KidsTimeController(store, flushMs = 1_000L)
        controller.reload()
        assertEquals(60_000L, controller.state.value.remainingMs)

        now += 1_000L
        controller.tick(playing = true)
        assertEquals(1_000L, controller.state.value.usedMs)
        assertEquals(59_000L, controller.state.value.remainingMs)
        assertEquals(1_000L, store.usageToday().usedMs)

        now += 1_000L
        controller.tick(playing = false)
        assertEquals(1_000L, controller.state.value.usedMs)
        assertEquals(1_000L, store.usageToday().usedMs)

        repeat(59) {
            now += 1_000L
            controller.tick(playing = true)
        }
        assertEquals(60_000L, controller.state.value.usedMs)
        assertEquals(0L, controller.state.value.remainingMs)
        assertEquals(KidsTimeReason.DAILY_LIMIT, controller.state.value.blocked)
        assertEquals(60_000L, store.usageToday().usedMs)
    }

    @Test
    fun controllerWarnsFiveMinutesBeforeTheAllowanceRunsOut() = runTest {
        val (user, store) = fresh()
        user.putSetting(KidsLimits.DAILY_KEY, "10")
        user.putSetting(KidsLimits.USAGE_KEY, KidsDayUsage(kidsDayKey(midnight, 0), usedMs = 5 * minute).encode())
        val controller = KidsTimeController(store, flushMs = 1_000_000L)
        controller.reload()
        assertEquals(5 * minute, controller.state.value.warningMs)
        assertNull(controller.state.value.blocked)
        assertEquals(5 * minute, controller.state.value.remainingMs)
    }

    @Test
    fun controllerBlocksAndUnblocksOnAGrant() = runTest {
        val (user, store) = fresh()
        user.putSetting(KidsLimits.DAILY_KEY, "10")
        user.putSetting(KidsLimits.USAGE_KEY, KidsDayUsage(kidsDayKey(midnight, 0), usedMs = 10 * minute).encode())
        val controller = KidsTimeController(store, flushMs = 1_000_000L)
        controller.reload()
        assertEquals(KidsTimeReason.DAILY_LIMIT, controller.state.value.blocked)
        assertEquals(0L, controller.state.value.remainingMs)

        controller.grantExtraMinutes()
        assertNull(controller.state.value.blocked)
        assertEquals(30 * minute, controller.state.value.remainingMs)
        assertEquals(30 * minute, store.usageToday().grantedMs)
    }

    @Test
    fun controllerRebuildsWhenTheLocalDayChanges() = runTest {
        val (user, store) = fresh()
        user.putSetting(KidsLimits.DAILY_KEY, "30")
        user.putSetting(KidsLimits.USAGE_KEY, KidsDayUsage(kidsDayKey(midnight, 0), usedMs = 30 * minute).encode())
        val controller = KidsTimeController(store, flushMs = 1_000_000L)
        controller.reload()
        assertEquals(KidsTimeReason.DAILY_LIMIT, controller.state.value.blocked)

        now = at(0, 1, dayOffset = 1)
        controller.tick(playing = false)
        assertNull(controller.state.value.blocked)
        assertEquals(30 * minute, controller.state.value.remainingMs)
        assertEquals(0L, controller.state.value.usedMs)
    }

    // ---- profile administration ----

    @Test
    fun limitsAreStoredPerProfileAndClearedWhenOff() = runTest {
        val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
        val profiles = ProfileRepositoryImpl(db, io, clock, newId = { "p" })
        profiles.ensureDefault()
        val kid = profiles.create("Kids", 1, true)

        profiles.setLimits(kid.id, KidsLimits(60, BedtimeWindow(20 * 60, 7 * 60)))
        assertEquals(KidsLimits(60, BedtimeWindow(20 * 60, 7 * 60)), profiles.limits(kid.id))
        assertEquals(KidsLimits(), profiles.limits(ProfileRepository.DEFAULT_ID))

        profiles.setLimits(kid.id, KidsLimits(dailyLimitMinutes = 0, bedtime = BedtimeWindow(19 * 60, 20 * 60)))
        assertEquals(KidsLimits(0, BedtimeWindow(19 * 60, 20 * 60)), profiles.limits(kid.id))

        profiles.setLimits(kid.id, KidsLimits())
        assertEquals(KidsLimits(), profiles.limits(kid.id))
        assertNull(db.userDataQueries.getSetting("kids_daily_limit::p").executeAsOneOrNull())
        assertNull(db.userDataQueries.getSetting("kids_bedtime::p").executeAsOneOrNull())
    }

    @Test
    fun deletingAProfileRemovesItsKidsSettings() = runTest {
        val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
        val profiles = ProfileRepositoryImpl(db, io, clock, newId = { "kid" })
        profiles.ensureDefault()
        val kid = profiles.create("Kids", 1, true)
        profiles.setLimits(kid.id, KidsLimits(30, BedtimeWindow(20 * 60, 7 * 60)))
        assertTrue(profiles.delete(kid.id))
        assertNull(db.userDataQueries.getSetting("kids_daily_limit::kid").executeAsOneOrNull())
        assertNull(db.userDataQueries.getSetting("kids_bedtime::kid").executeAsOneOrNull())
    }
}
