package com.yodesla.omniverse.feature.live.guide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task 85: the clamping rules behind guide time jumps (media keys, day strip, "Now"). */
class GuideTimeTest {
    private val day0 = dayStartMs(1_700_000_000_000L)
    private val day1 = nextDayStartMs(day0)
    private val day2 = nextDayStartMs(day1)
    private val day3 = nextDayStartMs(day2)
    private val noon = day0 + 12 * HOUR_MS

    @Test
    fun floorIsNowMinusTheWidestCatchupWindow() {
        assertEquals(noon - 3 * DAY_MS, catchupFloorMs(noon, 3))
        assertEquals(noon, catchupFloorMs(noon, 0))
        assertEquals(noon, catchupFloorMs(noon, -1))
    }

    @Test
    fun ceilingStopsAtTheLastProgrammeOrSevenDaysAhead() {
        assertEquals(noon + 3 * DAY_MS, guideCeilingMs(noon, noon + 3 * DAY_MS))
        assertEquals(noon + MAX_AHEAD_MS, guideCeilingMs(noon, 0L))
        assertEquals(noon + MAX_AHEAD_MS, guideCeilingMs(noon, noon + 30 * DAY_MS))
    }

    @Test
    fun jumpClampsAtBothEdges() {
        val floor = noon - 2 * DAY_MS
        val ceiling = noon + DAY_MS
        assertEquals(noon + GUIDE_JUMP_MS, clampJump(noon, GUIDE_JUMP_MS, floor, ceiling))
        assertEquals(noon - GUIDE_JUMP_MS, clampJump(noon, -GUIDE_JUMP_MS, floor, ceiling))
        // Already at an edge: the jump stays put instead of walking past the guide's data.
        assertEquals(floor, clampJump(floor, -GUIDE_JUMP_MS, floor, ceiling))
        assertEquals(ceiling, clampJump(ceiling, GUIDE_JUMP_MS, floor, ceiling))
        assertEquals(floor, clampJump(noon - 2 * DAY_MS - HOUR_MS, -GUIDE_JUMP_MS, floor, ceiling))
    }

    @Test
    fun primeTimeIsSixPmLocal() {
        assertEquals(day0 + 18 * HOUR_MS, primeTimeMs(day0, noon - 3 * DAY_MS, day3 + 12 * HOUR_MS))
    }

    @Test
    fun primeTimeThatAlreadyPassedTodayFallsBackToTheFloor() {
        val late = day0 + 21 * HOUR_MS
        assertEquals(late, primeTimeMs(day0, late, day3 + 12 * HOUR_MS))
    }

    @Test
    fun primeTimePastTheLastProgrammeStopsAtTheGuideEdge() {
        val end = day0 + 17 * HOUR_MS
        assertEquals(end, primeTimeMs(day0, noon - DAY_MS, end))
    }

    @Test
    fun daysRunFromTodayToTheDayOfTheLastProgramme() {
        assertEquals(listOf(day0, day1, day2, day3), guideDayStarts(noon, day3 + 5 * HOUR_MS))
        assertEquals(listOf(day0, day1), guideDayStarts(noon, day1 + 20 * HOUR_MS))
        assertEquals(15, guideDayStarts(noon, day0 + 60 * DAY_MS).size)
    }

    @Test
    fun aGuideWithNoDataStillOffersToday() {
        assertEquals(listOf(day0), guideDayStarts(noon, 0L))
        assertEquals(listOf(day0), guideDayStarts(noon, day0 - 1L))
    }

    @Test
    fun labelsAreTodayTomorrowThenTheWeekday() {
        assertEquals("Today", dayLabel(day0, day0, "Today", "Tomorrow"))
        assertEquals("Tomorrow", dayLabel(day1, day0, "Today", "Tomorrow"))
        val weekday = weekdayLabel(day2)
        assertTrue(weekday.isNotBlank())
        assertEquals(weekday, dayLabel(day2, day0, "Today", "Tomorrow"))
    }

    @Test
    fun dayIndexOfFollowsTheFocusedTime() {
        val days = listOf(day0, day1, day2)
        assertEquals(0, dayIndexOf(noon, days))
        assertEquals(1, dayIndexOf(day1 + 30 * 60_000L, days))
        assertEquals(2, dayIndexOf(day2 + 23 * HOUR_MS, days))
        assertEquals(-1, dayIndexOf(nextDayStartMs(day2), days))
    }

    @Test
    fun atNowCoversOneMinute() {
        assertTrue(isAtNow(noon + 59_000L, noon))
        assertTrue(isAtNow(noon - 59_000L, noon))
        assertFalse(isAtNow(noon + AT_NOW_MS, noon))
    }
}
