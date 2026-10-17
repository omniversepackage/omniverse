package com.yodesla.omniverse.core.data

/** Device-wide update policies. Daily time is local wall-clock time; Android may defer background jobs. */
data class AutoRefreshPolicy(val mode: String = "hours", val hours: Int = 6, val minute: Int = 180) {
    fun encode() = "$mode|$hours|$minute"
    companion object {
        const val SOURCES = "auto_refresh_sources"
        const val DISCOVERY = "auto_refresh_discovery"
        fun parse(raw: String?): AutoRefreshPolicy {
            val p = raw.orEmpty().split('|')
            return AutoRefreshPolicy(p.getOrNull(0)?.takeIf { it in setOf("off", "hours", "daily", "startup") } ?: "hours",
                (p.getOrNull(1)?.toIntOrNull() ?: 6).coerceIn(1, 168),
                (p.getOrNull(2)?.toIntOrNull() ?: 180).coerceIn(0, 1439))
        }
    }
}
