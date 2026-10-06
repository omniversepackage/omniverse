package com.yodesla.omniverse.feature.live
import com.yodesla.omniverse.designsystem.OmniButton

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.designsystem.CategoryAtmosphere
import com.yodesla.omniverse.designsystem.CategoryDestinationAccent
import com.yodesla.omniverse.designsystem.categoryAccent
import com.yodesla.omniverse.designsystem.CategoryDestinationHeader
import com.yodesla.omniverse.designsystem.CategorySelectionGlow
import com.yodesla.omniverse.designsystem.CategoryIdentity
import com.yodesla.omniverse.designsystem.ChannelRow
import com.yodesla.omniverse.designsystem.ChannelRowUi
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.LocalCompact
import com.yodesla.omniverse.designsystem.LogoImage
import com.yodesla.omniverse.designsystem.OmniMotion
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.SkeletonChannelRow
import com.yodesla.omniverse.designsystem.rememberTimeText
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import com.yodesla.omniverse.feature.live.multiview.MultiviewGroup
import com.yodesla.omniverse.feature.live.multiview.MultiviewLayout
import com.yodesla.omniverse.feature.live.multiview.MultiviewPicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun LiveRoute(
    viewModel: LiveViewModel,
    onPlay: (ContentKey, RemoteId) -> Unit,
    onAddSource: () -> Unit,
    onSearch: (() -> Unit)? = null,
    /** Non-null on TV: the header card that opens the 2-4 channel Multiview picker. */
    onMultiview: ((List<ContentKey>) -> Unit)? = null,
    /** App-wide Multiview session (Task 67): drives the header "Multiview · N" button and the menu. */
    session: com.yodesla.omniverse.feature.live.multiview.MultiviewSession? = null,
    /** Opens Multiview with the current session (the header button). */
    onOpenMultiview: () -> Unit = {},
    playingKey: ContentKey? = null,
    /** Right from the channel list opens the full guide grid. */
    onOpenGuide: () -> Unit = {},
    /** Task 84g: OK on a category opens the full Guide filtered to that category. */
    onOpenGuideForCategory: (String) -> Unit = { onOpenGuide() },
    onOpenNavigation: () -> Unit = {},
    /** Task 84h: OK on the header language-filter chip opens Settings › Sources. */
    onOpenLanguageFilter: () -> Unit = {},
    /** Still-playing live channel (Back from fullscreen), shown at the top of the preview pane. */
    miniPlayer: (@Composable (Modifier) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e ->
            when (e) {
                is LiveEvent.PlayChannel -> onPlay(e.key, e.categoryId)
                is LiveEvent.OpenGuide -> onOpenGuideForCategory(e.categoryId)
            }
        }
    }
    if (state.noSources) {
        NoSources(onAddSource)
        return
    }
    if (LocalCompact.current) {
        LiveScreenCompact(state, viewModel, playingKey)
        return
    }
    LiveScreen(state, viewModel, playingKey, onOpenGuide, onOpenNavigation, miniPlayer, onSearch, onMultiview, session, onOpenMultiview, onOpenLanguageFilter)
}

/**
 * Task 107: rail item index of the selected category. The rail's LazyColumn puts its header
 * chips (search, Multiview) before the category entries; -1 when the selection is not in the list.
 */
internal fun liveRailFocusIndex(hasSearch: Boolean, hasMultiview: Boolean, categoryIds: List<String>, selectedId: String?): Int {
    val i = categoryIds.indexOf(selectedId)
    return if (i >= 0) (if (hasSearch) 1 else 0) + (if (hasMultiview) 1 else 0) + i else -1
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun LiveScreen(state: LiveUiState, vm: LiveViewModel, playingKey: ContentKey?, onOpenGuide: () -> Unit, onOpenNavigation: () -> Unit, miniPlayer: (@Composable (Modifier) -> Unit)? = null, onSearch: (() -> Unit)? = null, onMultiview: ((List<ContentKey>) -> Unit)? = null, session: com.yodesla.omniverse.feature.live.multiview.MultiviewSession? = null, onOpenMultiview: () -> Unit = {}, onOpenLanguageFilter: () -> Unit = {}) {
    val c = OmniTheme.colors
    val context = LocalContext.current
    val channels = vm.channels.collectAsLazyPagingItems()
    val categoryFocus = remember { FocusRequester() }
    val firstChannel = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Task 107: the rail's own scroll state (hoisted here so it survives the brand cross-fade):
    // the selected chip must stay composed, or Left from the channel list has nothing to land on.
    val railListState = rememberLazyListState()
    var focused by remember { mutableStateOf<ChannelRow?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var groupNotice by remember { mutableStateOf<String?>(null) }
    var menuRow by remember { mutableStateOf<ChannelRow?>(null) }
    // Task 84g: hold-OK pick-up-and-move, same interaction as VodBrowse: Up/Down move, OK or Back drops.
    var movingCat by remember { mutableStateOf<String?>(null) }
    // The release of the hold that picked the category up must not drop it again.
    var dropArmed by remember { mutableStateOf(false) }
    val groups by vm.groups.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val pickerScope = rememberCoroutineScope()

    // Task 107: keep the selected category in view (and composed) so Left from the channel list
    // always lands on it. Only scrolls when it is off-screen, so dwell-select never jumps the rail.
    LaunchedEffect(state.selectedCategoryId, state.categories) {
        val target = liveRailFocusIndex(onSearch != null, onMultiview != null, state.categories.map { it.id }, state.selectedCategoryId)
        if (target >= 0 && railListState.layoutInfo.visibleItemsInfo.none { it.index == target }) railListState.requestScrollToItem(target)
    }

    // A sparse list (a couple of favourites, or an empty category) cedes width to the preview pane.
    val sparseList = channels.itemCount in 1..2 || (channels.itemCount == 0 && !state.loading)
    val listWeight by animateFloatAsState(
        targetValue = if (sparseList) 0.38f else 0.55f,
        animationSpec = OmniMotion.focusSpring(),
        label = "liveListWeight",
    )

    val selectedName = state.categories.firstOrNull { it.id == state.selectedCategoryId }?.name.orEmpty()
    com.yodesla.omniverse.designsystem.BrandStage(selectedName, Modifier.fillMaxSize()) {
    @Suppress("NAME_SHADOWING") val c = OmniTheme.colors
    Column(Modifier.fillMaxSize()) {
        // Compact masthead keeps the channel surface in view on 720p televisions.
        Row(
            Modifier.fillMaxWidth().padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, top = OmniSpacing.s, bottom = OmniSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Live TV", style = OmniTheme.type.headline, color = c.textPrimary)
            Spacer(Modifier.width(OmniSpacing.l))
            if (categoryAccent(selectedName) != null) {
                CategoryDestinationHeader(selectedName)
            } else {
                Text(selectedName, style = OmniTheme.type.body, color = c.textTertiary, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            // Task 84h: the active category-language filter; OK opens Settings > Sources.
            state.langFilter?.let { lf ->
                OmniButton(context.getString(R.string.live_lang_filter_chip, lf.label, lf.hiddenLive), onClick = onOpenLanguageFilter)
                Spacer(Modifier.width(OmniSpacing.l))
            }
            if (session?.openable == true) {
                OmniButton(context.getString(R.string.multiview_open, session.size), onClick = onOpenMultiview)
                Spacer(Modifier.width(OmniSpacing.l))
            }
            Text(rememberTimeText(), style = OmniTheme.type.numeric, color = c.textSecondary)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val previewMinWidth = 420.dp
            val effectiveListWeight = if (maxWidth > 0.dp) {
                val previewFraction = (previewMinWidth / maxWidth).coerceIn(0.30f, 0.65f)
                (1f - previewFraction).coerceIn(0.35f, 0.70f)
            } else listWeight
            Row(Modifier.fillMaxSize()) {
            // Categories: focusing one selects it after a short dwell, so D-pad scrolling stays cheap.
            FocusPivot(parentFraction = 0.3f, leading = 0.dp) {
                LazyColumn(
                    state = railListState,
                    modifier = Modifier
                        .width(248.dp)
                        .fillMaxHeight()
                        .focusRestorer(categoryFocus)
                        .focusProperties { right = firstChannel }
                        .onPreviewKeyEvent { e ->
                            val grabbed = movingCat
                            if (grabbed != null) {
                                // Holding a category: Up/Down move it, OK or Back drops it where it is.
                                when (e.key) {
                                    Key.DirectionUp -> { if (e.type == KeyEventType.KeyDown) vm.moveCategory(grabbed, -1); true }
                                    Key.DirectionDown -> { if (e.type == KeyEventType.KeyDown) vm.moveCategory(grabbed, 1); true }
                                    Key.DirectionCenter, Key.Enter -> {
                                        if (e.type == KeyEventType.KeyUp) {
                                            if (dropArmed) { movingCat = null; vm.finishMove() } else dropArmed = true
                                        }
                                        true
                                    }
                                    Key.Back -> { if (e.type == KeyEventType.KeyUp) { movingCat = null; vm.finishMove() }; true }
                                    else -> true
                                }
                            } else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onOpenNavigation(); true } else false
                        },
                    contentPadding = PaddingValues(start = OmniSpacing.tvSide, end = OmniSpacing.m, bottom = OmniSpacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                ) {
                    if (onSearch != null) item(key = "__search") {
                        com.yodesla.omniverse.designsystem.SearchEntryCard(onSearch, Modifier.fillMaxWidth().height(44.dp))
                    }
                    if (onMultiview != null) item(key = "__multiview") {
                        FocusCard(
                            onClick = { groupNotice = null; showPicker = true },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Box(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m), contentAlignment = Alignment.CenterStart) {
                                Column {
                                    Text("Multiview", style = OmniTheme.type.body, color = c.textPrimary, maxLines = 1)
                                    Text("2\u20134 channels at once", style = OmniTheme.type.caption, color = c.textTertiary, maxLines = 1)
                                }
                            }
                        }
                    }
                    items(state.categories, key = { it.id }) { cat ->
                        val selected = cat.id == state.selectedCategoryId
                        val grabbed = movingCat != null && movingCat == cat.id
                        var focusedAt by remember { mutableLongStateOf(0L) }
                        FocusCard(
                            // Task 84g: OK opens the full Guide filtered to this category.
                            onClick = { vm.openGuideForCategory(cat.id) },
                            // Task 84m: hold OK opens the category menu (Move / Hide category / Cancel); pinned entries have none.
                            onLongClick = if (!cat.special) ({ vm.openCategoryMenu(cat.id) }) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .then(if (selected) Modifier.focusRequester(categoryFocus) else Modifier)
                                .then(if (grabbed) Modifier.border(2.dp, c.accent, RoundedCornerShape(12.dp)) else Modifier)
                                .onFocusChanged { focusedAt = if (it.isFocused) System.nanoTime() else 0L },
                        ) {
                            DwellSelect(key = cat.id, focusedAt = { focusedAt }, onDwell = { vm.selectCategory(cat.id) })
                            if (selected) CategorySelectionGlow(cat.name, Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m), contentAlignment = Alignment.CenterStart) {
                                if (cat.special) {
                                    Text(
                                        cat.name, style = if (selected) OmniTheme.type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else OmniTheme.type.body,
                                        color = if (selected) c.accent else c.textPrimary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                } else {
                                    CategoryIdentity(cat.name, selected)
                                }
                            }
                            if (grabbed) Text("↕", style = OmniTheme.type.title, color = c.accent,
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = OmniSpacing.m))
                        }
                    }
                    if (state.categories.any { !it.special }) item(key = "__categories_hint") {
                        Text(context.getString(R.string.live_categories_hint), style = OmniTheme.type.caption, color = c.textTertiary, maxLines = 1,
                            modifier = Modifier.padding(start = OmniSpacing.m))
                    }
                }
            }
            // Channels.
            Box(Modifier.weight(effectiveListWeight).fillMaxHeight()) {
                FocusPivot(parentFraction = 0.3f, leading = 0.dp) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRestorer(firstChannel)
                            .focusProperties { left = categoryFocus }
                            .onPreviewKeyEvent { e ->
                                // Nothing focusable to the right of the list: Right opens the guide.
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onOpenGuide(); true } else false
                            },
                        contentPadding = PaddingValues(end = OmniSpacing.m, bottom = OmniSpacing.xxl),
                        verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                    ) {
                        if (channels.itemCount == 0 && state.loading) {
                            items(8) { SkeletonChannelRow() }
                        }
                        items(channels.itemCount, key = channels.itemKey { "${it.key.sourceId.value}/${it.key.remoteId.value}" }) { index ->
                            val row = channels[index] ?: return@items
                            val nn = row.epgKey?.let { state.nowNext[it] }
                            val fav = row.key in state.favorites
                            val ui = remember(row, nn, fav, playingKey) { row.toUi(nn, fav, row.key == playingKey) }
                            ChannelRow(
                                ui = ui,
                                onClick = { vm.onChannelClick(row) },
                                onLongClick = { menuRow = row },
                                modifier = (if (index == 0) Modifier.focusRequester(firstChannel) else Modifier)
                                    .onFocusChanged { if (it.isFocused) focused = row },
                                showNext = false,
                            )
                        }
                    }
                }
                if (channels.itemCount == 0 && !state.loading && channels.loadState.refresh !is androidx.paging.LoadState.Loading) {
                    Text(
                        (if (state.selectedCategoryId == LiveViewModel.FAVORITES) "No favourites yet. Hold OK on any channel to add it here." else "No channels in this category"), style = OmniTheme.type.body, color = c.textTertiary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            // Preview of the focused channel: see what's on before switching.
            LivePreviewPane(
                row = focused,
                nn = focused?.epgKey?.let { state.nowNext[it] },
                modifier = Modifier.weight(1f - effectiveListWeight).fillMaxHeight().widthIn(min = previewMinWidth).padding(start = OmniSpacing.s, end = OmniSpacing.tvSide, bottom = OmniSpacing.m),
                video = miniPlayer,
            )
            }
        }
    }

    // Multiview picker: a full-screen overlay above the list; Back (handled inside) cancels it.
    if (showPicker) {
        MultiviewPicker(
            channels = channels,
            groups = groups,
            notice = groupNotice,
            onDismiss = { showPicker = false },
            onConfirm = { keys -> showPicker = false; onMultiview?.invoke(keys) },
            onOpenGroup = { keys ->
                // Skip channels that are gone or now denied; open only if at least two survive.
                pickerScope.launch {
                    val openable = vm.openableGroupChannels(keys)
                    if (openable.size >= MultiviewLayout.MIN_TILES) {
                        showPicker = false
                        onMultiview?.invoke(openable)
                    } else {
                        groupNotice = context.getString(R.string.multiview_group_too_few)
                    }
                }
            },
            onSaveGroup = { name, keys -> pickerScope.launch { vm.groups.save(name, keys) } },
            onRenameGroup = { index, name -> pickerScope.launch { vm.groups.rename(index, name) } },
            onDeleteGroup = { index -> pickerScope.launch { vm.groups.delete(index) } },
        )
    }

    // Hold-OK on a channel: Watch / Favorites / Multiview / Remind me (no longer a silent favourite toggle).
    val menu = menuRow
    if (menu != null) {
        val nn = menu.epgKey?.let { state.nowNext[it] }
        val actions = channelMenuActions(
            ChannelMenuState(
                favorite = menu.key in state.favorites,
                inMultiview = session?.contains(menu.key) == true,
                canWatch = true,
                canRemind = (nn?.nextStartMs ?: 0L) > System.currentTimeMillis(),
                reminded = false,
            ),
            onWatch = { vm.onChannelClick(menu) },
            onToggleFavorite = { vm.toggleFavorite(menu) },
            onToggleMultiview = { session?.toggle(menu.key) },
            onRemind = { vm.remindNext(menu) },
        )
        com.yodesla.omniverse.designsystem.TitleMenu(
            title = menu.name,
            subtitle = listOfNotNull(menu.number?.toString(), nn?.nowTitle).joinToString("  ·  "),
            posterUrl = menu.logoUrl,
            actions = actions,
            onDismiss = { menuRow = null },
        )
    }

    // Task 84m: hold-OK on a provider category — Move / Hide category / Cancel, anchored next to the rail.
    val menuCat = state.menuCategoryId?.let { id -> state.categories.firstOrNull { it.id == id } }
    if (menuCat != null) {
        com.yodesla.omniverse.designsystem.CategoryMenu(
            title = menuCat.name,
            actions = listOf(
                com.yodesla.omniverse.designsystem.MenuAction("Move", onClick = {
                    vm.closeCategoryMenu()
                    movingCat = menuCat.id
                    dropArmed = false
                    pickerScope.launch { runCatching { categoryFocus.requestFocusWhenReady() } }
                }),
                com.yodesla.omniverse.designsystem.MenuAction("Hide category", onClick = { vm.hideCategory(menuCat.id) }, destructive = true),
                com.yodesla.omniverse.designsystem.MenuAction("Cancel", onClick = {
                    vm.closeCategoryMenu()
                    pickerScope.launch { runCatching { categoryFocus.requestFocusWhenReady() } }
                }),
            ),
            onDismiss = { vm.closeCategoryMenu() },
            modifier = Modifier.padding(start = 268.dp),
        )
    }
    // Task 84m: "Category hidden · Undo" toast; focus stays on the next category (requested below).
    state.hiddenNotice?.let { n ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            com.yodesla.omniverse.designsystem.UndoNotice(
                message = "Category hidden",
                actionLabel = "Undo",
                onAction = { vm.undoHideCategory() },
                onDismiss = { vm.clearHiddenNotice() },
                modifier = Modifier.padding(start = OmniSpacing.tvSide, bottom = OmniSpacing.xl),
            )
        }
    }

    }

    // Task 84m: after a hide the focused card is gone; land on the category that took its place.
    LaunchedEffect(state.hiddenNotice?.categoryId) {
        if (state.hiddenNotice != null) runCatching { categoryFocus.requestFocusWhenReady() }
    }

    // Never leave the previous category's programme in the preview while a new page loads.
    LaunchedEffect(state.selectedCategoryId) { focused = null }

    // Land on the first channel once loaded (no "dead" first key press).
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(channels.itemCount > 0) {
        if (channels.itemCount > 0 && !focusedOnce) focusedOnce = runCatching { firstChannel.requestFocus() }.isSuccess
    }

    // Ask for now/next of rows entering the viewport (the VM debounces).
    LaunchedEffect(channels, listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .map { idx -> idx.mapNotNull { i -> if (i < channels.itemCount) channels.peek(i)?.epgKey else null } }
            .distinctUntilChanged()
            .collectLatest { vm.requestNowNext(it) }
    }
}

/** Phone layout: "Live" title, a horizontal rail of category chips, full-width channel list. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun LiveScreenCompact(state: LiveUiState, vm: LiveViewModel, playingKey: ContentKey?) {
    val c = OmniTheme.colors
    val channels = vm.channels.collectAsLazyPagingItems()
    val firstChannel = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val selectedName = state.categories.firstOrNull { it.id == state.selectedCategoryId }?.name.orEmpty()
    com.yodesla.omniverse.designsystem.BrandStage(selectedName, Modifier.fillMaxSize()) {
    @Suppress("NAME_SHADOWING") val c = OmniTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            Text("Live", style = OmniTheme.type.title, color = c.textPrimary)
            CategoryDestinationAccent(
                selectedName,
                Modifier.padding(start = OmniSpacing.s).width(3.dp).height(14.dp),
            )
            Spacer(Modifier.weight(1f))
        }
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.s),
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s),
        ) {
            items(state.categories, key = { it.id }) { cat ->
                val selected = cat.id == state.selectedCategoryId
                FocusCard(
                    onClick = { vm.selectCategory(cat.id) },
                    modifier = Modifier.height(40.dp),
                ) {
                    Box(Modifier.fillMaxHeight().padding(horizontal = OmniSpacing.m), contentAlignment = Alignment.CenterStart) {
                        if (cat.special) {
                            Text(
                                cat.name,
                                style = if (selected) OmniTheme.type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else OmniTheme.type.body,
                                color = if (selected) c.accent else c.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            CategoryIdentity(cat.name, selected)
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().focusRestorer(firstChannel),
                contentPadding = PaddingValues(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, bottom = OmniSpacing.xxl),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
            ) {
                if (channels.itemCount == 0 && state.loading) {
                    items(8) { SkeletonChannelRow() }
                }
                items(channels.itemCount, key = channels.itemKey { "${it.key.sourceId.value}/${it.key.remoteId.value}" }) { index ->
                    val row = channels[index] ?: return@items
                    val nn = row.epgKey?.let { state.nowNext[it] }
                    val fav = row.key in state.favorites
                    val ui = remember(row, nn, fav, playingKey) { row.toUi(nn, fav, row.key == playingKey) }
                    CompactChannelRow(
                        ui = ui,
                        onClick = { vm.onChannelClick(row) },
                        modifier = if (index == 0) Modifier.focusRequester(firstChannel) else Modifier,
                    )
                }
            }
            if (channels.itemCount == 0 && !state.loading && channels.loadState.refresh !is androidx.paging.LoadState.Loading) {
                Text(
                    (if (state.selectedCategoryId == LiveViewModel.FAVORITES) "No favourites yet. Hold OK on any channel to add it here." else "No channels in this category"), style = OmniTheme.type.body, color = c.textTertiary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
    }

    // Land on the first channel once loaded (no "dead" first key press).
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(channels.itemCount > 0) {
        if (channels.itemCount > 0 && !focusedOnce) focusedOnce = runCatching { firstChannel.requestFocus() }.isSuccess
    }

    // Ask for now/next of rows entering the viewport (the VM debounces).
    LaunchedEffect(channels, listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .map { idx -> idx.mapNotNull { i -> if (i < channels.itemCount) channels.peek(i)?.epgKey else null } }
            .distinctUntilChanged()
            .collectLatest { vm.requestNowNext(it) }
    }
}

/** Phone channel row: number, logo, name, current programme (one line each). */
@Composable
private fun CompactChannelRow(ui: ChannelRowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = OmniTheme.colors
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth().height(64.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ui.number?.toString() ?: "", style = OmniTheme.type.numeric, color = c.textTertiary,
                textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 28.dp), maxLines = 1,
            )
            Spacer(Modifier.width(OmniSpacing.s))
            LogoImage(ui.logoUrl, ui.name, Modifier.size(48.dp, 28.dp))
            Spacer(Modifier.width(OmniSpacing.m))
            Column(Modifier.weight(1f)) {
                Text(ui.name, style = OmniTheme.type.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ui.nowTitle?.let { now ->
                    Text(now, style = OmniTheme.type.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Selects a category once focus has rested on it for 300 ms. */
@Composable
private fun DwellSelect(key: String, focusedAt: () -> Long, onDwell: () -> Unit) {
    LaunchedEffect(key, focusedAt()) {
        val start = focusedAt()
        if (start == 0L) return@LaunchedEffect
        delay(300)
        if (focusedAt() == start) onDwell()
    }
}

private fun ChannelRow.toUi(nn: NowNextUi?, favorite: Boolean, playing: Boolean) = ChannelRowUi(
    id = key.remoteId.value,
    number = number,
    name = name,
    logoUrl = logoUrl,
    nowTitle = nn?.nowTitle,
    nowProgress = nn?.nowProgress,
    nextTitle = nn?.nextTitle,
    nextStart = nn?.nextStart,
    isFavorite = favorite,
    hasCatchup = catchupDays > 0,
    isPlaying = playing,
)

@Composable
private fun NoSources(onAddSource: () -> Unit) {
    val c = OmniTheme.colors
    com.yodesla.omniverse.designsystem.CosmicBackdrop(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No TV source yet", style = OmniTheme.type.headline, color = c.textPrimary)
            Spacer(Modifier.height(OmniSpacing.s))
            Text("Add your provider login to start watching.", style = OmniTheme.type.body, color = c.textSecondary)
            Spacer(Modifier.height(OmniSpacing.l))
            OmniButton("Add a source", onAddSource, primary = true)
        }
    }
    }
}
