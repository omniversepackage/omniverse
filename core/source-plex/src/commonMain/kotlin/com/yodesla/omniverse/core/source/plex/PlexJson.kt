package com.yodesla.omniverse.core.source.plex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Lenient readers: Plex usually sends clean values, but a dirty row must not kill a list. */

internal fun JsonElement.asPrimitiveOrNull(): JsonPrimitive? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> this
    else -> null
}

internal fun JsonObject.str(key: String): String? {
    val p = this[key]?.asPrimitiveOrNull() ?: return null
    // JSON null is a primitive whose content is "null": a pending plex.tv PIN has "authToken": null.
    if (p is kotlinx.serialization.json.JsonNull) return null
    if (!p.isString) return p.content
    val s = p.content.trim()
    if (s.isEmpty() || s.equals("null", ignoreCase = true)) return null
    return s
}

internal fun JsonObject.int(key: String): Int? {
    val v = long(key) ?: return null
    if (v !in Int.MIN_VALUE..Int.MAX_VALUE) return null
    return v.toInt()
}

internal fun JsonObject.long(key: String): Long? {
    val p = this[key]?.asPrimitiveOrNull() ?: return null
    val c = p.content.trim()
    c.toLongOrNull()?.let { return it }
    c.toDoubleOrNull()?.let { d ->
        if (d.isFinite() && d == d.toLong().toDouble()) return d.toLong()
    }
    return null
}

internal fun JsonObject.float(key: String): Float? {
    val p = this[key]?.asPrimitiveOrNull() ?: return null
    return p.content.trim().toFloatOrNull()
}

internal fun JsonObject.bool(key: String): Boolean? {
    val p = this[key]?.asPrimitiveOrNull() ?: return null
    return when (p.content.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/**
 * A Plex sub-list like {"Genre":[{"tag":"Drama"}]}; tolerates a bare string,
 * an array of strings, or objects carrying [field].
 */
internal fun JsonObject.fieldList(key: String, field: String): List<String> {
    val e = this[key] ?: return emptyList()
    return when {
        e is JsonArray -> e.mapNotNull { el ->
            val p = el.asPrimitiveOrNull()
            if (p != null) {
                if (p.isString) p.content.trim().ifEmpty { null } else p.content
            } else {
                (el as? JsonObject)?.str(field)
            }
        }
        e is JsonPrimitive -> listOfNotNull(str(key))
        else -> emptyList()
    }
}
