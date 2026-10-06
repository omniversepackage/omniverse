package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.progressPosterKey
import kotlinx.coroutines.flow.first

/** A pick must reach this rating to earn the "prefer well-rated" bonus (task 96). */
const val PICK_MIN_RATING = 6.5f

/**
 * "Surprise me" (task 96): pick one unwatched title from the freshest catalog rows, weighted by the
 * genres this viewer actually watches, skipping anything with saved progress, honoring parental and
 * browse visibility, and preferring titles rated at least [PICK_MIN_RATING].
 */
object PickForMe {
    /** Genre tokens of a poster, normalized exactly like the browse filter's genre options. */
    fun genres(poster: PosterRow): List<String> =
        poster.genre.orEmpty().split(',', '|').map(String::trim).map(String::lowercase)
            .filter(String::isNotEmpty).distinct()

    /** Each watched title votes once per genre; the most-watched genre weighs 1.0. */
    fun genreWeights(watched: List<PosterRow>): Map<String, Float> {
        val counts = LinkedHashMap<String, Int>()
        watched.forEach { p -> genres(p).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val max = counts.values.maxOrNull() ?: 0
        return if (max <= 0) emptyMap() else counts.mapValues { it.value.toFloat() / max }
    }

    /** Same-title grouping: the TMDB id when known, else the exact catalog key. */
    fun titleGroup(poster: PosterRow): String =
        poster.tmdbId?.takeIf { it.isNotBlank() }?.let { "${poster.key.kind}:tmdb:$it" }
            ?: "${poster.key.kind}:key:${poster.key.sourceId.value}:${poster.key.remoteId.value}"

    /**
     * The pick itself; null when nothing qualifies. A title with saved progress never comes back
     * (any copy of it is excluded), a title in [alreadyPicked] is never offered twice, and a title
     * the viewer cannot see is never offered at all. Genre match is the primary rank; the
     * [PICK_MIN_RATING] bonus lifts a decent match over a better-matched dud, never over a clearly
     * better match, and never replaces the pick when nothing is well-rated.
     */
    fun pick(
        candidates: List<PosterRow>,
        watched: List<PosterRow>,
        visibility: Visibility,
        alreadyPicked: Set<String> = emptySet(),
    ): PosterRow? {
        val watchedGroups = watched.map(::titleGroup).toSet()
        val weights = genreWeights(watched)
        val pool = candidates
            .filter { p -> p.categoryId?.let { visibility(p.key.kind.name, p.key.sourceId.value, it.value) } ?: true }
            .filter { p -> titleGroup(p) !in watchedGroups && titleGroup(p) !in alreadyPicked }
            .distinctBy(::titleGroup)
        if (pool.isEmpty()) return null
        fun score(p: PosterRow): Float =
            genres(p).fold(0f) { acc, genre -> acc + (weights[genre] ?: 0f) } +
                if ((p.rating ?: 0f) >= PICK_MIN_RATING) 0.5f else 0f
        return pool.sortedWith(
            compareByDescending<PosterRow> { score(it) }
                .thenByDescending { it.rating ?: -1f }
                .thenBy { it.name.lowercase() }
                .thenBy { it.key.sourceId.value }
                .thenBy { it.key.remoteId.value },
        ).first()
    }
}

/** One "Surprise me" offer; a null [poster] means nothing is left to offer. */
data class PickForMeOffer(val poster: PosterRow?)

/**
 * Per-screen "Surprise me" session (task 96): gathers this viewer's watched titles (saved progress,
 * episodes resolved to their show) and the freshest candidates for the given kinds, then hands them
 * to [PickForMe]. Titles offered during this session are never offered again.
 */
class PickForMeSession(
    private val catalog: CatalogRepository,
    private val userData: UserDataRepository,
) {
    private val picked = mutableSetOf<String>()

    /** One fresh offer; [PickForMeOffer.poster] is null when everything is watched, hidden or offered. */
    suspend fun next(
        kinds: Collection<ContentKind>,
        excludedCategoryKeys: Collection<String>,
        visibility: Visibility,
    ): PickForMeOffer {
        val watched = userData.savedProgress(200).first().mapNotNull { p ->
            progressPosterKey(p.key, p.parentId)?.let { catalog.poster(it) }
        }
        val candidates = kinds.flatMap { kind -> catalog.recentlyAdded(kind, CANDIDATE_LIMIT, excludedCategoryKeys).first() }
        val pick = PickForMe.pick(candidates, watched, visibility, picked)
            ?: return PickForMeOffer(null)
        picked += PickForMe.titleGroup(pick)
        return PickForMeOffer(pick)
    }

    private companion object {
        const val CANDIDATE_LIMIT = 200
    }
}
