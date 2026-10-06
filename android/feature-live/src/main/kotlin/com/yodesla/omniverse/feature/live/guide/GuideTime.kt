package com.yodesla.omniverse.feature.live.guide

import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/*
 * Task 85 — the pure time math behind jumping around the guide: ±2 h media-key jumps, the header's
 * day strip, and the prime-time landing point. Kept free of Compose and of the DB so the clamping
 * rules (never before the catch-up floor, never past the last programme we actually have) are
 * testable on their own. All values are epoch milliseconds; day boundaries use the device's local
 * time zone, which is what the guide's own clock labels use.
 */

const val HOUR_MS = 3_600_000L
const val DAY_MS = 24 * HOUR_MS

/** One media-key press moves the guide by two hours. */
const val GUIDE_JUMP_MS = 2 * HOUR_MS

/** Prime time: the hour a day-strip selection lands on. */
const val PRIME_TIME_HOUR = 18

/** A jump is "back at the live edge" when it is within a minute of now. */
const val AT_NOW_MS = 60_000L

/** How far ahead the guide will scroll when the provider's data does not say otherwise. */
const val MAX_AHEAD_MS = 7 * DAY_MS

/**
 * Earliest time the guide may show: now minus the widest catch-up window among the grid's channels.
 * Without catch-up data the guide has nothing to the left of "now", so the floor is now.
 */
fun catchupFloorMs(nowMs: Long, maxCatchupDays: Int): Long =
    if (maxCatchupDays <= 0) nowMs else nowMs - maxCatchupDays.toLong() * DAY_MS

/**
 * Latest time the guide may show: the end of the last programme actually stored for the source,
 * capped at [maxAheadMs] from now so a stale guide (data synced weeks ago) cannot scroll forever.
 */
fun guideCeilingMs(nowMs: Long, maxEndMs: Long, maxAheadMs: Long = MAX_AHEAD_MS): Long {
    val cap = nowMs + maxAheadMs
    return if (maxEndMs > 0L) minOf(cap, maxEndMs) else cap
}

/** Jump [deltaMs] from [fromMs], clamped into [floorMs, ceilingMs]. */
fun clampJump(fromMs: Long, deltaMs: Long, floorMs: Long, ceilingMs: Long): Long {
    val lo = minOf(floorMs, ceilingMs)
    return (fromMs + deltaMs).coerceIn(lo, ceilingMs)
}

/** Local midnight (epoch ms) of the day containing [ms]. */
fun dayStartMs(ms: Long): Long = calendar(ms).let { c ->
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    c.timeInMillis
}

/** Local midnight of the day after [dayStartMs] (a calendar step, so DST shifts cannot drift). */
fun nextDayStartMs(dayStartMs: Long): Long = calendar(dayStartMs).let { c ->
    c.add(Calendar.DAY_OF_MONTH, 1)
    c.timeInMillis
}

/**
 * Where a day-strip selection lands: 18:00 local on [dayStartMs], clamped into the window the
 * guide can actually show (today's prime time that has already passed falls back to the floor).
 */
fun primeTimeMs(dayStartMs: Long, floorMs: Long, ceilingMs: Long): Long =
    (dayStartMs + PRIME_TIME_HOUR * HOUR_MS).coerceIn(minOf(floorMs, ceilingMs), ceilingMs)

/**
 * Local midnights the guide has data for, starting at the day containing [nowMs] and stopping at
 * the day of [maxEndMs]. No data (maxEnd 0) still yields today, so the strip is never empty.
 */
fun guideDayStarts(nowMs: Long, maxEndMs: Long, maxDays: Int = 15): List<Long> {
    val first = dayStartMs(nowMs)
    val out = ArrayList<Long>(maxDays)
    var day = first
    while (out.size < maxDays && day <= maxEndMs) {
        out += day
        day = nextDayStartMs(day)
    }
    if (out.isEmpty()) out += first // guide empty or entirely in the past: today is still a valid target
    return out
}

/** "Today", "Tomorrow", then the local weekday name (the strip's compact labels). */
fun dayLabel(dayStartMs: Long, firstDayStartMs: Long, todayLabel: String, tomorrowLabel: String): String = when (dayStartMs) {
    firstDayStartMs -> todayLabel
    nextDayStartMs(firstDayStartMs) -> tomorrowLabel
    else -> weekdayLabel(dayStartMs)
}

/** Short local weekday name for a day ("Mon"); falls back to a plain day number if the locale has none. */
fun weekdayLabel(dayStartMs: Long): String {
    val symbols = DateFormatSymbols(Locale.getDefault()).shortWeekdays
    val name = symbols.getOrNull(calendar(dayStartMs).get(Calendar.DAY_OF_WEEK))
    return if (name.isNullOrBlank()) "Day ${calendar(dayStartMs).get(Calendar.DAY_OF_WEEK)}" else name
}

/** Index of [ms]'s day within [dayStarts], or -1 when it is outside the strip. */
fun dayIndexOf(ms: Long, dayStarts: List<Long>): Int = dayStarts.indexOfFirst { ms >= it && ms < nextDayStartMs(it) }

/** True when [ms] is close enough to [nowMs] to call the guide "at now". */
fun isAtNow(ms: Long, nowMs: Long): Boolean = abs(ms - nowMs) < AT_NOW_MS

private fun calendar(ms: Long): Calendar = Calendar.getInstance().apply { timeInMillis = ms }
