package com.yodesla.omniverse.core.epg

/**
 * Parses an XMLTV timestamp into UTC epoch milliseconds, or null if malformed.
 *
 * Accepted forms (digits only, no 'Z', no colons):
 *  - `yyyyMMddHHmmss` or `yyyyMMddHHmm`, optionally followed by a space and `+HHMM` / `-HHMM`.
 *  - `yyyyMMddHHmmss+HHMM` / `yyyyMMddHHmmss-HHMM` (no space).
 * No offset means UTC. The offset is a local-time offset: local = UTC + offset.
 *
 * Date math is hand-rolled (days-from-civil); no java.time in commonMain.
 */
fun parseXmltvTime(s: String): Long? {
    val t = s.trim()
    val body: String
    val offsetSec: Long
    val sp = t.indexOf(' ')
    val op = if (sp < 0) t.indexOfAny(charArrayOf('+', '-')) else -1
    when {
        sp >= 0 -> {
            body = t.substring(0, sp)
            offsetSec = parseOffset(t.substring(sp + 1)) ?: return null
        }
        op > 0 -> {
            body = t.substring(0, op)
            offsetSec = parseOffset(t.substring(op)) ?: return null
        }
        else -> {
            body = t
            offsetSec = 0L
        }
    }
    val n = body.length
    if (n != 14 && n != 12) return null
    var year = 0
    var month = 0
    var day = 0
    var hour = 0
    var minute = 0
    var second = 0
    for (i in 0 until n) {
        val d = body[i] - '0'
        if (d !in 0..9) return null
        when (i) {
            0 -> year = d * 1000
            1 -> year += d * 100
            2 -> year += d * 10
            3 -> year += d
            4 -> month = d * 10
            5 -> month += d
            6 -> day = d * 10
            7 -> day += d
            8 -> hour = d * 10
            9 -> hour += d
            10 -> minute = d * 10
            11 -> minute += d
            12 -> second = d * 10
            13 -> second += d
        }
    }
    if (month !in 1..12) return null
    if (day !in 1..daysInMonth(year, month)) return null
    if (hour > 23 || minute > 59 || second > 59) return null
    val days = daysFromCivil(year.toLong(), month.toLong(), day.toLong())
    val epochSec = days * 86400L + hour * 3600L + minute * 60L + second
    return (epochSec - offsetSec) * 1000L
}

private fun parseOffset(s: String): Long? {
    if (s.length != 5) return null
    val sign = if (s[0] == '+') 1L else if (s[0] == '-') -1L else return null
    var h = 0
    var m = 0
    for (i in 1 until 5) {
        val d = s[i] - '0'
        if (d !in 0..9) return null
        if (i < 3) h = h * 10 + d else m = m * 10 + d
    }
    if (m > 59) return null
    return sign * (h * 3600L + m * 60L)
}

/** Days from 1970-01-01 for a proleptic Gregorian civil date (Howard Hinnant's algorithm). */
private fun daysFromCivil(y: Long, m: Long, d: Long): Long {
    val yy = if (m <= 2) y - 1 else y
    val era = if (yy >= 0) yy / 400 else (yy - 399) / 400
    val yoe = yy - era * 400
    val mp = if (m > 2) m - 3 else m + 9
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    else -> 0
}
