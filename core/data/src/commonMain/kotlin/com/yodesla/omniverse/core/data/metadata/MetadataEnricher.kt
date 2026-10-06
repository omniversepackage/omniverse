package com.yodesla.omniverse.core.data.metadata

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Detail-screen entry point for gap-filling. Does nothing unless [enabled] returns true
 * (the Settings opt-in, default OFF) and the item has a source-supplied TMDB id and at
 * least one blank field. Results, including misses, are cached in memory per id so
 * revisiting a title never repeats the request.
 */
class MetadataEnricher(
    private val lookup: suspend (WikidataMetadata.Kind, String) -> EnrichedMetadata?,
    private val enabled: suspend () -> Boolean,
    private val capacity: Int = 256,
) {
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, EnrichedMetadata?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EnrichedMetadata?>) = size > capacity
    }

    suspend fun enrich(
        kind: WikidataMetadata.Kind,
        tmdbId: String?,
        plot: String?,
        cast: String?,
        director: String?,
        genre: String?,
        posterUrl: String?,
    ): EnrichedMetadata? {
        val id = tmdbId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (listOf(plot, cast, director, genre, posterUrl).none { it.isNullOrBlank() }) return null
        if (!enabled()) return null
        val key = "${kind.name}:$id"
        // Holding the lock across the fetch dedupes concurrent requests for the same detail page.
        val found = mutex.withLock {
            if (cache.containsKey(key)) cache[key] else lookup(kind, id).also { cache[key] = it }
        } ?: return null
        return found.fillGaps(plot, cast, director, genre, posterUrl)
    }

    companion object {
        /** UserData setting; "true" enables lookups. Absent/anything else = OFF. */
        const val SETTING_KEY = "metadata_wikidata"
    }
}
