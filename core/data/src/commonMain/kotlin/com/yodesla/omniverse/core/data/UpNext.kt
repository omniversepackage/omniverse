package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.Episode
import com.yodesla.omniverse.core.model.RemoteId
import kotlinx.coroutines.CancellationException

/**
 * Finds the episode that comes after a finished one in a show (task 57 "Up next").
 * Null when the finished episode is the show's last one (or the lookup failed).
 */
fun interface NextEpisodeFinder {
    suspend fun nextAfter(seriesKey: ContentKey, completedEpisodeId: RemoteId): Episode?

    /**
     * The saved episode's own season/number (task 86b: Continue Watching's "S1 E5"). Null when the
     * provider does not list it. Default: null, so existing finders keep compiling.
     */
    suspend fun episodeAt(seriesKey: ContentKey, episodeId: RemoteId): Episode? = null
}

/**
 * [NextEpisodeFinder] backed by the provider's [com.yodesla.omniverse.core.source.ContentSource.seriesDetail]
 * (episodes are not stored in the local DB). Seasons are ordered by number and episodes by number
 * within a season, so the episode after [completedEpisodeId] is the next number in its season, else
 * E1 of the next season.
 *
 * Caching policy (task 63, M7): successful lookups are cached per show for [SUCCESS_TTL_MS] so a
 * Home refresh is not a fetch storm, yet a new season/episode surfaces within one TTL; failures
 * are NOT cached, so an offline lookup is retried on the next refresh instead of pinning "no card"
 * for the resolver's whole lifetime. The cache is bounded ([MAX_ENTRIES], oldest evicted), and a
 * cancelled lookup is rethrown, never stored.
 */
class UpNextResolver(
    private val sources: SourceRepository,
    private val clock: Clock = Clock { System.currentTimeMillis() },
) : NextEpisodeFinder {
    private class Cached(val episodes: List<Episode>?, val fetchedAtMs: Long)

    private val cache = LinkedHashMap<ContentKey, Cached>()

    override suspend fun nextAfter(seriesKey: ContentKey, completedEpisodeId: RemoteId): Episode? {
        val ordered = orderedEpisodes(seriesKey) ?: return null
        val index = ordered.indexOfFirst { it.remoteId == completedEpisodeId }
        if (index < 0) return null
        return ordered.getOrNull(index + 1)
    }

    override suspend fun episodeAt(seriesKey: ContentKey, episodeId: RemoteId): Episode? =
        orderedEpisodes(seriesKey)?.firstOrNull { it.remoteId == episodeId }

    /** The show's episodes in airing order, from the cache when fresh (one lookup serves both). */
    private suspend fun orderedEpisodes(seriesKey: ContentKey): List<Episode>? {
        val now = clock.nowMs()
        val cached = cache[seriesKey]
        if (cached != null && now - cached.fetchedAtMs < SUCCESS_TTL_MS) return cached.episodes
        val value = try {
            sources.contentSource(seriesKey.sourceId)?.seriesDetail(seriesKey.remoteId)?.seasons
                ?.sortedBy { it.number }
                ?.flatMap { season -> season.episodes.sortedBy { it.number } }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            null // provider down: no card now; retried on the next refresh (failures are not cached)
        }
        if (value != null) {
            cache[seriesKey] = Cached(value, now)
            if (cache.size > MAX_ENTRIES) cache.remove(cache.keys.first())
        }
        return value
    }

    companion object {
        /** How long a successful series-detail lookup stays valid before it is refetched. */
        const val SUCCESS_TTL_MS = 30L * 60_000L
        /** Most shows cached at once; the least recently fetched is evicted beyond this. */
        const val MAX_ENTRIES = 64
    }
}
