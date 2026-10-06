package com.yodesla.omniverse.core.data.search

/*
 * Task 82 — pure, platform-neutral search text helpers (no java.* / android.*: commonMain must stay
 * free of them for the Phase 6 iOS/tvOS targets). Used by the typo-tolerant search path: normalize a
 * query and a candidate title the same way, then compare.
 */

/** Leading words dropped from the front of a query/title before matching. */
private val ARTICLES = setOf("the", "a", "an")

/** Common accented Latin letters folded to their ASCII base (lowercase; input is lowercased first). */
private val ACCENTS: Map<Char, String> = buildMap {
    for (c in "àáâãäåāăą") put(c, "a")
    for (c in "èéêëěę") put(c, "e")
    for (c in "ìíîïĩį") put(c, "i")
    for (c in "òóôõöøō") put(c, "o")
    for (c in "ùúûüũū") put(c, "u")
    for (c in "ýÿŷ") put(c, "y")
    put('ñ', "n"); put('ń', "n")
    put('ç', "c"); put('ć', "c"); put('ĉ', "c"); put('ċ', "c"); put('č', "c")
    put('ß', "ss"); put('æ', "ae"); put('œ', "oe")
    put('ð', "d"); put('đ', "d"); put('þ', "th")
    put('ġ', "g"); put('ğ', "g"); put('ĥ', "h"); put('ħ', "h")
    put('ł', "l"); put('ŕ', "r"); put('ř', "r")
    put('š', "s"); put('ś', "s"); put('ş', "s")
    put('ť', "t"); put('ŧ', "t")
    put('ŵ', "w"); put('ž', "z"); put('ź', "z")
}

/**
 * Canonical form used for typo-tolerant matching: lowercase, apostrophes removed, "&" folded to
 * "and", accents folded, roman numerals (II, III, IV …) folded to arabic digits, punctuation turned
 * into separators, spaces collapsed, and leading "the/a/an" dropped. Applied identically to a query
 * and to a candidate title so equivalent spellings compare equal.
 */
fun normalize(s: String): String {
    var t = s.lowercase().replace("'", "").replace("\u2019", "").replace("&", " and ")
    val folded = StringBuilder(t.length)
    for (ch in t) {
        val base = ACCENTS[ch]
        if (base != null) folded.append(base) else folded.append(ch)
    }
    val tokens = folded
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .map { romanToArabic(it) }
    var lead = 0
    while (lead < tokens.size && tokens[lead] in ARTICLES) lead++
    return tokens.drop(lead).joinToString(" ")
}

/** Classic edit distance between two strings. */
fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        val tmp = prev; prev = curr; curr = tmp
    }
    return prev[b.length]
}

/**
 * The candidate whose normalized form is closest to [query], or null when nothing is within
 * [maxDistance] (or the query/candidates are empty). Comparison is on the normalized form; the
 * ORIGINAL candidate string is returned so the caller can display the real title.
 */
fun closest(query: String, candidates: List<String>, maxDistance: Int = 2): String? {
    val target = normalize(query)
    if (target.isEmpty() || candidates.isEmpty()) return null
    var best: String? = null
    var bestDistance = maxDistance + 1
    for (candidate in candidates) {
        val form = normalize(candidate)
        if (form.isEmpty()) continue
        val distance = levenshtein(target, form)
        if (distance < bestDistance) {
            bestDistance = distance
            best = candidate
            if (distance == 0) break
        }
    }
    return if (bestDistance <= maxDistance) best else null
}

/** Folds a valid roman-numeral token (length ≥ 2, value ≤ 39) to its arabic digits; otherwise unchanged. */
private fun romanToArabic(token: String): String {
    if (token.length < 2) return token
    val values = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000)
    if (!token.all { it in values }) return token
    var total = 0
    var i = 0
    while (i < token.length) {
        val cur = values.getValue(token[i])
        val next = if (i + 1 < token.length) values.getValue(token[i + 1]) else 0
        if (cur < next) { total += next - cur; i += 2 } else { total += cur; i++ }
    }
    return if (total in 1..39 && toRoman(total) == token) total.toString() else token
}

private fun toRoman(value: Int): String {
    if (value <= 0 || value > 3999) return ""
    val pairs = listOf(
        1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc",
        50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i",
    )
    val sb = StringBuilder()
    var v = value
    for ((n, r) in pairs) {
        while (v >= n) { sb.append(r); v -= n }
    }
    return sb.toString()
}
