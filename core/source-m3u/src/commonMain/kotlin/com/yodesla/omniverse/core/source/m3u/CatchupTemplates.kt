package com.yodesla.omniverse.core.source.m3u

/**
 * Builds the catch-up (timeshift) stream URL for an entry, in the de-facto formats used by
 * M3U playlists (as documented by Kodi's IPTV Simple client; implemented from the spec).
 *
 * [startUtcMs] / [endUtcMs] bound the requested window; [nowUtcMs] is "now". Returns null when
 * the entry has no usable catch-up configuration or the template is unusable.
 */
fun buildCatchupUrl(e: M3uEntry, startUtcMs: Long, endUtcMs: Long, nowUtcMs: Long): String? {
    val mode = e.catchup?.takeIf { it.isNotBlank() } ?: e.catchupSource?.takeIf { it.isNotBlank() }
    if (mode == null) return null
    val start = startUtcMs / 1000
    val end = endUtcMs / 1000
    val now = nowUtcMs / 1000
    val duration = (end - start).coerceAtLeast(0L)

    return when (mode.lowercase()) {
        "append" -> {
            val suffix = e.catchupSource ?: return null
            e.url + substitute(suffix, start, end, now, duration)
        }
        "shift" -> e.url + (if ('?' in e.url) "&" else "?") + "utc=$start&lutc=$now"
        "flussonic", "fs" -> flussonic(e.url, start, duration)
        "xc" -> xtream(e.url, start, duration)
        "default" -> {
            val template = e.catchupSource ?: return null
            substitute(template, start, end, now, duration)
                .let { if (it.startsWith("?") || it.startsWith("&")) e.url + it else it }
        }
        else -> null
    }
}

/**
 * Substitutes placeholders in a `catchup-source` template:
 * `{utc}`/`${start}`, `{utcend}`/`${end}`, `{lutc}`/`${now}`, `{duration}`, `{offset}`,
 * zero-padded UTC start date/time `{Y} {m} {d} {H} {M} {S}`, and `{duration:60}` (minutes).
 */
internal fun substitute(template: String, start: Long, end: Long, now: Long, duration: Long): String {
    val sb = StringBuilder()
    var i = 0
    while (i < template.length) {
        val c = template[i]
        if (c == '{') {
            val close = template.indexOf('}', i + 1)
            if (close > i + 1) {
                val token = template.substring(i + 1, close)
                val value = placeholder(token, start, end, now, duration)
                if (value != null) {
                    // `${start}` is the same placeholder as `{start}`: drop the leading `$`.
                    if (sb.isNotEmpty() && sb[sb.length - 1] == '$') sb.deleteAt(sb.length - 1)
                    sb.append(value)
                    i = close + 1
                    continue
                }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

private fun placeholder(token: String, start: Long, end: Long, now: Long, duration: Long): String? {
    val idx = token.indexOf(':')
    val name = if (idx > 0) token.substring(0, idx) else token
    val div = if (idx > 0) token.substring(idx + 1).toLongOrNull() else null
    val base = when (name) {
        "utc", "start" -> start
        "utcend", "end" -> end
        "lutc", "now" -> now
        "duration" -> duration
        "offset" -> (now - start).coerceAtLeast(0L)
        "Y" -> utcField(start, 0)
        "m" -> utcField(start, 1)
        "d" -> utcField(start, 2)
        "H" -> utcField(start, 3)
        "M" -> utcField(start, 4)
        "S" -> utcField(start, 5)
        else -> null
    } ?: return null
    if (div != null && div > 0) return (base / div).toString()
    return when (name) {
        "m", "d", "H", "M", "S" -> pad2(base)
        else -> base.toString()
    }
}

internal data class YmdHms(val y: Int, val mo: Int, val d: Int, val h: Int, val mi: Int, val s: Int)

/** Civil UTC date/time of an epoch-seconds value (proleptic Gregorian, Howard Hinnant's algorithm). */
internal fun utcYmdHms(epochSec: Long): YmdHms {
    var z = epochSec / 86400
    var secs = epochSec % 86400
    if (secs < 0) {
        secs += 86400
        z -= 1
    }
    z += 719468
    var era = z / 146097
    if (era < 0) era -= 1
    val doe = z - era * 146097
    var yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    var mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val mo = if (mp < 10) mp + 3 else mp - 9
    val year = if (mo <= 2) y + 1 else y
    return YmdHms(
        year.toInt(),
        mo.toInt(),
        d.toInt(),
        (secs / 3600).toInt(),
        ((secs / 60) % 60).toInt(),
        (secs % 60).toInt(),
    )
}

private fun utcField(epochSec: Long, idx: Int): Long = when (idx) {
    0 -> utcYmdHms(epochSec).y.toLong()
    1 -> utcYmdHms(epochSec).mo.toLong()
    2 -> utcYmdHms(epochSec).d.toLong()
    3 -> utcYmdHms(epochSec).h.toLong()
    4 -> utcYmdHms(epochSec).mi.toLong()
    else -> utcYmdHms(epochSec).s.toLong()
}

private fun pad2(n: Long): String = if (n < 10) "0$n" else n.toString()

/**
 * Flussonic: `http://h/ch/index.m3u8` -> `http://h/ch/index-{utc}-{duration}.m3u8`;
 * `.../mpegts` -> `.../timeshift_abs-{utc}.ts`.
 */
private fun flussonic(url: String, utc: Long, duration: Long): String? {
    val q = url.indexOf('?')
    val path = if (q >= 0) url.substring(0, q) else url
    val query = if (q >= 0) url.substring(q) else ""
    val slash = path.lastIndexOf('/')
    if (slash < 0) return null
    val prefix = path.substring(0, slash + 1)
    val seg = path.substring(slash + 1)
    val replaced = when {
        seg == "mpegts" -> "timeshift_abs-$utc.ts"
        seg.endsWith(".m3u8") -> seg.removeSuffix(".m3u8") + "-$utc-$duration.m3u8"
        else -> seg + "-$utc-$duration"
    }
    return prefix + replaced + query
}

/**
 * Xtream Codes: `http://h:p/[live/]user/pass/ID.ts` ->
 * `http://h:p/timeshift/user/pass/{duration-minutes}/{Y}-{m}-{d}:{H}-{M}/ID.ts`.
 */
private fun xtream(url: String, utc: Long, duration: Long): String? {
    val m = XTREAM_PATTERN.matchEntire(url) ?: return null
    val t = utcYmdHms(utc)
    val whenPart = "${t.y}-${pad2(t.mo.toLong())}-${pad2(t.d.toLong())}:${pad2(t.h.toLong())}-${pad2(t.mi.toLong())}"
    return "${m.groupValues[1]}/timeshift/${m.groupValues[2]}/${m.groupValues[3]}/${duration / 60}/$whenPart/${m.groupValues[4]}.ts"
}

private val XTREAM_PATTERN = Regex("^(.*?://[^/?]+(?::\\d+)?)/(?:live/)?([^/]+)/([^/]+)/([^/]+)\\.ts$")
