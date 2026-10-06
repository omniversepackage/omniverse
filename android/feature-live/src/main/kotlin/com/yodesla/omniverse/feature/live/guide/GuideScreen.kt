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
import androidx.compose.ui.input.key.type
import com.yodesla.omniverse.designsystem.LogoImage
import com.yodesla.omniverse.designsystem.glassSurface
import com.yodesla.omniverse.designsystem.channelTint
import com.yodesla.omniverse.designsystem.touchClick
import kotlinx.coroutines.launch

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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    // The cell the grid currently focuses, so hold-OK can open a menu for it.
    var focusedCell by remember { mutableStateOf<Pair<Int, GridProgramme?>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    CosmicBackdrop(Modifier.fillMaxSize()) {
    val categoryName = state.categories.firstOrNull { it.id == state.selectedCategoryId }?.name
    CategoryAtmosphere(categoryName.orEmpty(), Modifier.fillMaxSize())
    Column(Modifier.fillMaxSize()) {
        // Header: focused programme on the left, the still-playing channel (mini player) on the right.
        // No category chips: the Guide follows the category picked in the channel list's left
        // column (Kory: they duplicated that menu and cost two rows).
        Row(
            Modifier.fillMaxWidth().padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, top = OmniSpacing.l),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TV Guide", style = OmniTheme.type.title, color = c.textPrimary)
                    if (categoryName != null) {
                        Spacer(Modifier.width(OmniSpacing.m))
                        if (categoryAccent(categoryName) != null) {
                            CategoryDestinationHeader(categoryName, scale = 0.85f)
                        } else {
                            Text(categoryName, style = OmniTheme.type.caption, color = c.accent, maxLines = 1)
                        }
                    }
                    // Task 85: the day strip lives in the title row — a row of its own would come
                    // straight off the grid. Only the days this source actually has data for.
                    if (state.dayStarts.size > 1) {
                        GuideDayStrip(
                            dayStarts = state.dayStarts,
                            selectedDay = dayIndexOf(state.viewMs, state.dayStarts),
                            todayLabel = ctx.getString(com.yodesla.omniverse.feature.live.R.string.guide_day_today),
                            tomorrowLabel = ctx.getString(com.yodesla.omniverse.feature.live.R.string.guide_day_tomorrow),
                            onSelectDay = { index -> viewModel.jumpToDay(index); focusGrid() },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(OmniSpacing.m))
                        OmniButton(
                            ctx.getString(com.yodesla.omniverse.feature.live.R.string.guide_now),
                            onClick = { viewModel.jumpToNow(); focusGrid() },
                            primary = !state.atNow,
                        )
                        Spacer(Modifier.width(OmniSpacing.m))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (session?.openable == true && multiviewEnabled) {
                        OmniButton(
                            androidx.compose.ui.platform.LocalContext.current.getString(
                                com.yodesla.omniverse.feature.live.R.string.multiview_open, session.size,
                            ),
                            onClick = onOpenMultiview,
                        )
                        Spacer(Modifier.width(OmniSpacing.m))
                    }
                    Text(rememberTimeText(), style = OmniTheme.type.numeric, color = c.textSecondary)
                }
                Text(
                    state.focusedTitle ?: "", style = OmniTheme.type.headline, color = c.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.toast ?: listOfNotNull(if (state.focusedFavorite) "★" else null, state.focusedChannel, state.focusedTimes).joinToString("  ·  "),
                    style = OmniTheme.type.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.focusedArchive) { GuideBadge("Catch-up · OK to play", c.accent); Spacer(Modifier.width(OmniSpacing.m)) }
                    Text(if (state.focusedFuture) "OK  Remind me    Hold OK  Menu    Back  Categories" else "OK  Watch    Hold OK  Menu    Back  Categories", style = OmniTheme.type.body, color = c.textSecondary, maxLines = 1)
                    if (state.dayStarts.size > 1) {
                        Spacer(Modifier.width(OmniSpacing.l))
                        Text(
                            ctx.getString(com.yodesla.omniverse.feature.live.R.string.guide_time_hint),
                            style = OmniTheme.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Task 98 chips live in the header (Kory): saves a full row for channels.
                Row(
                    Modifier.padding(top = OmniSpacing.s),
                    horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                ) {
                    GuideFilter.entries.forEachIndexed { i, f ->
                        GuideChip(
                            f.label, state.filter == f,
                            focusRequester = if (i == 0) chipFocus else null,
                        ) {
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
            if (miniPlayer != null) {
                Spacer(Modifier.width(OmniSpacing.l))
                // Bigger preview, nudged toward the right edge (Kory).
                miniPlayer(Modifier.width(460.dp).offset(x = OmniSpacing.tvSide - OmniSpacing.m))
            }
        }
        Spacer(Modifier.height(OmniSpacing.m))
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
                            else Modifier.fillMaxSize()).padding(start = OmniSpacing.tvSide - OmniSpacing.m),
                        focusRequester = gridFocus,
                        initialRow = remember(grid, focusKey) { focusKey?.let(viewModel::rowOf)?.takeIf { it >= 0 } ?: 0 },
                        onSelect = { row, p -> viewModel.onSelect(row, p) },
                        onFocusedChange = { row, p -> focusedCell = row to p; viewModel.onFocused(row, p) },
                        onLongSelect = { row -> focusedCell = row to focusedCell?.second; menuOpen = true },
                        isMarked = viewModel::isReminded,
                        markVersion = state.reminderVersion,
                        onExitLeft = onExitLeft,
                        onJump = { fromMs, deltaMs -> viewModel.jumpBy(deltaMs, fromMs) },
                        onNow = viewModel::jumpToNow,
                        onViewTime = viewModel::onViewTime,
                        jump = state.jump,
                    )
                    if (showFeature) GuideFeaturePane(state, Modifier.fillMaxWidth().height(featureHeight).padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, bottom = OmniSpacing.m))
                }
            }
            LaunchedEffect(grid) { gridFocus.requestFocusWhenReady() }
        } else if (!state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (state.selectedCategoryId == GuideViewModel.FAVORITES) "No favourites yet. Hold OK on any channel to add it here." else "No channels in this category", style = OmniTheme.type.body, color = c.textTertiary)
            }
            // Task 111 M6: the grid is absent, so land focus on the chip row (reachable by D-pad).
            LaunchedEffect(grid) { chipFocus.requestFocusWhenReady() }
        }
    }

        // Hold-OK on a grid cell: Watch / Favorites / Multiview / Remind me (no longer a silent favourite toggle).
        if (menuOpen) {
            val (row, programme) = focusedCell ?: (0 to null)
            val key = viewModel.channelKeyAt(row)
            if (key != null) {
                val actions = channelMenuActions(
                    ChannelMenuState(
                        favorite = state.focusedFavorite,
                        inMultiview = session?.contains(key) == true,
                        canWatch = programme != null && (state.focusedLive || programme.hasArchive),
                        canRemind = programme != null && programme.startMs > now,
                        reminded = programme != null && viewModel.isReminded(row, programme.startMs),
                    ),
                    onWatch = { menuOpen = false; viewModel.onSelect(row, programme) },
                    onToggleFavorite = { viewModel.toggleFavorite(row) },
                    onToggleMultiview = { if (multiviewEnabled) session?.toggle(key) },
                    onRemind = { menuOpen = false; viewModel.onSelect(row, programme) },
                )
                com.yodesla.omniverse.designsystem.TitleMenu(
                    title = state.focusedTitle ?: key.remoteId.value,
                    subtitle = state.focusedDetail,
                    posterUrl = state.focusedLogo,
                    actions = actions,
                    onDismiss = { menuOpen = false },
                )
            }
        }
    }
}

/** Sparse categories should feel intentional rather than leaving a black half-screen. */
@Composable
private fun GuideFeaturePane(state: GuideUiState, modifier: Modifier = Modifier) {
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
    dayStarts: List<Long>,
    selectedDay: Int,
    todayLabel: String,
    tomorrowLabel: String,
    onSelectDay: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstDay = dayStarts.first()
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
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
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(
                when {
                    isFocused -> c.textPrimary
                    selected -> c.accent.copy(alpha = 0.24f)
                    else -> c.surface.copy(alpha = 0.72f)
                },
            )
            .border(1.dp, if (selected) c.accent else c.textTertiary.copy(alpha = 0.35f), RoundedCornerShape(19.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .touchClick(onClick)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onKeyEvent false
                if (e.key == Key.DirectionCenter || e.key == Key.Enter) { onClick(); true } else false
            }
            .padding(horizontal = 16.dp),
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
