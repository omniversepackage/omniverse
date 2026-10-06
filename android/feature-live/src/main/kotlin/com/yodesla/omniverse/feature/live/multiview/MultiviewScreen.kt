package com.yodesla.omniverse.feature.live.multiview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.KidsTimeState
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.LogoImage
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.feature.live.R
import com.yodesla.omniverse.player.EngineSurface
import com.yodesla.omniverse.player.FailureKind
import com.yodesla.omniverse.player.Media3ExoEngine
import com.yodesla.omniverse.player.PlayerState

/**
 * Live TV Multiview (Task 55, Task 67): 2-4 channels at once, each tile on its OWN engine (created
 * here, released here - every engine is released when the screen disposes). 2 channels sit side by
 * side, 3-4 form a 2x2 quad; the grid/focus/audio rules live in [MultiviewLayout] (unit-tested).
 * Only the focused tile has audio (a speaker badge marks it); D-pad moves focus; OK opens that
 * channel full screen through the normal live-player route; hold OK opens the tile menu (Swap
 * channel / Retry); Back exits Multiview. A failed stream only darkens its own tile.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultiviewRoute(
    viewModel: MultiviewViewModel,
    channels: List<ContentKey>,
    userAgent: String,
    onPlay: (ContentKey, RemoteId) -> Unit,
    onExit: () -> Unit,
    /** A tile was swapped: mirror it in the session so re-entering resumes the new channel. */
    onSwap: (oldKey: ContentKey, newKey: ContentKey) -> Unit = { _, _ -> },
    /** "Remove from Multiview": drop this tile and take it out of the session. */
    onRemove: (ContentKey) -> Unit = {},
    /** "End Multiview": clear the session and leave. */
    onEnd: () -> Unit = {},
    /** "Save as group": persist the current session as a saved group. */
    onSaveGroup: () -> Unit = {},
    /** Task 111 H2/M4: the shared Kids clock. While set, the tiles charge the allowance and a block
     *  stops every tile stream and blocks opening one full screen. */
    kidsTime: com.yodesla.omniverse.core.data.KidsTimeController? = null,
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    LaunchedEffect(channels) { viewModel.start(channels) }
    BackHandler { onExit() }

    val tiles = ui.tiles
    // One engine per tile, keyed by channel. [registry] owns every live engine: backgrounding stops
    // all of them (M2), and each is released exactly once when its tile leaves composition (Back, a
    // tile opening full screen, a swapped-out tile, or a tile the revalidated policy removed) or the
    // screen is disposed.
    val engines = tiles.mapIndexed { _, tile -> remember(tile.key) { Media3ExoEngine(context, userAgent) } }
    val registry = remember { MultiviewEngineRegistry() }
    var foreground by remember { mutableStateOf(true) }
    engines.forEachIndexed { index, engine ->
        DisposableEffect(engine) {
            registry.register(tiles[index].key, engine)
            onDispose { registry.unregister(tiles[index].key) }
        }
    }
    DisposableEffect(registry) { onDispose { registry.releaseAll() } }
    // Keep the screen on while Multiview is visible — the same mechanism the normal player uses.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // Home button / another app: close every tile's upstream connection. On return each tile
    // re-opens only through the view model's CURRENT policy, so a channel locked while the app was
    // backgrounded stays dark instead of streaming unauthorized.
    LifecycleStartEffect(Unit) {
        foreground = true
        onStopOrDispose {
            foreground = false
            registry.stopAll()
        }
    }

    // Task 111 H2/M4: the tiles charge the shared Kids clock while any of them is actually playing,
    // and a block stops every stream (and blocks opening a tile full screen).
    val kids by (kidsTime?.state ?: flowOf(KidsTimeState())).collectAsStateWithLifecycle(initialValue = KidsTimeState())
    LaunchedEffect(kidsTime) {
        if (kidsTime == null) return@LaunchedEffect
        kidsTime.reload()
        while (true) {
            delay(1_000)
            kidsTime.tick(playing = engines.any { it.state.value is PlayerState.Playing })
        }
    }
    LaunchedEffect(kids.blocked) {
        if (kids.blocked != null) registry.stopAll()
    }

    var focused by remember { mutableStateOf(0) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var swapFor by remember { mutableStateOf<Int?>(null) }
    var swapCandidates by remember { mutableStateOf<List<ChannelRow>>(emptyList()) }
    val requesters = remember(tiles.size) { List(tiles.size) { FocusRequester() } }
    LaunchedEffect(tiles.size) { if (tiles.isNotEmpty()) runCatching { requesters[0].requestFocus() } }
    LaunchedEffect(swapFor) { swapCandidates = if (swapFor != null) viewModel.swapCandidates() else emptyList() }

    val grid = MultiviewLayout.gridFor(tiles.size)
    val audible = MultiviewLayout.audibleIndex(focused, tiles.size)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                // While a tile menu or the swap picker is open, the D-pad belongs to the overlay.
                if (menuFor != null || swapFor != null) return@onPreviewKeyEvent false
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val dir = when (e.key) {
                    Key.DirectionUp -> PadDirection.UP
                    Key.DirectionDown -> PadDirection.DOWN
                    Key.DirectionLeft -> PadDirection.LEFT
                    Key.DirectionRight -> PadDirection.RIGHT
                    else -> return@onPreviewKeyEvent false
                }
                val n = MultiviewLayout.neighbor(focused, tiles.size, dir) ?: return@onPreviewKeyEvent false
                runCatching { requesters[n].requestFocus() }.isSuccess
            },
    ) {
        if (tiles.isEmpty()) {
            Text(
                "Loading...", style = OmniTheme.type.body, color = OmniTheme.colors.textTertiary,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(Modifier.fillMaxSize().padding(OmniSpacing.s)) {
            for (r in 0 until grid.rows) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (col in 0 until grid.cols) {
                        val index = r * grid.cols + col
                        if (index < tiles.size) {
                            val tile = tiles[index]
                            MultiviewTile(
                                tile = tile,
                                engine = engines[index],
                                focused = focused == index,
                                audible = audible == index,
                                openStream = foreground && kids.blocked == null && viewModel.allows(tile.key),
                                requester = requesters[index],
                                onFocus = { focused = index },
                                onOpen = { if (kids.blocked == null) tile.categoryId?.let { onPlay(tile.key, it) } },
                                onMenu = { menuFor = index },
                                modifier = Modifier.weight(1f).fillMaxHeight().padding(OmniSpacing.s),
                            )
                        } else {
                            // The empty cell of a 3-tile quad keeps its share of the row.
                            Spacer(Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }

        val menu = menuFor
        if (menu != null && menu in tiles.indices) {
            val key = tiles[menu].key
            TileMenu(
                name = tiles[menu].name,
                onSwap = { swapFor = menu; menuFor = null },
                onRetry = { viewModel.retry(menu); menuFor = null },
                onRemove = { viewModel.removeTile(key); onRemove(key); menuFor = null },
                onSaveGroup = { onSaveGroup(); menuFor = null },
                onEnd = { onEnd(); menuFor = null },
                onDismiss = { menuFor = null },
            )
        }
        val swap = swapFor
        if (swap != null && swap in tiles.indices) {
            SwapPicker(
                currentName = tiles[swap].name,
                candidates = swapCandidates,
                onPick = { key -> viewModel.swapChannel(swap, key); onSwap(tiles[swap].key, key); swapFor = null },
                onDismiss = { swapFor = null },
            )
        }
    }
}

@Composable
private fun MultiviewTile(
    tile: MultiviewTileUi,
    engine: Media3ExoEngine,
    focused: Boolean,
    audible: Boolean,
    openStream: Boolean,
    requester: FocusRequester,
    onFocus: () -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OmniTheme.colors
    val playerState by engine.state.collectAsStateWithLifecycle()
    LaunchedEffect(engine, audible) { engine.player.setVolume(if (audible) 1f else 0f) }
    // M2: [openStream] is false while backgrounded and for a tile the current policy denies, so a
    // spec never opens (or re-opens) a connection outside the foreground or without authorization.
    // [tile.attempt] is bumped by Retry so the engine re-opens even when the spec is unchanged.
    LaunchedEffect(engine, tile.spec, tile.attempt, openStream) {
        if (openStream) tile.spec?.let { engine.zap(it) }
    }
    val streamError = (playerState as? PlayerState.Failed)?.let { failureText(it.kind) }
    val problem = tile.error ?: streamError

    FocusCard(
        onClick = onOpen,
        onLongClick = onMenu,
        glass = false,
        modifier = modifier.focusRequester(requester).onFocusChanged { if (it.hasFocus) onFocus() },
    ) {
        EngineSurface(engine, Modifier.fillMaxSize())
        // Channel chip: always visible so every tile is identifiable at a glance.
        Row(
            Modifier.align(Alignment.TopStart).padding(8.dp)
                .background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(c.live))
            Text(
                listOfNotNull(tile.number?.toString(), tile.name.ifEmpty { null }).joinToString("  "),
                style = OmniTheme.type.caption, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // The tile that currently carries audio gets a small speaker badge.
        if (audible) SpeakerBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
        // The focused tile carries the name + now-playing caption (Task 55).
        if (focused) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.85f), 1f to Color.Black.copy(alpha = 0.95f)))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(tile.name, style = OmniTheme.type.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    tile.nowTitle ?: "No guide information", style = OmniTheme.type.caption,
                    color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // A dead stream only darkens its own tile; the others keep playing.
        if (problem != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
                Text(
                    problem, style = OmniTheme.type.body, color = Color.White, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        } else if (tile.spec == null) {
            Text(
                "Loading...", style = OmniTheme.type.caption, color = c.textTertiary,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Hold-OK menu for one tile: swap/retry this channel, take it out, save the grid as a group, or end. */
@Composable
private fun TileMenu(
    name: String,
    onSwap: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onSaveGroup: () -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OmniTheme.colors
    val context = LocalContext.current
    val first = remember(name) { FocusRequester() }
    BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize().background(Color(0xCC03060C)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(22.dp)).background(c.elevated).padding(OmniSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
        ) {
            Text(name, style = OmniTheme.type.headline, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OmniButton(
                context.getString(R.string.multiview_swap_channel),
                onClick = onSwap,
                modifier = Modifier.fillMaxWidth().focusRequester(first),
            )
            OmniButton(context.getString(R.string.multiview_retry), onClick = onRetry, modifier = Modifier.fillMaxWidth())
            OmniButton(context.getString(R.string.multiview_remove_tile), onClick = onRemove, modifier = Modifier.fillMaxWidth())
            OmniButton(context.getString(R.string.multiview_save_group_tile), onClick = onSaveGroup, modifier = Modifier.fillMaxWidth())
            OmniButton(context.getString(R.string.multiview_end), onClick = onEnd, modifier = Modifier.fillMaxWidth())
        }
    }
    LaunchedEffect(name) { runCatching { first.requestFocus() } }
}

/** Channel picker for a single slot: OK swaps that tile, everything else is untouched. */
@Composable
private fun SwapPicker(
    currentName: String,
    candidates: List<ChannelRow>,
    onPick: (ContentKey) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OmniTheme.colors
    val context = LocalContext.current
    val first = remember(currentName) { FocusRequester() }
    BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize().background(Color(0xE603060C)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = OmniSpacing.tvSide),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.m),
        ) {
            Text(context.getString(R.string.multiview_swap_for, currentName), style = OmniTheme.type.headline, color = c.textPrimary)
            if (candidates.isEmpty()) {
                Text("No other channels available", style = OmniTheme.type.body, color = c.textTertiary)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 200.dp),
                    horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                ) {
                    items(candidates, key = { "${it.key.sourceId.value}/${it.key.remoteId.value}" }) { row ->
                        FocusCard(
                            onClick = { onPick(row.key) },
                            glass = false,
                            modifier = Modifier.height(92.dp).then(if (row == candidates.first()) Modifier.focusRequester(first) else Modifier),
                        ) {
                            Row(Modifier.fillMaxSize().padding(OmniSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                                LogoImage(row.logoUrl, row.name, Modifier.size(56.dp, 34.dp))
                                Spacer(Modifier.width(OmniSpacing.s))
                                Text(
                                    row.name, style = OmniTheme.type.title, color = c.textPrimary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(currentName, candidates.size) { if (candidates.isNotEmpty()) runCatching { first.requestFocus() } }
}

/** A small drawn speaker glyph marking the tile that currently has audio. */
@Composable
private fun SpeakerBadge(modifier: Modifier = Modifier) {
    val color = OmniTheme.colors.accent
    Box(
        modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            val body = Path().apply {
                moveTo(w * 0.10f, h * 0.40f)
                lineTo(w * 0.30f, h * 0.40f)
                lineTo(w * 0.55f, h * 0.16f)
                lineTo(w * 0.55f, h * 0.84f)
                lineTo(w * 0.30f, h * 0.60f)
                lineTo(w * 0.10f, h * 0.60f)
                close()
            }
            drawPath(body, color)
            val stroke = Stroke(width = h * 0.09f)
            drawArc(color, startAngle = -50f, sweepAngle = 100f, useCenter = false, size = Size(w * 0.28f, h * 0.52f), topLeft = Offset(w * 0.60f, h * 0.24f), style = stroke)
            drawArc(color, startAngle = -50f, sweepAngle = 100f, useCenter = false, size = Size(w * 0.44f, h * 0.78f), topLeft = Offset(w * 0.52f, h * 0.11f), style = stroke)
        }
    }
}

private fun failureText(kind: FailureKind): String = when (kind) {
    FailureKind.DENIED -> "Can't open this channel"
    FailureKind.NOT_FOUND -> "Channel unavailable"
    FailureKind.NETWORK -> "Connection lost"
    FailureKind.UNSUPPORTED -> "Unsupported stream"
    FailureKind.OTHER -> "Something went wrong"
}
