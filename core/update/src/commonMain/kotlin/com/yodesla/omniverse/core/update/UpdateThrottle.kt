package com.yodesla.omniverse.core.update

/**
 * Pure clock helper so the app checks for updates at most once a day.
 * The caller stores the timestamp of each check (e.g. a user-data setting) and passes it back here.
 */
class UpdateThrottle(
    private val nowMs: () -> Long,
    private val lastCheckMs: () -> Long?,
    private val intervalMs: Long = DAY_MS,
) {
    fun due(): Boolean {
        val last = lastCheckMs() ?: return true
        return nowMs() - last >= intervalMs
    }

    companion object {
        const val DAY_MS: Long = 24L * 60L * 60L * 1000L
    }
}
