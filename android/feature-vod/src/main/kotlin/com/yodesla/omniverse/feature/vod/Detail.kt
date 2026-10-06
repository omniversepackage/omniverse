package com.yodesla.omniverse.feature.vod
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.requestFocusWhenReady

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.ItemOverride
import com.yodesla.omniverse.core.data.TitleIdentity
import com.yodesla.omniverse.core.data.TitleOverride
import com.yodesla.omniverse.core.data.LibraryCollection
import com.yodesla.omniverse.core.data.SmartCollectionFilter
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.Episode
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SeriesDetail
import com.yodesla.omniverse.core.model.VodDetail
import com.yodesla.omniverse.core.model.mediaProgressKey
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.designsystem.ErrorCard
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.CosmicBackdrop
import com.yodesla.omniverse.designsystem.LocalCompact
import com.yodesla.omniverse.designsystem.MenuAction
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.ProgressLine
import com.yodesla.omniverse.designsystem.SkeletonBox
import com.yodesla.omniverse.designsystem.TitleMenu
import com.yodesla.omniverse.designsystem.displayTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the VOD player needs to open and resume an item. */
@Immutable
data class PlayItem(
    val request: PlaybackRequest,
    val key: ContentKey,
    val parentId: RemoteId?,
    val title: String,
    val resumeMs: Long,
    /** Episodes: the next one, for auto-next. */
    val next: PlayItem? = null,
    /** False for catch-up programmes: they must not land in Continue Watching under the channel key. */
    val trackProgress: Boolean = true,
    /** Public catalog ID for optional community skip lookup; never a provider URL or credential. */
    val tmdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val durationMs: Long? = null,
    /** Original provider series metadata for cautious ID resolution when it supplies no TMDB ID. */
    val seriesLookupTitle: String? = null,
    val seriesYear: Int? = null,
)

data class RelatedMovie(val poster: PosterRow, val sourceName: String, val editionLabel: String?)

@Immutable
data class RelatedMetadata(
    val kind: String, val sourceId: String, val categoryId: String?,
    val poster: String?, val backdrop: String?, val plot: String?, val cast: String?,
)

@Immutable
data class DetailState(
    val loading: Boolean = true,
    /** Rotten Tomatoes critics/audience scores (exact TMDB id only). */
    val rt: com.yodesla.omniverse.core.data.metadata.RtScores? = null,
    val error: String? = null,
    /** A watched change the provider did not confirm (audit M10); null once every intent landed. */
    val watchSyncError: String? = null,
    val title: String = "",
    val backdrop: String? = null,
    val poster: String? = null,
    /** Display name of the source this item came from; names the play chips. */
    val sourceName: String = "",
    val year: Int? = null,
    val genre: String? = null,
    val runtimeSec: Int? = null,
    val ratingValue: Float? = null,
    val plot: String? = null,
    val cast: String? = null,
    val favorite: Boolean = false,
    val progress: Progress? = null,
    val durationMs: Long? = null,
    val series: SeriesDetail? = null,
    val season: Int = 1,
    /** Episode the play chip is ready on (task 71 "New episodes"); null = normal resume. */
    val focusEpisodeId: RemoteId? = null,
    val episodeProgress: Map<String, Progress> = emptyMap(),
    val movie: VodDetail? = null,
    val selectedVersionId: String? = null,
    val versionProgress: Map<String, Progress> = emptyMap(),
    val legacyMovieProgress: Progress? = null,
    /** Exact catalog id of this title; the version chooser's memory hangs off it. */
    val tmdbId: String? = null,
    /** This profile's last version choice for the title ("v:…" / "k:source:remote"); null if never picked. */
    val lastEditionPick: String? = null,
    /** Saved watch on each exact-ID alternative copy, keyed by that copy's item key. */
    val editionCopyProgress: Map<ContentKey, Progress> = emptyMap(),
    /** Runtime of each alternative copy that the source would state; absent when it would not. */
    val editionCopyRuntimeSec: Map<ContentKey, Int> = emptyMap(),
    val relatedMovies: List<RelatedMovie> = emptyList(),
    val relatedMetadata: RelatedMetadata? = null,
    val itemOverride: ItemOverride? = null,
    val titleIdentity: TitleIdentity? = null,
    val titleOverride: TitleOverride? = null,
    val collections: List<LibraryCollection> = emptyList(),
    val memberCollectionIds: Set<String> = emptySet(),
    /** Opt-in Wikidata/Wikipedia gap-filler result; only used where source and exact-ID fallbacks are blank. */
    val enriched: com.yodesla.omniverse.core.data.metadata.EnrichedMetadata? = null,
    /** TMDB title logo (task 84d); replaces the text title in the header when present. */
    val logoUrl: String? = null,
    /** US certification from TMDB (task 84d); null when unknown. */
    val certification: String? = null,
    /** TMDB episode stills by season (task 84d); used only where the source has no still. */
    val tmdbStills: Map<Int, List<com.yodesla.omniverse.core.data.metadata.TmdbEpisodeStill>> = emptyMap(),
    val plotAttribution: String? = null,
    val plotAttributionUrl: String? = null,
    val plotAttributionLicenseUrl: String? = null,
    /** Task 95: this profile's spoiler-free switch (Settings › Playback); drives the episode list. */
    val spoilerFree: Boolean = false,
    /** Task 95 sub-option "Also hide episode titles". */
    val spoilerHideTitles: Boolean = false,
    /** Episode ids revealed by pressing OK on a hidden card while this page is open; never persisted. */
    val revealedSpoilerIds: Set<String> = emptySet(),
)

/**
 * Task 90: the labels the Detail "More" menu holds for a title. The rarely-used management actions
 * (Edit details, Collections) always live here instead of the primary action row; the version/source
 * choice joins them only when the title has more than one playable copy. Pure so it can be tested
 * without a ViewModel or coroutines.
 */
internal fun moreMenuLabelsFor(key: ContentKey, s: DetailState, visibility: Visibility): List<String> {
    val labels = mutableListOf("Edit details", "Collections")
    if (s.series == null && s.movie != null) {
        val related = s.relatedMovies.filter { row ->
            row.poster.categoryId?.let { visibility(row.poster.key.kind.name, row.poster.key.sourceId.value, it.value) } ?: true
        }
        val playOptions = buildPlayOptions(key, s.sourceName, s.itemOverride?.editionLabel, s.movie.versions.orEmpty(), related, s.movie.record.name)
        val copies = buildEditionCopies(playOptions, key, s.runtimeSec, s.progress, s.versionProgress, s.editionCopyProgress, s.editionCopyRuntimeSec, related.associate { it.poster.key to it.poster.name })
        val ranked = editionCopiesRanked(copies, s.lastEditionPick)
        if (editionChooserNeeded(ranked)) {
            editionChipLabel(chosenEditionCopy(ranked, s.lastEditionPick))?.let { labels.add("Version: $it") }
        }
    }
    return labels
}

class DetailViewModel(
    private val key: ContentKey,
    private val sources: SourceRepository,
    private val userData: UserDataRepository,
    private val catalog: com.yodesla.omniverse.core.data.CatalogRepository? = null,
    private val visibility: kotlinx.coroutines.flow.Flow<Visibility> = ShowEverything,
    private val enricher: com.yodesla.omniverse.core.data.metadata.MetadataEnricher? = null,
    private val rottenTomatoes: com.yodesla.omniverse.core.data.metadata.RottenTomatoes? = null,
    /** TMDB artwork/details gap-filler (task 84d); null or keyless build = source art only. */
    private val tmdb: com.yodesla.omniverse.core.data.metadata.TmdbEnricher? = null,
    /** Episode to open the show ready on (task 71 "New episodes" card). */
    private val focusEpisode: RemoteId? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    // Task 90: the last My List removal that can still be undone, shown by the bottom-centre undo card.
    private val _undo = MutableStateFlow<com.yodesla.omniverse.core.data.UndoUi?>(null)
    val undo: StateFlow<com.yodesla.omniverse.core.data.UndoUi?> = _undo.asStateFlow()

    // Task 90: latest visibility, so the More menu's copy count matches what the screen actually shows.
    private var currentVisibility: com.yodesla.omniverse.core.data.Visibility = { _, _, _ -> true }

    init {
        rottenTomatoes?.let { rt ->
            viewModelScope.launch {
                val st = _state.first { !it.loading && (it.movie != null || it.series != null) }
                val found = if (st.movie != null) rt.scores(com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.MOVIE, st.movie.record.tmdbId)
                    else rt.scores(com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.SERIES, st.series?.record?.tmdbId)
                if (found != null) _state.update { it.copy(rt = found) }
            }
        }
    }

    init {
        // One opt-in lookup per detail page, after the source detail has loaded.
        enricher?.let { e ->
            viewModelScope.launch {
                val st = _state.first { !it.loading && (it.movie != null || it.series != null) }
                val m = st.movie
                val sr = st.series
                val found = runCatching {
                    if (m != null) e.enrich(com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.MOVIE, m.record.tmdbId,
                        st.plot, st.cast, m.director, m.genre, st.poster)
                    else e.enrich(com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.SERIES, sr?.record?.tmdbId,
                        st.plot, st.cast, sr?.director, sr?.record?.genre, st.poster)
                }.getOrNull() ?: return@launch
                _state.update { it.copy(enriched = found) }
            }
        }
    }

    init {
        // Task 84d/84f: one TMDB pass per detail page, after the source detail has loaded. Source
        // art and plot always win; TMDB fills the gaps (backdrop, synopsis), supplies the logo (it
        // is the only source), and adds certification/runtime the provider never gave us.
        tmdb?.let { t ->
            viewModelScope.launch {
                val st = _state.first { !it.loading && (it.movie != null || it.series != null) }
                val id = st.movie?.record?.tmdbId ?: st.series?.record?.tmdbId
                val kind = if (st.movie != null) com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.MOVIE
                    else com.yodesla.omniverse.core.data.metadata.WikidataMetadata.Kind.SERIES
                val m = runCatching { t.meta(kind, id) }.getOrNull() ?: return@launch
                _state.update {
                    it.copy(
                        backdrop = it.backdrop ?: com.yodesla.omniverse.core.data.metadata.Tmdb.backdropUrl(m.backdropPath),
                        logoUrl = it.logoUrl ?: com.yodesla.omniverse.core.data.metadata.Tmdb.logoUrl(m.logoPath),
                        certification = it.certification ?: m.certification,
                        runtimeSec = it.runtimeSec ?: m.runtimeMin?.times(60),
                        // Task 84f: the TMDB synopsis fills the gap the provider left. The opt-in
                        // Wikipedia pass below only gets the plot (and owes attribution) if this is blank.
                        plot = com.yodesla.omniverse.core.data.metadata.pickPlot(it.plot, m.overview),
                    )
                }
            }
        }
    }

    fun sourceId(): String = key.sourceId.value
    fun currentKey(): ContentKey = key

    /**
     * Task 90: the labels the Detail "More" menu holds for the current title. The rarely-used
     * management actions (Edit details, Collections) always live here instead of the primary row;
     * the version/source choice joins them only when the title has more than one playable copy.
     */
    fun moreMenuLabels(): List<String> = moreMenuLabelsFor(key, _state.value, currentVisibility)

    init {
        // Task 95: this profile's spoiler-free switches (Settings › Playback), both default off.
        kotlinx.coroutines.flow.combine(
            userData.setting(UserDataRepository.SPOILER_FREE),
            userData.setting(UserDataRepository.SPOILER_FREE_HIDE_TITLES),
        ) { on, titles -> com.yodesla.omniverse.core.data.SpoilerFree.enabled(on) to com.yodesla.omniverse.core.data.SpoilerFree.hideTitles(titles) }
            .onEach { (on, titles) -> _state.update { it.copy(spoilerFree = on, spoilerHideTitles = titles) } }
            .launchIn(viewModelScope)
    }

    init {
        load()
        visibility.onEach { currentVisibility = it }.launchIn(viewModelScope)
        userData.isFavorite(key).onEach { f -> _state.update { it.copy(favorite = f) } }.launchIn(viewModelScope)
        userData.collections().onEach { rows ->
            val identity = catalog?.poster(key)?.titleIdentity()
            val memberships = rows.filter { it.contentKind == key.kind && it.mode == "manual" }
                .filter { collection -> key in userData.collectionItems(collection.id) ||
                    (identity != null && identity in userData.collectionTitles(collection.id))
                }.mapTo(mutableSetOf()) { it.id }
            _state.update { it.copy(collections = rows, memberCollectionIds = memberships) }
        }.launchIn(viewModelScope)
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val source = sources.contentSource(key.sourceId) ?: return@launch _state.update { it.copy(loading = false, error = "This source was removed.") }
            try {
                val custom = userData.itemOverride(key)
                val titleIdentity = catalog?.poster(key)?.titleIdentity()
                val titleCustom = titleIdentity?.let { userData.titleOverride(it) }
                val sourceNames = sources.sources().first().associate { it.id to it.name }
                val allowed = visibility.first()
                if (key.kind == ContentKind.SERIES) {
                    val d = source.seriesDetail(key.remoteId)
                    val related = catalog?.exactSeriesMatches(key).orEmpty().map {
                        RelatedMovie(it, sourceNames[it.key.sourceId] ?: "Source", userData.itemOverride(it.key)?.editionLabel)
                    }
                    val fallback = if (d.record.posterUrl.isNullOrBlank() || d.record.plot.isNullOrBlank() || d.cast.isNullOrBlank())
                        related.firstOrNull { row -> row.poster.categoryId?.let { allowed("SERIES", row.poster.key.sourceId.value, it.value) } ?: true }
                            ?.let { row -> runCatching { sources.contentSource(row.poster.key.sourceId)?.seriesDetail(row.poster.key.remoteId) }.getOrNull()?.let { row to it } }
                    else null
                    val prog = HashMap<String, Progress>()
                    d.seasons.forEach { s -> s.episodes.forEach { e -> userData.progress(ContentKey(key.sourceId, ContentKind.EPISODE, e.remoteId))?.let { prog[e.remoteId.value] = it } } }
                    // Resume where the viewer left off: season of the most recently watched episode; when
                    // that watch is completed (task 57 "Up next"), the season of the NEXT episode instead.
                    val ordered = d.seasons.sortedBy { it.number }.flatMap { s -> s.episodes.sortedBy { it.number } }
                    val lastProg = prog.values.maxByOrNull { it.updatedMs }
                    val lastEpisode = lastProg?.let { p -> ordered.firstOrNull { it.remoteId == p.key.remoteId } }
                    val resumeEpisode = if (lastProg?.completed == true && lastEpisode != null) {
                        ordered.getOrNull(ordered.indexOfFirst { it.remoteId == lastEpisode.remoteId } + 1) ?: lastEpisode
                    } else lastEpisode
                    // Task 71: a "New episodes" card opens the show ready on the first unseen new
                    // episode; once that episode has progress the normal resume takes over.
                    val focus = focusEpisode?.let { id -> ordered.firstOrNull { it.remoteId == id && prog[id.value] == null } }
                    val lastSeason = (focus ?: resumeEpisode)?.let { ep ->
                        d.seasons.firstOrNull { s -> s.episodes.any { it.remoteId == ep.remoteId } }?.number
                    } ?: d.seasons.firstOrNull()?.number ?: 1
                    // Task 86: the chooser lists show copies too; only their saved watch is worth a lookup.
                    val tmdb = d.record.tmdbId?.trim()?.takeIf { it.isNotEmpty() }
                    val lastPick = editionPickSettingKey(tmdb)?.let { k -> userData.setting(k).first()?.takeIf { v -> v.isNotBlank() } }
                    val altProgress = related.filter { row ->
                        row.poster.categoryId?.let { allowed("SERIES", row.poster.key.sourceId.value, it.value) } ?: true
                    }.map { it.poster.key }.distinct().take(MAX_EDITION_ALTS)
                        .mapNotNull { alt -> userData.progress(alt)?.let { alt to it } }.toMap()
                    _state.update {
                        it.copy(
                            loading = false, title = titleCustom?.displayTitle ?: custom?.displayTitle ?: displayTitle(d.record.name, d.record.year),
                            backdrop = d.record.backdropUrls.firstOrNull(),
                            poster = titleCustom?.customPosterUrl ?: custom?.customPosterUrl ?: d.record.posterUrl?.takeIf { it.isNotBlank() },
                            plot = d.record.plot?.takeIf { it.isNotBlank() },
                            cast = d.cast?.takeIf { it.isNotBlank() },
                            sourceName = sourceNames[key.sourceId] ?: "Source",
                            year = d.record.year, genre = d.record.genre, ratingValue = d.record.rating, // season count lives in the overline
                            series = d, season = lastSeason, episodeProgress = prog, itemOverride = custom,
                            focusEpisodeId = focus?.remoteId,
                            titleIdentity = titleIdentity, titleOverride = titleCustom,
                            relatedMovies = related,
                            relatedMetadata = fallback?.let { (row, detail) -> RelatedMetadata("SERIES", row.poster.key.sourceId.value,
                                row.poster.categoryId?.value, detail.record.posterUrl, detail.record.backdropUrls.firstOrNull(), detail.record.plot, detail.cast) },
                            tmdbId = tmdb, lastEditionPick = lastPick, editionCopyProgress = altProgress,
                        )
                    }
                    loadStills(lastSeason)
                } else {
                    val d = source.vodDetail(key.remoteId)
                    val related = catalog?.exactMovieMatches(key).orEmpty().map {
                        RelatedMovie(it, sourceNames[it.key.sourceId] ?: "Source", userData.itemOverride(it.key)?.editionLabel)
                    }
                    val fallback = if (d.record.posterUrl.isNullOrBlank() || d.plot.isNullOrBlank() || d.cast.isNullOrBlank())
                        related.firstOrNull { row -> row.poster.categoryId?.let { allowed("VOD", row.poster.key.sourceId.value, it.value) } ?: true }
                            ?.let { row -> runCatching { sources.contentSource(row.poster.key.sourceId)?.vodDetail(row.poster.key.remoteId) }.getOrNull()?.let { row to it } }
                    else null
                    // Task 86: what the version chooser needs about the OTHER copies — this profile's
                    // saved pick, each copy's saved watch, and each copy's runtime when the source states one.
                    val tmdb = d.record.tmdbId?.trim()?.takeIf { it.isNotEmpty() }
                    val lastPick = editionPickSettingKey(tmdb)?.let { k -> userData.setting(k).first()?.takeIf { v -> v.isNotBlank() } }
                    val altKeys = related.filter { row ->
                        row.poster.categoryId?.let { allowed("VOD", row.poster.key.sourceId.value, it.value) } ?: true
                    }.map { it.poster.key }.distinct().take(MAX_EDITION_ALTS)
                    val altProgress = altKeys.mapNotNull { alt -> userData.progress(alt)?.let { alt to it } }.toMap()
                    val altRuntime = altKeys.mapNotNull { alt ->
                        runCatching { sources.contentSource(alt.sourceId)?.vodDetail(alt.remoteId)?.durationSec }.getOrNull()?.let { alt to it }
                    }.toMap()
                    val versionProgress = if (d.versions.size > 1) d.versions.mapNotNull { version ->
                        userData.progress(mediaProgressKey(key, version.id))?.let { version.id to it }
                    }.toMap().toMutableMap() else mutableMapOf()
                    val legacyProgress = userData.progress(key)
                    val legacyDuration = legacyProgress?.durationMs?.takeIf { it > 0 }
                    if (d.versions.size > 1 && legacyProgress != null && legacyProgress.positionMs > 0 &&
                        (legacyDuration?.let { legacyProgress.positionMs < it * 0.92 } ?: true)) {
                        val firstId = d.versions.first().id
                        if (firstId !in versionProgress) {
                            val variantKey = mediaProgressKey(key, firstId)
                            userData.saveProgress(variantKey, key.remoteId, legacyProgress.positionMs, legacyProgress.durationMs)
                            userData.progress(variantKey)?.let { versionProgress[firstId] = it }
                        }
                        // Retire the old shared row only when a real duration proves the viewer reached
                        // its end. An unknown-length film has nothing to prove, so the shared row stays
                        // as the Continue Watching resume point instead of being marked watched.
                        if (legacyDuration != null) userData.saveProgress(key, null, legacyDuration, legacyDuration)
                    }
                    val selected = versionProgress.maxByOrNull { it.value.updatedMs }?.key ?: defaultEditionVersionId(d.versions, lastPick)
                    val p = selected?.let(versionProgress::get) ?: if (d.versions.size > 1) null else legacyProgress
                    _state.update {
                        it.copy(
                            loading = false, title = titleCustom?.displayTitle ?: custom?.displayTitle ?: displayTitle(d.record.name, d.record.year),
                            backdrop = d.backdropUrls.firstOrNull(),
                            poster = titleCustom?.customPosterUrl ?: custom?.customPosterUrl ?: d.record.posterUrl?.takeIf { it.isNotBlank() },
                            plot = d.plot?.takeIf { it.isNotBlank() },
                            cast = d.cast?.takeIf { it.isNotBlank() }, movie = d,
                            sourceName = sourceNames[key.sourceId] ?: "Source",
                            year = d.record.year, genre = d.genre ?: d.record.genre,
                            runtimeSec = d.durationSec, ratingValue = d.record.rating,
                            progress = p, durationMs = d.durationSec?.let { s -> s * 1000L },
                            selectedVersionId = selected, versionProgress = versionProgress,
                            legacyMovieProgress = if (d.versions.size > 1) null else legacyProgress,
                            tmdbId = tmdb, lastEditionPick = lastPick,
                            editionCopyProgress = altProgress, editionCopyRuntimeSec = altRuntime,
                            relatedMovies = related, itemOverride = custom,
                            relatedMetadata = fallback?.let { (row, detail) -> RelatedMetadata("VOD", row.poster.key.sourceId.value,
                                row.poster.categoryId?.value, detail.record.posterUrl, detail.backdropUrls.firstOrNull(), detail.plot, detail.cast) },
                            titleIdentity = titleIdentity, titleOverride = titleCustom,
                        )
                    }
                }
            } catch (e: SourceException) {
                // Redacted: provider URLs carry credentials.
                android.util.Log.w("Omniverse", "detail ${key.kind} failed: ${e::class.simpleName}: ${com.yodesla.omniverse.core.model.Redact.text(e.message.orEmpty())} | cause=${e.cause?.let { it::class.simpleName + ": " + com.yodesla.omniverse.core.model.Redact.text(it.message.orEmpty()) }}")
                _state.update { it.copy(loading = false, error = "Couldn't load details. ${if (e is SourceException.Network) "Check your connection." else "The provider didn't answer as expected."}") }
            }
        }
    }

    fun selectSeason(n: Int) {
        _state.update { it.copy(season = n) }
        loadStills(n)
    }

    /** Task 95: OK on a spoiler-hidden episode card reveals its description for the rest of the visit. */
    fun revealEpisode(ep: Episode) {
        _state.update { it.copy(revealedSpoilerIds = it.revealedSpoilerIds + ep.remoteId.value) }
    }

    /** Task 84d: warm TMDB stills for the season being shown (cache-first, off = no request). */
    private fun loadStills(season: Int) {
        val t = tmdb ?: return
        viewModelScope.launch {
            val id = _state.first { !it.loading }.series?.record?.tmdbId ?: return@launch
            val stills = runCatching { t.seasonStills(id, season) }.getOrNull().orEmpty()
            if (stills.isNotEmpty()) _state.update { it.copy(tmdbStills = it.tmdbStills + (season to stills)) }
        }
    }

    // Task 90: removing from My List captures an exact restore token so the undo card can re-add it.
    fun toggleFavorite() = viewModelScope.launch {
        if (_state.value.favorite) {
            val token = userData.removeFromMyList(key)
            _undo.value = com.yodesla.omniverse.core.data.UndoUi("Removed from My List", token)
        } else {
            userData.setFavorite(key, true)
        }
    }

    fun undoLast() {
        val ui = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch {
            when (val token = ui.token) {
                is com.yodesla.omniverse.core.data.UndoToken.ContinueWatching -> userData.restoreContinueWatching(token)
                is com.yodesla.omniverse.core.data.UndoToken.Favorite -> userData.restoreMyList(token)
                else -> Unit
            }
        }
    }

    fun dismissUndo() { _undo.value = null }

    fun saveLocalDetails(customTitle: String, sortTitle: String, editionLabel: String) = viewModelScope.launch {
        val title = customTitle.trim().ifEmpty { null }
        val sort = sortTitle.trim().ifEmpty { null }
        val current = _state.value
        val identity = current.titleIdentity
        val old = current.itemOverride ?: ItemOverride()
        val item = if (identity == null) old.copy(displayTitle = title, sortTitle = sort, editionLabel = editionLabel.trim().ifEmpty { null })
            else old.copy(displayTitle = null, sortTitle = null, editionLabel = editionLabel.trim().ifEmpty { null })
        if (identity != null) {
            val group = (current.titleOverride ?: TitleOverride()).copy(displayTitle = title, sortTitle = sort)
            userData.setTitleOverride(identity, group)
            _state.update { it.copy(titleOverride = group) }
        }
        userData.setItemOverride(key, item)
        _state.update { it.copy(itemOverride = item, title = title ?: it.movie?.record?.let { m -> displayTitle(m.name, m.year) }
            ?: it.series?.record?.let { s -> displayTitle(s.name, s.year) } ?: it.title) }
    }

    fun savePosterUri(uri: String?) = viewModelScope.launch {
        val current = _state.value
        val identity = current.titleIdentity
        val old = current.itemOverride ?: ItemOverride()
        val item = old.copy(customPosterUrl = if (identity == null) uri else null)
        if (identity != null) {
            val group = (current.titleOverride ?: TitleOverride()).copy(customPosterUrl = uri)
            userData.setTitleOverride(identity, group)
            _state.update { it.copy(titleOverride = group) }
        }
        userData.setItemOverride(key, item)
        _state.update { it.copy(itemOverride = item,
            poster = uri ?: it.movie?.record?.posterUrl ?: it.series?.record?.posterUrl) }
    }

    fun createCollection(name: String, pinnedHome: Boolean = false) = viewModelScope.launch {
        if (name.trim().isNotEmpty()) {
            val id = java.util.UUID.randomUUID().toString()
            userData.saveCollection(LibraryCollection(id, name.trim(), key.kind, pinnedHome = pinnedHome))
            _state.value.titleIdentity?.let { userData.addTitleToCollection(id, it) }
                ?: userData.addToCollection(id, key)
        }
    }

    fun createSmartCollection(name: String, filter: SmartCollectionFilter, pinnedHome: Boolean) = viewModelScope.launch {
        if (name.trim().isNotEmpty()) userData.saveCollection(LibraryCollection(java.util.UUID.randomUUID().toString(),
            name.trim(), key.kind, pinnedHome = pinnedHome, mode = "smart", smartFilterJson = filter.encode()))
    }

    fun addToCollection(id: String) = viewModelScope.launch {
        _state.value.titleIdentity?.let { userData.addTitleToCollection(id, it) }
            ?: userData.addToCollection(id, key)
    }

    fun removeFromCollection(id: String) = viewModelScope.launch {
        _state.value.titleIdentity?.let { userData.removeTitleFromCollection(id, it) }
        userData.removeFromCollection(id, key)
    }

    fun toggleCollectionPin(collection: LibraryCollection) = viewModelScope.launch {
        userData.saveCollection(collection.copy(pinnedHome = !collection.pinnedHome))
    }

    fun moveCollection(id: String, direction: Int) = viewModelScope.launch {
        val ordered = _state.value.collections.toMutableList()
        val from = ordered.indexOfFirst { it.id == id }
        val to = from + direction
        if (from < 0 || to !in ordered.indices) return@launch
        java.util.Collections.swap(ordered, from, to)
        ordered.forEachIndexed { index, collection ->
            if (collection.sortOrder != index.toLong()) userData.saveCollection(collection.copy(sortOrder = index.toLong()))
        }
    }

    fun saveCollection(value: LibraryCollection) = viewModelScope.launch {
        val currentOrder = userData.collections().first().firstOrNull { it.id == value.id }?.sortOrder
        userData.saveCollection(value.copy(sortOrder = currentOrder ?: value.sortOrder))
    }

    fun deleteCollection(id: String) = viewModelScope.launch { userData.deleteCollection(id) }

    fun selectVersion(id: String) {
        if (_state.value.movie?.versions?.any { it.id == id } == true) {
            _state.update { state ->
                val primaryId = state.movie?.versions?.firstOrNull()?.id
                state.copy(selectedVersionId = id, lastEditionPick = "v:$id",
                    progress = state.versionProgress[id] ?: if (id == primaryId) state.legacyMovieProgress else null)
            }
            rememberEditionPick("v:$id")
        }
    }

    /**
     * Task 86: remember which copy of this exact-ID title the viewer chose, per profile, so the
     * next time the title opens Play and the Version chip follow it. Blank/absent id = nothing to
     * remember (a source with no trustworthy catalog id cannot be merged with another anyway).
     */
    fun rememberEditionPick(optionId: String) {
        val settingKey = editionPickSettingKey(_state.value.tmdbId) ?: return
        _state.update { it.copy(lastEditionPick = optionId) }
        viewModelScope.launch { userData.putSetting(settingKey, optionId) }
    }

    fun moviePlayItem(fromStart: Boolean): PlayItem? {
        val m = _state.value.movie ?: return null
        val versionId = _state.value.selectedVersionId.takeIf { m.versions.size > 1 }
        val progressKey = versionId?.let { mediaProgressKey(key, it) } ?: key
        val resume = _state.value.progress?.positionMs?.takeIf { !fromStart } ?: 0L
        return PlayItem(PlaybackRequest.Vod(key.sourceId, key.remoteId, m.record.containerExt, versionId), progressKey,
            if (versionId != null) key.remoteId else null, _state.value.title, resume,
            tmdbId = m.record.tmdbId, durationMs = m.durationSec?.times(1000L))
    }

    fun episodePlayItem(ep: Episode, fromStart: Boolean = false): PlayItem {
        val all = _state.value.series?.seasons?.flatMap { it.episodes }.orEmpty()
        return buildEpisodeQueue(ep, all, key, _state.value.title, _state.value.episodeProgress,
            _state.value.series?.record?.tmdbId, _state.value.series?.record?.name,
            _state.value.series?.record?.year, fromStart)
    }

    private fun episodeKey(ep: Episode) = ContentKey(key.sourceId, ContentKind.EPISODE, ep.remoteId)

    private fun watchedProgress(ep: Episode): Progress {
        val dur = ep.durationSec?.times(1000L)?.takeIf { it > 0 }
        return Progress(episodeKey(ep), key.remoteId, dur ?: 1L, dur, System.currentTimeMillis(), completed = true)
    }

    /** Mark one episode watched (completed, leaves Continue Watching) and refresh state immediately. */
    fun markWatched(ep: Episode) = viewModelScope.launch {
        userData.setWatched(episodeKey(ep), key.remoteId, true, ep.durationSec?.times(1000L))
        _state.update { it.copy(episodeProgress = it.episodeProgress + (ep.remoteId.value to watchedProgress(ep))) }
        launch { pushWatchToProvider(ep.remoteId, true, episodeLabel(ep)) }
    }

    /** Mark one episode unwatched (its progress row is deleted) and refresh state immediately. */
    fun markUnwatched(ep: Episode) = viewModelScope.launch {
        userData.setWatched(episodeKey(ep), key.remoteId, false, null)
        _state.update { it.copy(episodeProgress = it.episodeProgress - ep.remoteId.value) }
        launch { pushWatchToProvider(ep.remoteId, false, episodeLabel(ep)) }
    }

    /** Mark every episode of [seasonNumber] watched or unwatched, then refresh state once. */
    fun markSeason(seasonNumber: Int, watched: Boolean) = viewModelScope.launch {
        val eps = _state.value.series?.seasons?.firstOrNull { it.number == seasonNumber }?.episodes.orEmpty()
        if (eps.isEmpty()) return@launch
        var progress = _state.value.episodeProgress
        eps.forEach { ep ->
            userData.setWatched(episodeKey(ep), key.remoteId, watched, ep.durationSec?.times(1000L))
            progress = if (watched) progress + (ep.remoteId.value to watchedProgress(ep)) else progress - ep.remoteId.value
        }
        _state.update { it.copy(episodeProgress = progress) }
        // One sequential provider pass for the whole season, after the local marks are already visible.
        launch { eps.forEach { pushWatchToProvider(it.remoteId, watched, episodeLabel(it)) } }
    }

    /**
     * The provider half of a watched change (audit M10). The local mark above is already saved and
     * stays exactly as the viewer left it; if the provider did not confirm, the intent is remembered
     * and [DetailState.watchSyncError] shows a short message until [retryWatchSync] lands it.
     * The source itself serializes/coalesces per ratingKey, so rapid flips cannot invert remotely.
     */
    private suspend fun pushWatchToProvider(remoteId: RemoteId, watched: Boolean, label: String) {
        try {
            sources.contentSource(key.sourceId)?.setWatched(remoteId, watched)
            failedWatchSync.remove(remoteId.value)
            _state.update { it.copy(watchSyncError = if (failedWatchSync.isEmpty()) null else watchSyncErrorText()) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failedWatchSync[remoteId.value] = FailedWatchSync(remoteId, watched, label)
            _state.update { it.copy(watchSyncError = watchSyncErrorText()) }
        }
    }

    /** Re-send every watched change the provider didn't confirm (local state is already correct). */
    fun retryWatchSync() = viewModelScope.launch {
        failedWatchSync.values.toList().forEach { failed -> pushWatchToProvider(failed.remoteId, failed.watched, failed.label) }
    }

    private fun watchSyncErrorText(): String {
        val failed = failedWatchSync.values
        return when (failed.size) {
            0 -> ""
            1 -> "Watched change for ${failed.first().label} didn't reach the provider."
            else -> "${failed.size} watched changes didn't reach the provider."
        }
    }

    private fun episodeLabel(ep: Episode) = "S${ep.season}E${ep.number}"

    private class FailedWatchSync(val remoteId: RemoteId, val watched: Boolean, val label: String)

    private val failedWatchSync = mutableMapOf<String, FailedWatchSync>()
}

/** Carry the entire remaining series queue through player-to-player navigation. */
internal fun buildEpisodeQueue(ep: Episode, all: List<Episode>, seriesKey: ContentKey, seriesTitle: String,
    progress: Map<String, Progress>, tmdbId: String? = null,
    seriesLookupTitle: String? = null, seriesYear: Int? = null, fromStart: Boolean = false): PlayItem {
    fun item(e: Episode, next: PlayItem?): PlayItem {
        val k = ContentKey(seriesKey.sourceId, ContentKind.EPISODE, e.remoteId)
        val title = episodeTitle(e.title, seriesTitle, e.number)
        val label = "$seriesTitle · S${e.season}E${e.number}" + if (title == "Episode ${e.number}") "" else " · $title"
        return PlayItem(
            PlaybackRequest.EpisodeItem(seriesKey.sourceId, e.remoteId, e.containerExt), k, seriesKey.remoteId,
            label,
            progress[e.remoteId.value]?.positionMs?.takeIf { !(fromStart && e.remoteId == ep.remoteId) } ?: 0L, next,
            tmdbId = tmdbId, season = e.season, episode = e.number, durationMs = e.durationSec?.times(1000L),
            seriesLookupTitle = seriesLookupTitle, seriesYear = seriesYear,
        )
    }
    val start = all.indexOfFirst { it.remoteId == ep.remoteId }.takeIf { it >= 0 } ?: return item(ep, null)
    var next: PlayItem? = null
    for (index in all.lastIndex downTo start) next = item(all[index], next)
    return checkNotNull(next)
}

@Composable
fun DetailRoute(viewModel: DetailViewModel, visibility: kotlinx.coroutines.flow.Flow<Visibility>, onOpen: (ContentKey) -> Unit, onPlay: (PlayItem) -> Unit) {
    val context = LocalContext.current
    var artworkError by remember { mutableStateOf<String?>(null) }
    val posterPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                viewModel.savePosterUri(uri.toString())
                artworkError = null
            } catch (_: SecurityException) {
                artworkError = "This image could not be saved for future use."
            }
        }
    }
    val raw by viewModel.state.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val allowed: Visibility by visibility.collectAsStateWithLifecycle(initialValue = { _, _, _ -> false })
    // A source can become locked after this detail was loaded. Keep alternate
    // metadata separate from the primary record and re-check visibility here.
    val visibleFallback = raw.relatedMetadata?.takeIf { row ->
        row.categoryId?.let { allowed(row.kind, row.sourceId, it) } ?: true
    }
    val withFallback = if (visibleFallback == null) raw else raw.copy(
        poster = raw.poster ?: visibleFallback.poster,
        backdrop = raw.backdrop ?: visibleFallback.backdrop,
        plot = raw.plot ?: visibleFallback.plot,
        cast = raw.cast ?: visibleFallback.cast,
    )
    // Opt-in Wikidata gap-filling comes last: source and visible exact-ID metadata always win.
    val s = raw.enriched?.let { e ->
        withFallback.copy(
            plot = withFallback.plot ?: e.plot,
            cast = withFallback.cast ?: e.cast,
            poster = withFallback.poster ?: e.posterUrl,
            plotAttribution = if (withFallback.plot == null && e.plot != null) e.attribution else null,
            plotAttributionUrl = if (withFallback.plot == null && e.plot != null) e.attributionUrl else null,
            plotAttributionLicenseUrl = if (withFallback.plot == null && e.plot != null) e.attributionLicenseUrl else null,
        )
    } ?: withFallback
    val related = s.relatedMovies.filter { row -> row.poster.categoryId?.let { allowed(row.poster.key.kind.name, row.poster.key.sourceId.value, it.value) } ?: true }
    // Reuse an exact-ID alternative's art only after parental filtering. Never show
    // artwork from a locked source or from a merely similar title.
    val displayPoster = withFallback.poster ?: related.firstNotNullOfOrNull { it.poster.posterUrl } ?: s.poster
    var showSources by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showCollections by remember { mutableStateOf(false) }
    // Task 90: rarely-used actions (Edit details, Collections, version/source choice) move into a
    // glass "More" menu so the primary row stays Play · Start over · My List · More.
    var showMore by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf("") }
    var editSort by remember { mutableStateOf("") }
    var editEdition by remember { mutableStateOf("") }
    var newCollection by remember { mutableStateOf("") }
    var pinNewCollection by remember { mutableStateOf(false) }
    var smartMode by remember { mutableStateOf(false) }
    var smartKeyword by remember { mutableStateOf("") }
    var smartYear by remember { mutableStateOf("") }
    var smartYearTo by remember { mutableStateOf("") }
    var smartRating by remember { mutableStateOf("") }
    var smartGenre by remember { mutableStateOf("") }
    var smartStarted by remember { mutableStateOf(0) }
    var smartEditionLabel by remember { mutableStateOf(0) }
    var smartSourceOnly by remember { mutableStateOf(false) }
    var managingCollection by remember { mutableStateOf<LibraryCollection?>(null) }
    var manageName by remember { mutableStateOf("") }
    var manageKeyword by remember { mutableStateOf("") }
    var manageYearFrom by remember { mutableStateOf("") }
    var manageYearTo by remember { mutableStateOf("") }
    var manageRating by remember { mutableStateOf("") }
    var manageGenre by remember { mutableStateOf("") }
    var manageStarted by remember { mutableStateOf(0) }
    var manageEditionLabel by remember { mutableStateOf(0) }
    var manageSourceId by remember { mutableStateOf<String?>(null) }
    var confirmCollectionDelete by remember { mutableStateOf(false) }
    fun openCollectionManager(collection: LibraryCollection) {
        managingCollection = collection
        manageName = collection.name
        val filter = SmartCollectionFilter.decode(collection.smartFilterJson) ?: SmartCollectionFilter()
        manageKeyword = filter.titleContains.orEmpty()
        manageYearFrom = filter.yearFrom?.toString().orEmpty()
        manageYearTo = filter.yearTo?.toString().orEmpty()
        manageRating = filter.ratingAtLeast?.toString().orEmpty()
        manageGenre = filter.genreContains.orEmpty()
        manageStarted = when (filter.started) { true -> 1; false -> 2; null -> 0 }
        manageEditionLabel = when (filter.hasEditionLabel) { true -> 1; false -> 2; null -> 0 }
        manageSourceId = filter.sourceId
        confirmCollectionDelete = false
    }
    fun openEdit() {
        editTitle = (s.titleOverride?.displayTitle ?: s.itemOverride?.displayTitle).orEmpty()
        editSort = (s.titleOverride?.sortTitle ?: s.itemOverride?.sortTitle).orEmpty()
        editEdition = s.itemOverride?.editionLabel.orEmpty()
        showEdit = true
    }
    // Every way this title can be played, best quality first: chips under the title replace the
    // old dialog, which stays behind "More…" when there are too many to show.
    val currentKey = viewModel.currentKey()
    val providerName = s.movie?.record?.name ?: s.series?.record?.name
    val playOptions = remember(currentKey, s.sourceName, s.itemOverride?.editionLabel, s.movie?.versions, related, providerName) {
        buildPlayOptions(currentKey, s.sourceName, s.itemOverride?.editionLabel, s.movie?.versions.orEmpty(), related, providerName)
    }
    // Task 86: every copy the viewer can actually see, described well enough to choose between.
    // [related] is already re-checked against live visibility above, so a switched-off library, a
    // locked category or a kids profile contributes no copy here at all.
    val editionCopies = remember(playOptions, currentKey, s.runtimeSec, s.progress, s.versionProgress,
        s.editionCopyProgress, s.editionCopyRuntimeSec, related) {
        buildEditionCopies(playOptions, currentKey, s.runtimeSec, s.progress, s.versionProgress,
            s.editionCopyProgress, s.editionCopyRuntimeSec, related.associate { it.poster.key to it.poster.name })
    }
    val rankedCopies = remember(editionCopies, s.lastEditionPick) { editionCopiesRanked(editionCopies, s.lastEditionPick) }
    val chosenCopy = remember(rankedCopies, s.lastEditionPick) { chosenEditionCopy(rankedCopies, s.lastEditionPick) }
    val selectedOptionId = remember(playOptions, s.selectedVersionId, chosenCopy, currentKey) {
        if (chosenCopy != null && chosenCopy.key != currentKey) chosenCopy.id
        else defaultPlayOptionId(playOptions, currentKey, s.selectedVersionId)
    }
    fun pickPlayOption(option: PlayOption) {
        when {
            option.key == currentKey && option.versionId != null -> viewModel.selectVersion(option.versionId)
            option.key != currentKey -> { viewModel.rememberEditionPick(option.id); onOpen(option.key) }
        }
    }
    /** Play follows the copy the viewer last watched or picked; with neither, the best copy standing. */
    fun playChosenCopy(fromStart: Boolean) {
        val copy = chosenCopy
        if (copy != null && copy.key != currentKey) {
            if (copy.id != s.lastEditionPick) viewModel.rememberEditionPick(copy.id)
            onOpen(copy.key)
        } else viewModel.moviePlayItem(fromStart)?.let(onPlay)
    }
    val chips: @Composable () -> Unit = { PlayOptionChips(playOptions, selectedOptionId, ::pickPlayOption, { showSources = true }) }
    val c = OmniTheme.colors
    val primary = remember { FocusRequester() }
    val realBackdrop = s.backdrop != null
    CosmicBackdrop(Modifier.fillMaxSize()) {
        // Full-bleed artwork dissolving into the page on the left and bottom (Midnight Cinema);
        // with no wide backdrop the poster itself becomes the darkened background.
        DetailBackdrop(s.backdrop, displayPoster)
        // Task 69: the backdrop's own washes leave the text column too bright over a wide still
        // (secondary body text needs ≥4.5:1). A dedicated scrim holds the text zone near-opaque
        // and clears only past the text's right edge; the artwork keeps its full right side.
        if (!LocalCompact.current && s.error == null && !s.loading) {
            if (s.series == null) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(0.62f).fillMaxHeight(0.85f)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to c.background.copy(alpha = 0.88f), 1f to c.background.copy(alpha = 0.98f))),
                )
            } else {
                Box(
                    Modifier.align(Alignment.TopStart).fillMaxWidth(0.75f).fillMaxHeight(0.62f)
                        .background(Brush.horizontalGradient(0f to c.background, 0.85f to c.background.copy(alpha = 0.92f), 1f to Color.Transparent)),
                )
            }
        }
        if (displayPoster != null && !LocalCompact.current) {
            // The portrait is the reliable focal point even when supplied wide art
            // is only an abstract blur. Keep it clear of the series episode column.
            val isSeries = s.series != null
            AsyncImage(
                model = displayPoster, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.align(if (isSeries) Alignment.TopEnd else Alignment.CenterEnd)
                    .padding(top = if (isSeries) OmniSpacing.xxl else 0.dp, end = 88.dp)
                    .size(if (isSeries) 220.dp else 280.dp, if (isSeries) 330.dp else 420.dp)
                    .shadow(24.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp)).border(1.dp, c.textTertiary.copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
            )
        }
        when {
            s.loading -> Column(Modifier.align(Alignment.BottomStart).padding(OmniSpacing.tvSide)) {
                SkeletonBox(Modifier.size(420.dp, 56.dp)); Spacer(Modifier.height(OmniSpacing.m)); SkeletonBox(Modifier.size(520.dp, 90.dp))
            }
            s.error != null -> ErrorCard("Something went wrong", s.error!!, "Try again", { viewModel.load() }, Modifier.align(Alignment.Center))
            s.series == null -> Column(
                // A real wide backdrop keeps details bottom-left; over a poster-only page they center.
                Modifier.align(if (realBackdrop) Alignment.BottomStart else Alignment.CenterStart)
                    .padding(start = OmniSpacing.tvSide, bottom = if (realBackdrop) OmniSpacing.xxl else 0.dp).widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
            ) {
                DetailHeader(s, overline = "Movie", big = true, chips = chips)
                Spacer(Modifier.height(OmniSpacing.s))
                MovieActions(s, viewModel, primary,
                    play = ::playChosenCopy, progressVisible = chosenCopy?.key == currentKey,
                    onMore = { showMore = true })
            }
            else -> Column(
                Modifier.fillMaxHeight().fillMaxWidth(if (LocalCompact.current) 1f else 0.72f)
                    .padding(start = OmniSpacing.tvSide, top = OmniSpacing.xl),
            ) {
                // Task 119: the series hero (title/logo/meta/actions/season tabs) is now a bounded,
                // internally-scrollable region and the episode list is the weighted remainder. Before
                // this the whole column was one non-scrolling fillMaxHeight Column, so a tall hero left
                // the episode LazyColumn a viewport shorter than a card and the focused episode's
                // title/description were cut off at the screen bottom (Mushoku Tensei).
                Column(
                    Modifier.weight(DetailSeriesHeroWeight).verticalScroll(rememberScrollState())
                        .padding(bottom = OmniSpacing.s),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                ) {
                    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                        val n = s.series!!.seasons.size
                        DetailHeader(s, overline = "Series  \u00b7  $n season${if (n == 1) "" else "s"}", big = false, chips = chips)
                    }
                    Spacer(Modifier.height(OmniSpacing.m))
                    // Primary actions first (Continue / Start over / My List / More); management actions
                    // move into the More menu, matching the movie layout.
                    SeriesActions(s, viewModel, onPlay, primary, onMore = { showMore = true })
                }
                SeriesEpisodes(s, viewModel, onPlay)
            }
        }
    }
    if (showSources) {
        EditionSourceChooser(
            copies = rankedCopies,
            chosenId = chosenCopy?.id,
            onPick = { copy ->
                showSources = false
                if (copy.key != currentKey) { viewModel.rememberEditionPick(copy.id); onOpen(copy.key) }
                else if (copy.versionId != null) viewModel.selectVersion(copy.versionId)
            },
            onDismiss = { showSources = false },
        )
    }
    if (showEdit) {
        Dialog(onDismissRequest = { showEdit = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.width(540.dp).heightIn(max = 700.dp).background(c.background, RoundedCornerShape(18.dp))
                .verticalScroll(rememberScrollState()).padding(OmniSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                Text("Your library details", style = OmniTheme.type.title, color = c.textPrimary)
                Text("Saved only in Omniverse; your provider is unchanged.", style = OmniTheme.type.caption, color = c.textSecondary)
                // Documented exception (task 69): these are text inputs, not tiles — material3's own
                // focus treatment (border + cursor) is the right one here.
                OutlinedTextField(editTitle, { editTitle = it }, label = { Text("Display title") }, singleLine = true)
                OutlinedTextField(editSort, { editSort = it }, label = { Text("Sort title") }, singleLine = true)
                OutlinedTextField(editEdition, { editEdition = it }, label = { Text("Edition label") }, singleLine = true)
                OmniButton("Choose poster from this device", {
                    try { posterPicker.launch(arrayOf("image/*")) }
                    catch (_: android.content.ActivityNotFoundException) { artworkError = "No image picker is installed on this device." }
                })
                if (s.titleOverride?.customPosterUrl != null || s.itemOverride?.customPosterUrl != null) OmniButton("Use provider poster", { viewModel.savePosterUri(null) })
                artworkError?.let { Text(it, style = OmniTheme.type.caption, color = c.textSecondary) }
                Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    OmniButton("Save", { viewModel.saveLocalDetails(editTitle, editSort, editEdition); showEdit = false })
                    OmniButton("Cancel", { showEdit = false })
                }
            }
        }
    }
    if (showCollections) {
        Dialog(onDismissRequest = { showCollections = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.width(540.dp).heightIn(max = 700.dp).background(c.background, RoundedCornerShape(18.dp))
                .verticalScroll(rememberScrollState()).padding(OmniSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                Text("Collections", style = OmniTheme.type.title, color = c.textPrimary)
                val collectionKind = if (s.series == null) ContentKind.VOD else ContentKind.SERIES
                LazyColumn(Modifier.height(300.dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                    items(s.collections.filter { it.contentKind == collectionKind }, key = { it.id }) { collection ->
                        Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                            if (collection.mode == "smart") Text("${collection.name} · Smart", Modifier.weight(1f),
                                style = OmniTheme.type.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            else {
                                val included = collection.id in s.memberCollectionIds
                                OmniButton("${collection.name} · ${if (included) "Remove" else "Add"}", {
                                    if (included) viewModel.removeFromCollection(collection.id) else viewModel.addToCollection(collection.id)
                                }, Modifier.weight(1f))
                            }
                            OmniButton(if (collection.pinnedHome) "Unpin" else "Pin", { viewModel.toggleCollectionPin(collection) })
                            OmniButton("Manage", { openCollectionManager(collection); showCollections = false })
                        }
                    }
                }
                OutlinedTextField(newCollection, { newCollection = it }, label = { Text("New collection name") }, singleLine = true)
                OmniButton(if (smartMode) "Smart filter ✓" else "Smart filter", { smartMode = !smartMode })
                if (smartMode) {
                    OutlinedTextField(smartKeyword, { smartKeyword = it }, label = { Text("Title contains") }, singleLine = true)
                    OutlinedTextField(smartYear, { smartYear = it }, label = { Text("From year") }, singleLine = true)
                    OutlinedTextField(smartYearTo, { smartYearTo = it }, label = { Text("Through year") }, singleLine = true)
                    OutlinedTextField(smartRating, { smartRating = it }, label = { Text("Minimum rating /10") }, singleLine = true)
                    OutlinedTextField(smartGenre, { smartGenre = it }, label = { Text("Genre contains") }, singleLine = true)
                    OmniButton(when (smartStarted) { 1 -> "Started titles"; 2 -> "Not started titles"; else -> "Any watch state" },
                        { smartStarted = (smartStarted + 1) % 3 })
                    OmniButton(when (smartEditionLabel) { 1 -> "Labeled editions"; 2 -> "Unlabeled editions"; else -> "Any edition label" },
                        { smartEditionLabel = (smartEditionLabel + 1) % 3 })
                    OmniButton(if (smartSourceOnly) "This source only ✓" else "All sources", { smartSourceOnly = !smartSourceOnly })
                }
                OmniButton(if (pinNewCollection) "Pinned on Home ✓" else "Pin on Home", { pinNewCollection = !pinNewCollection })
                OmniButton(if (smartMode) "Create smart collection" else "Create and add", {
                    if (newCollection.isNotBlank()) {
                        if (smartMode) viewModel.createSmartCollection(newCollection,
                            SmartCollectionFilter(smartKeyword.trim().ifEmpty { null }, smartYear.toIntOrNull(), smartYearTo.toIntOrNull(),
                                ratingAtLeast = smartRating.toFloatOrNull(),
                                sourceId = if (smartSourceOnly) viewModel.sourceId() else null,
                                genreContains = smartGenre.trim().ifEmpty { null },
                                started = when (smartStarted) { 1 -> true; 2 -> false; else -> null },
                                hasEditionLabel = when (smartEditionLabel) { 1 -> true; 2 -> false; else -> null }), pinNewCollection)
                        else viewModel.createCollection(newCollection, pinNewCollection)
                        newCollection = ""; showCollections = false
                    }
                })
            }
        }
    }
    managingCollection?.let { collection ->
        Dialog(onDismissRequest = { managingCollection = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.width(540.dp).heightIn(max = 700.dp).background(c.background, RoundedCornerShape(18.dp))
                .verticalScroll(rememberScrollState()).padding(OmniSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                Text("Manage collection", style = OmniTheme.type.title, color = c.textPrimary)
                OutlinedTextField(manageName, { manageName = it }, label = { Text("Name") }, singleLine = true)
                if (collection.mode == "smart") {
                    OutlinedTextField(manageKeyword, { manageKeyword = it }, label = { Text("Title contains") }, singleLine = true)
                    OutlinedTextField(manageGenre, { manageGenre = it }, label = { Text("Genre contains") }, singleLine = true)
                    OutlinedTextField(manageYearFrom, { manageYearFrom = it }, label = { Text("From year") }, singleLine = true)
                    OutlinedTextField(manageYearTo, { manageYearTo = it }, label = { Text("Through year") }, singleLine = true)
                    OutlinedTextField(manageRating, { manageRating = it }, label = { Text("Minimum rating /10") }, singleLine = true)
                    OmniButton(when (manageStarted) { 1 -> "Started titles"; 2 -> "Not started titles"; else -> "Any watch state" },
                        { manageStarted = (manageStarted + 1) % 3 })
                    OmniButton(when (manageEditionLabel) { 1 -> "Labeled editions"; 2 -> "Unlabeled editions"; else -> "Any edition label" },
                        { manageEditionLabel = (manageEditionLabel + 1) % 3 })
                    OmniButton(if (manageSourceId != null) "Restricted to source ✓" else "All sources", {
                        manageSourceId = if (manageSourceId == null) viewModel.sourceId() else null
                    })
                }
                OmniButton(if (collection.pinnedHome) "Pinned on Home ✓" else "Pin on Home", {
                    managingCollection = collection.copy(pinnedHome = !collection.pinnedHome)
                })
                Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                    OmniButton("Move up", { viewModel.moveCollection(collection.id, -1) })
                    OmniButton("Move down", { viewModel.moveCollection(collection.id, 1) })
                }
                OmniButton("Save changes", {
                    if (manageName.isNotBlank()) {
                        val rule = if (collection.mode == "smart") SmartCollectionFilter(
                            manageKeyword.trim().ifEmpty { null }, manageYearFrom.toIntOrNull(), manageYearTo.toIntOrNull(),
                            manageRating.toFloatOrNull(), manageSourceId,
                            manageGenre.trim().ifEmpty { null }, when (manageStarted) { 1 -> true; 2 -> false; else -> null },
                            hasEditionLabel = when (manageEditionLabel) { 1 -> true; 2 -> false; else -> null }) else null
                        viewModel.saveCollection(collection.copy(name = manageName.trim(), smartFilterJson = rule?.encode()))
                        managingCollection = null
                    }
                })
                if (confirmCollectionDelete) {
                    Text("Delete this collection? Its saved list cannot be restored.", style = OmniTheme.type.body, color = c.textSecondary)
                    OmniButton("Confirm delete", { viewModel.deleteCollection(collection.id); managingCollection = null })
                    OmniButton("Keep collection", { confirmCollectionDelete = false })
                } else OmniButton("Delete collection", { confirmCollectionDelete = true })
            }
        }
    }
    if (showMore) {
        DetailMoreMenu(
            actions = viewModel.moreMenuLabels().map { label ->
                MenuAction(label, when {
                    label == "Edit details" -> ::openEdit
                    label == "Collections" -> ({ showCollections = true })
                    label.startsWith("Version:") -> ({ showSources = true })
                    else -> ({ })
                })
            },
            onDismiss = { showMore = false },
        )
    }
    undo?.let { ui ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            com.yodesla.omniverse.designsystem.UndoCard(
                message = ui.message,
                onUndo = { viewModel.undoLast() },
                onDismiss = { viewModel.dismissUndo() },
                modifier = Modifier.padding(bottom = OmniSpacing.xl),
            )
        }
    }
    LaunchedEffect(s.loading) { if (!s.loading) primary.requestFocusWhenReady() }
}

/**
 * Task 90: the Detail "More" menu — a glass dialog of full-width rows holding the rarely-used
 * actions moved out of the primary row. Back (or picking a row) closes it; focus lands on row one.
 */
@Composable
private fun DetailMoreMenu(actions: List<MenuAction>, onDismiss: () -> Unit) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(22.dp)).background(c.elevated).padding(OmniSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
        ) {
            Text("More", style = t.title, color = c.textSecondary)
            actions.forEachIndexed { i, a ->
                FocusCard(onClick = { onDismiss(); a.onClick() },
                    modifier = Modifier.fillMaxWidth().height(52.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier)) {
                    Box(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.l), contentAlignment = Alignment.CenterStart) {
                        Text(a.label, style = t.body.copy(shadow = null), color = if (a.destructive) c.live else c.textPrimary)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocusWhenReady() } }
}

/**
 * "Editions & sources" chooser (task 38, extended by task 86): every parentally visible copy of
 * this title, best choice first — the copy holding watch progress, then this profile's saved pick,
 * then the highest resolution from the more trustworthy source when quality ties. Each row reads
 * "4K · Director's Cut" over "Plex · 2h 12m · Resume 23 min · Current source"; no URLs, no raw
 * endpoints. Opens focused on the copy Play will use.
 */
@Composable
private fun EditionSourceChooser(
    copies: List<EditionCopy>,
    chosenId: String?,
    onPick: (EditionCopy) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val first = remember { FocusRequester() }
    val unlabelled = stringResource(R.string.edition_unlabelled)
    val currentTag = stringResource(R.string.current_source)
    val resumeFormat = stringResource(R.string.edition_resume_format)
    val rows = remember(copies, chosenId, unlabelled, currentTag, resumeFormat) {
        copies.map { copy ->
            Triple(copy, editionRowTitle(copy) ?: unlabelled,
                editionRowMeta(copy, editionResumeMinutes(copy)?.let { resumeFormat.format(it) },
                    if (copy.isCurrent) currentTag else null))
        }
    }
    val listHeight = minOf(500, rows.size * 78).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(560.dp)
                .heightIn(max = 640.dp)
                .background(c.background, RoundedCornerShape(18.dp))
                .padding(OmniSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.m),
        ) {
            Text(stringResource(R.string.editions_sources_title), style = t.title, color = c.textPrimary)
            Text(stringResource(R.string.editions_sources_hint), style = t.caption, color = c.textSecondary)
            LazyColumn(
                modifier = Modifier.height(listHeight),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
            ) {
                items(rows, key = { it.first.id }) { (copy, title, meta) ->
                    val chosen = copy.id == chosenId
                    FocusCard(
                        onClick = { onPick(copy) },
                        modifier = Modifier.fillMaxWidth().height(72.dp)
                            .then(if (copy.isCurrent) Modifier.focusRequester(first) else Modifier),
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(if (chosen) c.accent.copy(alpha = 0.18f) else Color.Transparent)
                                .padding(horizontal = OmniSpacing.m),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text((if (chosen) "✓  " else "") + title,
                                    style = t.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(meta, style = t.caption, color = c.textSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocusWhenReady() } }
}

/** Overline, serif title, one meta row, edition chips, plot, cast: shared by movies and series. */
@Composable
private fun DetailHeader(s: DetailState, overline: String, big: Boolean, chips: @Composable () -> Unit = {}) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val edition = s.itemOverride?.editionLabel?.takeIf { it.isNotBlank() }
    Text((if (edition == null) overline else "$overline  ·  $edition").uppercase(),
        style = t.overline, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    // Task 84d: the official title logo replaces the text title when TMDB has one (fit, left-aligned,
    // capped so a wide logo never pushes the meta row off-screen). Text stays as the fallback.
    if (s.logoUrl != null) {
        AsyncImage(
            model = s.logoUrl, contentDescription = s.title, contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = Modifier.heightIn(max = 160.dp).widthIn(max = 420.dp),
        )
    } else {
        Text(s.title, style = if (big) t.hero else t.display, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    DetailMetaRow(s.year, s.runtimeSec, s.genre, s.ratingValue, s.rt, certification = s.certification)
    chips()
    s.plot?.let { Text(it, style = t.body, color = c.textSecondary, maxLines = if (big) 3 else 2, overflow = TextOverflow.Ellipsis) }
    s.plotAttribution?.let { label ->
        val uriHandler = LocalUriHandler.current
        Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            // Task 69: focusable links get the shared FocusCard treatment; a link with no URL is
            // plain text (nothing to focus, nothing to open).
            if (s.plotAttributionUrl != null) {
                FocusCard(onClick = { s.plotAttributionUrl?.let { uri -> runCatching { uriHandler.openUri(uri) } } }, modifier = Modifier.height(30.dp)) {
                    Box(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.s), contentAlignment = Alignment.CenterStart) {
                        Text(label, style = t.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Text(label, style = t.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (s.plotAttributionLicenseUrl != null) {
                FocusCard(onClick = { s.plotAttributionLicenseUrl?.let { uri -> runCatching { uriHandler.openUri(uri) } } }, modifier = Modifier.height(30.dp)) {
                    Box(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.s), contentAlignment = Alignment.CenterStart) {
                        Text(stringResource(R.string.metadata_attribution_license), style = t.caption, color = c.textTertiary, maxLines = 1)
                    }
                }
            } else {
                Text(stringResource(R.string.metadata_attribution_license), style = t.caption, color = c.textTertiary, maxLines = 1)
            }
        }
    }
    s.cast?.takeIf { it.isNotBlank() }?.let {
        Text("Starring  $it", style = t.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Movie action row. Task 90: the primary row is Play/Resume · Start over · My List · More. Edit
 * details, Collections and the version/source choice move into the More menu (see [DetailMoreMenu]).
 * Task 86 behaviour is unchanged: Play still follows the watched/saved copy.
 */
@Composable
private fun MovieActions(s: DetailState, vm: DetailViewModel, primary: FocusRequester,
                         play: (Boolean) -> Unit, progressVisible: Boolean,
                         onMore: () -> Unit) {
    val c = OmniTheme.colors
    val resumable = progressVisible && (s.progress?.positionMs ?: 0L) > 30_000
    Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
        OmniButton(
            if (resumable) "Resume" else "Play",
            { play(false) }, Modifier.focusRequester(primary), primary = true,
        )
        if (resumable) OmniButton("Start over", { play(true) })
        OmniButton(if (s.favorite) "In My List" else "+ My List", { vm.toggleFavorite() })
        OmniButton("More", onMore)
    }
    if (resumable && s.durationMs != null) {
        Row(Modifier.padding(top = OmniSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            ProgressLine(s.progress!!.positionMs.toFloat() / s.durationMs, Modifier.width(220.dp))
            Spacer(Modifier.width(OmniSpacing.m))
            Text("${fmtLeft(s.durationMs - s.progress.positionMs)} left", style = OmniTheme.type.caption, color = c.textTertiary)
        }
    }
}

/** Series detail splits its height between a scrollable header and the episode list. */
internal const val DetailSeriesHeroWeight = 0.65f
internal const val DetailSeriesEpisodesWeight = 0.35f
internal const val DetailEpisodeCardHeightDp = 96f
internal const val DetailEpisodeFocusParentFraction = 0.2f

@Composable
private fun SeriesActions(s: DetailState, vm: DetailViewModel, onPlay: (PlayItem) -> Unit, primary: FocusRequester,
                          onMore: () -> Unit) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val series = s.series ?: return
    // "Continue" = the most recently watched episode; when that watch is completed (task 57 "Up
    // next") the NEXT episode; otherwise the first episode. A "New episodes" card (task 71) opens
    // the show ready on the episode it flagged instead.
    val all = series.seasons.sortedBy { it.number }.flatMap { s -> s.episodes.sortedBy { it.number } }
    val lastProg = s.episodeProgress.values.maxByOrNull { it.updatedMs }
    val last = lastProg?.let { p -> all.firstOrNull { it.remoteId == p.key.remoteId } }
    val focus = s.focusEpisodeId?.let { id -> all.firstOrNull { it.remoteId == id } }
    val start = focus ?: (if (lastProg?.completed == true && last != null) all.getOrNull(all.indexOfFirst { it.remoteId == last.remoteId } + 1) ?: last else last ?: all.firstOrNull())
    Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
        if (start != null) {
            OmniButton(
                (if (last != null && focus == null) "Continue" else "Play") + "  S${start.season} \u00b7 E${start.number}",
                { onPlay(vm.episodePlayItem(start)) }, Modifier.focusRequester(primary), primary = true,
            )
            if (last != null && (s.episodeProgress[last.remoteId.value]?.positionMs ?: 0L) > 0L) {
                OmniButton("Start over", { onPlay(vm.episodePlayItem(last, fromStart = true)) })
            }
        }
        OmniButton(if (s.favorite) "In My List" else "+ My List", { vm.toggleFavorite() })
        OmniButton("More", onMore)
    }
    Spacer(Modifier.height(OmniSpacing.s))
    // Audit M10: a watched change the provider never accepted stays visible here, not silent.
    s.watchSyncError?.let { msg ->
        Spacer(Modifier.height(OmniSpacing.s))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.live.copy(alpha = 0.16f))
                .padding(horizontal = OmniSpacing.m, vertical = OmniSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
        ) {
            Text(msg, style = t.caption, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            OmniButton("Retry", { vm.retryWatchSync() })
        }
    }
    Spacer(Modifier.height(OmniSpacing.l))
    FocusPivot(leading = 0.dp) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s), contentPadding = PaddingValues(end = OmniSpacing.tvSide)) {
            items(series.seasons, key = { it.number }) { season ->
                val selected = season.number == s.season
                FocusCard(onClick = { vm.selectSeason(season.number) }, modifier = Modifier.height(36.dp)) {
                    Box(Modifier.fillMaxHeight().padding(horizontal = OmniSpacing.m), contentAlignment = Alignment.Center) {
                        Text(season.name ?: "Season ${season.number}", style = t.caption, color = if (selected) c.accent else c.textSecondary)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(OmniSpacing.s))
}

/**
 * The episode list (task 119): the weighted lower region of the series column. A ColumnScope extension
 * so the LazyColumn can take exactly the height the hero region left (weight), guaranteeing a focused
 * episode card fits in the viewport instead of being cut off at the screen bottom.
 */
@Composable
private fun ColumnScope.SeriesEpisodes(s: DetailState, vm: DetailViewModel, onPlay: (PlayItem) -> Unit) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val series = s.series ?: return
    val all = series.seasons.sortedBy { it.number }.flatMap { s -> s.episodes.sortedBy { it.number } }
    // Task 95: unwatched episodes after the newest watch stay spoiler-hidden until OK reveals one.
    val hiddenIds = if (s.spoilerFree) com.yodesla.omniverse.core.data.SpoilerFree.hiddenEpisodeIds(all.map { it.remoteId.value }, s.episodeProgress) else emptySet()
    val episodes = series.seasons.firstOrNull { it.number == s.season }?.episodes.orEmpty()
    // An episode with a saved position asks Resume / Start over instead of silently resuming.
    var resumeChoice by remember { mutableStateOf<Episode?>(null) }
    // Hold-OK on an episode card opens the watched/unwatched menu.
    var menuEpisode by remember { mutableStateOf<Episode?>(null) }
    resumeChoice?.let { ep ->
        val resumeFocus = remember { FocusRequester() }
        val at = s.episodeProgress[ep.remoteId.value]?.positionMs ?: 0L
        Dialog(onDismissRequest = { resumeChoice = null }) {
            Column(Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(c.background).padding(OmniSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                Text("S${ep.season} · E${ep.number}  ${episodeTitle(ep.title, s.title, ep.number)}",
                    style = t.title, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                OmniButton("Resume from ${fmt(at)}", { resumeChoice = null; onPlay(vm.episodePlayItem(ep)) },
                    Modifier.fillMaxWidth().focusRequester(resumeFocus), primary = true)
                OmniButton("Start over", { resumeChoice = null; onPlay(vm.episodePlayItem(ep, fromStart = true)) },
                    Modifier.fillMaxWidth())
            }
        }
        LaunchedEffect(ep.remoteId) { runCatching { resumeFocus.requestFocusWhenReady() } }
    }
    menuEpisode?.let { ep ->
        val watched = isWatchedProgress(s.episodeProgress[ep.remoteId.value])
        val seasonEpisodes = series.seasons.firstOrNull { it.number == ep.season }?.episodes.orEmpty()
        val seasonWatched = seasonEpisodes.isNotEmpty() && seasonEpisodes.all { isWatchedProgress(s.episodeProgress[it.remoteId.value]) }
        Dialog(onDismissRequest = { menuEpisode = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            TitleMenu(
                title = "S${ep.season} \u00b7 E${ep.number}  ${episodeTitle(ep.title, s.title, ep.number)}",
                subtitle = s.title, posterUrl = ep.stillUrl?.takeIf { it.isNotBlank() }
                    ?: s.tmdbStills[ep.season]?.firstOrNull { it.episode == ep.number }?.stillPath
                        ?.let { com.yodesla.omniverse.core.data.metadata.Tmdb.stillUrl(it) },
                actions = listOf(
                    MenuAction(if (watched) "Mark unwatched" else "Mark watched",
                        { if (watched) vm.markUnwatched(ep) else vm.markWatched(ep) }),
                    MenuAction(if (seasonWatched) "Mark season ${ep.season} unwatched" else "Mark season ${ep.season} watched",
                        { vm.markSeason(ep.season, !seasonWatched) }),
                ),
                onDismiss = { menuEpisode = null },
            )
        }
    }
    FocusPivot(parentFraction = DetailEpisodeFocusParentFraction, leading = 0.dp) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(DetailSeriesEpisodesWeight),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
            contentPadding = PaddingValues(end = OmniSpacing.tvSide, bottom = OmniSpacing.xxl),
        ) {
            items(episodes, key = { it.remoteId.value }) { ep ->
                val prog = s.episodeProgress[ep.remoteId.value]
                val watched = isWatchedProgress(prog)
                // Task 95: hidden until the viewer presses OK on this card (revealed for the visit).
                val hidden = s.spoilerFree && ep.remoteId.value in hiddenIds && ep.remoteId.value !in s.revealedSpoilerIds
                // Task 84d: TMDB fills the thumbnail only when the provider has no episode still.
                val still = ep.stillUrl?.takeIf { it.isNotBlank() }
                    ?: s.tmdbStills[ep.season]?.firstOrNull { it.episode == ep.number }?.stillPath
                        ?.let { com.yodesla.omniverse.core.data.metadata.Tmdb.stillUrl(it) }
                FocusCard(
                    onClick = {
                        if (hidden) vm.revealEpisode(ep)
                        else if ((prog?.positionMs ?: 0L) > 0L) resumeChoice = ep
                        else onPlay(vm.episodePlayItem(ep))
                    },
                    onLongClick = { menuEpisode = ep },
                    modifier = Modifier.fillMaxWidth().height(DetailEpisodeCardHeightDp.dp),
                ) {
                    Row(Modifier.fillMaxSize().padding(OmniSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                                .background(if (hidden && !supportsRuntimeBlur()) c.background else c.elevated),
                        ) {
                            // Task 95: blurred still on API 31+, plain dark placeholder below it.
                            if (!hidden || supportsRuntimeBlur()) {
                                AsyncImage(
                                    model = still, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = if (hidden) Modifier.fillMaxSize().blur(14.dp) else Modifier.fillMaxSize(),
                                )
                            }
                            if (prog != null) {
                                val fraction = if (watched) 1f else prog.durationMs?.takeIf { it > 0 }?.let { prog.positionMs.toFloat() / it } ?: 0f
                                if (fraction > 0f) ProgressLine(fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp))
                            }
                            if (watched) Text("\u2713", Modifier.align(Alignment.TopEnd).padding(6.dp), style = t.caption, color = c.accent)
                        }
                        Spacer(Modifier.width(OmniSpacing.m))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                listOfNotNull("Episode ${ep.number}", ep.durationSec?.let { "${it / 60} min" }).joinToString("  \u00b7  ").uppercase(),
                                style = t.overline, color = c.textTertiary, maxLines = 1,
                            )
                            // A bare "Episode N" would just repeat the overline above it.
                            if (!(hidden && s.spoilerHideTitles)) {
                                episodeTitle(ep.title, s.title, ep.number).takeUnless { it.equals("Episode ${ep.number}", ignoreCase = true) }
                                    ?.let { Text(it, style = t.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            if (hidden) Text(com.yodesla.omniverse.core.data.SpoilerFree.HIDDEN_DESCRIPTION, style = t.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            else ep.plot?.let { Text(it, style = t.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}

/** "1h 12m" / "24m" for time remaining. */
internal fun fmtLeft(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

internal fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Providers often name episodes "Show Name (2021) - S01E02 - Real Title". Strip the show name,
 * year and SxxEyy code; fall back to "Episode N" if nothing meaningful is left.
 */
internal fun episodeTitle(raw: String, show: String, number: Int): String {
    var t = raw
    val showBase = show.replace(Regex("""\s*[(\[]\d{4}[)\]]\s*$"""), "")
    for (prefix in listOf(show, showBase)) {
        if (prefix.isNotBlank() && t.startsWith(prefix, ignoreCase = true)) t = t.substring(prefix.length)
    }
    t = t.replace(Regex("""^\s*[(\[]\d{4}[)\]]"""), "")
        .replace(Regex("""(?i)\bS\d{1,2}\s*E\d{1,3}\b"""), "")
        .trim(' ', '-', '–', ':', '|', '.')
    return t.ifBlank { "Episode $number" }
}

/**
 * A saved episode row counts as watched when it is complete: full position for a known length, or
 * the position-1 sentinel setWatched stores when the length is unknown. Partial watches never match.
 */
internal fun isWatchedProgress(p: Progress?): Boolean {
    if (p == null) return false
    if (p.completed) return true
    val dur = p.durationMs?.takeIf { it > 0 }
    return if (dur != null) p.positionMs >= dur else p.positionMs == 1L
}
