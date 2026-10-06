package com.yodesla.omniverse.feature.home.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.categories.CategoryLanguage
import com.yodesla.omniverse.core.data.SkipSettings
import com.yodesla.omniverse.core.data.SourceErrorKind
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniPickerDialog
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.PickerOption
import com.yodesla.omniverse.designsystem.glassSurface
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import com.yodesla.omniverse.player.FRAME_RATE_MATCH_ALWAYS
import com.yodesla.omniverse.player.FRAME_RATE_MATCH_OFF
import com.yodesla.omniverse.player.FRAME_RATE_MATCH_SEAMLESS
import com.yodesla.omniverse.player.SubtitleStyleDialog
import com.yodesla.omniverse.player.subtitleStyleSummary
import com.yodesla.omniverse.feature.home.CategoryGroupCount
import com.yodesla.omniverse.feature.home.HiddenCategoryUi
import com.yodesla.omniverse.feature.home.R
import com.yodesla.omniverse.feature.home.SettingsState
import com.yodesla.omniverse.feature.home.SettingsViewModel

/**
 * Task 84e: the right pane - the selected category's rows under a large title. Built-in rows come
 * from [settingsRows]; the app module's slot (if any) renders after them. Pickers and the full
 * disclosure texts open as glass dialogs, so nothing is ever deleted from the page.
 */
@Composable
fun SettingsCategoryPane(
    category: SettingsCategory,
    state: SettingsState,
    viewModel: SettingsViewModel,
    version: String,
    disclaimer: String,
    guestMode: Boolean,
    compact: Boolean,
    onAddSource: () -> Unit,
    onOpenProfiles: () -> Unit,
    onSetPickerAtStart: (Boolean) -> Unit,
    onCreateProfile: () -> Unit,
    slot: (@Composable () -> Unit)?,
    sourceExtras: @Composable (SourceId) -> Unit,
    registry: RowFocusRegistry,
    categoryTitle: String,
    modifier: Modifier = Modifier,
    /** Task 90: dismissed Continue Watching titles (Display pane row; opens the restore dialog). */
    hiddenCwCount: Int = 0,
    onOpenHiddenCw: (() -> Unit)? = null,
    /** Task 99: Settings search jump - scroll to this row key, focus it, then report handled. */
    jumpKey: String? = null,
    onJumpHandled: () -> Unit = {},
    onJumpMiss: (String) -> Unit = {},
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    var picker by remember(category) { mutableStateOf<PickerSpec?>(null) }
    var langDialog by remember(category) { mutableStateOf(false) }
    var hiddenDialog by remember(category) { mutableStateOf(false) }
    var styleDialog by remember(category) { mutableStateOf(false) }
    var info by remember(category) { mutableStateOf<Pair<String, String>?>(null) }
    val ctx = remember(category, registry) { RowCtx(category, registry) { title, body -> info = title to body } }
    val rows = settingsRows(
        category,
        compact = compact,
        guestMode = guestMode,
        hasPin = category == SettingsCategory.ProfilesAndKids && slot != null,
        hasUpdateCheck = category == SettingsCategory.AboutAndUpdates && slot != null,
        hasCustomizeHome = category == SettingsCategory.Display && slot != null,
    )

    // Task 99: a search jump scrolls the pane by index, so the list state is hoisted here.
    val listState = remember(category) { LazyListState() }
    LaunchedEffect(jumpKey) {
        if (jumpKey == null) return@LaunchedEffect
        val keys = settingsPaneItemKeys(
            category,
            sourceIds = state.sources.map { it.summary.id.value },
            hasHiddenCategories = state.hiddenCategories.isNotEmpty(),
            spoilerFree = state.spoilerFree,
            compact = compact,
            guestMode = guestMode,
            hasSlot = slot != null,
            hasHiddenCwRow = onOpenHiddenCw != null,
        )
        val idx = settingsJumpIndex(keys, jumpKey)
        // Task 111 M5: the search index is static but the pane is conditional. A hit whose row is
        // not rendered (spoiler-free off, compact, guest mode, no slot, nothing hidden) must not
        // request focus on a FocusRequester attached to nothing - that leaves the D-pad dead.
        if (idx >= 0) {
            listState.scrollToItem(idx)
            registry.requester(jumpKey).requestFocusWhenReady()
        } else {
            onJumpMiss(jumpKey)
        }
        onJumpHandled()
    }

    FocusPivot(parentFraction = 0.35f, leading = 0.dp) {
        LazyColumn(
            modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = OmniSpacing.l, end = OmniSpacing.tvSide, top = OmniSpacing.xl, bottom = 240.dp),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
        ) {
            item(key = SettingsRowKeys.TITLE) { Text(categoryTitle, style = t.browseHeading, color = c.textPrimary) }
            when (category) {
                SettingsCategory.Sources -> sourcesRows(state, viewModel, onAddSource, sourceExtras, ctx, registry, openLangDialog = { langDialog = true }, openHiddenDialog = { hiddenDialog = true }, openPicker = { picker = it })
                SettingsCategory.Playback -> playbackRows(state, viewModel, rows, ctx, openStyleDialog = { styleDialog = true }) { picker = it }
                SettingsCategory.Display -> {
                    displayRows(viewModel, rows, ctx) { picker = it }
                    // Customize Home is app-provided (the app owns HomeViewModel).
                    if (SettingsRowKeys.CUSTOMIZE_HOME in rows) slot?.let { s -> item(key = SettingsRowKeys.CUSTOMIZE_HOME) { SlotFocusBox(ctx.registry, SettingsRowKeys.CUSTOMIZE_HOME) { s() } } }
                if (onOpenHiddenCw != null) item(key = SettingsRowKeys.HIDDEN_CW) {
                    OmniActionRow("Hidden from Continue Watching", if (hiddenCwCount > 0) "$hiddenCwCount · Show" else "None",
                        secondary = "Titles you removed stay hidden until you restore them.", onClick = onOpenHiddenCw, ctx = ctx, key = SettingsRowKeys.HIDDEN_CW)
                }
                }
                SettingsCategory.ProfilesAndKids -> profileRows(state, guestMode, onOpenProfiles, onSetPickerAtStart, onCreateProfile, ctx, slot)
                SettingsCategory.Metadata -> metadataRows(state, viewModel, ctx)
                // Slot-only categories: the app module owns everything here.
                SettingsCategory.PhoneRemote, SettingsCategory.BackupRestore -> slot?.let { s -> item(key = SettingsRowKeys.SLOT) { SlotFocusBox(ctx.registry, SettingsRowKeys.SLOT) { s() } } }
                SettingsCategory.AboutAndUpdates -> {
                    aboutRows(version, disclaimer, rows, ctx)
                    // Check for updates is app-provided (the app owns UpdateViewModel).
                    if (SettingsRowKeys.UPDATE_CHECK in rows) slot?.let { s -> item(key = SettingsRowKeys.UPDATE_CHECK) { SlotFocusBox(ctx.registry, SettingsRowKeys.UPDATE_CHECK) { s() } } }
                }
            }
            item(key = SettingsRowKeys.END_SPACER) { Spacer(Modifier.height(OmniSpacing.l)) }
        }
    }

    picker?.let { p ->
        OmniPickerDialog(
            title = p.title,
            options = p.options,
            selected = p.selected,
            onSelect = { v -> p.onPick(v); picker = null },
            onDismiss = { picker = null },
        )
    }
    info?.let { (title, body) -> OmniInfoDialog(title, body, onDismiss = { info = null }) }
    // Task 84h: multi-select of the language groups present in this profile's sources.
    if (langDialog) {
        CategoryLanguageDialog(
            groups = state.categoryLangGroups,
            initial = state.categoryLangFilter,
            onSave = { sel -> viewModel.setCategoryLanguages(sel); langDialog = false },
            onDismiss = { langDialog = false },
        )
    }
    // Task 84m: the categories this profile hid (hold-OK › Hide category); "Show" brings one back.
    if (hiddenDialog) {
        HiddenCategoriesDialog(
            rows = state.hiddenCategories,
            onShow = { key -> viewModel.showHiddenCategory(key) },
            onShowAll = { viewModel.showAllHiddenCategories() },
            onDismiss = { hiddenDialog = false },
        )
    }
    // Task 84j: per-profile subtitle appearance. Writes straight to the four profile keys.
    if (styleDialog) {
        val raw by viewModel.subtitleStyle.collectAsStateWithLifecycle(initialValue = null)
        SubtitleStyleDialog(
            size = raw?.size,
            background = raw?.background,
            color = raw?.color,
            position = raw?.position,
            onChange = { key, value -> viewModel.setPlaybackPref(key, value) },
            onDismiss = { styleDialog = false },
        )
    }
}

// ---------------------------------------------------------------- per-category row emitters
// These run in LazyListScope (not composition): every composable call lives inside an item block.

private fun LazyListScope.sourcesRows(
    state: SettingsState,
    viewModel: SettingsViewModel,
    onAddSource: () -> Unit,
    sourceExtras: @Composable (SourceId) -> Unit,
    ctx: RowCtx,
    registry: RowFocusRegistry,
    openLangDialog: () -> Unit,
    openHiddenDialog: () -> Unit = {},
    openPicker: (PickerSpec) -> Unit = {},
) {
    state.sources.forEachIndexed { i, src ->
        item(key = SettingsRowKeys.SOURCE_PREFIX + src.summary.id.value) {
            val c = OmniTheme.colors
            val t = OmniTheme.type
            val id = src.summary.id.value
            val updateKey = "src-update-$id"
            val removeFr = remember { FocusRequester() }
            val keepFr = remember { FocusRequester() }
            val confirming = state.confirmRemove?.value == id
            var wasConfirming by remember { mutableStateOf(false) }
            // Swapping Remove for the confirm pair destroys the focused node; without this the
            // focus falls through to the category rail. Land on the SAFE choice, and come back after.
            // requestFocusWhenReady retries across frames: the swapped-in pill is not laid out in the
            // frame the row recomposes, so a single requestFocus() failed there and runCatching hid it
            // - focus never reached the confirmation, which is why Remove looked dead on the D-pad.
            LaunchedEffect(confirming) {
                if (confirming) { keepFr.requestFocusWhenReady(); wasConfirming = true }
                else if (wasConfirming) { removeFr.requestFocusWhenReady(); wasConfirming = false }
            }
            Column(Modifier.widthIn(max = 960.dp)) {
                Text(src.summary.name, style = t.title, color = c.textPrimary)
                Spacer(Modifier.height(OmniSpacing.s))
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    RowPill(
                        stringResource(R.string.settings_update_now),
                        modifier = Modifier
                            .focusRequester(registry.requester(updateKey))
                            .then(if (i == 0) Modifier.onFocusChanged { if (it.hasFocus) registry.noteFocused(ctx.category, updateKey) } else Modifier),
                    ) { viewModel.refresh(src.summary.id) }
                    if (confirming) {
                        RowPill(stringResource(R.string.settings_yes_remove), accent = true) { viewModel.remove(src.summary.id) }
                        RowPill(stringResource(R.string.settings_keep_it), Modifier.focusRequester(keepFr)) { viewModel.askRemove(null) }
                    } else {
                        RowPill(stringResource(R.string.settings_remove), Modifier.focusRequester(removeFr), destructive = true) { viewModel.askRemove(src.summary.id) }
                    }
                    if (src.errorKind == SourceErrorKind.AUTH && src.summary.kind == SourceKind.PLEX) {
                        RowPill(stringResource(R.string.settings_relink)) { onAddSource() }
                    }
                }
                sourceExtras(src.summary.id)
            }
        }
    }
    // Task 84h: the category-language filter, right under the sources it applies to.
    item(key = SettingsRowKeys.CATEGORY_LANG) {
        val title = stringResource(R.string.settings_cat_lang_title)
        val options = listOf(
            PickerOption("all", stringResource(R.string.settings_cat_lang_all)),
            PickerOption("en", stringResource(R.string.settings_cat_lang_english_only)),
            PickerOption("choose", stringResource(R.string.settings_cat_lang_choose)),
        )
        val current = when {
            state.categoryLangFilter.isEmpty() -> "all"
            state.categoryLangFilter == setOf(CategoryLanguage.ENGLISH) -> "en"
            else -> "choose"
        }
        val value = when (current) {
            "all" -> stringResource(R.string.settings_cat_lang_all)
            "en" -> stringResource(R.string.settings_cat_lang_english_only)
            else -> com.yodesla.omniverse.core.data.categories.categoryLanguageFilterLabel(state.categoryLangFilter)
        }
        OmniPickerRow(
            title,
            value = value,
            secondary = stringResource(R.string.settings_cat_lang_note),
            onClick = {
                openPicker(
                    PickerSpec(title, options, current) { v ->
                        when (v) {
                            "all" -> viewModel.setCategoryLanguages(emptySet())
                            "en" -> viewModel.setCategoryLanguages(setOf(CategoryLanguage.ENGLISH))
                            else -> openLangDialog()
                        }
                    },
                )
            },
            ctx = ctx, key = SettingsRowKeys.CATEGORY_LANG,
        )
    }
    item(key = SettingsRowKeys.CATEGORY_LANG_UNTAGGED) {
        OmniSwitchRow(
            stringResource(R.string.settings_cat_lang_keep_untagged),
            checked = state.categoryLangKeepUntagged, onCheckedChange = viewModel::setCategoryLangKeepUntagged,
            disclosure = stringResource(R.string.settings_cat_lang_keep_untagged_note),
            ctx = ctx, key = SettingsRowKeys.CATEGORY_LANG_UNTAGGED,
        )
    }
    item(key = SettingsRowKeys.CATEGORY_LANG_VOD) {
        OmniSwitchRow(
            stringResource(R.string.settings_cat_lang_vod),
            checked = state.categoryLangFilterVod, onCheckedChange = viewModel::setCategoryLangFilterVod,
            disclosure = stringResource(R.string.settings_cat_lang_vod_note),
            ctx = ctx, key = SettingsRowKeys.CATEGORY_LANG_VOD,
        )
    }
    // Task 84m: only shown when something is hidden; opens the list with a "Show" button per row.
    if (state.hiddenCategories.isNotEmpty()) {
        item(key = SettingsRowKeys.HIDDEN_CATEGORIES) {
            OmniActionRow(
                stringResource(R.string.settings_hidden_categories),
                label = stringResource(R.string.settings_hidden_categories_count, state.hiddenCategories.size),
                secondary = stringResource(R.string.settings_hidden_categories_note),
                onClick = openHiddenDialog,
                ctx = ctx, key = SettingsRowKeys.HIDDEN_CATEGORIES,
            )
        }
    }
    item(key = SettingsRowKeys.ADD_SOURCE) {
        OmniActionRow(stringResource(R.string.settings_add_source), stringResource(R.string.settings_add), onClick = onAddSource, ctx = ctx, key = SettingsRowKeys.ADD_SOURCE)
    }
}

/**
 * Task 84m: Settings › Sources › Hidden categories. Lists every category this profile switched off
 * (hold-OK › Hide category, or a hidden library) with a "Show" button each, plus "Show all".
 */
@Composable
private fun HiddenCategoriesDialog(
    rows: List<HiddenCategoryUi>,
    onShow: (String) -> Unit,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val firstFr = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.background(Color(0xCC03060C))) {
                Column(
                    Modifier
                        .width(640.dp)
                        .padding(OmniSpacing.xl)
                        .clip(RoundedCornerShape(24.dp))
                        .glassSurface(),
                ) {
                    Text(stringResource(R.string.settings_hidden_categories), style = t.title, color = c.textPrimary)
                    Spacer(Modifier.height(OmniSpacing.s))
                    Text(stringResource(R.string.settings_hidden_categories_note), style = t.body, color = c.textSecondary)
                    Spacer(Modifier.height(OmniSpacing.l))
                    LazyColumn(Modifier.height(360.dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                        items(rows, key = { it.key }) { row ->
                            HiddenCategoryRow(row, onShow, if (row == rows.firstOrNull()) firstFr else null)
                        }
                    }
                    Spacer(Modifier.height(OmniSpacing.l))
                    Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                        OmniButton(stringResource(R.string.settings_show_all), onShowAll, primary = true)
                        OmniButton(stringResource(R.string.settings_close), onDismiss)
                    }
                }
            }
        }
    }
    LaunchedEffect(rows.firstOrNull()?.key) { if (rows.isNotEmpty()) runCatching { firstFr.requestFocusWhenReady() } }
}

@Composable
private fun HiddenCategoryRow(row: HiddenCategoryUi, onShow: (String) -> Unit, firstFr: FocusRequester?) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.elevated)
            .padding(horizontal = OmniSpacing.m, vertical = OmniSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, style = t.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${row.sourceName} · ${row.kindLabel}", style = t.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OmniButton(stringResource(R.string.settings_show), { onShow(row.key) }, modifier = if (firstFr != null) Modifier.focusRequester(firstFr) else Modifier)
    }
}

private data class PickerSpec(
    val title: String,
    val options: List<PickerOption>,
    val selected: String?,
    val onPick: (String) -> Unit,
)

private fun LazyListScope.playbackRows(
    state: SettingsState,
    viewModel: SettingsViewModel,
    rows: List<String>,
    ctx: RowCtx,
    openStyleDialog: () -> Unit = {},
    openPicker: (PickerSpec) -> Unit,
) {
    item(key = SettingsRowKeys.AUDIO) {
        val audio by viewModel.audioLanguage.collectAsStateWithLifecycle(initialValue = null)
        val current = audio ?: ""
        val options = listOf(
            PickerOption("", stringResource(R.string.settings_audio_default)),
            PickerOption("en", stringResource(R.string.settings_audio_en)),
            PickerOption("es", stringResource(R.string.settings_audio_es)),
            PickerOption("fr", stringResource(R.string.settings_audio_fr)),
            PickerOption("de", stringResource(R.string.settings_audio_de)),
            PickerOption("ja", stringResource(R.string.settings_audio_ja)),
        )
        val title = stringResource(R.string.settings_audio_title)
        OmniPickerRow(
            title,
            value = options.firstOrNull { it.value == current }?.label ?: options.first().label,
            onClick = { openPicker(PickerSpec(title, options, current) { viewModel.setPlaybackPref("pref_audio_language", it) }) },
            ctx = ctx, key = SettingsRowKeys.AUDIO,
        )
    }
    item(key = SettingsRowKeys.SUBTITLES) {
        val subs by viewModel.subtitles.collectAsStateWithLifecycle(initialValue = null)
        val current = subs ?: "forced"
        val options = listOf(
            PickerOption("forced", stringResource(R.string.settings_subs_forced)),
            PickerOption("always", stringResource(R.string.settings_subs_always)),
            PickerOption("off", stringResource(R.string.settings_subs_off)),
        )
        val title = stringResource(R.string.settings_subs_title)
        OmniPickerRow(
            title,
            value = options.firstOrNull { it.value == current }?.label ?: options.first().label,
            secondary = stringResource(R.string.settings_tracks_note),
            onClick = { openPicker(PickerSpec(title, options, current) { viewModel.setPlaybackPref("pref_subtitles", it) }) },
            ctx = ctx, key = SettingsRowKeys.SUBTITLES,
        )
    }
    item(key = SettingsRowKeys.SUBTITLE_STYLE) {
        val raw by viewModel.subtitleStyle.collectAsStateWithLifecycle(initialValue = null)
        OmniActionRow(
            stringResource(R.string.settings_subtitle_style_title),
            stringResource(R.string.settings_open),
            secondary = raw?.let { subtitleStyleSummary(it.size, it.background, it.color, it.position) } ?: "Default",
            onClick = openStyleDialog,
            ctx = ctx, key = SettingsRowKeys.SUBTITLE_STYLE,
        )
    }
    item(key = SettingsRowKeys.LOCAL_SKIP) {
        OmniSwitchRow(
            stringResource(R.string.local_skip_analysis_label),
            checked = state.localSkipAnalysis, onCheckedChange = viewModel::setLocalSkipAnalysis,
            disclosure = stringResource(R.string.local_skip_analysis_disclosure),
            ctx = ctx, key = SettingsRowKeys.LOCAL_SKIP,
        )
    }
    item(key = SettingsRowKeys.COMMUNITY_SKIP) {
        OmniSwitchRow(
            stringResource(R.string.community_skip_label),
            checked = state.communitySkipLookup, onCheckedChange = viewModel::setCommunitySkipLookup,
            disclosure = stringResource(R.string.community_skip_disclosure),
            ctx = ctx, key = SettingsRowKeys.COMMUNITY_SKIP,
        )
    }
    item(key = SettingsRowKeys.TVMAZE_CREDIT) {
        val uriHandler = LocalUriHandler.current
        OmniActionRow(
            stringResource(R.string.community_skip_tvmaze_credit), stringResource(R.string.settings_open),
            onClick = { runCatching { uriHandler.openUri("https://www.tvmaze.com/") } },
            ctx = ctx, key = SettingsRowKeys.TVMAZE_CREDIT,
        )
    }
    item(key = SettingsRowKeys.INTRO) {
        val options = skipModeOptions()
        val title = stringResource(R.string.skip_intro_label)
        OmniPickerRow(
            title,
            value = skipModeLabel(state.introSkipMode),
            onClick = { openPicker(PickerSpec(title, options, state.introSkipMode) { viewModel.setSkipMode(SkipSettings.INTRO_MODE, it) }) },
            ctx = ctx, key = SettingsRowKeys.INTRO,
        )
    }
    item(key = SettingsRowKeys.CREDITS) {
        val options = skipModeOptions()
        val title = stringResource(R.string.skip_credits_label)
        OmniPickerRow(
            title,
            value = skipModeLabel(state.creditsSkipMode),
            secondary = stringResource(R.string.skip_mode_note),
            onClick = { openPicker(PickerSpec(title, options, state.creditsSkipMode) { viewModel.setSkipMode(SkipSettings.CREDITS_MODE, it) }) },
            ctx = ctx, key = SettingsRowKeys.CREDITS,
        )
    }
    if (SettingsRowKeys.FRAME_RATE in rows) {
        item(key = SettingsRowKeys.FRAME_RATE) {
            val title = stringResource(R.string.match_frame_rate_label)
            val options = frameRateModeOptions()
            OmniPickerRow(
                title,
                value = options.firstOrNull { it.value == state.matchFrameRate }?.label ?: options[1].label,
                secondary = stringResource(R.string.match_frame_rate_disclosure),
                onClick = { openPicker(PickerSpec(title, options, state.matchFrameRate) { viewModel.setMatchFrameRate(it) }) },
                ctx = ctx, key = SettingsRowKeys.FRAME_RATE,
            )
        }
    }
    if (SettingsRowKeys.PRELOAD_NEXT_CHANNEL in rows) {
        item(key = SettingsRowKeys.PRELOAD_NEXT_CHANNEL) {
            OmniSwitchRow(
                stringResource(R.string.preload_next_channel_label),
                checked = state.preloadNextChannel, onCheckedChange = viewModel::setPreloadNextChannel,
                disclosure = stringResource(R.string.preload_next_channel_disclosure),
                ctx = ctx, key = SettingsRowKeys.PRELOAD_NEXT_CHANNEL,
            )
        }
    }
    if (SettingsRowKeys.SPOILER_FREE in rows) {
        item(key = SettingsRowKeys.SPOILER_FREE) {
            OmniSwitchRow(
                stringResource(R.string.spoiler_free_label),
                checked = state.spoilerFree, onCheckedChange = viewModel::setSpoilerFree,
                disclosure = stringResource(R.string.spoiler_free_disclosure),
                ctx = ctx, key = SettingsRowKeys.SPOILER_FREE,
            )
        }
    }
    // Task 95: the sub-option only makes sense (and only appears) while spoiler protection is on.
    if (state.spoilerFree && SettingsRowKeys.SPOILER_FREE_HIDE_TITLES in rows) {
        item(key = SettingsRowKeys.SPOILER_FREE_HIDE_TITLES) {
            OmniSwitchRow(
                stringResource(R.string.spoiler_free_titles_label),
                checked = state.spoilerHideTitles, onCheckedChange = viewModel::setSpoilerHideTitles,
                ctx = ctx, key = SettingsRowKeys.SPOILER_FREE_HIDE_TITLES,
            )
        }
    }
    // Task 107: where Back lands after opening a channel or title from Search (per-profile).
    item(key = SettingsRowKeys.BACK_FROM_SEARCH) {
        val options = listOf(
            PickerOption(com.yodesla.omniverse.core.data.UserDataRepository.BACK_FROM_SEARCH_GUIDE_OR_DETAILS, stringResource(R.string.settings_back_from_search_guide)),
            PickerOption(com.yodesla.omniverse.core.data.UserDataRepository.BACK_FROM_SEARCH_SEARCH, stringResource(R.string.settings_back_from_search_search)),
        )
        val title = stringResource(R.string.settings_back_from_search_label)
        OmniPickerRow(
            title,
            value = options.firstOrNull { it.value == state.backFromSearch }?.label ?: options.first().label,
            secondary = stringResource(R.string.settings_back_from_search_note),
            onClick = { openPicker(PickerSpec(title, options, state.backFromSearch) { viewModel.setBackFromSearch(it) }) },
            ctx = ctx, key = SettingsRowKeys.BACK_FROM_SEARCH,
        )
    }
}

@Composable
private fun frameRateModeOptions(): List<PickerOption> = listOf(
    PickerOption(FRAME_RATE_MATCH_OFF, stringResource(R.string.match_frame_rate_mode_off)),
    PickerOption(FRAME_RATE_MATCH_SEAMLESS, stringResource(R.string.match_frame_rate_mode_seamless)),
    PickerOption(FRAME_RATE_MATCH_ALWAYS, stringResource(R.string.match_frame_rate_mode_always)),
)

@Composable
private fun skipModeOptions(): List<PickerOption> = listOf(
    PickerOption(SkipSettings.OFF, stringResource(R.string.skip_mode_off)),
    PickerOption(SkipSettings.BUTTON, stringResource(R.string.skip_mode_button)),
    PickerOption(SkipSettings.AUTO, stringResource(R.string.skip_mode_auto)),
)

@Composable
private fun skipModeLabel(mode: String): String = when (mode) {
    SkipSettings.OFF -> stringResource(R.string.skip_mode_off)
    SkipSettings.AUTO -> stringResource(R.string.skip_mode_auto)
    else -> stringResource(R.string.skip_mode_button)
}

private fun LazyListScope.displayRows(
    viewModel: SettingsViewModel,
    rows: List<String>,
    ctx: RowCtx,
    openPicker: (PickerSpec) -> Unit,
) {
    item(key = SettingsRowKeys.EXPERIENCE) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val options = listOf(
            PickerOption("simple", stringResource(R.string.settings_mode_simple)),
            PickerOption("full", stringResource(R.string.settings_mode_full)),
        )
        val title = stringResource(R.string.settings_experience_title)
        OmniPickerRow(
            title,
            value = if (state.mode == "full") stringResource(R.string.settings_mode_full) else stringResource(R.string.settings_mode_simple),
            onClick = { openPicker(PickerSpec(title, options, state.mode) { viewModel.setMode(it) }) },
            ctx = ctx, key = SettingsRowKeys.EXPERIENCE,
        )
    }
    // Task 103: scores stay hidden on Sports cards until the viewer reveals them with OK.
    if (SettingsRowKeys.HIDE_SPORTS_SCORES in rows) {
        item(key = SettingsRowKeys.HIDE_SPORTS_SCORES) {
            val state by viewModel.state.collectAsStateWithLifecycle()
            OmniSwitchRow(
                stringResource(R.string.settings_hide_sports_scores_label),
                checked = state.hideSportsScores, onCheckedChange = viewModel::setHideSportsScores,
                disclosure = stringResource(R.string.settings_hide_sports_scores_disclosure),
                ctx = ctx, key = SettingsRowKeys.HIDE_SPORTS_SCORES,
            )
        }
    }
    // Task 84: per-profile text size (text only; layout keeps its proportions).
    item(key = SettingsRowKeys.TEXT_SIZE) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val options = listOf(
            PickerOption(com.yodesla.omniverse.designsystem.TEXT_SIZE_NORMAL, stringResource(R.string.text_size_normal)),
            PickerOption(com.yodesla.omniverse.designsystem.TEXT_SIZE_LARGE, stringResource(R.string.text_size_large)),
            PickerOption(com.yodesla.omniverse.designsystem.TEXT_SIZE_XLARGE, stringResource(R.string.text_size_xlarge)),
        )
        val title = stringResource(R.string.text_size_label)
        OmniPickerRow(
            title,
            value = options.firstOrNull { it.value == state.textSize }?.label ?: options.first().label,
            onClick = { openPicker(PickerSpec(title, options, state.textSize) { viewModel.setTextSize(it) }) },
            secondary = stringResource(R.string.text_size_note),
            ctx = ctx, key = SettingsRowKeys.TEXT_SIZE,
        )
    }
    // The Display slot (Customize Home) is rendered by the pane caller after these rows.
    if (SettingsRowKeys.SCREENSAVER in rows) {
        item(key = SettingsRowKeys.SCREENSAVER) {
            OmniTextRow(
                stringResource(R.string.screensaver_section),
                secondary = stringResource(R.string.screensaver_info),
                disclosure = stringResource(R.string.screensaver_info),
                ctx = ctx, key = SettingsRowKeys.SCREENSAVER,
            )
        }
    }
}

private fun LazyListScope.profileRows(
    state: SettingsState,
    guestMode: Boolean,
    onOpenProfiles: () -> Unit,
    onSetPickerAtStart: (Boolean) -> Unit,
    onCreateProfile: () -> Unit,
    ctx: RowCtx,
    slot: (@Composable () -> Unit)?,
) {
    if (guestMode) {
        // Task 84b: no profiles to manage - offer to take the Guest over instead.
        item(key = SettingsRowKeys.GUEST_NOTE) {
            OmniActionRow(
                stringResource(R.string.profiles_guest_settings_note),
                stringResource(R.string.profiles_create),
                onClick = onCreateProfile,
                ctx = ctx, key = SettingsRowKeys.GUEST_NOTE,
            )
        }
    } else {
        item(key = SettingsRowKeys.MANAGE) {
            OmniActionRow(stringResource(R.string.profiles_manage), stringResource(R.string.settings_open), onClick = onOpenProfiles, ctx = ctx, key = SettingsRowKeys.MANAGE)
        }
        item(key = SettingsRowKeys.PICKER) {
            OmniSwitchRow(
                stringResource(R.string.profiles_picker_label),
                checked = state.pickerAtStart, onCheckedChange = onSetPickerAtStart,
                disclosure = stringResource(R.string.profiles_picker_note),
                ctx = ctx, key = SettingsRowKeys.PICKER,
            )
        }
    }
    // Parental controls (PIN pad, category locks) arrive from the app slot.
    if (slot != null) item(key = SettingsRowKeys.PARENTAL) { SlotFocusBox(ctx.registry, SettingsRowKeys.PARENTAL) { slot() } }
}

private fun LazyListScope.metadataRows(state: SettingsState, viewModel: SettingsViewModel, ctx: RowCtx) {
    item(key = SettingsRowKeys.WIKIDATA) {
        OmniSwitchRow(
            stringResource(R.string.metadata_lookup_label),
            checked = state.metadataLookup, onCheckedChange = viewModel::setMetadataLookup,
            disclosure = stringResource(R.string.metadata_lookup_disclosure),
            ctx = ctx, key = SettingsRowKeys.WIKIDATA,
        )
    }
    item(key = SettingsRowKeys.RT) {
        OmniSwitchRow(
            stringResource(R.string.rt_scores_label),
            checked = state.rtScores, onCheckedChange = viewModel::setRtScores,
            disclosure = stringResource(R.string.rt_scores_disclosure),
            ctx = ctx, key = SettingsRowKeys.RT,
        )
    }
}

private fun LazyListScope.aboutRows(version: String, disclaimer: String, rows: List<String>, ctx: RowCtx) {
    item(key = SettingsRowKeys.VERSION) {
        OmniTextRow(stringResource(R.string.settings_version_title), secondary = version, ctx = ctx, key = SettingsRowKeys.VERSION)
    }
    // Check for updates (and diagnostics, if the app ever ships them) arrive from the slot.
    if (SettingsRowKeys.LEGAL in rows) {
        item(key = SettingsRowKeys.LEGAL) {
            OmniTextRow(
                stringResource(R.string.settings_legal_title),
                secondary = stringResource(R.string.tmdb_attribution),
                disclosure = disclaimer,
                ctx = ctx, key = SettingsRowKeys.LEGAL,
            )
        }
    }
}

// ---------------------------------------------------------------- info dialog

/** The full disclosure text a truncated row hides: glass panel, scrollable, OK/Back closes. */
@Composable
private fun OmniInfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 640.dp).padding(OmniSpacing.tvSide).glassSurface(corner = 16.dp).padding(OmniSpacing.l),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.m),
            ) {
                Text(title, style = t.headline, color = c.textPrimary)
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(body, style = OmniTheme.type.body, color = c.textSecondary)
                }
                OmniButton(stringResource(R.string.settings_close), onDismiss, Modifier.focusRequester(fr), primary = true)
            }
        }
        LaunchedEffect(Unit) { fr.requestFocusWhenReady() }
    }
}

// ------------------------------------------------- category-language dialog

/**
 * Task 84h: multi-select of the language groups this profile's categories actually carry.
 * Rows are "Arabic · 213 categories"; toggling is local until Save, so Back/Cancel never
 * half-applies a filter. Empty selection on save = "All" (filter off).
 */
@Composable
private fun CategoryLanguageDialog(
    groups: List<CategoryGroupCount>,
    initial: Set<CategoryLanguage>,
    onSave: (Set<CategoryLanguage>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    var sel by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 640.dp).padding(OmniSpacing.tvSide).glassSurface(corner = 16.dp).padding(OmniSpacing.l),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
            ) {
                Text(stringResource(R.string.settings_cat_lang_choose_title), style = t.headline, color = c.textPrimary)
                Text(stringResource(R.string.settings_cat_lang_choose_note), style = t.caption, color = c.textTertiary)
                if (groups.isEmpty()) {
                    Text(stringResource(R.string.settings_cat_lang_choose_empty), style = t.body, color = c.textSecondary)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs)) {
                        items(groups, key = { it.language.name }) { g ->
                            LangToggleRow(
                                label = g.language.label,
                                secondary = stringResource(R.string.settings_cat_lang_categories_count, g.count),
                                checked = g.language in sel,
                                focusFirst = g == groups.first(),
                                onToggle = {
                                    sel = if (g.language in sel) sel - g.language else sel + g.language
                                },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = OmniSpacing.m), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m, Alignment.End)) {
                    OmniButton(stringResource(R.string.settings_cat_lang_cancel), onDismiss)
                    OmniButton(stringResource(R.string.settings_cat_lang_save), { onSave(sel) }, Modifier.focusRequester(fr), primary = true)
                }
            }
        }
        LaunchedEffect(Unit) { if (groups.isEmpty()) fr.requestFocusWhenReady() }
    }
}

/**
 * Task 99 / 100: a focus boundary around slot-provided content (Parental, Customize Home, updates,
 * the phone-remote and backup panes). The slot's own rows register nothing in [RowFocusRegistry], so
 * a Settings search jump needs a focusable target here — but the wrapper must not itself be a focus
 * stop, or D-pad lands on it and can never step into the rows it wraps (a node's contained children
 * are not "below" it, so Down from the wrapper finds nothing and focus is stuck on a box with no
 * click). A [Column] lays a multi-composable slot out vertically: the About & updates slot is an
 * OmniActionRow then DiagnosticsSection, and the old Box stacked them on top of each other (the
 * update row overlapped by diagnostics text). [focusGroup] keeps it a traversal boundary that search
 * focus can target while D-pad flows straight into the slot's first focusable child — the same
 * pattern the browse content area uses in the app shell (focusRequester + focusGroup, not focusable).
 */
@Composable
private fun SlotFocusBox(registry: RowFocusRegistry, key: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .focusRequester(registry.requester(key))
            .focusGroup(),
    ) { content() }
}

/** One toggle row of [CategoryLanguageDialog], styled like the designsystem picker rows. */
@Composable
private fun LangToggleRow(label: String, secondary: String, checked: Boolean, focusFirst: Boolean, onToggle: () -> Unit) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusFirst) fr.requestFocusWhenReady() }
    Box(
        Modifier.fillMaxWidth().height(56.dp)
            .focusRequester(fr)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused || checked) Modifier.glassSurface(corner = 26.dp, focused = focused, shadow = false) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = OmniSpacing.l), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (checked) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = c.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(OmniSpacing.s))
            Column(Modifier.weight(1f)) {
                Text(label, style = t.body, color = if (checked) c.textPrimary else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(secondary, style = t.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
