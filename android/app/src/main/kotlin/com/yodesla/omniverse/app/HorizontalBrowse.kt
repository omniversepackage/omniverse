package com.yodesla.omniverse.app
import com.yodesla.omniverse.designsystem.blendDestinationTop
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.Glow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.designsystem.*
import com.yodesla.omniverse.feature.home.HomeCard
import com.yodesla.omniverse.feature.home.HomeRoute
import com.yodesla.omniverse.feature.home.HomeViewModel
import com.yodesla.omniverse.feature.vod.VodBrowseRoute
import com.yodesla.omniverse.feature.vod.VodBrowseViewModel
import kotlinx.coroutines.launch

/** The app shell joins Home and VOD features without introducing a feature-to-feature dependency. */
@Composable
internal fun HorizontalBrowseRoute(
    browse: VodBrowseViewModel,
    home: HomeViewModel,
    title: String,
    onOpenNavigation: () -> Unit,
    onSearch: (() -> Unit)?,
    onOpenHomeCard: (HomeCard) -> Unit,
    onPlay: (ContentKey, Boolean) -> Unit,
    onOpen: (ContentKey) -> Unit,
    entry: Int = 0,
    leftMenuEnabled: Boolean = true,
    warnBeforeMenu: Boolean = true,
    onDisableWarning: () -> Unit = {},
) {
    val state by browse.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val homeFocus = remember { FocusRequester() }
    val categoryFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(id: String?) = categoryFocus.getOrPut(id ?: "__all") { FocusRequester() }
    val selectedFocus = if (state.horizontalHome) homeFocus else requester(state.selected)
    var moving by remember { mutableStateOf<String?>(null) }
    var dropArmed by remember { mutableStateOf(false) }
    var barVisible by rememberSaveable(entry) { mutableStateOf(true) }
    var leftPresses by remember { mutableIntStateOf(0) }
    var confirmMenu by remember { mutableStateOf(false) }
    var skipWarning by remember { mutableStateOf(false) }
    fun requestMainMenu() {
        if (!leftMenuEnabled) return
        if (++leftPresses < 2) return
        leftPresses = 0
        if (warnBeforeMenu) confirmMenu = true else onOpenNavigation()
    }
    val homeLabel = stringResource(if (state.kind == ContentKind.VOD) R.string.movies_home else R.string.shows_home)
    LaunchedEffect(state.horizontalHome) {
        if (state.horizontalHome) list.scrollToItem(0)
    }

    BackHandler(enabled = moving == null && state.menuCategoryId == null && !state.libraryOpen) {
        if (!barVisible) {
            barVisible = true
            scope.launch { kotlinx.coroutines.delay(200); selectedFocus.requestFocusWhenReady() }
        } else if (state.horizontalHome) onOpenNavigation() else {
            browse.showHorizontalHome()
            scope.launch { list.animateScrollToItem(0); homeFocus.requestFocusWhenReady() }
        }
    }

    CosmicBackdrop(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().onKeyEvent { event ->
        if (!barVisible && !state.libraryOpen && state.menuCategoryId == null &&
            event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
            // Traverse the content first; only restore the chooser at its upper edge.
            if (!focusManager.moveFocus(FocusDirection.Up)) {
                barVisible = true
                scope.launch { kotlinx.coroutines.delay(200); selectedFocus.requestFocusWhenReady() }
            }
            true
        } else false
    }.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key != Key.DirectionLeft) leftPresses = 0
        if (event.key != Key.DirectionLeft || event.type != KeyEventType.KeyDown) false
        else if (event.nativeKeyEvent.repeatCount > 0) true
        else if (!barVisible) {
            if (focusManager.moveFocus(FocusDirection.Left)) leftPresses = 0 else requestMainMenu()
            true
        } else false
    }) {
        AnimatedVisibility(barVisible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        LazyRow(
            state = list,
            contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.s),
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                val grabbed = moving ?: return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) browse.moveCategory(grabbed, if (event.key == Key.DirectionLeft) -1 else 1)
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (event.type == KeyEventType.KeyUp) {
                            if (dropArmed) { moving = null; browse.finishMove() } else dropArmed = true
                        }
                        true
                    }
                    Key.Back -> {
                        if (event.type == KeyEventType.KeyUp) { moving = null; browse.finishMove() }
                        true
                    }
                    else -> true
                }
            },
        ) {
            item(key = "__home") {
                CategoryTab(
                    onClick = { browse.showHorizontalHome() },
                    modifier = Modifier.widthIn(min = 64.dp, max = 184.dp).width(IntrinsicSize.Max).height(32.dp).focusRequester(homeFocus).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) { requestMainMenu(); true } else false
                    },
                ) {
                    if (state.horizontalHome) CategorySelectionGlow(homeLabel, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m), contentAlignment = Alignment.CenterStart) {
                        androidx.tv.material3.Text(homeLabel, style = OmniTheme.type.body, color = if (state.horizontalHome) OmniTheme.colors.accent else OmniTheme.colors.textPrimary)
                    }
                }
            }
            items(state.categories, key = { it.id ?: "__all" }) { category ->
                val selected = !state.horizontalHome && category.id == state.selected
                CategoryTab(
                    onClick = { barVisible = false; browse.select(category.id) },
                    onLongClick = category.id?.let { id -> { browse.openCategoryMenu(id) } },
                    modifier = Modifier.widthIn(min = 64.dp, max = 184.dp).width(IntrinsicSize.Max).height(32.dp).focusRequester(requester(category.id)),
                ) {
                    if (selected || (moving != null && moving == category.id)) CategorySelectionGlow(category.name, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m), contentAlignment = Alignment.CenterStart) {
                        CategoryIdentity(category.name, selected)
                    }
                }
            }
        }
        }
        // Let Compose's directional focus search traverse the content rows before the bar.
        // A parent Up handler runs before default focus traversal, even when attached as onKeyEvent.
        Box(Modifier.weight(1f).fillMaxWidth().blendDestinationTop(barVisible)) {
            if (state.horizontalHome) {
                key(entry) {
                    HomeRoute(home, onOpenHomeCard, onOpenNavigation = ::requestMainMenu, onPlay = onPlay, preserveBrowsePosition = true)
                }
            } else {
                VodBrowseRoute(browse, title, ::requestMainMenu, onSearch, onPlay, showCategoryRail = false, onOpen = onOpen)
            }
        }
    }
    }
    if (confirmMenu) Dialog(onDismissRequest = { confirmMenu = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val stayFocus = remember { FocusRequester() }
        Column(Modifier.widthIn(max = 500.dp).glassSurface().padding(OmniSpacing.xl), verticalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
            androidx.tv.material3.Text("Open the main menu?", style = OmniTheme.type.title, color = OmniTheme.colors.textPrimary)
            androidx.tv.material3.Text("This prevents accidental exits when Left is pressed too many times. Change Left opens main menu in Settings › Display.", style = OmniTheme.type.body, color = OmniTheme.colors.textSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(skipWarning, { skipWarning = it })
                androidx.tv.material3.Text("Don’t show this warning again", style = OmniTheme.type.body, color = OmniTheme.colors.textPrimary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                OmniButton("Stay here", { confirmMenu = false }, Modifier.focusRequester(stayFocus))
                OmniButton("Main menu", { if (skipWarning) onDisableWarning(); confirmMenu = false; onOpenNavigation() }, primary = true)
            }
        }
        LaunchedEffect(Unit) { stayFocus.requestFocusWhenReady() }
    }
    state.menuCategoryId?.let { id ->
        val category = state.categories.firstOrNull { it.id == id }
        if (category != null) CategoryMenu(
            title = category.name,
            actions = listOf(
                MenuAction(stringResource(R.string.horizontal_move_category), onClick = {
                    browse.closeCategoryMenu(); moving = id; dropArmed = false
                    scope.launch {
                        list.animateScrollToItem(state.categories.indexOf(category) + 1)
                        requester(id).requestFocusWhenReady()
                    }
                }),
                MenuAction(stringResource(R.string.horizontal_hide_category), onClick = { browse.hideCategory(id) }, destructive = true),
                MenuAction(stringResource(R.string.horizontal_cancel), onClick = { browse.closeCategoryMenu(); scope.launch { requester(id).requestFocusWhenReady() } }),
            ),
            onDismiss = { browse.closeCategoryMenu(); scope.launch { requester(id).requestFocusWhenReady() } },
        )
    }
    state.hiddenNotice?.let {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            UndoNotice(stringResource(R.string.horizontal_category_hidden), stringResource(R.string.horizontal_undo),
                { browse.undoHideCategory() }, { browse.clearHiddenNotice() }, modifier = Modifier.padding(OmniSpacing.tvSide))
        }
    }
    LaunchedEffect(state.hiddenNotice?.categoryId) {
        if (state.hiddenNotice != null) {
            val index = if (state.horizontalHome) 0 else state.categories.indexOfFirst { it.id == state.selected }.coerceAtLeast(0) + 1
            list.animateScrollToItem(index)
            selectedFocus.requestFocusWhenReady()
        }
    }
}

/** Compact navigation chrome: no oversized focus halo or opaque strip behind the tabs. */
@Composable
private fun CategoryTab(onClick: () -> Unit, modifier: Modifier, onLongClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(OmniSpacing.pill)
    Card(onClick, modifier = modifier.touchClick(onClick, onLongClick), onLongClick = onLongClick,
        shape = CardDefaults.shape(shape), scale = CardDefaults.scale(focusedScale = 1.02f),
        glow = CardDefaults.glow(focusedGlow = Glow.None),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, OmniTheme.colors.focusBorder), shape = shape)),
        colors = CardDefaults.colors(containerColor = OmniTheme.colors.surface.copy(alpha = 0.55f), focusedContainerColor = OmniTheme.colors.elevated)) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}
