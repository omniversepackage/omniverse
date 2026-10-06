package com.yodesla.omniverse.core.source.m3u

import com.yodesla.omniverse.core.source.SyncDiagnostics
import okio.BufferedSource

/**
 * Streaming M3U/M3U8 playlist parser.
 *
 * Reads line by line ([BufferedSource.readUtf8Line]), never the whole file. Emits entries as a
 * cold [Sequence] in playlist order. Tolerates CRLF, a UTF-8 BOM, blank lines and unknown
 * `#` tags. A malformed `#EXTINF` is reported via [diagnostics] and skipped; a bare URL line
 * (no preceding `#EXTINF`) becomes an entry named after the URL's last path segment.
 */
class M3uParser(private val diagnostics: SyncDiagnostics = SyncDiagnostics.None) {

    fun parse(source: BufferedSource, onHeader: (M3uHeader) -> Unit = {}): Sequence<M3uEntry> =
        sequence {
            var header = M3uHeader(emptyList(), null, null, null)
            var firstLine = true
            var index = 0
            var pending: Pending? = null

            while (true) {
                val raw = source.readUtf8Line() ?: break
                var line = raw
                if (firstLine) {
                    firstLine = false
                    if (line.startsWith(BOM)) line = line.substring(BOM.length)
                }
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> Unit

                    trimmed.startsWith("#EXTM3U", ignoreCase = true) -> {
                        header = M3uHeader.parseAttrs(trimmed.substring(7))
                        onHeader(header)
                    }

                    trimmed.startsWith("#EXTINF:", ignoreCase = true) -> {
                        if (pending != null) diagnostics.skipped("extinf", "previous entry has no URL")
                        pending = Pending.from(trimmed.substring(8), diagnostics)
                    }

                    trimmed.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        pending?.applyVlcopt(trimmed.substring(11))
                    }

                    trimmed.startsWith("#EXTGRP:", ignoreCase = true) -> {
                        pending?.applyGroup(trimmed.substring(8))
                    }

                    trimmed.startsWith("#KODIPROP:", ignoreCase = true) -> {
                        pending?.applyKodiProp(trimmed.substring(10))
                    }

                    trimmed.startsWith("#") -> Unit // any other comment/tag line

                    else -> {
                        val p = pending
                        pending = null
                        val entry = if (p != null) {
                            p.toEntry(trimmed, header, index++)
                        } else {
                            M3uEntry(
                                name = lastPathSegment(trimmed),
                                url = trimmed,
                                durationSec = 0,
                                attrs = emptyMap(),
                                group = null,
                                tvgId = null,
                                tvgName = null,
                                logo = null,
                                chno = null,
                                catchup = header.catchup,
                                catchupDays = header.catchupDays,
                                catchupSource = header.catchupSource,
                                userAgent = null,
                                referrer = null,
                                index = index++,
                            )
                        }
                        yield(entry)
                    }
                }
            }
        }

    /** A `#EXTINF` line that has not yet been paired with its URL line. */
    private class Pending(
        val durationSec: Int,
        val attrs: Map<String, String>,
        val name: String,
        var group: String?,
        var groupLocked: Boolean,
        var userAgent: String?,
        var referrer: String?,
    ) {
        companion object {
            fun from(body: String, diagnostics: SyncDiagnostics): Pending? {
                val sp = body.indexOfFirst { it == ' ' || it == '\t' || it == ',' }
                val durationToken = if (sp >= 0) body.substring(0, sp) else body
                val durationSec = durationToken.toIntOrNull()
                    ?: run {
                        diagnostics.skipped("extinf", "bad duration: $durationToken")
                        return null
                    }
                val rest = if (sp >= 0) body.substring(sp + 1) else ""
                val scanned = scanM3uAttrs(rest, commaTerminates = true)
                return Pending(
                    durationSec = durationSec,
                    attrs = scanned.values,
                    name = scanned.name.orEmpty(),
                    group = scanned.values[ATTR_GROUP],
                    groupLocked = scanned.values.containsKey(ATTR_GROUP),
                    userAgent = null,
                    referrer = null,
                )
            }
        }

        fun applyVlcopt(option: String) {
            val eq = option.indexOf('=')
            if (eq < 0) return
            val key = option.substring(0, eq).trim().lowercase()
            val value = option.substring(eq + 1).trim()
            when (key) {
                "http-user-agent" -> userAgent = value
                "http-referrer" -> referrer = value
            }
        }

        fun applyGroup(value: String) {
            if (!groupLocked && group == null) group = value
        }

        fun applyKodiProp(prop: String) {
            val eq = prop.indexOf('=')
            if (eq < 0) return
            if (!prop.substring(0, eq).trim().equals("inputstream.adaptive.stream_headers", ignoreCase = true)) return
            for (pair in prop.substring(eq + 1).split('&')) {
                val ieq = pair.indexOf('=')
                if (ieq < 0) continue
                val key = pair.substring(0, ieq).trim().lowercase()
                val value = pair.substring(ieq + 1).trim()
                when (key) {
                    "user-agent" -> userAgent = value
                    "referer", "referrer" -> referrer = value
                }
            }
        }

        fun toEntry(url: String, header: M3uHeader, index: Int): M3uEntry = M3uEntry(
            name = name,
            url = url,
            durationSec = durationSec,
            attrs = attrs,
            group = group,
            tvgId = attrs["tvg-id"],
            tvgName = attrs["tvg-name"],
            logo = attrs["tvg-logo"],
            chno = attrs["chno"]?.toIntOrNull(),
            catchup = attrs[ATTR_CATCHUP] ?: header.catchup,
            catchupDays = attrs[ATTR_CATCHUP_DAYS]?.toIntOrNull() ?: header.catchupDays,
            catchupSource = attrs[ATTR_CATCHUP_SOURCE] ?: header.catchupSource,
            userAgent = userAgent,
            referrer = referrer,
            index = index,
        )
    }

    private fun lastPathSegment(url: String): String {
        val noQuery = url.substringBefore('?').substringBefore('#')
        val slash = noQuery.lastIndexOf('/')
        if (slash < 0) return noQuery
        val seg = noQuery.substring(slash + 1)
        return seg.ifEmpty { noQuery }
    }

    private companion object {
        const val BOM = "\uFEFF"
        const val ATTR_GROUP = "group-title"
        const val ATTR_CATCHUP = "catchup"
        const val ATTR_CATCHUP_DAYS = "catchup-days"
        const val ATTR_CATCHUP_SOURCE = "catchup-source"
    }
}

/** Attrs plus the text after the first unquoted comma (null when there is none). */
internal data class M3uAttrScan(val values: Map<String, String>, val name: String?)

/**
 * Scans `key="v" key2='v2' key3=v3,Display Name`: keys are lower-cased, double- and
 * single-quoted values may contain commas and spaces. When [commaTerminates] is true the first
 * comma outside quotes ends the attr list and becomes [M3uAttrScan.name] (the display name);
 * when false a bare comma merely separates attrs (header lines).
 */
internal fun scanM3uAttrs(text: String, commaTerminates: Boolean): M3uAttrScan {
    if (!commaTerminates) return M3uAttrScan(scanAttrList(text), null)
    val p = firstUnquotedComma(text)
    if (p < 0) {
        // No name-comma: either pure attrs, or a bare display name.
        return if ('=' in text) M3uAttrScan(scanAttrList(text), null)
        else M3uAttrScan(emptyMap(), text.trim())
    }
    val left = text.substring(0, p)
    return if ('=' in left) {
        // Real attrs before the comma: everything after it is the display name.
        M3uAttrScan(scanAttrList(left), text.substring(p + 1).trim())
    } else {
        // No attrs at all: the whole text (including commas) is the display name.
        M3uAttrScan(emptyMap(), text.trim())
    }
}

/** Index of the first comma outside quotes, or -1. */
private fun firstUnquotedComma(text: String): Int {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '"' || c == '\'') {
            var j = i + 1
            while (j < text.length && text[j] != c) j++
            i = if (j < text.length) j + 1 else text.length
        } else {
            if (c == ',') return i
            i++
        }
    }
    return -1
}

/** Scans a comma-free (or header) attr list into a lower-cased map. */
private fun scanAttrList(text: String): Map<String, String> {
    val attrs = LinkedHashMap<String, String>()
    var i = 0
    var key = ""
    while (i < text.length) {
        val c = text[i]
        when {
            c == ' ' || c == '\t' -> i++
            c == ',' -> {
                if (key.isNotEmpty()) {
                    attrs[key.lowercase()] = ""
                    key = ""
                }
                i++
            }
            c == '=' -> {
                if (key.isEmpty()) {
                    i++
                } else {
                    val (value, next) = readM3uValue(text, i + 1)
                    attrs[key.lowercase()] = value
                    key = ""
                    i = next
                }
            }
            c == '"' || c == '\'' -> {
                val (value, next) = readM3uValue(text, i)
                if (key.isNotEmpty()) attrs[key.lowercase()] = value
                key = ""
                i = next
            }
            else -> {
                key += c
                i++
            }
        }
    }
    if (key.isNotEmpty()) attrs[key.lowercase()] = ""
    return attrs
}

/** Reads a quoted or bare value starting at [start]; returns the value and the index after it. */
internal fun readM3uValue(text: String, start: Int): Pair<String, Int> {
    var i = start
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
    if (i < text.length && (text[i] == '"' || text[i] == '\'')) {
        val q = text[i]
        var j = i + 1
        while (j < text.length && text[j] != q) j++
        return text.substring(i + 1, j) to if (j < text.length) j + 1 else text.length
    }
    var j = i
    while (j < text.length) {
        val v = text[j]
        if (v == ',' || v == '"' || v == '\'' || v == ' ' || v == '\t') break
        j++
    }
    return text.substring(i, j) to j
}
