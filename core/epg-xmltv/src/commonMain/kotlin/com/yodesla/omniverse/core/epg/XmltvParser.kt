package com.yodesla.omniverse.core.epg

import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.source.SyncDiagnostics
import okio.BufferedSource
import okio.Buffer

private enum class Kind { START, END, TEXT, CDATA, IGNORE, EOF }

/** A `<channel>` element from the XMLTV file. */
data class XmltvChannel(val id: String, val displayName: String?, val iconUrl: String?)

/**
 * Streaming XMLTV parser. Reads [source] in 64 KiB chunks, decodes UTF-8 itself (a
 * multi-byte code point split across a chunk boundary is carried over), and never loads
 * the whole file into memory. Memory stays flat for arbitrarily large files.
 *
 * Only `<channel>` and `<programme>` (plus the handful of children it knows) are handled;
 * every other element, including unknown nested ones, is ignored.
 */
class XmltvParser(private val diagnostics: SyncDiagnostics = SyncDiagnostics.None) {

    /**
     * Lazily parses [source]; blocking I/O (callers run it on an IO dispatcher). Closes nothing.
     *
     * @param channelKeys if non-null, only programmes whose `channel` attribute is in this set are kept.
     * @param window a programme is kept when it overlaps the window: `end > startMs && start < endMs`.
     * A truncated file ends the sequence quietly after the last complete programme.
     */
    fun parse(
        source: BufferedSource,
        sourceId: SourceId,
        window: TimeWindow,
        channelKeys: Set<String>?,
        onChannel: (XmltvChannel) -> Unit = {},
    ): Sequence<ProgrammeRecord> {
        val input = maybeGunzip(source)
        val tz = XmltvTokenizer(input)

        var inTv = false
        var inChannel = false
        var channelId: String? = null
        var channelName: String? = null
        var channelIcon: String? = null

        var inProgramme = false
        var progChannel: String? = null
        var progStart: Long? = null
        var progEnd: Long? = null
        var progStartRaw: String? = null
        var progEndRaw: String? = null
        var progTitle: String? = null
        var progSubtitle: String? = null
        var progDesc: String? = null
        var progCategory: String? = null
        var progEpOnscreen: String? = null
        var progEpFirst: String? = null
        var progIcon: String? = null

        var target = Target.NONE
        var targetCdata = false
        var targetOnscreen = false
        val textBuf = StringBuilder()

        return sequence {
            fun startTarget(t: Target) {
                target = t
                targetCdata = false
                textBuf.setLength(0)
            }

            fun finishText(): String {
                val raw = textBuf.toString()
                textBuf.setLength(0)
                val decoded = if (targetCdata) raw else decodeEntities(raw)
                val trimmed = decoded.trim()
                return if (trimmed.length <= 1) trimmed else collapseWhitespace(trimmed)
            }

            fun handleEnd(name: String): ProgrammeRecord? {
            when (name) {
                "display-name" -> if (target == Target.CHANNEL_NAME) {
                    target = Target.NONE
                    channelName = finishText()
                }
                "title" -> if (target == Target.TITLE) {
                    target = Target.NONE
                    progTitle = finishText()
                }
                "sub-title" -> if (target == Target.SUBTITLE) {
                    target = Target.NONE
                    progSubtitle = finishText()
                }
                "desc" -> if (target == Target.DESC) {
                    target = Target.NONE
                    progDesc = finishText()
                }
                "category" -> if (target == Target.CATEGORY) {
                    target = Target.NONE
                    progCategory = finishText()
                }
                "episode-num" -> if (target == Target.EPNUM) {
                    val v = finishText()
                    target = Target.NONE
                    if (targetOnscreen) progEpOnscreen = v else progEpFirst = v
                }
                "channel" -> if (inChannel) {
                    inChannel = false
                    target = Target.NONE
                    onChannel(XmltvChannel(channelId.orEmpty(), channelName, channelIcon))
                    channelId = null
                    channelName = null
                    channelIcon = null
                }
                "programme" -> if (inProgramme) {
                    inProgramme = false
                    target = Target.NONE
                    val ch = progChannel
                    val st = progStart
                    val en = progEnd
                    val result: ProgrammeRecord?
                    when {
                        ch == null -> { diagnostics.skipped("programme", "missing channel"); result = null }
                        st == null -> { diagnostics.skipped("programme", "bad start: ${progStartRaw ?: "missing"}"); result = null }
                        en == null -> { diagnostics.skipped("programme", "bad stop: ${progEndRaw ?: "missing"}"); result = null }
                        else -> result = if ((channelKeys == null || ch in channelKeys) && en > window.startMs && st < window.endMs) {
                            ProgrammeRecord(
                                sourceId = sourceId,
                                channelKey = ch,
                                startMs = st,
                                endMs = en,
                                title = progTitle?.takeIf { it.isNotEmpty() } ?: "(No title)",
                                subtitle = progSubtitle?.takeIf { it.isNotEmpty() },
                                description = progDesc?.takeIf { it.isNotEmpty() },
                                category = progCategory?.takeIf { it.isNotEmpty() },
                                episodeNum = (progEpOnscreen ?: progEpFirst)?.takeIf { it.isNotEmpty() },
                                iconUrl = progIcon,
                                hasArchive = false,
                            )
                        } else null
                    }
                    progChannel = null
                    progStart = null
                    progEnd = null
                    progStartRaw = null
                    progEndRaw = null
                    progTitle = null
                    progSubtitle = null
                    progDesc = null
                    progCategory = null
                    progEpOnscreen = null
                    progEpFirst = null
                    progIcon = null
                    return result
                }
                else -> {}
            }
            return null
        }

            while (true) {
                when (tz.next()) {
                    Kind.EOF -> break
                    Kind.IGNORE -> {}
                    Kind.TEXT, Kind.CDATA -> if (target != Target.NONE) {
                        if (tz.kind == Kind.CDATA) targetCdata = true
                        textBuf.append(tz.text)
                    }
                    Kind.START -> {
                        val name = tz.name
                        val attrs = tz.attrs
                        when {
                            !inTv -> if (name == "tv") inTv = true
                            inChannel -> when (name) {
                                "display-name" -> if (channelName == null && target == Target.NONE) startTarget(Target.CHANNEL_NAME)
                                "icon" -> channelIcon = channelIcon ?: nonBlank(attr(attrs, "src"))
                                else -> {}
                            }
                            inProgramme -> when (name) {
                                "title" -> if (progTitle == null && target == Target.NONE) startTarget(Target.TITLE)
                                "sub-title" -> if (progSubtitle == null && target == Target.NONE) startTarget(Target.SUBTITLE)
                                "desc" -> if (progDesc == null && target == Target.NONE) startTarget(Target.DESC)
                                "category" -> if (progCategory == null && target == Target.NONE) startTarget(Target.CATEGORY)
                                "episode-num" -> if (target == Target.NONE && (progEpFirst == null || (attr(attrs, "system") == "onscreen" && progEpOnscreen == null))) {
                                    startTarget(Target.EPNUM)
                                    targetOnscreen = attr(attrs, "system") == "onscreen"
                                }
                                "icon" -> progIcon = progIcon ?: nonBlank(attr(attrs, "src"))
                                else -> {}
                            }
                            name == "channel" -> {
                                inChannel = true
                                target = Target.NONE
                                channelId = attr(attrs, "id")
                                channelName = null
                                channelIcon = null
                            }
                            name == "programme" -> {
                                inProgramme = true
                                target = Target.NONE
                                progChannel = nonBlank(attr(attrs, "channel"))
                                progStartRaw = attr(attrs, "start")
                                progStart = progStartRaw?.let { parseXmltvTime(it) }
                                progEndRaw = attr(attrs, "stop")
                                progEnd = progEndRaw?.let { parseXmltvTime(it) }
                                progTitle = null
                                progSubtitle = null
                                progDesc = null
                                progCategory = null
                                progEpOnscreen = null
                                progEpFirst = null
                                progIcon = null
                            }
                            else -> {}
                        }
                        if (tz.selfClosing) handleEnd(name)?.let { yield(it) }
                    }
                    Kind.END -> handleEnd(tz.name)?.let { yield(it) }
                }
            }
        }
    }
}

private enum class Target { NONE, CHANNEL_NAME, TITLE, SUBTITLE, DESC, CATEGORY, EPNUM }

private fun nonBlank(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

private fun attr(attrs: List<String>, name: String): String? {
    for (i in attrs.indices step 2) {
        if (attrs[i] == name) return attrs[i + 1]
    }
    return null
}

/** Decodes &amp; &lt; &gt; &quot; &apos; &#NN; &#xHH;; unknown entities are kept as-is. */
internal fun decodeEntities(s: String): String {
    var amp = s.indexOf('&')
    if (amp < 0) return s
    val out = StringBuilder(s.length)
    var from = 0
    while (amp >= 0) {
        val semi = s.indexOf(';', amp + 1)
        if (semi < 0 || semi - (amp + 1) > 12) {
            out.append(s, from, s.length)
            from = s.length
            break
        }
        val code = s.substring(amp + 1, semi)
        val cp = when (code) {
            "amp" -> '&'
            "lt" -> '<'
            "gt" -> '>'
            "quot" -> '"'
            "apos" -> '\''
            else -> numericEntity(code)
        }
        if (cp != null) {
            out.append(s, from, amp)
            out.append(cp)
            from = semi + 1
        } else {
            out.append(s, from, semi + 1)
            from = semi + 1
        }
        amp = s.indexOf('&', from)
    }
    if (from < s.length) out.append(s, from, s.length)
    return out.toString()
}

private fun numericEntity(code: String): Char? {
    if (code.length < 2 || code[0] != '#') return null
    val hex = code[1] == 'x' || code[1] == 'X'
    val body = if (hex) code.substring(2) else code.substring(1)
    if (body.isEmpty() || body.length > 8) return null
    for (c in body) {
        val ok = c in '0'..'9' || (hex && (c in 'a'..'f' || c in 'A'..'F'))
        if (!ok) return null
    }
    val v = if (hex) body.toInt(16) else body.toInt()
    if (v !in 0..0xFFFF) return null
    return v.toChar()
}

/** Collapses runs of internal whitespace to a single space. */
private fun collapseWhitespace(s: String): String {
    val out = StringBuilder(s.length)
    var lastWasSpace = false
    for (c in s) {
        if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
            lastWasSpace = true
        } else {
            if (lastWasSpace && out.length > 0) out.append(' ')
            out.append(c)
            lastWasSpace = false
        }
    }
    return out.toString()
}

// ---------------------------------------------------------------------------
// Tokenizer
// ---------------------------------------------------------------------------

/**
 * Small pull tokenizer over a [BufferedSource]. Reads 64 KiB byte chunks, decodes UTF-8
 * chunk by chunk while carrying an incomplete trailing code point over to the next chunk,
 * and hands out tags, text, CDATA and ignorable markup (PIs, doctypes, comments).
 * Never reads the whole source into memory.
 */
private class XmltvTokenizer(private val source: BufferedSource) {

    var kind = Kind.EOF
        private set
    var name = ""
        private set
    var text = ""
        private set
    var selfClosing = false
        private set
    /** Flat list [attrName1, attrValue1, attrName2, ...]; reused between tokens — copy what you need. */
    val attrs: MutableList<String> = ArrayList<String>(16)

    private var buf = ""
    private var pos = 0
    private var eof = false
    private val chunk = ByteArray(64 * 1024)
    private val pending = Buffer()

    /** Advances to the next token. Returns [Kind.EOF] when the input is exhausted. */
    fun next(): Kind {
        while (true) {
            if (pos >= buf.length) {
                if (!refill()) {
                    kind = Kind.EOF
                    return Kind.EOF
                }
            }
            var p = pos
            while (p < buf.length) {
                val c = buf[p]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') p++ else break
            }
            if (p >= buf.length) {
                pos = p
                continue
            }
            if (buf[p] == '<') {
                pos = p
                return parseTag()
            }
            pos = p
            return parseText()
        }
    }

    /** Reads one chunk from [source] and appends the decoded characters to [buf]. */
    private fun refill(): Boolean {
        if (eof) return false
        if (pos >= buf.length) {
            buf = ""
            pos = 0
        } else if (pos > 32 * 1024) {
            buf = buf.substring(pos)
            pos = 0
        }
        if (!source.request(1)) {
            eof = true
            if (pending.size > 0) {
                buf += pending.readUtf8()
                pending.clear()
            }
            return true
        }
        val k = chunk.size.coerceAtMost(source.buffer.size.toInt())
        val n = source.read(chunk, 0, k)
        val full = completePrefixLen(chunk, n)
        pending.write(chunk, 0, full)
        buf += pending.readUtf8()
        pending.clear()
        if (full < n) pending.write(chunk, full, n - full)
        return true
    }

    /**
     * Length of the longest complete-UTF-8-sequence prefix of [b][0 until n).
     * A trailing partial multi-byte sequence is held back for the next chunk.
     */
    private fun completePrefixLen(b: ByteArray, n: Int): Int {
        var i = n - 1
        while (i >= 0 && i >= n - 3) {
            val c = b[i].toInt() and 0xFF
            when {
                c < 0x80 -> return n
                c and 0xC0 == 0x80 -> i--
                else -> {
                    val len = when (c and 0xF8) {
                        0xF0 -> 4
                        0xE0 -> 3
                        else -> 2
                    }
                    return if (n - i >= len) n else i
                }
            }
        }
        return n
    }

    private fun parseText(): Kind {
        val sb = StringBuilder()
        while (true) {
            val end = buf.indexOf('<', pos)
            if (end >= 0) {
                if (end > pos) sb.append(buf, pos, end)
                pos = end
                text = sb.toString()
                kind = Kind.TEXT
                return Kind.TEXT
            }
            // Consume what we appended BEFORE refilling: otherwise the next pass re-appends it,
            // and at EOF the caller would get the same text forever (infinite loop → OOM).
            if (pos < buf.length) sb.append(buf, pos, buf.length)
            pos = buf.length
            if (!refill()) {
                if (sb.isEmpty()) {
                    kind = Kind.EOF
                    return Kind.EOF
                }
                text = sb.toString()
                kind = Kind.TEXT
                return Kind.TEXT
            }
        }
    }

    private fun parseTag(): Kind {
        // buf[pos] == '<'; need one more char or a definitive EOF
        if (pos + 1 >= buf.length) refill()
        if (pos + 1 >= buf.length) {
            kind = Kind.EOF
            return Kind.EOF
        }
        val c = buf[pos + 1]
        when (c) {
            '!' -> {
                if (startsWithAt("<!--", pos)) return skipUntil("-->", Kind.IGNORE)
                if (startsWithAt("<![CDATA[", pos)) {
                    pos += 9
                    return parseCdata()
                }
                return skipUntil(">", Kind.IGNORE)
            }
            '?' -> {
                selfClosing = false
                return skipUntil("?>", Kind.IGNORE)
            }
            '/' -> {
                pos += 2
                name = readName()
                skipToClose()
                selfClosing = false
                kind = Kind.END
                return Kind.END
            }
            else -> {
                pos++
                name = readName()
                attrs.clear()
                selfClosing = skipAttrs()
                kind = Kind.START
                return Kind.START
            }
        }
    }

    /** Collects raw CDATA content until `]]>`; returned as one CDATA token. */
    private fun parseCdata(): Kind {
        val sb = StringBuilder()
        while (true) {
            val end = buf.indexOf("]]>", pos)
            if (end >= 0) {
                if (end > pos) sb.append(buf, pos, end)
                pos = end + 3
                text = sb.toString()
                kind = Kind.CDATA
                return Kind.CDATA
            }
            if (pos < buf.length) sb.append(buf, pos, buf.length)
            pos = buf.length
            if (!refill()) {
                text = sb.toString()
                kind = Kind.CDATA
                return Kind.CDATA
            }
        }
    }

    private fun skipUntil(marker: String, k: Kind): Kind {
        while (true) {
            val end = buf.indexOf(marker, pos)
            if (end >= 0) {
                pos = end + marker.length
                kind = k
                return k
            }
            pos = buf.length
            if (!refill()) {
                kind = Kind.IGNORE
                return Kind.IGNORE
            }
        }
    }

    /** Reads an element/attribute name starting at [pos]; advances [pos]. */
    private fun readName(): String {
        val sb = StringBuilder()
        while (true) {
            while (pos < buf.length) {
                val c = buf[pos]
                if (c.isLetterOrDigit() || c == '_' || c == '-' || c == ':' || c == '.') {
                    sb.append(c)
                    pos++
                } else break
            }
            if (pos < buf.length || eof) return sb.toString()
            refill()
        }
    }

    /**
     * Skips attributes until the closing `>` of a start tag. Records name/value pairs in
     * [attrs]. Attribute values may use double or single quotes and may straddle chunks.
     * Returns whether the tag was self-closing (`/>`).
     */
    private fun skipAttrs(): Boolean {
        while (true) {
            if (pos >= buf.length) {
                if (!refill()) return false
                continue
            }
            val c = buf[pos]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> pos++
                c == '>' -> {
                    pos++
                    return false
                }
                c == '/' -> {
                    pos++
                    if (peekAndRefill() == '>') {
                        pos++
                        return true
                    }
                }
                else -> {
                    // Make sure the rest of this start tag is buffered before slicing an attribute:
                    // an attribute split across two chunks (e.g. `sta|rt="..."`) was being read as two
                    // bogus attributes, silently dropping ~0.2% of programmes. refill() keeps data from
                    // `pos` onward, so waiting for the tag's `>` is safe.
                    if (!eof && buf.indexOf('>', pos) < 0) {
                        if (!refill()) return false
                        continue
                    }
                    val nameStart = pos
                    while (pos < buf.length) {
                        val d = buf[pos]
                        if (d == '=' || d == '>' || d == '/' || d == ' ' || d == '\t' || d == '\n' || d == '\r') break
                        pos++
                    }
                    val attrName = buf.substring(nameStart, pos)
                    // skip whitespace before '='
                    while (pos < buf.length && (buf[pos] == ' ' || buf[pos] == '\t' || buf[pos] == '\n' || buf[pos] == '\r')) pos++
                    var value = ""
                    if (pos < buf.length && buf[pos] == '=') {
                        pos++
                        while (pos < buf.length && (buf[pos] == ' ' || buf[pos] == '\t' || buf[pos] == '\n' || buf[pos] == '\r')) pos++
                        if (pos < buf.length && (buf[pos] == '"' || buf[pos] == '\'')) {
                            val quote = buf[pos]
                            pos++
                            val sb = StringBuilder()
                            value = readQuoted(quote, sb)
                        } else {
                            value = readUnquoted()
                        }
                    }
                    if (attrName.isNotEmpty()) {
                        attrs.add(attrName)
                        attrs.add(value)
                    }
                }
            }
        }
    }

    /** Reads a quoted attribute value (quote already consumed) across chunk boundaries. */
    private fun readQuoted(quote: Char, sb: StringBuilder): String {
        while (true) {
            val end = buf.indexOf(quote, pos)
            if (end >= 0) {
                if (end > pos) sb.append(buf, pos, end)
                pos = end + 1
                return sb.toString()
            }
            if (pos < buf.length) sb.append(buf, pos, buf.length)
            pos = buf.length
            if (!refill()) return sb.toString()
        }
    }

    /** Reads an unquoted attribute value until whitespace or `>`. */
    private fun readUnquoted(): String {
        val start = pos
        while (pos < buf.length) {
            val c = buf[pos]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '>' || c == '/') break
            pos++
        }
        return buf.substring(start, pos)
    }

    /** Skips an (unexpected) end tag's attributes, if any, until its `>`. */
    private fun skipToClose() {
        while (true) {
            if (pos >= buf.length) {
                if (!refill()) return
                continue
            }
            if (buf[pos] == '>') {
                pos++
                return
            }
            pos++
        }
    }

    private fun peekAndRefill(): Char {
        while (pos >= buf.length) {
            if (!refill()) return '\u0000'
        }
        return buf[pos]
    }

    private fun startsWithAt(s: String, at: Int): Boolean {
        if (at + s.length > buf.length) {
            if (!refill()) return false
            if (at + s.length > buf.length) return false
        }
        return buf.regionMatches(at, s, 0, s.length)
    }
}
