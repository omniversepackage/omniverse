package com.yodesla.omniverse.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/**
 * Kids time limits (task 106): a daily watching allowance and a bedtime window, both stored per
 * profile in the profile-scoped settings table, plus the usage clock that enforces them while a
 * player is actually playing.
 *
 * All date math here is pure epoch-ms arithmetic with an explicit local UTC offset — commonMain has
 * no java.time, and the tests must be able to place a tick exactly on a midnight boundary.
 */

/** A bedtime window in local wall-clock minutes, e.g. 20:00-07:00. */
data class BedtimeWindow(val startMinute: Int, val endMinute: Int) {
    /** True when the window runs past midnight (ends at or before it starts), e.g. 20:00-07:00. */
    val isOvernight: Boolean get() = endMinute <= startMinute

    /** Is [minuteOfDay] (0..1439, local) inside this window? Overnight windows wrap midnight. */
    fun contains(minuteOfDay: Int): Boolean =
        if (isOvernight) minuteOfDay >= startMinute || minuteOfDay < endMinute
        else minuteOfDay in startMinute until endMinute

    /** Whole minutes until the window opens from [minuteOfDay]; 0 when already inside it. */
    fun minutesUntilStart(minuteOfDay: Int): Int = when {
        contains(minuteOfDay) -> 0
        minuteOfDay < startMinute -> startMinute - minuteOfDay
        else -> MINUTES_PER_DAY - minuteOfDay + startMinute
    }

    fun encode(): String = "${hour(startMinute)}:${minute(startMinute)}-${hour(endMinute)}:${minute(endMinute)}"

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        /** "20:00-07:00" back to a window; null for absent/blank/"off"/malformed values. */
        fun decode(value: String?): BedtimeWindow? {
            val parts = value?.trim()?.takeIf { it.isNotEmpty() && it != KidsLimits.OFF }?.split('-') ?: return null
            if (parts.size != 2) return null
            val start = parseClock(parts[0]) ?: return null
            val end = parseClock(parts[1]) ?: return null
            return BedtimeWindow(start, end)
        }

        private fun parseClock(text: String): Int? {
            val hm = text.trim().split(':')
            if (hm.size != 2) return null
            val h = hm[0].toIntOrNull() ?: return null
            val m = hm[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return h * 60 + m
        }

        private fun hour(minuteOfDay: Int) = (minuteOfDay / 60).toString().padStart(2, '0')
        private fun minute(minuteOfDay: Int) = (minuteOfDay % 60).toString().padStart(2, '0')
    }
}

/** What a profile is allowed to watch: a daily allowance (0 = off) and a bedtime window (null = off). */
data class KidsLimits(val dailyLimitMinutes: Int = 0, val bedtime: BedtimeWindow? = null) {
    val hasDailyLimit: Boolean get() = dailyLimitMinutes > 0
    val isOff: Boolean get() = dailyLimitMinutes <= 0 && bedtime == null

    /** The daily allowance in ms; 0 when the daily limit is off. */
    val allowanceMs: Long get() = dailyLimitMinutes.coerceAtLeast(0) * 60_000L

    companion object {
        /** The offered choices (task 106): Off / 30 min / 1 h / 2 h / 3 h. */
        val DAILY_CHOICES = listOf(0, 30, 60, 120, 180)

        /** Bedtime steps the editor moves in (task 106 keeps the picker coarse on purpose). */
        const val BEDTIME_STEP_MINUTES = 30

        /** Profile-scoped setting keys (see UserDataRepositoryImpl.isProfileSetting). */
        const val DAILY_KEY = "kids_daily_limit"
        const val BEDTIME_KEY = "kids_bedtime"
        const val USAGE_KEY = "kids_usage"

        /** "Off" for the daily limit setting. */
        const val OFF = "off"

        /** A gentle notice this long before either limit bites. */
        const val WARNING_MS = 5 * 60_000L

        /** The extension a parental PIN buys. */
        const val EXTRA_MINUTES = 30
    }
}

/** Minutes since local midnight for [epochMs] at [offsetMinutes] from UTC. */
fun kidsMinutesOfDay(epochMs: Long, offsetMinutes: Int): Int {
    val local = epochMs + offsetMinutes.toLong() * 60_000L
    val remainder = local % DAY_MS
    return (if (remainder < 0) remainder + DAY_MS else remainder).toInt() / 60_000
}

/** The local calendar day of [epochMs] as "YYYY-MM-DD" — what a daily allowance resets on. */
fun kidsDayKey(epochMs: Long, offsetMinutes: Int): String {
    val local = epochMs + offsetMinutes.toLong() * 60_000L
    val z = floorDiv(local, DAY_MS) + 719468L
    val era = (if (z >= 0) z else z - 146096L) / 146097L
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
    val y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val day = (doy - (153L * mp + 2L) / 5L + 1L).toInt()
    val month = (if (mp < 10L) mp + 3L else mp - 9L).toInt()
    val year = (if (month <= 2) y + 1L else y).toString().padStart(4, '0')
    return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

private const val DAY_MS = 86_400_000L

/** Wall-clock and monotonic readings this close agree, so a day-key change is a real midnight and
 *  not a clock/timezone jump (task 111 M4). */
private const val CLOCK_JUMP_TOLERANCE_MS = 2 * 60_000L

/** Truncating `/` rounds toward zero; day keys need the floor so times before an epoch work. */
private fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    return if (quotient * divisor != value && (value < 0) != (divisor < 0)) quotient - 1 else quotient
}

/** Which rule stopped the viewer. */
enum class KidsTimeReason { DAILY_LIMIT, BEDTIME }

/** The outcome of one clock tick. */
sealed interface KidsTimeEvent {
    data object None : KidsTimeEvent

    /** Inside the 5-minute notice window; [remainingMs] is time left until the limit. */
    data class Warn(val remainingMs: Long, val reason: KidsTimeReason) : KidsTimeEvent

    /** The allowance is spent or it is bedtime: playback must stop. */
    data class Blocked(val reason: KidsTimeReason) : KidsTimeEvent
}

/** Today's watched time for one profile. One settings row per profile; the day field resets it. */
@Serializable
data class KidsDayUsage(
    val day: String = "",
    val usedMs: Long = 0,
    val grantedMs: Long = 0,
    /** Monotonic reading (SystemClock.elapsedRealtime on device) when this row was last written;
     *  -1 when the platform has no monotonic source or the row predates task 111 M4. */
    val elapsedMs: Long = -1,
    /** Local epoch (wall clock + local UTC offset) when this row was last written; paired with
     *  [elapsedMs] it tells a real midnight rollover apart from a clock/timezone jump (task 111 M4). */
    val localMs: Long = 0,
) {
    val totalMs: Long get() = usedMs + grantedMs

    fun encode(): String = KidsDayUsage.json.encodeToString(serializer(), this)

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(value: String?): KidsDayUsage? = try {
            value?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(serializer(), it) }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Per-profile, per-day usage over the profile-scoped settings table. Reads and writes follow the
 * active profile exactly like every other viewer setting, so switching profiles switches limits.
 *
 * [offsetMinutes] is the viewer's local UTC offset; it decides both the day a limit resets on and
 * where bedtime falls. It is a lambda because the device offset is only known at runtime.
 */
class KidsUsageStore(
    private val userData: UserDataRepository,
    private val clock: Clock,
    val offsetMinutes: () -> Int = { 0 },
    /** Monotonic elapsed-ms source (SystemClock.elapsedRealtime on device). Null means the platform
     *  has none, and day rollovers fall back to trusting the wall clock (the pre-task-111 behaviour). */
    private val elapsedMs: () -> Long? = { null },
) {
    fun nowMs(): Long = clock.nowMs()

    private fun currentElapsed(): Long = elapsedMs() ?: -1L

    private fun currentLocal(): Long = nowMs() + offsetMinutes().toLong() * 60_000L

    fun dayKey(): String = kidsDayKey(nowMs(), offsetMinutes())

    fun limits(): Flow<KidsLimits> = combine(
        userData.setting(KidsLimits.DAILY_KEY),
        userData.setting(KidsLimits.BEDTIME_KEY),
    ) { daily, bedtime ->
        KidsLimits(daily?.toIntOrNull()?.coerceAtLeast(0) ?: 0, BedtimeWindow.decode(bedtime))
    }

    suspend fun limitsNow(): KidsLimits = limits().first()

    /**
     * Today's row for the active profile. A row left over from another wall day resets the allowance
     * only when the wall clock advanced continuously across midnight; a jump in wall time that the
     * monotonic clock did not confirm (timezone change, manual clock set, NTP correction) carries the
     * allowance into the new day instead, so a child's limit resets at most once per real day
     * (task 111 M4).
     */
    suspend fun usageToday(): KidsDayUsage {
        val today = dayKey()
        val nowLocal = currentLocal()
        val mono = elapsedMs()
        val stored = KidsDayUsage.decode(userData.setting(KidsLimits.USAGE_KEY).first())
            ?: return KidsDayUsage(today, elapsedMs = mono ?: -1L, localMs = nowLocal)
        if (stored.day == today) return stored
        return if (isRealDayRollover(stored, nowLocal, mono)) {
            KidsDayUsage(today, elapsedMs = mono ?: -1L, localMs = nowLocal)
        } else {
            stored.copy(day = today, elapsedMs = mono ?: stored.elapsedMs, localMs = nowLocal)
        }
    }

    /** A day-key change is genuine when local time and the monotonic clock advanced together (or the
     *  monotonic clock reset, i.e. a reboot, so we trust the wall clock). A timezone change moves the
     *  day key while local time jumps ahead of monotonic time, so it is carried instead. */
    private fun isRealDayRollover(stored: KidsDayUsage, nowLocal: Long, mono: Long?): Boolean = when {
        mono == null || stored.elapsedMs < 0 -> true
        mono < stored.elapsedMs -> true
        else -> kotlin.math.abs((nowLocal - stored.localMs) - (mono - stored.elapsedMs)) <= CLOCK_JUMP_TOLERANCE_MS
    }

    /** Add watched time to [dayKey] (defaults to today). The controller flushes to the day the time
     *  was actually watched, not the day the clock now claims (task 111 L3). */
    suspend fun addWatchedMs(deltaMs: Long, dayKey: String = dayKey()) {
        if (deltaMs <= 0) return
        val row = rowForDay(dayKey)
        userData.putSetting(
            KidsLimits.USAGE_KEY,
            row.copy(usedMs = row.usedMs + deltaMs, elapsedMs = currentElapsed(), localMs = currentLocal()).encode(),
        )
    }

    /** A parental PIN buys [minutes] more on top of [dayKey]'s allowance. */
    suspend fun grantExtraMinutes(minutes: Int = KidsLimits.EXTRA_MINUTES, dayKey: String = dayKey()) {
        val row = rowForDay(dayKey)
        userData.putSetting(
            KidsLimits.USAGE_KEY,
            row.copy(
                grantedMs = row.grantedMs + minutes.coerceAtLeast(0) * 60_000L,
                elapsedMs = currentElapsed(),
                localMs = currentLocal(),
            ).encode(),
        )
    }

    private suspend fun rowForDay(dayKey: String): KidsDayUsage {
        val stored = KidsDayUsage.decode(userData.setting(KidsLimits.USAGE_KEY).first())
        return if (stored == null || stored.day != dayKey) KidsDayUsage(dayKey) else stored
    }

    /** Time left today, or null when the daily limit is off. Bedtime is not modelled here. */
    suspend fun remainingMs(): Long? {
        val limits = limitsNow()
        if (!limits.hasDailyLimit) return null
        val usage = usageToday()
        return (limits.allowanceMs + usage.grantedMs - usage.usedMs).coerceAtLeast(0)
    }
}

/**
 * One watching session's clock. It counts only while [tick] is called with playing = true, so
 * paused or buffering time never spends the allowance, and a gap longer than [maxTickMs] (the app
 * was backgrounded, not the player paused) is not counted either.
 */
class KidsTimeGuard(
    val limits: KidsLimits,
    usedMs: Long,
    grantedMs: Long,
    private val clock: () -> Long,
    private val offsetMinutes: () -> Int = { 0 },
    private val maxTickMs: Long = 2_000L,
) {
    var usedMs: Long = usedMs.coerceAtLeast(0); private set
    var grantedMs: Long = grantedMs.coerceAtLeast(0); private set

    /** Watched time accumulated since the last flush to storage. */
    var pendingMs: Long = 0L; private set

    private var lastTickMs = clock()

    /** The allowance including any PIN-granted extension. */
    val allowanceMs: Long get() = limits.allowanceMs + grantedMs

    /** Time left today, or null when the daily limit is off. */
    val remainingMs: Long? get() =
        if (!limits.hasDailyLimit) null else (allowanceMs - usedMs).coerceAtLeast(0)

    val inBedtime: Boolean
        get() = limits.bedtime?.contains(kidsMinutesOfDay(clock(), offsetMinutes())) == true

    /** Advance the clock by one tick. Returns the current rule state after doing so. */
    fun tick(playing: Boolean): KidsTimeEvent {
        val now = clock()
        val delta = (now - lastTickMs).coerceIn(0L, maxTickMs)
        lastTickMs = now
        if (playing && delta > 0) {
            usedMs += delta
            pendingMs += delta
        }
        return evaluate(now)
    }

    /** Evaluate the rules at [nowMs] without counting any time. */
    fun evaluate(nowMs: Long = clock()): KidsTimeEvent {
        val bedtime = limits.bedtime
        if (bedtime != null) {
            val minuteOfDay = kidsMinutesOfDay(nowMs, offsetMinutes())
            if (bedtime.contains(minuteOfDay)) return KidsTimeEvent.Blocked(KidsTimeReason.BEDTIME)
            val untilBedtime = bedtime.minutesUntilStart(minuteOfDay) * 60_000L
            if (untilBedtime <= KidsLimits.WARNING_MS) return KidsTimeEvent.Warn(untilBedtime, KidsTimeReason.BEDTIME)
        }
        if (limits.hasDailyLimit) {
            val remaining = allowanceMs - usedMs
            if (remaining <= 0) return KidsTimeEvent.Blocked(KidsTimeReason.DAILY_LIMIT)
            if (remaining <= KidsLimits.WARNING_MS) return KidsTimeEvent.Warn(remaining, KidsTimeReason.DAILY_LIMIT)
        }
        return KidsTimeEvent.None
    }

    /** Hand the accumulated watch time to storage. */
    fun takePendingMs(): Long {
        val taken = pendingMs
        pendingMs = 0
        return taken
    }

    fun grantExtraMinutes(minutes: Int = KidsLimits.EXTRA_MINUTES) {
        grantedMs += minutes.coerceAtLeast(0) * 60_000L
    }
}

/** What a player renders: the rules, today's clock, and which rule (if any) is currently stopping it. */
data class KidsTimeState(
    val limits: KidsLimits = KidsLimits(),
    val usedMs: Long = 0,
    val grantedMs: Long = 0,
    /** Time left today, or null when the daily limit is off. */
    val remainingMs: Long? = null,
    /** Non-null inside the 5-minute notice window: ms until the limit bites. */
    val warningMs: Long? = null,
    /** Which rule the notice is about. */
    val warningReason: KidsTimeReason? = null,
    /** Non-null when playback must be paused. */
    val blocked: KidsTimeReason? = null,
) {
    val isActive: Boolean get() = !limits.isOff
}

/**
 * The app-wide Kids clock the players tick and render from. One guard lives at a time (only one
 * player can be playing), it is rebuilt from storage at session start and whenever the local day
 * changes, and watch time is flushed to storage in [flushMs] batches so a crash cannot lose more
 * than that much of a child's allowance.
 */
class KidsTimeController(
    private val store: KidsUsageStore,
    private val flushMs: Long = 10_000L,
) {
    private val _state = MutableStateFlow(KidsTimeState())
    val state: StateFlow<KidsTimeState> = _state.asStateFlow()

    private var guard: KidsTimeGuard? = null
    private var guardDay: String = ""

    /** Rebuild from storage: flush what the previous session counted, then re-read limits and usage. */
    suspend fun reload() {
        flushPending()
        val limits = store.limitsNow()
        val usage = store.usageToday()
        guard = KidsTimeGuard(limits, usage.usedMs, usage.grantedMs, store::nowMs, store.offsetMinutes)
        guardDay = store.dayKey()
        publish(guard?.evaluate() ?: KidsTimeEvent.None)
    }

    /** One player tick. [playing] = the engine is actually playing right now. */
    suspend fun tick(playing: Boolean) {
        val current = guard
        if (current == null || guardDay != store.dayKey()) {
            reload()
            return
        }
        val event = current.tick(playing)
        if (current.pendingMs >= flushMs) flushPending()
        publish(event)
    }

    /** Persist whatever the current session has counted but not yet written, to the day it was
     *  watched on — not the day the (possibly jumped) clock now claims (task 111 L3). */
    suspend fun flushPending() {
        val taken = guard?.takePendingMs() ?: 0L
        if (taken > 0) store.addWatchedMs(taken, guardDay)
    }

    /** A parental PIN was accepted: today gets [minutes] more, immediately. */
    suspend fun grantExtraMinutes(minutes: Int = KidsLimits.EXTRA_MINUTES) {
        store.grantExtraMinutes(minutes, if (guard == null) store.dayKey() else guardDay)
        val current = guard
        if (current == null) {
            reload()
        } else {
            current.grantExtraMinutes(minutes)
            publish(current.evaluate())
        }
    }

    private fun publish(event: KidsTimeEvent) {
        val current = guard ?: return
        _state.value = KidsTimeState(
            limits = current.limits,
            usedMs = current.usedMs,
            grantedMs = current.grantedMs,
            remainingMs = current.remainingMs,
            warningMs = (event as? KidsTimeEvent.Warn)?.remainingMs,
            warningReason = (event as? KidsTimeEvent.Warn)?.reason,
            blocked = (event as? KidsTimeEvent.Blocked)?.reason,
        )
    }
}
