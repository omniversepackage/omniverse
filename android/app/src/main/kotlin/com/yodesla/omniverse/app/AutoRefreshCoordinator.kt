package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.data.AutoRefreshPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.TimeZone

/** Shared startup/background gate. Manual refreshes remain available regardless of policy. */
class AutoRefreshCoordinator(private val graph: AppGraph) {
    private val lock = Mutex()
    suspend fun run(appStart: Boolean = false) = lock.withLock {
        for (key in listOf(AutoRefreshPolicy.DISCOVERY, AutoRefreshPolicy.SOURCES)) {
            val p = AutoRefreshPolicy.parse(graph.userData.setting(key).first())
            val now = graph.clock.nowMs()
            val last = graph.userData.setting("${key}_attempt").first()?.toLongOrNull() ?: 0L
            if (!due(p, last, now, appStart)) continue
            // Persist attempt before work: failed providers cannot cause a launch/retry storm.
            graph.userData.putSetting("${key}_attempt", now.toString())
            if (key == AutoRefreshPolicy.SOURCES) {
                // Explicit user timing overrides the engine's older fixed stale intervals.
                graph.syncAllStale(forceAll = true)
            } else graph.discovery.refresh()
        }
    }
    companion object {
        fun due(p: AutoRefreshPolicy, last: Long, now: Long, appStart: Boolean, zone: TimeZone = TimeZone.getDefault()): Boolean {
            if (p.mode == "off") return false
            if (p.mode == "startup") return appStart
            if (last == 0L) return true
            if (now < last) return true // clock corrected backwards
            if (p.mode == "hours") return now - last >= p.hours * 3_600_000L
            val target = Calendar.getInstance(zone).apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, p.minute / 60)
                set(Calendar.MINUTE, p.minute % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (now < timeInMillis) add(Calendar.DAY_OF_MONTH, -1)
            }
            return last < target.timeInMillis
        }
    }
}
