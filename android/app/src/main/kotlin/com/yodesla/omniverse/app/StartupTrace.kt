package com.yodesla.omniverse.app

import android.os.SystemClock
import android.util.Log

/**
 * Task 101: cold-start timing. Every startup milestone logs one line carrying the milliseconds
 * elapsed since the process began, so `adb logcat -s OmniverseStartup` reads as a startup timeline:
 * process start → Application.onCreate → first frame → Home rows visible → startup sync start.
 *
 * [elapsedRealtime] is monotonic and keeps counting through deep sleep, so the numbers are
 * comparable across a cold start, a warm start and a profile-switch restart.
 */
object StartupTrace {
    const val TAG = "OmniverseStartup"

    /** When this process reached [StartupTrace] (monotonic). */
    val processStartMs: Long = SystemClock.elapsedRealtime()

    @Volatile private var firstFrameLoggedMs: Long? = null

    /** Milliseconds since the process start. */
    fun elapsedMs(): Long = SystemClock.elapsedRealtime() - processStartMs

    /** One milestone line: `+312 ms  MainActivity.onCreate`. Also lands in the [AppLog] ring (task 104). */
    fun mark(event: String) {
        val line = "+${elapsedMs()} ms  $event"
        Log.i(TAG, line)
        AppLog.log("startup $line")
    }

    /** The first Compose frame was drawn. Logged once per process. */
    fun firstFrame() {
        if (firstFrameLoggedMs != null) return
        firstFrameLoggedMs = elapsedMs()
        Log.i(TAG, "+$firstFrameLoggedMs ms  first frame drawn")
        AppLog.log("startup +$firstFrameLoggedMs ms  first frame drawn")
    }

    /** Milliseconds from process start to the first frame, null until it happened. */
    fun firstFrameMs(): Long? = firstFrameLoggedMs
}
