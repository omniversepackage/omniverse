package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.data.AutoRefreshPolicy
import java.time.*
import kotlin.test.*

class AutoRefreshCoordinatorTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    @Test fun dailyRunsAfterSelectedLocalTimeOnlyOncePerDay() {
        val p = AutoRefreshPolicy("daily", minute = 8 * 60 + 30)
        val last = at("2026-10-16T08:31:00")
        assertFalse(AutoRefreshCoordinator.due(p, last, at("2026-10-17T08:29:00"), false, zone))
        assertTrue(AutoRefreshCoordinator.due(p, last, at("2026-10-17T08:30:00"), false, zone))
        assertFalse(AutoRefreshCoordinator.due(p, at("2026-10-17T08:31:00"), at("2026-10-17T09:00:00"), true, zone))
    }
    @Test fun dailyTimeStaysAtChosenWallClockAcrossDaylightSaving() {
        val p = AutoRefreshPolicy("daily", minute = 8 * 60 + 30)
        assertFalse(AutoRefreshCoordinator.due(p, at("2026-03-07T08:31:00"), at("2026-03-08T08:29:00"), false, zone))
        assertTrue(AutoRefreshCoordinator.due(p, at("2026-03-07T08:31:00"), at("2026-03-08T08:30:00"), false, zone))
    }
    @Test fun intervalStartupAndOffAreIndependent() {
        val now = at("2026-10-17T10:00:00")
        assertFalse(AutoRefreshCoordinator.due(AutoRefreshPolicy("off"), 0, now, true, zone))
        assertFalse(AutoRefreshCoordinator.due(AutoRefreshPolicy("startup"), 0, now, false, zone))
        assertTrue(AutoRefreshCoordinator.due(AutoRefreshPolicy("startup"), now, now, true, zone))
        assertFalse(AutoRefreshCoordinator.due(AutoRefreshPolicy(hours = 12), now - 11 * 3_600_000, now, true, zone))
        assertTrue(AutoRefreshCoordinator.due(AutoRefreshPolicy(hours = 12), now - 12 * 3_600_000, now, false, zone))
    }
}
