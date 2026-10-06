package com.yodesla.omniverse.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure-JVM tests for the task 104 log ring buffer: capacity, order, scrubbing, one line per event. */
class AppLogTest {

    @Test fun keepsTheLastFiftyLinesOldestFirst() {
        AppLog.clear()
        for (i in 1..60) AppLog.log("event $i")
        val lines = AppLog.snapshot()
        assertEquals(AppLog.CAPACITY, lines.size)
        assertEquals("event 11", lines.first())
        assertEquals("event 60", lines.last())
    }

    @Test fun credentialsAreScrubbedOnTheWayIn() {
        AppLog.clear()
        AppLog.log("GET http://srv/live/abc123/s3cr3t/1234.ts failed")
        AppLog.log("plex http://h:32400/library?X-Plex-Token=SECRET123")
        AppLog.log("proxy alice:s3cret@host refused")
        AppLog.log("password=hunter2 rejected for user=kory")
        val text = AppLog.snapshot().joinToString("\n")
        assertTrue("/live/***/***/" in text)
        assertTrue("X-Plex-Token=***" in text)
        assertTrue("***:***@host" in text)
        assertTrue("password=***" in text)
        assertFalse("abc123" in text)
        assertFalse("s3cr3t" in text)
        assertFalse("SECRET123" in text)
        assertFalse("hunter2" in text)
    }

    @Test fun oneEventIsExactlyOneRingSlot() {
        AppLog.clear()
        AppLog.log("two\nlines\r\nhere")
        assertEquals(1, AppLog.snapshot().size)
        assertEquals("two lines here", AppLog.snapshot().single())
    }

    @Test fun blankAndOverlongEventsAreHandled() {
        AppLog.clear()
        AppLog.log("   ")
        assertEquals(0, AppLog.snapshot().size)
        AppLog.log("x".repeat(AppLog.MAX_LINE_CHARS + 500))
        assertEquals(AppLog.MAX_LINE_CHARS, AppLog.snapshot().single().length)
    }
}
