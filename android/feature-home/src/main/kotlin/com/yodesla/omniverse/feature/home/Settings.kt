package com.yodesla.omniverse.feature.home
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.yodesla.omniverse.designsystem.requestFocusWhenReady

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.Icon
import com.yodesla.omniverse.designsystem.glassSurface
import com.yodesla.omniverse.feature.home.settings.RowFocusRegistry
import com.yodesla.omniverse.feature.home.settings.SettingsCategory
import com.yodesla.omniverse.feature.home.settings.SettingsCategoryPane
import com.yodesla.omniverse.feature.home.settings.SettingsSearchEntry
import com.yodesla.omniverse.feature.home.settings.SettingsSlot
import com.yodesla.omniverse.feature.home.settings.clickableTv
import com.yodesla.omniverse.feature.home.settings.searchSettings
import com.yodesla.omniverse.feature.home.settings.settingsCategories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.SkipSettings
import com.yodesla.omniverse.core.data.SourceErrorKind
import com.yodesla.omniverse.core.data.SourceSummary
import com.yodesla.omniverse.core.data.SourceStatusKeys
import com.yodesla.omniverse.core.data.SyncEngine
import com.yodesla.omniverse.core.data.SyncScope
import com.yodesla.omniverse.core.data.SyncStage
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.encodeError
import com.yodesla.omniverse.core.data.encodeSync
import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.source.SourceException
import kotlinx.coroutines.CancellationException
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.LocalCompact
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.player.FRAME_RATE_MATCH_SEAMLESS
import com.yodesla.omniverse.player.FRAME_RATE_MATCH_SETTING
import com.yodesla.omniverse.player.FrameRateMatchMode
import com.yodesla.omniverse.player.SUBTITLE_STYLE_BACKGROUND
import com.yodesla.omniverse.player.SUBTITLE_STYLE_COLOR
import com.yodesla.omniverse.player.SUBTITLE_STYLE_POSITION
import com.yodesla.omniverse.player.SUBTITLE_STYLE_SIZE
import com.yodesla.omniverse.player.SubtitleStyleRaw
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Immutable
data class SourceUi(
    val summary: SourceSummary,
    val account: String? = null,
    val syncing: String? = null,
    val errorKind: SourceErrorKind? = null,
    val failedAtMs: Long? = null,
)

@Immutable
data class SettingsState(
    val sources: List<SourceUi> = emptyList(),
    val mode: String = "simple",
    val introSkipMode: String = SkipSettings.BUTTON,
    val creditsSkipMode: String = SkipSettings.BUTTON,
    val communitySkipLookup: Boolean = false,
    val localSkipAnalysis: Boolean = false,
    val confirmRemove: SourceId? = null,
    val metadataLookup: Boolean = false,
    val rtScores: Boolean = true,
    /** Task 59: ask "Who's watching?" at startup when 2+ profiles exist (absent setting = on). */
    val pickerAtStart: Boolean = true,
    /** Task 100: per-profile frame-rate matching ("off" / "seamless" / "always"; default Seamless only). */
    val matchFrameRate: String = FRAME_RATE_MATCH_SEAMLESS,
    /** Task 94: keep a hidden engine primed on the next live channel (per-profile, default on). */
    val preloadNextChannel: Boolean = true,
    /** Task 95: hide spoilers for unwatched episodes (per-profile, default off). */
    val spoilerFree: Boolean = false,
    /** Task 95 sub-option: also hide episode titles (per-profile, default off). */
    val spoilerHideTitles: Boolean = false,
    /** Task 107: where Back lands after opening a search result (per-profile; default guide/details). */
    val backFromSearch: String = com.yodesla.omniverse.core.data.UserDataRepository.BACK_FROM_SEARCH_GUIDE_OR_DETAILS,
    /** Task 84: per-profile text size ("normal" / "large" / "xlarge"); drives the theme's text scale. */
    val textSize: String = com.yodesla.omniverse.designsystem.TEXT_SIZE_NORMAL,
    /** Task 84h: category-language filter (Settings › Sources). Empty set = off ("All"). */
    val categoryLangFilter: Set<com.yodesla.omniverse.core.data.categories.CategoryLanguage> = emptySet(),
    /** Task 84h: keep categories the classifier cannot place (default ON). */
    val categoryLangKeepUntagged: Boolean = true,
    /** Task 84h: extend the filter to Movies & Shows (default OFF). */
    val categoryLangFilterVod: Boolean = false,
    /** Task 84h: language groups actually present in this profile's sources, with counts. */
    val categoryLangGroups: List<CategoryGroupCount> = emptyList(),
    /** Task 84m: categories this profile hid (hold-OK › Hide category); drives the "Hidden categories" row. */
    val hiddenCategories: List<HiddenCategoryUi> = emptyList(),
    /** Task 103: keep live and final scores off Sports cards until the viewer reveals them (per profile). */
    val hideSportsScores: Boolean = false,
)

/** Task 84h: one row of the "Choose…" dialog — a language group present in this profile's sources. */
@Immutable
data class CategoryGroupCount(val language: com.yodesla.omniverse.core.data.categories.CategoryLanguage, val count: Int)
/** Task 90: one dismissed Continue Watching title, resolved for the Settings hidden list. */
@Immutable
data class HiddenCwRow(
    val key: com.yodesla.omniverse.core.model.ContentKey,
    val name: String,
    val posterUrl: String?,
    val lastWatchedMs: Long,
)

/** Task 84m: one row of Settings › Sources › Hidden categories ("key" is "KIND|sourceId|remoteId"). */
@Immutable
data class HiddenCategoryUi(val key: String, val name: String, val sourceName: String, val kindLabel: String)

class SettingsViewModel(
    private val sources: SourceRepository,
    private val sync: SyncEngine,
    private val userData: UserDataRepository,
    private val clock: Clock,
    /** Public builds ship Rotten Tomatoes off until the user turns the switch on (Task 75). */
    private val publicBuild: Boolean = false,
    /** Task 84h: needed only to count the language groups present in this profile's categories. */
    private val catalog: CatalogRepository? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState(rtScores = !publicBuild))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    // Task 90: titles dismissed from Continue Watching for the current profile, resolved through the
    // catalog for poster/title. A key that no longer resolves still shows (as "Removed title") so its
    // Restore keeps working.
    val hiddenContinueWatching: StateFlow<List<HiddenCwRow>> =
        userData.hiddenContinueWatching()
            .map { entries ->
                entries.map { e ->
                    val posterKey = com.yodesla.omniverse.core.model.progressPosterKey(e.key, e.progress?.parentId) ?: e.key
                    val row = catalog?.poster(posterKey)
                    HiddenCwRow(
                        key = e.key,
                        name = row?.name ?: "Removed title",
                        posterUrl = row?.posterUrl,
                        lastWatchedMs = e.progress?.updatedMs ?: 0L,
                    )
                }
            }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())
    fun restoreHiddenContinueWatching(key: com.yodesla.omniverse.core.model.ContentKey) {
        viewModelScope.launch { userData.restoreHiddenContinueWatching(key) }
    }
    fun restoreAllHiddenContinueWatching() {
        viewModelScope.launch { userData.restoreAllHiddenContinueWatching() }
    }

    init {
        sources.sources().onEach { list ->
            _state.update { s -> s.copy(sources = list.map { sum -> s.sources.firstOrNull { it.summary.id == sum.id }?.copy(summary = sum) ?: SourceUi(sum) }) }
            list.forEach { loadAccount(it.id) }
        }.launchIn(viewModelScope)
        userData.setting(MODE_KEY).onEach { m -> _state.update { it.copy(mode = m ?: "simple") } }.launchIn(viewModelScope)
        userData.setting(SkipSettings.INTRO_MODE).onEach { value ->
            _state.update { it.copy(introSkipMode = value?.takeIf { mode -> mode in skipModes } ?: SkipSettings.BUTTON) }
        }.launchIn(viewModelScope)
        userData.setting(SkipSettings.CREDITS_MODE).onEach { value ->
            _state.update { it.copy(creditsSkipMode = value?.takeIf { mode -> mode in skipModes } ?: SkipSettings.BUTTON) }
        }.launchIn(viewModelScope)
        userData.setting(SkipSettings.COMMUNITY_LOOKUP).onEach { value ->
            _state.update { it.copy(communitySkipLookup = value == "true") }
        }.launchIn(viewModelScope)
        userData.setting(com.yodesla.omniverse.core.data.metadata.MetadataEnricher.SETTING_KEY).onEach { value ->
            _state.update { it.copy(metadataLookup = value == "true") }
        }.launchIn(viewModelScope)
        userData.setting(com.yodesla.omniverse.core.data.metadata.RottenTomatoes.SETTING_KEY).onEach { value ->
            _state.update { it.copy(rtScores = com.yodesla.omniverse.core.data.metadata.RottenTomatoes.enabledFor(value, publicBuild)) }
        }.launchIn(viewModelScope)
        userData.setting(SkipSettings.LOCAL_ANALYSIS).onEach { value ->
            _state.update { it.copy(localSkipAnalysis = value == "true") }
        }.launchIn(viewModelScope)
        userData.setting(com.yodesla.omniverse.core.data.ProfileRepository.PICKER_KEY).onEach { value ->
            _state.update { it.copy(pickerAtStart = value != "false") }
        }.launchIn(viewModelScope)
        userData.setting(FRAME_RATE_MATCH_SETTING).onEach { value ->
            _state.update { it.copy(matchFrameRate = FrameRateMatchMode.of(value).storedValue) }
        }.launchIn(viewModelScope)
        userData.setting(com.yodesla.omniverse.core.data.UserDataRepository.PRELOAD_NEXT_CHANNEL).onEach { value ->
            _state.update { it.copy(preloadNextChannel = value != "false") }
        }.launchIn(viewModelScope)
        // Task 95: spoiler-free mode + its sub-option (Settings › Playback), both default off.
        userData.setting(com.yodesla.omniverse.core.data.UserDataRepository.SPOILER_FREE).onEach { value ->
            _state.update { it.copy(spoilerFree = com.yodesla.omniverse.core.data.SpoilerFree.enabled(value)) }
        }.launchIn(viewModelScope)
        userData.setting(com.yodesla.omniverse.core.data.UserDataRepository.SPOILER_FREE_HIDE_TITLES).onEach { value ->
            _state.update { it.copy(spoilerHideTitles = com.yodesla.omniverse.core.data.SpoilerFree.hideTitles(value)) }
        }.launchIn(viewModelScope)
        // Task 107: Back destination after opening a search result (Settings › Playback).
        userData.setting(com.yodesla.omniverse.core.data.UserDataRepository.BACK_FROM_SEARCH).onEach { value ->
            _state.update {
                it.copy(
                    backFromSearch = if (com.yodesla.omniverse.core.data.backFromSearchGoesToSearch(value))
                        com.yodesla.omniverse.core.data.UserDataRepository.BACK_FROM_SEARCH_SEARCH
                    else com.yodesla.omniverse.core.data.UserDataRepository.BACK_FROM_SEARCH_GUIDE_OR_DETAILS,
                )
            }
        }.launchIn(viewModelScope)
        userData.setting(com.yodesla.omniverse.designsystem.TEXT_SIZE_KEY).onEach { value ->
            _state.update { it.copy(textSize = value?.takeIf { v -> v in textSizes } ?: com.yodesla.omniverse.designsystem.TEXT_SIZE_NORMAL) }
        }.launchIn(viewModelScope)
        // Task 103: the score switch the Sports page reads (same key, same profile).
        userData.setting(com.yodesla.omniverse.core.data.sports.FollowedTeams.HIDE_SCORES_KEY).onEach { value ->
            _state.update { it.copy(hideSportsScores = value == "true" || value == "1") }
        }.launchIn(viewModelScope)
        // Task 84h: the category-language filter (Settings > Sources).
        userData.setting(com.yodesla.omniverse.core.data.categories.CATEGORY_LANG_FILTER_KEY).onEach { value ->
            _state.update { it.copy(categoryLangFilter = com.yodesla.omniverse.core.data.categories.parseCategoryLanguageFilter(value)) }
        }.launchIn(viewModelScope)
        userData.setting(com.yodesla.omniverse.core.data.categories.CATEGORY_LANG_KEEP_UNTAGGED_KEY).onEach { value ->
            _state.update { it.copy(categoryLangKeepUntagged = value != "false") }
        }.launchIn(viewModelScope)
        userData.setting(com.yodesla.omniverse.core.data.categories.CATEGORY_LANG_FILTER_VOD_KEY).onEach { value ->
            _state.update { it.copy(categoryLangFilterVod = value == "true") }
        }.launchIn(viewModelScope)
        observeCategoryLanguages()
        observeHiddenCategories()
    }

    /** Task 84m: the categories this profile hid, resolved to names through the catalog (hidden ones
     *  included), so the Settings row can list them and offer "Show" per row. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeHiddenCategories() {
        val cat = catalog ?: return
        val kinds = listOf(
            com.yodesla.omniverse.core.model.ContentKind.LIVE,
            com.yodesla.omniverse.core.model.ContentKind.VOD,
            com.yodesla.omniverse.core.model.ContentKind.SERIES,
        )
        val rowsFlow: kotlinx.coroutines.flow.Flow<List<HiddenCategoryUi>> =
            sources.sources().flatMapLatest<List<SourceSummary>, List<HiddenCategoryUi>> { srcs ->
                val flows: List<kotlinx.coroutines.flow.Flow<List<HiddenCategoryUi>>> = srcs.flatMap { s ->
                    kinds.map { kind ->
                        combine(cat.categories(s.id, kind, includeHidden = true), userData.hiddenCategoryKeys()) { cs, hidden ->
                            val names = cs.associateBy { "${kind.name}|${s.id.value}|${it.remoteId.value}" }
                            hidden.mapNotNull { key ->
                                names[key]?.let { c -> HiddenCategoryUi(key, c.name, s.name, kindLabel(kind)) }
                            }
                        }
                    }
                }
                if (flows.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList()) else flows.reduce { acc, f -> acc.combine(f) { a, b -> a + b } }
            }
        rowsFlow.map { rows -> rows.sortedWith(compareBy({ it.sourceName }, { it.name })) }
            .onEach { rows -> _state.update { it.copy(hiddenCategories = rows) } }
            .launchIn(viewModelScope)
    }

    private fun kindLabel(kind: com.yodesla.omniverse.core.model.ContentKind): String = when (kind) {
        com.yodesla.omniverse.core.model.ContentKind.LIVE -> "Live"
        com.yodesla.omniverse.core.model.ContentKind.VOD -> "Movies"
        com.yodesla.omniverse.core.model.ContentKind.SERIES -> "Shows"
        else -> kind.name
    }

    /** Task 84m: Settings › Sources › Hidden categories › Show — un-hide one category. */
    fun showHiddenCategory(key: String) = viewModelScope.launch {
        val parts = key.split('|')
        if (parts.size != 3) return@launch
        val kind = runCatching { com.yodesla.omniverse.core.model.ContentKind.valueOf(parts[0]) }.getOrNull() ?: return@launch
        userData.setCategoryHidden(SourceId(parts[1]), kind, parts[2], hidden = false)
    }

    /** Task 84m: Settings › Sources › Hidden categories › Show all — un-hide every hidden category. */
    fun showAllHiddenCategories() = viewModelScope.launch {
        for (row in _state.value.hiddenCategories) {
            val parts = row.key.split('|')
            if (parts.size != 3) continue
            val kind = runCatching { com.yodesla.omniverse.core.model.ContentKind.valueOf(parts[0]) }.getOrNull() ?: continue
            userData.setCategoryHidden(SourceId(parts[1]), kind, parts[2], hidden = false)
        }
    }

    /** Task 84h: which language groups this profile's categories actually carry, with counts, so the
     *  "Choose…" dialog offers only what exists ("Arabic · 213 categories"). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeCategoryLanguages() {
        val cat = catalog ?: return
        val kinds = listOf(
            com.yodesla.omniverse.core.model.ContentKind.LIVE,
            com.yodesla.omniverse.core.model.ContentKind.VOD,
            com.yodesla.omniverse.core.model.ContentKind.SERIES,
        )
        val namesFlow: kotlinx.coroutines.flow.Flow<List<com.yodesla.omniverse.core.data.categories.CategoryLanguage>> =
            sources.sources().flatMapLatest<List<SourceSummary>, List<com.yodesla.omniverse.core.data.categories.CategoryLanguage>> { srcs ->
                val flows: List<kotlinx.coroutines.flow.Flow<List<com.yodesla.omniverse.core.data.categories.CategoryLanguage>>> =
                    srcs.flatMap { s ->
                        kinds.map { kind ->
                            cat.categories(s.id, kind, includeHidden = true).map { cs ->
                                cs.map { com.yodesla.omniverse.core.data.categories.categoryLanguage(it.name) }
                            }
                        }
                    }
                if (flows.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList()) else flows.reduce { acc, f -> acc.combine(f) { a, b -> a + b } }
            }
        namesFlow.map { names ->
            names.groupingBy { it }.eachCount()
                .filterKeys { it != com.yodesla.omniverse.core.data.categories.CategoryLanguage.UNKNOWN }
                .entries.sortedWith(
                    compareByDescending<Map.Entry<com.yodesla.omniverse.core.data.categories.CategoryLanguage, Int>> { it.value }
                        .thenBy { it.key.ordinal },
                ).map { CategoryGroupCount(it.key, it.value) }
        }.onEach { groups -> _state.update { it.copy(categoryLangGroups = groups) } }.launchIn(viewModelScope)
    }

    private fun loadAccount(id: SourceId) = viewModelScope.launch {
        val result: Triple<String, SourceErrorKind?, Long?> = try {
            val info = sources.contentSource(id)?.accountInfo()
            if (info == null) return@launch
            Triple(
                buildList {
                    add(when (info.status) {
                        AccountStatus.ACTIVE -> "Active"
                        AccountStatus.EXPIRED -> "Expired"
                        AccountStatus.BANNED, AccountStatus.DISABLED -> "Disabled by provider"
                        else -> "Status unknown"
                    })
                    add(info.expiresAtMs?.let { "until " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "no expiry date")
                    info.maxConnections?.let { add("up to $it screen${if (it == 1) "" else "s"} at once") }
                }.joinToString(" · "),
                null,
                null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceException) {
            val kind = classify(e)
            val at = clock.nowMs()
            Triple(
                when (kind) {
                    SourceErrorKind.AUTH -> "Login rejected — check the username and password"
                    else -> "Couldn't reach the provider"
                },
                kind,
                at,
            )
        }
        update(id) { it.copy(account = result.first, errorKind = result.second, failedAtMs = result.third) }
        userData.putSetting(SourceStatusKeys.error(id), encodeError(result.second, result.third))
    }

    fun refresh(id: SourceId) = viewModelScope.launch {
        var failed: SourceErrorKind? = null
        var failedAt: Long? = null
        var lastStage: SyncStage? = null
        sync.sync(id, SyncScope.ALL, force = true).collect { p ->
            if (p.stage != lastStage) {
                lastStage = p.stage
                userData.putSetting(SourceStatusKeys.sync(id), encodeSync(p.stage))
            }
            val err = p.error
            if (err != null) {
                failed = classify(err)
                failedAt = clock.nowMs()
            }
            val stageText = if (p.finished) null else "Updating… ${p.stage.name.lowercase().replace('_', ' ')} ${if (p.done > 0) p.done else ""}".trim()
            update(id) {
                it.copy(
                    syncing = stageText,
                    errorKind = if (p.finished) failed else it.errorKind,
                    failedAtMs = if (p.finished) failedAt else it.failedAtMs,
                )
            }
            if (p.finished) {
                userData.putSetting(SourceStatusKeys.sync(id), "")
                userData.putSetting(SourceStatusKeys.error(id), encodeError(failed, failedAt))
            }
        }
    }

    private fun classify(e: SourceException): SourceErrorKind = when (e) {
        is SourceException.AuthFailed, is SourceException.Expired -> SourceErrorKind.AUTH
        is SourceException.Network -> SourceErrorKind.NETWORK
        else -> SourceErrorKind.OTHER
    }

    fun askRemove(id: SourceId?) = _state.update { it.copy(confirmRemove = id) }

    fun remove(id: SourceId) = viewModelScope.launch {
        sources.remove(id)
        _state.update { it.copy(confirmRemove = null) }
    }

    fun setMode(mode: String) = viewModelScope.launch { userData.putSetting(MODE_KEY, mode) }

    /** Default audio language / subtitles mode for playback (read by the player). */
    val audioLanguage = userData.setting("pref_audio_language")
    val subtitles = userData.setting("pref_subtitles")
    fun setPlaybackPref(key: String, value: String) = viewModelScope.launch { userData.putSetting(key, value) }

    /** Task 84j: the four per-profile subtitle-appearance strings, combined for the Settings row. */
    val subtitleStyle = combine(
        userData.setting(SUBTITLE_STYLE_SIZE),
        userData.setting(SUBTITLE_STYLE_BACKGROUND),
        userData.setting(SUBTITLE_STYLE_COLOR),
        userData.setting(SUBTITLE_STYLE_POSITION),
    ) { size, background, color, position -> SubtitleStyleRaw(size, background, color, position) }

    fun setSkipMode(key: String, mode: String) = viewModelScope.launch {
        if ((key == SkipSettings.INTRO_MODE || key == SkipSettings.CREDITS_MODE) && mode in skipModes) {
            userData.putSetting(key, mode)
        }
    }

    fun setCommunitySkipLookup(enabled: Boolean) = viewModelScope.launch {
        userData.putSetting(SkipSettings.COMMUNITY_LOOKUP, enabled.toString())
    }

    fun setRtScores(enabled: Boolean) = viewModelScope.launch {
        userData.putSetting(com.yodesla.omniverse.core.data.metadata.RottenTomatoes.SETTING_KEY, enabled.toString())
    }

    fun setPickerAtStart(enabled: Boolean) = viewModelScope.launch {
        userData.putSetting(com.yodesla.omniverse.core.data.ProfileRepository.PICKER_KEY, enabled.toString())
    }

    /** Task 84: writes the per-profile text size; the theme re-scales immediately from the same setting. */
    fun setTextSize(value: String) = viewModelScope.launch {
        if (value in textSizes) userData.putSetting(com.yodesla.omniverse.designsystem.TEXT_SIZE_KEY, value)
    }

    /** Task 103: the per-profile score switch the Sports page reads. */
    fun setHideSportsScores(enabled: Boolean) = viewModelScope.launch {
        userData.putSetting(com.yodesla.omniverse.core.data.sports.FollowedTeams.HIDE_SCORES_KEY, enabled.toString())
    }

    fun setMetadataLookup(enabled: Boolean) = viewModelScope.launch {
        userData.putSetting(com.yodesla.omniverse.core.data.metadata.MetadataEnricher.SETTING_KEY, enabled.toString())
    }

    fun setLocalSkipAnalysis(enabled: Boolean) = viewModelScope.launch {
        userData.putSetting(SkipSettings.LOCAL_ANALYSIS, enabled.toString())
    }

    /** Task 100: per-profile frame-rate matching ("off" / "seamless" / "always"). */
    fun setMatchFrameRate(mode: String) = viewModelScope.launch {
        userData.putSetting(FRAME_RATE_MATCH_SETTING, FrameRateMatchMode.of(mode).storedValue)
    }

    /** Task 94: pre-load the next live channel on a hidden engine (per-profile, default on). */
    fun setPreloadNextChannel(enabled: Boolean) = viewModelScope.launch {
        userData.putSetting(com.yodesla.omniverse.core.data.UserDataRepository.PRELOAD_NEXT_CHANNEL, enabled.toString())
    }

    /** Task 95: hide spoilers for unwatched episodes (per-profile, default off). */
    fun setSpoilerFree(enabled: Boolean) = viewModelScope.launch {
        userData.putSetting(com.yodesla.omniverse.core.data.UserDataRepository.SPOILER_FREE, enabled.toString())
    }

    /** Task 95: also hide episode titles (per-profile, default off). */
    fun setSpoilerHideTitles(enabled: Boolean) = viewModelScope.launch {
        userData.putSetting(com.yodesla.omniverse.core.data.UserDataRepository.SPOILER_FREE_HIDE_TITLES, enabled.toString())
    }

    /** Task 107: where Back lands after opening a search result (per-profile). */
    fun setBackFromSearch(value: String) = viewModelScope.launch {
        userData.putSetting(com.yodesla.omniverse.core.data.UserDataRepository.BACK_FROM_SEARCH, value)
    }

    /** Task 84h: pick the allowed language groups (empty = "All", filter off). */
    fun setCategoryLanguages(selected: Set<com.yodesla.omniverse.core.data.categories.CategoryLanguage>) = viewModelScope.launch {
        userData.putSetting(
            com.yodesla.omniverse.core.data.categories.CATEGORY_LANG_FILTER_KEY,
            com.yodesla.omniverse.core.data.categories.encodeCategoryLanguageFilter(selected),
        )
    }

    fun setCategoryLangKeepUntagged(enabled: Boolean) = viewModelScope.launch {
        userData.putSetting(com.yodesla.omniverse.core.data.categories.CATEGORY_LANG_KEEP_UNTAGGED_KEY, enabled.toString())
    }

    fun setCategoryLangFilterVod(enabled: Boolean) = viewModelScope.launch {
        userData.putSetting(com.yodesla.omniverse.core.data.categories.CATEGORY_LANG_FILTER_VOD_KEY, enabled.toString())
    }

    private fun update(id: SourceId, f: (SourceUi) -> SourceUi) =
        _state.update { s -> s.copy(sources = s.sources.map { if (it.summary.id == id) f(it) else it }) }

    fun lastSynced(ms: Long?): String = ms?.let {
        val mins = (clock.nowMs() - it) / 60_000
        when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 48 * 60 -> "${mins / 60} h ago"
            else -> "${mins / 1440} days ago"
        }
    } ?: "never"

    companion object {
        const val MODE_KEY = "experience_mode"
        private val skipModes = setOf(SkipSettings.OFF, SkipSettings.BUTTON, SkipSettings.AUTO)
        private val textSizes = setOf(
            com.yodesla.omniverse.designsystem.TEXT_SIZE_NORMAL,
            com.yodesla.omniverse.designsystem.TEXT_SIZE_LARGE,
            com.yodesla.omniverse.designsystem.TEXT_SIZE_XLARGE,
        )
    }
}

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    appName: String,
    version: String,
    disclaimer: String,
    onAddSource: () -> Unit,
    /** Task 59: opens the profile management screens (owned by the shell). */
    onOpenProfiles: () -> Unit = {},
    onSetPickerAtStart: (Boolean) -> Unit = viewModel::setPickerAtStart,
    /** Task 84b: the last profile was deleted - the app runs as the Guest. */
    guestMode: Boolean = false,
    /** Task 84b: "Create a profile" in guest mode (shell opens the editor). */
    onCreateProfile: () -> Unit = {},
    /** Task 84e: per-category content owned by the app module (typed replacement for extraSections). */
    extraCategories: Map<SettingsCategory, SettingsSlot> = emptyMap(),
    /** Per-source management under each source's buttons (libraries, Plex servers). */
    sourceExtras: @Composable (SourceId) -> Unit = {},
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val compact = LocalCompact.current
    val registry = remember { RowFocusRegistry() }
    var selected by remember { mutableStateOf(SettingsCategory.Sources) }
    var enteredPane by remember { mutableStateOf(false) }
    val railFr = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // Task 99: Settings search. The field never grabs focus on open (the rail keeps it); typing
    // or voice filters the static index, and OK on a hit jumps to its section and focuses the row.
    var query by remember { mutableStateOf("") }
    var pendingJump by remember { mutableStateOf<Pair<SettingsCategory, String>?>(null) }
    // Task 111 M5: a search hit whose row is not rendered in its pane shows a short notice.
    var jumpMiss by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(jumpMiss) { if (jumpMiss != null) { kotlinx.coroutines.delay(2500); jumpMiss = null } }
    val searchFr = remember { FocusRequester() }
    val hits = remember(query) { searchSettings(query) }
    val searching = query.isNotBlank()
    // Task 90: dismissed Continue Watching titles, restorable from Settings > Display.
    val hidden by viewModel.hiddenContinueWatching.collectAsStateWithLifecycle(initialValue = emptyList())
    var showHiddenCw by remember { mutableStateOf(false) }
    if (showHiddenCw) {
        HiddenCwDialog(
            hidden = hidden,
            onRestore = viewModel::restoreHiddenContinueWatching,
            onRestoreAll = viewModel::restoreAllHiddenContinueWatching,
            onDismiss = { showHiddenCw = false },
        )
    }
    val categories = remember(extraCategories) {
        settingsCategories(
            hasPhoneRemote = extraCategories.containsKey(SettingsCategory.PhoneRemote),
            hasBackup = extraCategories.containsKey(SettingsCategory.BackupRestore),
        )
    }
    LaunchedEffect(categories) {
        if (categories.none { it == selected }) { selected = categories.first(); enteredPane = false }
    }
    LaunchedEffect(Unit) { railFr.requestFocusWhenReady() }
    LaunchedEffect(enteredPane, selected) {
        // Task 99: while a search jump to this category is pending, the pane owns focus instead.
        if (enteredPane && pendingJump?.first != selected) registry.focusReady(selected)
    }
    // Task 84e: while the viewer is inside the rows pane, Back returns to the category list.
    BackHandler(enabled = enteredPane) { enteredPane = false; scope.launch { railFr.requestFocusWhenReady() } }
    // Task 99: Back leaves the search state first, before the pane/rail handling above.
    BackHandler(enabled = searching) { query = "" }
    val jumpTo: (SettingsSearchEntry) -> Unit = { hit ->
        selected = hit.category
        enteredPane = true
        pendingJump = hit.category to hit.rowKey
        query = ""
    }
    val jumpKeyForPane = pendingJump?.let { p -> if (p.first == selected) p.second else null }

    com.yodesla.omniverse.designsystem.CosmicBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsSearchField(query, searchFr, Modifier.fillMaxWidth().padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, top = OmniSpacing.l, bottom = OmniSpacing.m)) { q -> query = q }
            if (jumpMiss != null) {
                Text(
                    "That setting is not available in this view",
                    style = OmniTheme.type.body, color = OmniTheme.colors.textTertiary,
                    modifier = Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.s),
                )
            }
            if (searching) {
                // Task 99: while searching, the results list replaces the rail and pane.
                if (hits.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_search_no_results, query.trim()),
                        style = OmniTheme.type.body, color = OmniTheme.colors.textTertiary,
                        modifier = Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.xl),
                    )
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.s),
                        verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                    ) {
                        items(hits, key = { it.category to it.rowKey }) { hit ->
                            SettingsSearchResult(hit, categoryTitle(hit.category)) { jumpTo(hit) }
                        }
                    }
                }
            } else if (compact) {
                // Phone: one column. The list is the screen; OK opens a category full-width.
                Box(Modifier.weight(1f)) {
                    if (enteredPane) {
                        SettingsCategoryPane(
                            category = selected, state = s, viewModel = viewModel,
                            version = "$appName $version", disclaimer = disclaimer, guestMode = guestMode, compact = compact,
                            onAddSource = onAddSource, onOpenProfiles = onOpenProfiles, onSetPickerAtStart = onSetPickerAtStart,
                            onCreateProfile = onCreateProfile, slot = extraCategories[selected], sourceExtras = sourceExtras,
                            registry = registry, categoryTitle = categoryTitle(selected),
                            hiddenCwCount = hidden.size, onOpenHiddenCw = { showHiddenCw = true },
                            jumpKey = jumpKeyForPane, onJumpHandled = { pendingJump = null }, onJumpMiss = { jumpMiss = it },
                            modifier = Modifier.fillMaxSize().onFocusChanged { if (!it.hasFocus) enteredPane = false },
                        )
                    } else {
                        SettingsRail(categories, selected, railFr, Modifier.fillMaxSize()) { cat -> selected = cat; enteredPane = true }
                    }
                }
            } else {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    SettingsRail(categories, selected, railFr, Modifier.width(320.dp).fillMaxHeight(), onHighlight = { selected = it }) { cat ->
                        selected = cat; enteredPane = true
                    }
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .onFocusChanged { if (!it.hasFocus) enteredPane = false },
                    ) {
                        SettingsCategoryPane(
                            category = selected, state = s, viewModel = viewModel,
                            version = "$appName $version", disclaimer = disclaimer, guestMode = guestMode, compact = compact,
                            onAddSource = onAddSource, onOpenProfiles = onOpenProfiles, onSetPickerAtStart = onSetPickerAtStart,
                            onCreateProfile = onCreateProfile, slot = extraCategories[selected], sourceExtras = sourceExtras,
                            registry = registry, categoryTitle = categoryTitle(selected),
                            hiddenCwCount = hidden.size, onOpenHiddenCw = { showHiddenCw = true },
                            jumpKey = jumpKeyForPane, onJumpHandled = { pendingJump = null }, onJumpMiss = { jumpMiss = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Task 99: the Settings search field. Deliberately never auto-focused - the rail keeps focus when
 * Settings opens, and the field is just the focus stop above it. Voice works through the TV's IME
 * (hold the mic), same documented exception as the Search screen (task 69): a text input keeps
 * material3's own focus treatment.
 */
@Composable
private fun SettingsSearchField(
    query: String,
    field: FocusRequester,
    modifier: Modifier = Modifier,
    onQuery: (String) -> Unit,
) {
    val c = OmniTheme.colors
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.settings_search_hint), style = OmniTheme.type.body, color = c.textTertiary) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = c.textTertiary) },
        textStyle = OmniTheme.type.body.copy(color = c.textPrimary),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.elevated, cursorColor = c.accent,
            focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
        ),
        modifier = modifier.focusRequester(field),
    )
}

/** One Settings search result: the setting's label over its category; OK jumps there. */
@Composable
private fun SettingsSearchResult(hit: SettingsSearchEntry, category: String, onPick: () -> Unit) {
    val c = OmniTheme.colors
    FocusCard(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = OmniSpacing.l, vertical = OmniSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(hit.label, style = OmniTheme.type.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(category, style = OmniTheme.type.caption, color = c.textTertiary, maxLines = 1)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
        }
    }
}

/** The left rail: Apple TV-style category list. OK enters the rows pane for the category. Scrolls
 * vertically so the last categories stay reachable on a 1080p screen (task 100). */
@Composable
private fun SettingsRail(
    categories: List<SettingsCategory>,
    selected: SettingsCategory,
    railFr: FocusRequester,
    modifier: Modifier = Modifier,
    /** Moving the highlight shows that category's pane at once (Apple TV style); OK still enters it. */
    onHighlight: (SettingsCategory) -> Unit = {},
    onPick: (SettingsCategory) -> Unit,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    // The rail is taller than a 1080p screen once every category is listed, so it scrolls. D-pad
    // moves scroll a row into view on their own (a scroll container brings its focused child into
    // view); this only covers a selection change that arrives without a focus move - a search jump
    // or opening a pane - which would otherwise leave the active category below the fold.
    val scroll = rememberScrollState()
    val selBringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        // Retry a few frames: the requester only attaches to the newly selected row once that row is
        // composed and laid out, same lazy-attach race requestFocusWhenReady guards against.
        repeat(10) {
            withFrameNanos { }
            if (runCatching { selBringIntoView.bringIntoView() }.isSuccess) return@LaunchedEffect
        }
    }
    Column(
        modifier.glassSurface(corner = 0.dp, shadow = false)
            // Entering the rail always lands on the CURRENT category (like the main nav rail).
            .focusProperties { onEnter = { railFr.requestFocus() } }
            .verticalScroll(scroll)
            .padding(vertical = OmniSpacing.xl, horizontal = OmniSpacing.m),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = t.overline, color = c.accent,
            modifier = Modifier.padding(start = OmniSpacing.m, bottom = OmniSpacing.m))
        for (cat in categories) {
            val isSel = cat == selected
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier.fillMaxWidth().height(48.dp)
                    .then(if (isSel) Modifier.focusRequester(railFr).bringIntoViewRequester(selBringIntoView) else Modifier)
                    .onFocusChanged { focused = it.isFocused; if (it.isFocused) onHighlight(cat) }
                    .then(if (focused || isSel) Modifier.glassSurface(corner = 26.dp, focused = focused, shadow = false) else Modifier)
                    .clickableTv { onPick(cat) },
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.padding(horizontal = OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        categoryIcon(cat), contentDescription = categoryTitle(cat),
                        tint = when {
                            focused -> c.textPrimary
                            isSel -> c.accent
                            else -> c.textTertiary
                        },
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(OmniSpacing.m))
                    Text(categoryTitle(cat), style = if (isSel || focused) t.title else t.body,
                        color = if (isSel || focused) c.textPrimary else c.textSecondary, maxLines = 1)
                }
                if (isSel && !focused) {
                    Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
                }
            }
        }
    }
}

/** Task 90: the dismissed-Continue-Watching list - poster + title + last-watched date, Restore per
 * title and Restore all. Back closes it. */
@Composable
private fun HiddenCwDialog(
    hidden: List<HiddenCwRow>,
    onRestore: (com.yodesla.omniverse.core.model.ContentKey) -> Unit,
    onRestoreAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OmniTheme.colors
    val firstRow = remember { FocusRequester() }
    val dateFmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 900.dp).clip(RoundedCornerShape(OmniSpacing.l))
                .background(c.elevated.copy(alpha = 0.92f))
                .padding(OmniSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.m),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                Text("Hidden from Continue Watching", style = OmniTheme.type.title, color = c.textPrimary)
                if (hidden.isNotEmpty()) com.yodesla.omniverse.feature.home.settings.RowPill("Restore all", accent = true) { onRestoreAll() }
                com.yodesla.omniverse.feature.home.settings.RowPill("Close") { onDismiss() }
            }
            if (hidden.isEmpty()) {
                Text("Nothing hidden. Dismissed titles will appear here.", style = OmniTheme.type.body, color = c.textSecondary)
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                ) {
                    itemsIndexed(hidden) { i, row ->
                        Row(
                            Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(firstRow) else Modifier),
                            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(width = 64.dp, height = 96.dp).clip(RoundedCornerShape(OmniSpacing.s))
                                    .background(c.surface),
                            ) {
                                if (!row.posterUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = row.posterUrl, contentDescription = null,
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(row.name, style = OmniTheme.type.body, color = c.textPrimary)
                                val whenText = if (row.lastWatchedMs > 0) dateFmt.format(Date(row.lastWatchedMs)) else "—"
                                Text("Last watched $whenText", style = OmniTheme.type.caption, color = c.textSecondary)
                            }
                            com.yodesla.omniverse.feature.home.settings.RowPill("Restore") { onRestore(row.key) }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { firstRow.requestFocusWhenReady() }
}

@Composable
private fun categoryTitle(category: SettingsCategory): String = stringResource(
    when (category) {
        SettingsCategory.Sources -> R.string.settings_cat_sources
        SettingsCategory.Playback -> R.string.settings_cat_playback
        SettingsCategory.Display -> R.string.settings_cat_display
        SettingsCategory.ProfilesAndKids -> R.string.settings_cat_profiles
        SettingsCategory.Metadata -> R.string.settings_cat_metadata
        SettingsCategory.PhoneRemote -> R.string.settings_cat_phone_remote
        SettingsCategory.BackupRestore -> R.string.settings_cat_backup
        SettingsCategory.AboutAndUpdates -> R.string.settings_cat_about
    },
)

private fun categoryIcon(category: SettingsCategory): ImageVector = when (category) {
    SettingsCategory.Sources -> Icons.Outlined.Cloud
    SettingsCategory.Playback -> Icons.Outlined.PlayCircle
    SettingsCategory.Display -> Icons.Outlined.Tune
    SettingsCategory.ProfilesAndKids -> Icons.Outlined.People
    SettingsCategory.Metadata -> Icons.Outlined.Info
    SettingsCategory.PhoneRemote -> Icons.Outlined.PhoneAndroid
    SettingsCategory.BackupRestore -> Icons.Outlined.Backup
    SettingsCategory.AboutAndUpdates -> Icons.Outlined.SystemUpdate
}
