package com.yodesla.omniverse.feature.live.guide
import com.yodesla.omniverse.designsystem.requestFocusWhenReady

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.guide.GuideFilter
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.feature.live.ChannelMenuState
import com.yodesla.omniverse.feature.live.channelMenuActions
import com.yodesla.omniverse.feature.live.LiveProviderButton
import com.yodesla.omniverse.designsystem.CategoryAtmosphere
import com.yodesla.omniverse.designsystem.CategoryDestinationHeader
import com.yodesla.omniverse.designsystem.categoryAccent
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.CosmicBackdrop
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.epg.EpgGrid
import com.yodesla.omniverse.designsystem.epg.GridProgramme
import com.yodesla.omniverse.designsystem.rememberTimeText
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.yodesla.omniverse.designsystem.LogoImage
import com.yodesla.omniverse.designsystem.glassSurface
import com.yodesla.omniverse.designsystem.channelTint
import com.yodesla.omniverse.designsystem.touchClick
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun GuideRoute(
    viewModel: GuideViewModel,
    onPlay: (ContentKey, RemoteId) -> Unit,
    onExitLeft: () -> Unit = {},
    onPlayCatchup: (GuideEvent.PlayCatchup) -> Unit = {},
    /** The still-playing live channel, drawn top-right (see MiniLivePlayer). */
    miniPlayer: (@Composable (Modifier) -> Unit)? = null,
    /** Channel to land on (the one just watched); first row when absent or not in this category. */
    focusKey: ContentKey? = null,
    /** App-wide Multiview session (Task 67): drives the header button and the hold-OK menu. */
    session: com.yodesla.omniverse.feature.live.multiview.MultiviewSession? = null,
    /** Opens Multiview with the current session (the header button). */
    onOpenMultiview: () -> Unit = {},
    /** Task 111 H2: false while a Kids limit/bedtime block is active — the Multiview entry is hidden. */
    multiviewEnabled: Boolean = true,
    /** Settings "Horizontal categories": the Live TV category row, shown under the title row. */
    /** Settings "Horizontal categories": the Live TV category row above the header. It gets the
     *  focus requester of its selected tab and a callback to call once a category is picked. */
    categoryBar: (@Composable (focus: FocusRequester, onPicked: () -> Unit) -> Unit)? = null,
) {
    // Performance: the route only follows layout-level state. The focused programme (title, times,
    // badges) changes on every D-pad press and is read only by GuideInfoLine / the feature pane /
    // the menu, so a key press no longer recomposes the header, chips and grid call site.
    val state by remember(viewModel) {
        viewModel.state.map { it.layoutOnly() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(viewModel.state.value.layoutOnly())
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e ->
            when (e) {
                is GuideEvent.PlayChannel -> onPlay(e.key, e.categoryId)
                is GuideEvent.PlayCatchup -> onPlayCatchup(e)
            }
        }
    }
    val c = OmniTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val gridFocus = remember { FocusRequester() }
    // Task 111 M6: when a filter yields no channels the grid is not rendered, so focus lands on the
    // chip row instead of the absent grid (otherwise Down does nothing and the empty message is
    // unreachable by D-pad).
    val chipFocus = remember { FocusRequester() }
    val uiScope = rememberCoroutineScope()
    // A jump from the header must leave the viewer on the channel they were on, so focus goes back
    // to the grid instead of staying on the button that was pressed.
    fun focusGrid() = uiScope.launch { gridFocus.requestFocusWhenReady() }
    fun focusChips() = uiScope.launch { chipFocus.requestFocusWhenReady() }
    // Guide time is fixed per visit; the grid's "now" line only needs minute precision.
    val now = remember(state.grid) { System.currentTimeMillis() }
    // The cell the grid currently focuses, so hold-OK can open a menu for it. Plain holder, not
    // state: it changes on every key press and nothing draws from it.
    val focusedCell = remember { arrayOfNulls<Pair<Int, GridProgramme?>>(1) }
    var menuOpen by remember { mutableStateOf(false) }
    // Hoisted: the empty-Favorites fallback below must happen once per visit, not every time.
    var fellBack by remember { mutableStateOf(false) }
    // Top category bar (only with categoryBar): visible on arrival, hidden once the viewer moves on.
    var barVisible by remember { mutableStateOf(true) }
    val barFocus = remember { FocusRequester() }
    fun hideBar() { if (barVisible) { barVisible = false; focusGrid() } }
    fun showBar() { barVisible = true; uiScope.launch { kotlinx.coroutines.delay(220); runCatching { barFocus.requestFocus() } } }
    // Back brings the bar back first; with the bar showing, Back leaves the Guide as before.
    androidx.activity.compose.BackHandler(enabled = categoryBar != null && !barVisible && !menuOpen) { showBar() }

    CosmicBackdrop(Modifier.fillMaxSize()) {
    val categoryName = state.categories.firstOrNull { it.id == state.selectedCategoryId }?.name
    // (CategoryAtmosphere dropped here: another full-screen blended layer under the grid.)
    Column(Modifier.fillMaxSize()) {
        // Compact header (Kory: the old one used half the screen): three short rows, then the grid.
        Row(
            Modifier.fillMaxWidth().padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, top = OmniSpacing.s),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Category bar (top-bar setting): above everything, slides away once a category is
                // picked or the viewer moves on; Back or Up from the top row brings it back.
                if (categoryBar != null) androidx.compose.animation.AnimatedVisibility(
                    barVisible,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
                ) {
                    Box(Modifier.onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { hideBar(); true } else false
                    }) { categoryBar(barFocus, ::hideBar) }
                }
                // Row 1: title, category, filters, Multiview, clock.
                Row(
                    Modifier.onPreviewKeyEvent { e ->
                        if (categoryBar != null && !barVisible && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { showBar(); true } else false
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("TV Guide", style = OmniTheme.type.title, color = c.textPrimary)
                    if (categoryName != null) {
                        Spacer(Modifier.width(OmniSpacing.m))
                        if (categoryAccent(categoryName) != null) {
                            CategoryDestinationHeader(categoryName, scale = 0.8f)
                        } else {
                            Text(categoryName, style = OmniTheme.type.caption, color = c.accent, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.width(OmniSpacing.m))
                    // Task 98 filter chips, now on the title row.
                    LazyRow(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = OmniSpacing.xs, horizontal = OmniSpacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                    ) {
                        item(key = "providers") { LiveProviderButton(viewModel.providerFilter) }
                        GuideFilter.entries.forEachIndexed { i, f ->
                            item(key = f.name) {
                                GuideChip(f.label, state.filter == f, focusRequester = if (i == 0) chipFocus else null) {
                                    viewModel.selectFilter(f)
                                    when (guideFocusTarget(state.grid?.channelCount ?: 0, state.loading)) {
                                        GuideFocusTarget.Grid -> focusGrid()
                                        GuideFocusTarget.Chips -> focusChips()
                                        GuideFocusTarget.None -> {}
                                    }
                                }
                            }
                        }
                    }
                    if (session?.openable == true && multiviewEnabled) {
                        Spacer(Modifier.width(OmniSpacing.s))
                        GuideChip(ctx.getString(com.yodesla.omniverse.feature.live.R.string.multiview_open, session.size), false, onClick = onOpenMultiview)
                    }
                    Spacer(Modifier.width(OmniSpacing.m))
                    Text(rememberTimeText(), style = OmniTheme.type.numeric, color = c.textSecondary)
                }
                // (The Now / Today / weekday strip was removed: Kory found it unnecessary. Scroll right,
                // or Fast-forward / Rewind ±2 h, to move through time; Play/Pause or Guide returns to now.)
                // Row 3: the focused programme in one line, with the key hints at the end.
                GuideInfoLine(viewModel, showTimeHint = state.dayStarts.size > 1)
            }
            if (miniPlayer != null) {
                Spacer(Modifier.width(OmniSpacing.l))
                miniPlayer(Modifier.width(240.dp).offset(x = OmniSpacing.tvSide - OmniSpacing.m))
            }
        }
        Spacer(Modifier.height(OmniSpacing.s))
        val grid = state.grid
        if (grid != null && grid.channelCount > 0) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val fullGridHeight = 48.dp + 52.dp * grid.channelCount
                // Show the feature whenever all rows fit AND there is room for useful detail.
                // A fixed four-row threshold left five-row categories half empty on the Shield.
                val showFeature = grid.channelCount <= 8 && maxHeight - fullGridHeight >= 160.dp
                val featureHeight = (maxHeight - fullGridHeight - OmniSpacing.m).coerceAtMost(192.dp)
                Column(Modifier.fillMaxSize()) {
                    EpgGrid(
                        data = grid,
                        nowMs = now,
                        modifier = (if (showFeature) Modifier.fillMaxWidth().height(fullGridHeight)
                            else Modifier.fillMaxSize()).padding(start = OmniSpacing.tvSide - OmniSpacing.m)
                            // Moving around the grid puts the top category bar away.
                            .onPreviewKeyEvent { e -> if (categoryBar != null && barVisible && e.type == KeyEventType.KeyDown) barVisible = false; false },
                        focusRequester = gridFocus,
                        initialRow = remember(grid, focusKey) { focusKey?.let(viewModel::rowOf)?.takeIf { it >= 0 } ?: 0 },
                        onSelect = viewModel::onSelect,
                        onFocusedChange = remember(viewModel) { { row: Int, p: GridProgramme? -> focusedCell[0] = row to p; viewModel.onFocused(row, p) } },
                        onLongSelect = remember(viewModel) { { row: Int -> focusedCell[0] = row to focusedCell[0]?.second; menuOpen = true } },
                        isMarked = viewModel::isReminded,
                        markVersion = state.reminderVersion,
                        onExitLeft = onExitLeft,
                        onJump = remember(viewModel) { { fromMs: Long, deltaMs: Long -> viewModel.jumpBy(deltaMs, fromMs) } },
                        onNow = viewModel::jumpToNow,
                        onViewTime = viewModel::onViewTime,
                        jump = state.jump,
                    )
                    if (showFeature) GuideFeaturePane(viewModel, Modifier.fillMaxWidth().height(featureHeight).padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, bottom = OmniSpacing.m))
                }
            }
            LaunchedEffect(grid) { gridFocus.requestFocusWhenReady() }
        } else if (!state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (state.selectedCategoryId == GuideViewModel.FAVORITES) "No favourites yet. Hold OK on any channel to add it here." else "No channels in this category", style = OmniTheme.type.body, color = c.textTertiary)
            }
            // Task 111 M6: the grid is absent, so land focus on the chip row (reachable by D-pad).
            LaunchedEffect(grid) { chipFocus.requestFocusWhenReady() }
            // An empty Favorites category (with no filter applied) is a dead end on arrival: show
            // all channels instead. Once per visit, so choosing Favorites later still shows the hint.
            LaunchedEffect(state.selectedCategoryId, state.filter) {
                if (!fellBack && state.selectedCategoryId == GuideViewModel.FAVORITES && state.filter == GuideFilter.ALL) {
                    fellBack = true
                    viewModel.selectCategory(GuideViewModel.ALL)
                }
            }
        }
    }

        // Hold-OK on a grid cell: Watch / Favorites / Multiview / Remind me (no longer a silent favourite toggle).
        if (menuOpen) GuideCellMenu(viewModel, focusedCell[0] ?: (0 to null), now, session, multiviewEnabled, onDismiss = { menuOpen = false })
    }
}

/** Layout-level view of the state: everything that changes per key press is reset. */
private fun GuideUiState.layoutOnly() = copy(
    focusedTitle = null, focusedDetail = null, focusedChannel = null, focusedChannelName = null,
    focusedLogo = null, focusedTimes = null, focusedArchive = false, focusedLive = false,
    focusedFuture = false, focusedFavorite = false, toast = null, viewMs = 0L, atNow = true,
)

/** One line: ★ title · channel · times [catch-up]  …  OK Watch · Hold OK Menu · ⏪⏩ ±2 h */
@Composable
private fun GuideInfoLine(viewModel: GuideViewModel, showTimeHint: Boolean) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = OmniTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (s.focusedFavorite) "★ " else "") + (s.focusedTitle ?: ""),
            style = OmniTheme.type.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val detail = s.toast ?: listOfNotNull(s.focusedChannel, s.focusedTimes).joinToString("  ·  ")
        if (detail.isNotEmpty()) {
            Spacer(Modifier.width(OmniSpacing.m))
            Text(detail, style = OmniTheme.type.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        if (s.focusedLive) { Spacer(Modifier.width(OmniSpacing.s)); GuideBadge("LIVE", c.accent) }
        if (s.focusedArchive) { Spacer(Modifier.width(OmniSpacing.s)); GuideBadge("Catch-up", c.accent) }
        Spacer(Modifier.weight(0.01f))
        Spacer(Modifier.width(OmniSpacing.l))
        Text(
            (if (s.focusedFuture) "OK Remind" else "OK Watch") + "  ·  Hold OK Menu" + (if (showTimeHint) "  ·  " + ctx.getString(com.yodesla.omniverse.feature.live.R.string.guide_time_hint) else ""),
            style = OmniTheme.type.caption, color = c.textTertiary, maxLines = 1,
        )
    }
}

@Composable
private fun GuideCellMenu(
    viewModel: GuideViewModel,
    cell: Pair<Int, GridProgramme?>,
    now: Long,
    session: com.yodesla.omniverse.feature.live.multiview.MultiviewSession?,
    multiviewEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val (row, programme) = cell
    val key = viewModel.channelKeyAt(row) ?: return
    val sourceChoices by androidx.compose.runtime.produceState<List<Pair<String, com.yodesla.omniverse.core.data.ChannelRow>>>(emptyList(), key) {
        value = viewModel.sourceChoices(row)
    }
    val actions = channelMenuActions(
        ChannelMenuState(
            favorite = state.focusedFavorite,
            inMultiview = session?.contains(key) == true,
            canWatch = programme != null && (state.focusedLive || programme.hasArchive),
            canRemind = programme != null && programme.startMs > now,
            reminded = programme != null && viewModel.isReminded(row, programme.startMs),
        ),
        onWatch = { onDismiss(); viewModel.onSelect(row, programme) },
        onToggleFavorite = { viewModel.toggleFavorite(row) },
        onToggleMultiview = { if (multiviewEnabled) session?.toggle(key) },
        onRemind = { onDismiss(); viewModel.onSelect(row, programme) },
    )
    com.yodesla.omniverse.designsystem.TitleMenu(
        title = state.focusedTitle ?: key.remoteId.value,
        subtitle = state.focusedDetail,
        posterUrl = state.focusedLogo,
        actions = actions + sourceChoices.map { (sourceName, channel) ->
            com.yodesla.omniverse.designsystem.MenuAction("Watch live via $sourceName · ${channel.name}", { onDismiss(); viewModel.playSource(channel) })
        },
        onDismiss = onDismiss,
    )
}

/** Sparse categories should feel intentional rather than leaving a black half-screen. */
@Composable
private fun GuideFeaturePane(viewModel: GuideViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val tint = channelTint(state.focusedChannelName ?: "Live TV")
    Row(
        modifier
            .glassSurface(corner = 16.dp, tint = tint)
            .padding(OmniSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(0.35f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            LogoImage(state.focusedLogo, state.focusedChannelName ?: "Live TV", Modifier.size(160.dp, 88.dp), plain = true)
        }
        Spacer(Modifier.width(OmniSpacing.l))
        Column(Modifier.weight(0.65f), verticalArrangement = Arrangement.Center) {
            Text(
                if (state.focusedLive) "ON NOW" else if (state.focusedArchive) "CATCH-UP" else "TV GUIDE",
                style = t.overline, color = c.accent,
            )
            Spacer(Modifier.height(OmniSpacing.s))
            Text(state.focusedTitle ?: "Choose a programme", style = t.headline, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(OmniSpacing.s))
            Text(state.focusedDetail ?: "Explore this channel's schedule", style = t.body, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GuideBadge(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = OmniTheme.type.caption, color = color, maxLines = 1)
    }
}

/** Task 85: Today / Tomorrow / weekday chips — one press jumps to prime time on the focused row. */
@Composable
private fun GuideDayStrip(
    viewModel: GuideViewModel,
    dayStarts: List<Long>,
    todayLabel: String,
    tomorrowLabel: String,
    nowLabel: String,
    onSelectDay: (Int) -> Unit,
    onNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only the selected day / at-now flags are followed here, not every time step.
    val selectedDay by remember(viewModel, dayStarts) {
        viewModel.state.map { dayIndexOf(it.viewMs, dayStarts) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(dayIndexOf(viewModel.state.value.viewMs, dayStarts))
    val atNow by remember(viewModel) { viewModel.state.map { it.atNow }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(viewModel.state.value.atNow)
    val firstDay = dayStarts.first()
    LazyRow(modifier = modifier, contentPadding = PaddingValues(horizontal = OmniSpacing.xs, vertical = OmniSpacing.xs), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
        // "Now" first: the most common jump, reachable with one Up from the grid's left side.
        item(key = "__now") { GuideChip(nowLabel, atNow, onClick = onNow) }
        itemsIndexed(dayStarts) { index, startMs ->
            GuideChip(dayLabel(startMs, firstDay, todayLabel, tomorrowLabel), index == selectedDay) { onSelectDay(index) }
        }
    }
}

/** Compact focusable chip: the header's day buttons (OmniButton is a 52 dp action, too tall here). */
@Composable
private fun GuideChip(text: String, selected: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val c = OmniTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(
                when {
                    isFocused -> c.textPrimary
                    selected -> c.accent.copy(alpha = 0.24f)
                    else -> c.surface.copy(alpha = 0.72f)
                },
            )
            .border(
                if (isFocused) 2.dp else 1.dp,
                when {
                    isFocused -> c.focusBorder
                    selected -> c.accent
                    else -> c.textTertiary.copy(alpha = 0.35f)
                },
                RoundedCornerShape(17.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .touchClick(onClick)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onKeyEvent false
                if (e.key == Key.DirectionCenter || e.key == Key.Enter) { onClick(); true } else false
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = OmniTheme.type.caption, maxLines = 1,
            color = when {
                isFocused -> c.background
                selected -> c.accent
                else -> c.textSecondary
            },
        )
    }
}

/** Task 111 M6: where focus must land after a filter/category change. An empty (non-loading) grid
 *  is not rendered, so focus goes to the chip row, not the absent grid. */
internal enum class GuideFocusTarget { Grid, Chips, None }

internal fun guideFocusTarget(channelCount: Int, loading: Boolean): GuideFocusTarget = when {
    loading -> GuideFocusTarget.None
    channelCount > 0 -> GuideFocusTarget.Grid
    else -> GuideFocusTarget.Chips
}
