package com.yodesla.omniverse.player

/** What one [SleepTimer.tick] decided (Task 77). */
sealed interface SleepTimerEvent {
    data object None : SleepTimerEvent

    /** Inside the 60 s warning window: show the "press OK to keep watching" card. */
    data class Warn(val remainingMs: Long) : SleepTimerEvent

    /** The deadline passed: the player must stop and exit. The timer disarms itself. */
    data object Fire : SleepTimerEvent
}

/**
 * Per-playback-session sleep timer (Task 77). Armed with a minute choice or a remaining-time
 * choice ("end of this programme"), it warns 60 s before firing, then fires once and disarms.
 * Pure countdown math with an injected clock so it is unit-testable; the player screens own when
 * it ticks and what firing does. Never persisted — every new playback session starts at Off.
 */
class SleepTimer(private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        /** The "keep watching" card appears this long before firing. */
        const val WARNING_MS = 60_000L
    }

    private var deadlineMs: Long? = null

    val isArmed: Boolean get() = deadlineMs != null
    val remainingMs: Long? get() = deadlineMs?.minus(clock())

    /** Arm for whole [minutes] from now (the 15/30/60/90 choices). */
    fun armMinutes(minutes: Int) {
        deadlineMs = clock() + minutes.coerceAtLeast(1) * 60_000L
    }

    /** Arm for a known remaining duration, e.g. until the current EPG programme ends. */
    fun armRemaining(remainingMs: Long) {
        deadlineMs = clock() + remainingMs.coerceAtLeast(0L)
    }

    /** Back to Off — also what "press OK to keep watching" does. */
    fun cancel() {
        deadlineMs = null
    }

    /** Call once per UI tick. [Warn] repeats while inside the window; [Fire] happens once. */
    fun tick(): SleepTimerEvent {
        val deadline = deadlineMs ?: return SleepTimerEvent.None
        val remaining = deadline - clock()
        if (remaining <= 0L) {
            deadlineMs = null
            return SleepTimerEvent.Fire
        }
        if (remaining <= WARNING_MS) return SleepTimerEvent.Warn(remaining)
        return SleepTimerEvent.None
    }
}
