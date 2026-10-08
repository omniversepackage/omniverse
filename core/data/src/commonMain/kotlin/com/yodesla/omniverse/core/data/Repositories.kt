package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.model.AccountInfo
import androidx.paging.PagingSource
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.source.SourceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Read models the UI consumes. Everything is main-safe (suspend / Flow / PagingSource),
 * reads only committed DB data, and never touches the network (sync does that).
 * Hidden items (hidden table) are excluded unless includeHidden = true (group manager).
 */

data class SourceSummary(
    val id: SourceId,
    val kind: SourceKind,
    val name: String,
    val lastSyncLiveMs: Long?,
    val lastSyncVodMs: Long?,
    val lastSyncEpgMs: Long?,
)

interface SourceRepository {
    fun sources(): Flow<List<SourceSummary>>
    /** Persists [config] (credentials stay on-device) and returns its id. */
    suspend fun add(config: SourceConfig): SourceId
    suspend fun update(config: SourceConfig)
    /** Removes the source and its catalog rows. User data is kept (orphaned) by design. */
    suspend fun remove(id: SourceId)
    suspend fun config(id: SourceId): SourceConfig?
    /** Live ContentSource for [id] (built via the registered SourceFactory list), cached per id. */
    suspend fun contentSource(id: SourceId): ContentSource?
    /**
     * Logs in with [config] WITHOUT saving anything, so onboarding can reject bad details before a
     * source exists. Throws SourceException (e.g. Unsupported if no factory handles the kind).
     */
    suspend fun probe(config: SourceConfig): AccountInfo
}

data class ChannelRow(
    val key: ContentKey,
    val number: Int?,
    val name: String,
    val logoUrl: String?,
    val epgKey: String?,
    val catchupDays: Int,
    val categoryId: RemoteId,
)

data class PosterRow(
    val key: ContentKey,
    val name: String,
    val posterUrl: String?,
    val year: Int?,
    val rating: Float?,
    /** Primary category (parental locks filter on it). */
    val categoryId: RemoteId? = null,
    /** Trustworthy source-supplied title identity only; never inferred from title/year. */
    val tmdbId: String? = null,
    /** Wide 16:9 art for the billboard/hero; null when the source has none (IPTV VOD). */
    val backdropUrl: String? = null,
    /** Short synopsis: the provider's own plot, else the cached TMDB overview (task 84f); null when unknown. */
    val plot: String? = null,
    /** TMDB title logo (task 84d) — the one art kind only TMDB supplies; null when uncached. */
    val logoUrl: String? = null,
    /** US certification from TMDB (G / PG / PG-13 / R ...); null when unknown. */
    val certification: String? = null,
    /** TMDB runtime in minutes; used only where the source carries no runtime. */
    val runtimeMin: Int? = null,
    /** Provider genre string, provider's own separator (shows and VOD) — task 86b. */
    val genre: String? = null,
    /** Task 87b: TMDB backdrop for landscape CARDS, deliberately different from [backdropUrl]
     * (the billboard/hero art). Null when the title has only one backdrop — cards then reuse the
     * banner art with the title logo overlaid. */
    val cardBackdropUrl: String? = null,
) {
    fun titleIdentity(): TitleIdentity? = tmdbId?.takeIf { it.isNotBlank() &&
        (key.kind == ContentKind.VOD || key.kind == ContentKind.SERIES) }?.let {
        TitleIdentity(key.kind, "tmdb", it)
    }
}

/**
 * Task 115: the Movies/Shows browse sort choice. Applied in SQL before paging (never to a
 * loaded page alone). [PROVIDER] and [ALPHABETICAL] are the two choices browse had before;
 * [RELEASE_YEAR] is newest release year first and [RATING] highest rating first — null
 * year/rating rows sort last in those modes, with deterministic name/source/id ties.
 */
enum class BrowseSort { PROVIDER, ALPHABETICAL, RELEASE_YEAR, RATING }

interface CatalogRepository {
    fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean = false): Flow<List<Category>>
    fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow>
    /**
     * "All channels" of a source: every visible channel in at least one category not in
     * [excludedCategories] (parental-locked ones), ordered by channel number. Rows carry [ALL_CHANNELS]
     * as their category so the player zaps through the same list.
     */
    fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): PagingSource<Int, ChannelRow>
    suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow>
    suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey>
    /** Whole category as a list (guide grid; categories are at most a few thousand rows). */
    suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow>
    /** Channels in display order across all visible categories (for channel up/down zapping). */
    suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?>
    /** The visible channels of a category, in list order: what Up/Down steps through while watching. */
    suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey>
    suspend fun channel(key: ContentKey): ChannelRow?
    fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow>
    fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow>
    /** Every source at once; Plex suppresses IPTV movie duplicates only on exact TMDB id. */
    fun vodAll(excludedCategoryKeys: Collection<String> = emptyList()): PagingSource<Int, PosterRow>
    fun seriesAll(excludedCategoryKeys: Collection<String> = emptyList()): PagingSource<Int, PosterRow>
    fun vodAllAlphabetic(excludedCategoryKeys: Collection<String> = emptyList()): PagingSource<Int, PosterRow> = vodAll(excludedCategoryKeys)
    fun seriesAllAlphabetic(excludedCategoryKeys: Collection<String> = emptyList()): PagingSource<Int, PosterRow> = seriesAll(excludedCategoryKeys)
    /**
     * Task 115: paged single-source browse (all categories when [categoryId] is null) ordered by
     * [sort] in SQL before paging. Defaults fall back to the unsorted page so fakes keep compiling.
     */
    fun vodSorted(sourceId: SourceId, categoryId: RemoteId?, sort: BrowseSort): PagingSource<Int, PosterRow> = vod(sourceId, categoryId)
    fun seriesSorted(sourceId: SourceId, categoryId: RemoteId?, sort: BrowseSort): PagingSource<Int, PosterRow> = series(sourceId, categoryId)
    /** Task 115: "All" across every source ordered by [sort]; same dedup/visibility as [vodAll]. */
    fun vodAllSorted(excludedCategoryKeys: Collection<String> = emptyList(), sort: BrowseSort): PagingSource<Int, PosterRow> =
        if (sort == BrowseSort.ALPHABETICAL) vodAllAlphabetic(excludedCategoryKeys) else vodAll(excludedCategoryKeys)
    fun seriesAllSorted(excludedCategoryKeys: Collection<String> = emptyList(), sort: BrowseSort): PagingSource<Int, PosterRow> =
        if (sort == BrowseSort.ALPHABETICAL) seriesAllAlphabetic(excludedCategoryKeys) else seriesAll(excludedCategoryKeys)
    /**
     * Task 92: the Crunchyroll "Anime library" — every anime title of [kind] across every source,
     * same visibility ([excludedCategoryKeys]), exact-TMDB dedup (Plex wins) and paging as
     * [vodAll]/[seriesAll]; [alphabetic] only orders the result. Anime = an anime whole-word token
     * in the primary category name or the provider genre (see AnimeRules).
     */
    fun animeLibrary(
        kind: ContentKind,
        excludedCategoryKeys: Collection<String> = emptyList(),
        alphabetic: Boolean = false,
    ): PagingSource<Int, PosterRow> = if (kind == ContentKind.SERIES) seriesAll(excludedCategoryKeys) else vodAll(excludedCategoryKeys)
    /** Task 115: the anime library ordered by [sort] (same scope/dedup as [animeLibrary]). */
    fun animeLibrarySorted(
        kind: ContentKind,
        excludedCategoryKeys: Collection<String> = emptyList(),
        sort: BrowseSort = BrowseSort.PROVIDER,
    ): PagingSource<Int, PosterRow> = animeLibrary(kind, excludedCategoryKeys, sort == BrowseSort.ALPHABETICAL)
    /**
     * Task 117: the anime library with the browse [filter] applied in SQL before paging (same
     * scope/dedup/sort as [animeLibrarySorted]). The default ignores the filter so fakes keep
     * compiling; the real repo runs the filter in the anime queries' WHERE clauses.
     */
    fun animeLibraryFilteredSorted(
        kind: ContentKind,
        excludedCategoryKeys: Collection<String> = emptyList(),
        filter: SmartCollectionFilter = SmartCollectionFilter(),
        sort: BrowseSort = BrowseSort.PROVIDER,
    ): PagingSource<Int, PosterRow> = animeLibrarySorted(kind, excludedCategoryKeys, sort)
    /** Display name the provider gave one category (the anime classifier needs it); null when unknown. */
    suspend fun categoryName(sourceId: SourceId, kind: ContentKind, categoryId: RemoteId): String? = null
    /**
     * Paged browse with the filter applied in SQL (task 84i). Null [sourceId] = every source (Plex
     * wins exact-TMDB duplicates, [excludedCategoryKeys] hides `KIND|source|category` rows);
     * [alphabetic] only orders the all-sources case. Defaults fall back to the unfiltered page so
     * fakes keep compiling.
     */
    fun vodFiltered(
        sourceId: SourceId?,
        categoryId: RemoteId?,
        filter: SmartCollectionFilter,
        excludedCategoryKeys: Collection<String> = emptyList(),
        alphabetic: Boolean = false,
    ): PagingSource<Int, PosterRow> = if (sourceId == null) vodAll(excludedCategoryKeys) else vod(sourceId, categoryId)
    fun seriesFiltered(
        sourceId: SourceId?,
        categoryId: RemoteId?,
        filter: SmartCollectionFilter,
        excludedCategoryKeys: Collection<String> = emptyList(),
        alphabetic: Boolean = false,
    ): PagingSource<Int, PosterRow> = if (sourceId == null) seriesAll(excludedCategoryKeys) else series(sourceId, categoryId)
    /**
     * Task 115: paged browse with the filter applied in SQL and ordered by [sort] before paging;
     * same scope as [vodFiltered]/[seriesFiltered]. Defaults fall back to the alphabetic-aware
     * filtered queries so fakes keep compiling.
     */
    fun vodFilteredSorted(
        sourceId: SourceId?,
        categoryId: RemoteId?,
        filter: SmartCollectionFilter,
        excludedCategoryKeys: Collection<String> = emptyList(),
        sort: BrowseSort = BrowseSort.PROVIDER,
    ): PagingSource<Int, PosterRow> =
        vodFiltered(sourceId, categoryId, filter, excludedCategoryKeys, sort == BrowseSort.ALPHABETICAL)
    fun seriesFilteredSorted(
        sourceId: SourceId?,
        categoryId: RemoteId?,
        filter: SmartCollectionFilter,
        excludedCategoryKeys: Collection<String> = emptyList(),
        sort: BrowseSort = BrowseSort.PROVIDER,
    ): PagingSource<Int, PosterRow> =
        seriesFiltered(sourceId, categoryId, filter, excludedCategoryKeys, sort == BrowseSort.ALPHABETICAL)
    /** Distinct genre names present in the browsed scope (task 84i filter panel), display order. */
    suspend fun genreOptions(
        kind: ContentKind,
        sourceId: SourceId?,
        categoryId: RemoteId?,
        excludedCategoryKeys: Collection<String> = emptyList(),
    ): List<String> = emptyList()
    fun recentlyAdded(kind: ContentKind, limit: Int = 30): Flow<List<PosterRow>>
    /** Shelf with visibility and one-card-per-title applied before the limit; [excludedKeys] are `KIND|source|category`. */
    fun recentlyAdded(kind: ContentKind, limit: Int, excludedKeys: Collection<String>): Flow<List<PosterRow>> = recentlyAdded(kind, limit)
    /** Poster data for a VOD or SERIES key (EPISODE: pass the series key). Null if not in the catalog. */
    suspend fun poster(key: ContentKey): PosterRow?
    /**
     * Task 87b: re-read the local TMDB art cache for rows already on screen (no network, no paging
     * restart). Browse pages call it when the TMDB enricher reports new art, so freshly cached
     * backdrops and card art appear without leaving the page.
     */
    suspend fun refillArt(rows: List<PosterRow>): List<PosterRow> = rows
    /** Other movie items sharing the same external TMDB id; caller must apply parental visibility. */
    suspend fun exactMovieMatches(key: ContentKey): List<PosterRow> = emptyList()
    suspend fun exactSeriesMatches(key: ContentKey): List<PosterRow> = emptyList()
    /** Available exact-ID variants in preferred source order; caller applies live parental visibility. */
    suspend fun titleCandidates(identity: TitleIdentity): List<PosterRow> = emptyList()
    suspend fun smartCollectionItems(kind: ContentKind, filter: SmartCollectionFilter, limit: Int = 100): List<PosterRow> = emptyList()
    /** Same-kind titles sharing genre/cast/director with [seed], best match first (task 53). Caller applies visibility/progress exclusions. */
    suspend fun similarTo(seed: ContentKey, limit: Int = 20): List<PosterRow> = emptyList()
    fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>>
}

data class NowNext(val now: ProgrammeRecord?, val next: ProgrammeRecord?)

interface EpgRepository {
    /** Guide keys are only unique per provider (two sources often share "bbc1.uk"): always scope by source. */
    suspend fun nowNext(sourceId: SourceId, epgKeys: Collection<String>, atMs: Long): Map<String, NowNext>
    suspend fun programmes(sourceId: SourceId, epgKey: String, window: TimeWindow): List<ProgrammeRecord>

    /** End of the latest programme stored for this source (0 when the guide is empty): the guide's forward limit. */
    suspend fun maxProgrammeEnd(sourceId: SourceId): Long = 0L

    /** Live Sports: sports airings from every source's guide, now through [windowMs], soonest first. */
    suspend fun sportsSchedule(nowMs: Long, windowMs: Long = 3 * 86_400_000L, limit: Int = 800): List<com.yodesla.omniverse.core.data.sports.SportsAiring> = emptyList()

    /** Live Sports: channels that are clearly sports outlets, in channel-number order. */
    suspend fun sportsChannels(limit: Int = 60): List<ChannelRow> = emptyList()

    /** Channels whose name contains [term] (case-insensitive), for matching TV networks to channels. */
    suspend fun channelsNamed(term: String, limit: Int = 40): List<ChannelRow> = emptyList()
}

data class SearchHit(val key: ContentKey, val title: String)

/** A guide programme matching a search, on the provider's channel that airs it. */
data class ProgrammeHit(
    val channel: ContentKey,
    val categoryId: RemoteId,
    val channelName: String,
    val logoUrl: String?,
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Task 122: the MAIN search filter set. Filters combine (AND) and narrow the ordinary text search;
 * with a blank query they discover titles on their own. The kind chips restrict kind (neither chip
 * = both kinds); [anime] follows the library's category-name / provider-genre classification, never
 * a title substring. Decades arrive as [yearFrom]/[yearTo] bounds (1980s = 1980..1989).
 */
data class SearchFilters(
    val movies: Boolean = false,
    val shows: Boolean = false,
    val anime: Boolean = false,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val ratingAtLeast: Float? = null,
    val genres: List<String> = emptyList(),
) {
    /** A title filter is on: blank-query discovery runs and live/programme search is suppressed. */
    val active: Boolean
        get() = movies || shows || anime || yearFrom != null || yearTo != null || ratingAtLeast != null || genres.isNotEmpty()

    /** Kinds to search: neither chip selected = both kinds. */
    val kinds: List<ContentKind>
        get() = buildList {
            if (movies || !shows) add(ContentKind.VOD)
            if (shows || !movies) add(ContentKind.SERIES)
        }

    companion object {
        val None = SearchFilters()
    }
}

/**
 * Task 122: one kind's filtered-search page. [rows] is capped at the requested limit; [more] says
 * matches exist beyond the cap, so the UI can say "showing the first N" instead of pretending the
 * cap is the whole result set. With a blank query [more] is exact (SQL fetched limit+1); with text
 * it means "more within the over-fetched FTS window" (FTS4 has no relevance ranking).
 */
data class SearchFilteredPage(val rows: List<PosterRow>, val more: Boolean = false)

interface SearchRepository {
    /** FTS prefix search; user text is sanitized (never raw FTS syntax). Grouped by kind in order. */
    suspend fun search(query: String, limitPerKind: Int = 20): Map<ContentKind, List<SearchHit>>

    /**
     * Task 122: cross-source movie/show search narrowed by [filters] (AND), every filter applied in
     * SQL before the limit. Blank [query] = pure filter discovery. [excludedCategoryKeys] are
     * `KIND|source|category` keys this profile must not see (parental, Kids, language, hidden
     * libraries); exact-TMDB duplicates collapse Plex-first. At most [limitPerKind] rows per kind;
     * [SearchFilteredPage.more] reports matches beyond the cap.
     */
    suspend fun searchFiltered(
        query: String,
        filters: SearchFilters,
        excludedCategoryKeys: Collection<String> = emptyList(),
        limitPerKind: Int = 24,
    ): Map<ContentKind, SearchFilteredPage> = emptyMap()

    /** Decade starts (1980, 2010 …) that actually have titles in [kinds] under [excludedCategoryKeys], newest first. */
    suspend fun decadeOptions(kinds: Collection<ContentKind>, excludedCategoryKeys: Collection<String> = emptyList()): List<Int> = emptyList()

    /** Programmes airing now or within [windowMs] whose title contains [query]; soonest first. */
    suspend fun searchProgrammes(query: String, nowMs: Long, windowMs: Long = 86_400_000L, limit: Int = 30): List<ProgrammeHit> = emptyList()

    /**
     * Movie/show titles (VOD + SERIES) most-recent first, capped at [limit], for typo-tolerant
     * "did you mean" matching. Rows carry only what the caller needs to rank and apply the current
     * profile's browse visibility (key, name, primary category). Default: empty.
     */
    suspend fun recentTitles(limit: Int = 2000): List<PosterRow> = emptyList()
}

data class Progress(
    val key: ContentKey,
    val parentId: RemoteId?,
    val positionMs: Long,
    val durationMs: Long?,
    val updatedMs: Long,
    /** The saved row is a finished watch (DB completed = 1): never in Continue Watching. */
    val completed: Boolean = false,
)

/** A show whose most recent progress is a completed episode (task 57 "Up next"). */
data class CompletedShow(
    val seriesKey: ContentKey,
    val lastEpisodeKey: ContentKey,
    val updatedMs: Long,
)

data class ItemOverride(
    val displayTitle: String? = null,
    val sortTitle: String? = null,
    val editionLabel: String? = null,
    val customPosterUrl: String? = null,
)

/** Exact external identity only. Source item keys and edition labels remain separate. */
data class TitleIdentity(val kind: ContentKind, val namespace: String, val externalId: String)

data class TitleOverride(
    val displayTitle: String? = null,
    val sortTitle: String? = null,
    val customPosterUrl: String? = null,
)

data class LibraryCollection(
    val id: String,
    val name: String,
    val contentKind: ContentKind,
    val pinnedHome: Boolean = false,
    val mode: String = "manual",
    val smartFilterJson: String? = null,
    val sortOrder: Long = 0,
)

@Serializable
data class SmartCollectionFilter(
    val titleContains: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val ratingAtLeast: Float? = null,
    val sourceId: String? = null,
    val genreContains: String? = null,
    /** true = started at least once; false = no saved progress. */
    val started: Boolean? = null,
    /** true = this concrete item has a nonblank saved edition label; false = it does not. Never inferred. */
    val hasEditionLabel: Boolean? = null,
    /** true = has a completed progress row; false = none. Combined with [started] this is the watched/unwatched split (task 84i). */
    val completed: Boolean? = null,
    /** Matches items carrying ANY of these genre names (OR); null = no genre filter. */
    val genres: List<String>? = null,
    /** TMDB-cached runtime bounds in minutes; items without a cached runtime drop out when set. */
    val runtimeAtLeastMin: Int? = null,
    val runtimeAtMostMin: Int? = null,
    /** true = only items from PLEX sources. */
    val plexOnly: Boolean? = null,
    /** true = only items whose title suggests a 4K/UHD copy (4k, uhd, 2160). */
    val uhdOnly: Boolean? = null,
    /** Pin the rule to one category of [sourceId]; null = the whole source. */
    val categoryId: String? = null,
) {
    fun encode(): String = Json.encodeToString(serializer(), this)
    companion object {
        fun decode(value: String?): SmartCollectionFilter? =
            value?.let { runCatching { Json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

interface UserDataRepository {
    fun favorites(kind: ContentKind? = null): Flow<List<ContentKey>>
    fun isFavorite(key: ContentKey): Flow<Boolean>
    suspend fun setFavorite(key: ContentKey, favorite: Boolean)

    /**
     * Task 97: the "Play next" queue — a per-profile, viewer-ordered list of titles the viewer
     * queued from title menus, the Play next Home row, or the player's end-of-item offer. Stored in
     * the favourite table under list_id 'playnext'; order is the viewer's own (Move up / Move down).
     * Default: empty.
     */
    fun playNext(): Flow<List<ContentKey>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** Whether [key] is currently in this profile's Play next queue. Default: false. */
    fun isQueued(key: ContentKey): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)

    /** Add [key] to the back of the queue, or remove it. Adding an already-queued title is a no-op. Default: no-op. */
    suspend fun setQueued(key: ContentKey, queued: Boolean) {}

    /**
     * Move a queued title one slot up or down in viewer order. No-op when it is not queued or the
     * move would leave the list. Default: no-op.
     */
    suspend fun moveQueued(key: ContentKey, up: Boolean) {}
    suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?)
    fun continueWatching(limit: Int = 20): Flow<List<Progress>>

    /**
     * Continue Watching deduped to ONE row per exact title (task 89). Every copy of a title (Plex +
     * IPTV, two IPTV categories sharing a stream, a Plex onDeck import plus a local watch) is a
     * separate progress row, so the raw [continueWatching] list can show the same movie twice. This
     * collapses each title to its single most-recently-watched copy and that copy's progress: grouped
     * by non-empty exact TMDB id only (VOD by its id, a series — and every episode of it — by the
     * parent series' id), never by title/year; a copy with no TMDB id stays its own card. A copy whose
     * category is in [excludedCategoryKeys] ("KIND|source|category") is never chosen as the
     * representative, so a locked newest copy cannot hide a visible older one. Movies CW, Home CW and
     * the Netflix "Continue Watching for …" row all read this one source. Default: [continueWatching].
     */
    fun continueWatchingDeduped(limit: Int = 20, excludedCategoryKeys: Collection<String> = emptyList()): Flow<List<Progress>> =
        continueWatching(limit)

    /**
     * Every saved progress row — finished watches included, unlike [continueWatching] — newest
     * first, capped at [limit]. Exclusion lists (e.g. "Because you watched") must use this rather
     * than the presentation-limited Continue Watching flow. Default: empty.
     */
    fun savedProgress(limit: Int = 200): Flow<List<Progress>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /**
     * Shows whose most recent progress row is a completed episode, newest first (task 57 "Up
     * next"): the show left Continue Watching but the binge rolled on. Only shows with activity
     * at or after [sinceMs]. Default: empty.
     */
    fun completedShows(sinceMs: Long, limit: Int = 20): Flow<List<CompletedShow>> = kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun progress(key: ContentKey): Progress?

    /**
     * Mark a movie or episode watched (a completed row: full position, never in Continue Watching)
     * or unwatched (its progress row is deleted). [durationMs] is the known length; when unknown a
     * watched row stores position 1. Default: no-op.
     */
    suspend fun setWatched(key: ContentKey, parentId: RemoteId?, watched: Boolean, durationMs: Long?) {}

    /**
     * Import progress the provider already has (e.g. Plex onDeck). Written only when there is no
     * local row for [key] or the local row's updated time is older than [atMs], so a local watch
     * is never overwritten by a stale remote one. Default: no-op.
     */
    suspend fun importRemoteProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?, atMs: Long) {}

    /**
     * Forget saved progress for a movie or show (all its episodes/versions): it leaves Continue
     * Watching. When the item carries a trustworthy title identity (exact TMDB id), EVERY copy of
     * that title across sources is dismissed atomically — Continue Watching shows one card per
     * title, so removing it must not let another source's copy resurface — and remote progress for
     * any copy stays suppressed until progress is made after the dismissal. Progress itself stays
     * per playable variant. Returns an [UndoToken] capturing the exact rows deleted and the
     * re-import tombstones written, so [restoreContinueWatching] can put them all back.
     */
    suspend fun removeFromContinueWatching(key: ContentKey): UndoToken = UndoToken.None

    /**
     * Put back a Continue Watching dismissal captured by [removeFromContinueWatching]: re-insert
     * every deleted progress row at its exact position and clear the tombstones that were suppressing
     * it, so the title returns to Continue Watching exactly as it was. Default: no-op.
     */
    suspend fun restoreContinueWatching(token: UndoToken) {}

    /**
     * Remove a title from My List (its favourite row) and return an [UndoToken] holding the row's
     * exact order and added time, so [restoreMyList] can put it back in the same slot. Default: None.
     */
    suspend fun removeFromMyList(key: ContentKey): UndoToken = UndoToken.None

    /** Put back a My List removal captured by [removeFromMyList], at its exact order and added time. */
    suspend fun restoreMyList(token: UndoToken) {}

    /**
     * Titles the viewer dismissed from Continue Watching for this profile, newest first, for the
     * Settings "Hidden from Continue Watching" list. Default: empty.
     */
    fun hiddenContinueWatching(limit: Int = 100): Flow<List<HiddenContinueWatching>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    /** Put one dismissed title back into Continue Watching and drop it from the hidden list. */
    suspend fun restoreHiddenContinueWatching(key: ContentKey) {}

    /** Put every dismissed title back into Continue Watching and clear the hidden list. */
    suspend fun restoreAllHiddenContinueWatching() {}

    suspend fun recordChannelWatched(key: ContentKey)
    fun recentChannels(limit: Int = 20): Flow<List<ContentKey>>
    suspend fun setHidden(key: ContentKey, hidden: Boolean)

    /** Switch a whole library/category off (or back on) for this profile. [kind] is LIVE, VOD or SERIES. */
    suspend fun setCategoryHidden(sourceId: SourceId, kind: ContentKind, categoryId: String, hidden: Boolean) {}

    /** Switched-off categories as "KIND|sourceId|categoryId", the same keys the All queries exclude. */
    fun hiddenCategoryKeys(): Flow<Set<String>> = kotlinx.coroutines.flow.flowOf(emptySet())
    fun setting(key: String): Flow<String?>
    suspend fun putSetting(key: String, value: String)
    suspend fun itemOverride(key: ContentKey): ItemOverride? = null
    fun itemOverrides(): Flow<Map<ContentKey, ItemOverride>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    suspend fun setItemOverride(key: ContentKey, value: ItemOverride) {}
    suspend fun titleOverride(identity: TitleIdentity): TitleOverride? = null
    fun titleOverrides(): Flow<Map<TitleIdentity, TitleOverride>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    suspend fun setTitleOverride(identity: TitleIdentity, value: TitleOverride) {}
    fun collections(): Flow<List<LibraryCollection>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun saveCollection(value: LibraryCollection) {}
    suspend fun deleteCollection(id: String) {}
    suspend fun collectionItems(id: String): List<ContentKey> = emptyList()
    suspend fun addToCollection(id: String, key: ContentKey) {}
    suspend fun removeFromCollection(id: String, key: ContentKey) {}
    /** Title-level manual membership (VOD/SERIES only), in collection order. */
    suspend fun collectionTitles(id: String): List<TitleIdentity> = emptyList()
    suspend fun addTitleToCollection(id: String, identity: TitleIdentity) {}
    suspend fun removeTitleFromCollection(id: String, identity: TitleIdentity) {}
    /** Viewer-defined item or series intro end, credits start/remaining, in milliseconds. */
    fun localSkipPoints(key: ContentKey): Flow<Map<String, Long>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    suspend fun setLocalSkipPoint(key: ContentKey, type: String, positionMs: Long?) {}

    companion object {
        /**
         * Task 94: "Pre-load next channel" (Settings › Playback). Per-profile, default ON (absent =
         * on; only the literal "false" turns it off). The live player keeps one hidden engine primed
         * on the next channel while this is on.
         */
        const val PRELOAD_NEXT_CHANNEL = "preload_next_channel"

        /**
         * Task 95: "Hide spoilers for unwatched episodes" (Settings › Playback). Per-profile,
         * default OFF (only the literal "true" turns it on). Unwatched episodes after the last
         * touched one show a blurred thumbnail and a hidden description in show details, and the
         * "Up next" subtitles and the player's next-episode card drop the spoiler text.
         */
        const val SPOILER_FREE = "spoiler_free"

        /** Task 95 sub-option "Also hide episode titles"; per-profile, default OFF. */
        const val SPOILER_FREE_HIDE_TITLES = "spoiler_free_hide_titles"

        /**
         * Task 107: "Back from a search result goes to" (Settings › Playback). Per-profile.
         * Absent or unknown value = [BACK_FROM_SEARCH_GUIDE_OR_DETAILS] (Back from a player or
         * details page opened from Search lands on the Guide or the title's Movies/Shows section);
         * the literal [BACK_FROM_SEARCH_SEARCH] keeps the Search screen itself on the stack.
         */
        const val BACK_FROM_SEARCH = "back_from_search"
        const val BACK_FROM_SEARCH_SEARCH = "search"
        const val BACK_FROM_SEARCH_GUIDE_OR_DETAILS = "guide_details"
    }
}

/** Task 107: true only for the literal "search" value; absent/unknown = guide/details (default). */
fun backFromSearchGoesToSearch(raw: String?): Boolean = raw == UserDataRepository.BACK_FROM_SEARCH_SEARCH

/** Pseudo category id for "All channels" (Live list, Guide and player zapping). */
const val ALL_CHANNELS = "__all"

/** Device-local playback preference. A new, unconfirmed suggestion never becomes a marker. */
object SkipSettings {
    const val INTRO_MODE = "skip_intro_mode"
    const val CREDITS_MODE = "skip_credits_mode"
    /** Existing persisted key; true means fetch online markers, not force auto-skip. */
    const val COMMUNITY_LOOKUP = "skip_community_auto"
    const val LOCAL_ANALYSIS = "skip_local_analysis"
    const val BUTTON = "button"
    const val AUTO = "auto"
    const val OFF = "off"
}

/*
 * Task 66 — Settings > Sources status line. Pure, platform-neutral mapping from the sync/catalog
 * state we already hold into one trustworthy line per source. No URLs, usernames or tokens ever
 * appear here: only counts and coarse failure kinds. The failure kind/time is carried between the
 * Settings view model and the per-source extras through the settings table (SourceStatusKeys), so
 * both surfaces read the same recorded sync/account result without a schema change.
 */

/** Coarse class of the last account/sync failure, mapped from [com.yodesla.omniverse.core.source.SourceException]. */
enum class SourceErrorKind { AUTH, NETWORK, OTHER }

/** What one source's status line should say. Built by [sourceStatus]. */
sealed class SourceStatus {
    data class UpToDate(val movies: Long, val shows: Long, val channels: Long) : SourceStatus()
    data class Updating(val stage: SyncStage) : SourceStatus()
    data object Offline : SourceStatus()
    data class SignInExpired(val plex: Boolean) : SourceStatus()
    data class LastFailed(val atMs: Long) : SourceStatus()
    data object Checking : SourceStatus()
}

data class SourceStatusInput(
    val counts: Map<ContentKind, Long>,
    val syncingStage: SyncStage?,
    val errorKind: SourceErrorKind?,
    val lastFailedMs: Long?,
    val plex: Boolean,
)

/** Priority: updating > sign-in problem > offline > other failure > healthy (with counts). */
fun sourceStatus(i: SourceStatusInput): SourceStatus = when {
    i.syncingStage != null -> SourceStatus.Updating(i.syncingStage)
    i.errorKind == SourceErrorKind.AUTH -> SourceStatus.SignInExpired(i.plex)
    i.errorKind == SourceErrorKind.NETWORK -> SourceStatus.Offline
    i.errorKind == SourceErrorKind.OTHER -> SourceStatus.LastFailed(i.lastFailedMs ?: 0L)
    i.counts.isEmpty() -> SourceStatus.Checking
    else -> SourceStatus.UpToDate(
        movies = i.counts[ContentKind.VOD] ?: 0L,
        shows = i.counts[ContentKind.SERIES] ?: 0L,
        channels = i.counts[ContentKind.LIVE] ?: 0L,
    )
}

/** "1,234 movies · 567 shows · 89 channels" — grouped digits, no provider detail. */
fun formatCounts(movies: Long, shows: Long, channels: Long): String =
    "${groupDigits(movies)} movies · ${groupDigits(shows)} shows · ${groupDigits(channels)} channels"

private fun groupDigits(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val out = StringBuilder()
    val lead = s.length % 3
    if (lead > 0) out.append(s.substring(0, lead))
    var i = lead
    while (i < s.length) {
        if (out.isNotEmpty()) out.append(',')
        out.append(s.substring(i, i + 3))
        i += 3
    }
    return out.toString()
}

/** "just now" / "12 min ago" / "6 h ago" / "3 days ago" — the wording the Settings screen uses. */
fun relativeTime(nowMs: Long, thenMs: Long): String {
    val mins = (nowMs - thenMs) / 60_000L
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 48 * 60 -> "${mins / 60} h ago"
        else -> "${mins / 1440} days ago"
    }
}

/** Status line text (no action word). The follow-up action is [retryAction]. */
fun SourceStatus.statusText(nowMs: Long): String = when (this) {
    is SourceStatus.UpToDate -> "Up to date · ${formatCounts(movies, shows, channels)}"
    is SourceStatus.Updating -> "Updating… ${stage.name.lowercase().replace('_', ' ')}"
    SourceStatus.Offline -> "Offline — couldn't reach the server"
    is SourceStatus.SignInExpired -> if (plex) "Sign-in expired" else "Check your login"
    is SourceStatus.LastFailed -> if (atMs <= 0L) "Last update failed" else "Last update failed ${relativeTime(nowMs, atMs)}"
    SourceStatus.Checking -> "Checking…"
}

/** "Retry" / "Re-link" / "Re-enter login" / null — the clickable follow-up shown next to the status line. */
fun SourceStatus.retryAction(): String? = when (this) {
    SourceStatus.Offline -> "Retry"
    is SourceStatus.LastFailed -> "Retry"
    // Plex re-links through the PIN flow (a new source); Xtream/M3U re-enter the same source's login.
    is SourceStatus.SignInExpired -> if (plex) "Re-link" else "Re-enter login"
    else -> null
}

/** Settings-table keys that carry the recorded status between the view model and per-source extras. */
object SourceStatusKeys {
    fun error(id: SourceId): String = "src_err_${id.value}"
    fun sync(id: SourceId): String = "src_sync_${id.value}"
}

fun encodeError(kind: SourceErrorKind?, atMs: Long?): String =
    if (kind == null) "" else "${kind.name}|${atMs ?: 0L}"

fun decodeError(value: String?): Pair<SourceErrorKind?, Long?> {
    if (value.isNullOrEmpty()) return null to null
    val parts = value.split('|')
    val kind = runCatching { SourceErrorKind.valueOf(parts[0]) }.getOrNull()
    val at = parts.getOrNull(1)?.toLongOrNull()
    return kind to at
}

fun encodeSync(stage: SyncStage?): String = stage?.name ?: ""

fun decodeSync(value: String?): SyncStage? =
    value?.takeIf { it.isNotEmpty() }?.let { runCatching { SyncStage.valueOf(it) }.getOrNull() }
