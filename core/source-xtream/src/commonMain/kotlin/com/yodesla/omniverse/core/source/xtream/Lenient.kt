package com.yodesla.omniverse.core.source.xtream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val jsonParser = Json { ignoreUnknownKeys = true }

private fun JsonElement.asPrimitive(): JsonPrimitive? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> this
    else -> null
}

fun JsonObject.str(key: String): String? {
    val p = this[key]?.asPrimitive() ?: return null
    if (!p.isString) return p.content
    val s = p.content.trim()
    if (s.isEmpty() || s.equals("null", ignoreCase = true)) return null
    return s
}

fun JsonObject.int(key: String): Int? {
    val v = long(key) ?: return null
    if (v !in Int.MIN_VALUE..Int.MAX_VALUE) return null
    return v.toInt()
}

fun JsonObject.long(key: String): Long? {
    val p = this[key]?.asPrimitive() ?: return null
    val c = p.content.trim()
    c.toLongOrNull()?.let { return it }
    c.toDoubleOrNull()?.let { d ->
        if (d.isFinite() && d == d.toLong().toDouble()) return d.toLong()
    }
    return null
}

fun JsonObject.float(key: String): Float? {
    val p = this[key]?.asPrimitive() ?: return null
    return p.content.trim().toFloatOrNull()
}

fun JsonObject.bool(key: String): Boolean? {
    val p = this[key]?.asPrimitive() ?: return null
    return when (p.content.trim().lowercase()) {
        "true" -> true
        "false" -> false
        "1" -> true
        "0" -> false
        else -> when (p.content.trim().toLongOrNull()) {
            1L -> true
            0L -> false
            else -> null
        }
    }
}

fun JsonObject.strList(key: String): List<String> {
    val e = this[key] ?: return emptyList()
    return when {
        e is JsonArray -> e.mapNotNull { el ->
            val p = el.asPrimitive() ?: return@mapNotNull null
            if (p.isString) p.content.trim().ifEmpty { null } else p.content
        }
        e is JsonPrimitive -> listOfNotNull(str(key))
        else -> emptyList()
    }
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private val htmlEntityRegex =
    Regex("(&amp;|&lt;|&gt;|&quot;|&#39;|&apos;|&#(\\d+);|&#x([0-9a-fA-F]+);)")

fun decodeHtmlEntities(s: String): String {
    if (!s.contains('&')) return s
    return htmlEntityRegex.replace(s) { m ->
        val numeric = m.groupValues[2]
        val hex = m.groupValues[3]
        val cp = when {
            numeric.isNotEmpty() -> numeric.toIntOrNull()
            hex.isNotEmpty() -> hex.toIntOrNull(16)
            else -> null
        }
        when {
            m.value == "&amp;" -> "&"
            m.value == "&lt;" -> "<"
            m.value == "&gt;" -> ">"
            m.value == "&quot;" -> "\""
            m.value == "&#39;" || m.value == "&apos;" -> "'"
            cp != null && cp in 0..0x10FFFF -> String(Character.toChars(cp))
            else -> m.value
        }
    }
}

private const val B64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private val b64Value: Map<Char, Int> = B64_ALPHABET.withIndex().associate { (i, c) -> c to i }

fun lenientBase64(s: String?): String? {
    if (s == null) return null
    val normalized = s.trim()
        .replace('-', '+')
        .replace('_', '/')
        .replace(Regex("\\s"), "")
    if (normalized.length % 4 == 1) return s
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    if (padded.any { it != '=' && (it !in b64Value) }) return s
    val out = ArrayList<Byte>(padded.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (c in padded) {
        if (c == '=') continue
        buffer = (buffer shl 6) or b64Value.getValue(c)
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.add(((buffer shr bits) and 0xFF).toByte())
        }
    }
    return out.toByteArray().utf8WithReplacement()
}

private fun ByteArray.utf8WithReplacement(): String {
    val sb = StringBuilder(size)
    var i = 0
    while (i < size) {
        val b0 = this[i].toInt() and 0xFF
        val len = when {
            b0 and 0x80 == 0 -> 1
            b0 and 0xE0 == 0xC0 -> 2
            b0 and 0xF0 == 0xE0 -> 3
            b0 and 0xF8 == 0xF0 -> 4
            else -> { sb.append('\uFFFD'); i++; continue }
        }
        var cp = when (len) {
            1 -> b0
            2 -> b0 and 0x1F
            3 -> b0 and 0x0F
            else -> b0 and 0x07
        }
        var ok = i + len <= size
        for (k in 1 until len) {
            if (!ok) break
            val b = this[i + k].toInt() and 0xFF
            if (b and 0xC0 != 0x80) { ok = false; break }
            cp = (cp shl 6) or (b and 0x3F)
        }
        val valid = ok && cp <= 0x10FFFF && cp !in 0xD800..0xDFFF &&
            (len == 1 || (len == 2 && cp >= 0x80) || (len == 3 && cp >= 0x800) || cp >= 0x10000)
        if (valid) sb.append(String(Character.toChars(cp))) else sb.append('\uFFFD')
        i += if (valid) len else 1
    }
    return sb.toString()
}

internal fun parseLenient(text: String): JsonElement? = try {
    jsonParser.parseToJsonElement(text)
} catch (e: Exception) {
    null
}
