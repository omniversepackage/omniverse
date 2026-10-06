package com.yodesla.omniverse.core.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateThrottleTest {
    @Test
    fun firstCheckIsDueWhenNoTimestampStored() {
        val throttle = UpdateThrottle(nowMs = { 0L }, lastCheckMs = { null })
        assertTrue(throttle.due())
    }

    @Test
    fun recentCheckIsNotDue() {
        val throttle = UpdateThrottle(nowMs = { 1_000L }, lastCheckMs = { 0L })
        assertFalse(throttle.due())
    }

    @Test
    fun dueOnceIntervalElapsed() {
        val throttle = UpdateThrottle(nowMs = { 86_400_000L }, lastCheckMs = { 0L })
        assertTrue(throttle.due())
    }

    @Test
    fun respectsCustomInterval() {
        val notDue = UpdateThrottle(nowMs = { 900L }, lastCheckMs = { 0L }, intervalMs = 1_000L)
        assertFalse(notDue.due())
        val due = UpdateThrottle(nowMs = { 1_000L }, lastCheckMs = { 0L }, intervalMs = 1_000L)
        assertTrue(due.due())
    }
}
