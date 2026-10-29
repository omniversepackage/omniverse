package com.yodesla.omniverse.designsystem.epg

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.designsystem.LocalTextScale
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.OmniType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * PLAN.md §8.4 — the TV guide. Deliberately NOT built from lazy lists of focusable cells:
 *  - ONE focusable node; the selection is plain state (row + time), D-pad just changes numbers.
 *  - Programme cells are drawn on a Canvas with a TextMeasurer cache: no composition per cell,
 *    so scrolling costs a redraw, not a recomposition of hundreds of nodes.
 *  - Only visible rows (+1 screen) request data; results are cached per (row, 6 h block).
 */

@Immutable
data class GridChannel(val id: String, val number: Int?, val name: String, val logoUrl: String?, val favorite: Boolean = false)

@Immutable
data class GridProgramme(val startMs: Long, val endMs: Long, val title: String, val hasArchive: Boolean = false)

/**
 * A one-shot request to scroll the grid to [timeMs]. [token] makes two requests for the same time
 * distinct (pressing "Today" again after scrolling away must move the grid back).
 */
@Immutable
data class GridJump(val token: Long, val timeMs: Long)

interface EpgGridData {
    val channelCount: Int
    fun channel(index: Int): GridChannel
    /** Programmes for [index] overlapping [fromMs, toMs). Called off the draw path; may suspend on DB. */
    suspend fun programmes(index: Int, fromMs: Long, toMs: Long): List<GridProgramme>
}

@Composable
fun EpgGrid(
    data: EpgGridData,
    nowMs: Long,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 42.dp, // compact rows: more channels per screen (Kory)
    channelColumn: Dp = 240.dp,
    visibleHours: Float = 2f,
    onSelect: (row: Int, programme: GridProgramme?) -> Unit = { _, _ -> },
    onFocusedChange: (row: Int, programme: GridProgramme?) -> Unit = { _, _ -> },
    /** Hold OK on a row (e.g. toggle favourite). */
    onLongSelect: (row: Int) -> Unit = {},
    onExitLeft: (() -> Unit)? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    /** Row selected when [data] first appears (e.g. the channel just watched). */
    initialRow: Int = 0,
    /** Programmes with a reminder get a small accent dot; read at draw time. */
    isMarked: (row: Int, startMs: Long) -> Boolean = { _, _ -> false },
    /** Change to force a redraw when [isMarked]'s answers change. */
    markVersion: Int = 0,
    /**
     * Task 85: Fast-forward / Rewind (and Channel Up/Down on TV remotes) asked to move [deltaMs] from
     * the grid's current time. The parent returns the landing time — it knows the catch-up floor and
     * the guide's real extent — or null to ignore the key.
     */
    onJump: (fromMs: Long, deltaMs: Long) -> Long? = { _, _ -> null },
    /** Task 85: the "Now" key; the parent returns the live-edge time to land on, or null to ignore. */
    onNow: () -> Long? = { null },
    /** Where the selection sits after every move, so the parent can follow the viewer. */
    onViewTime: (Long) -> Unit = {},
    /** Scroll to [GridJump.timeMs] (day strip, header buttons). */
    jump: GridJump? = null,
) {
    val c = OmniTheme.colors
    // Long-lived effects call the latest callbacks without restarting.
    val onFocusedChangeNow by androidx.compose.runtime.rememberUpdatedState(onFocusedChange)
    val onViewTimeNow by androidx.compose.runtime.rememberUpdatedState(onViewTime)
    // Guide rows are fixed-height (42 dp) canvas cells. Cap their text at 1.15 so two-line channel
    // names and single-line programme titles stay readable inside the row even at "Extra large" (1.3).
    val type = OmniType().scaled(minOf(LocalTextScale.current, 1.15f))
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 512)
    val noInfoLayout = remember(measurer, type.body, c.textTertiary) {
        measurer.measure("No guide information", type.body.copy(color = c.textTertiary))
    }
    val favoriteLayout = remember(measurer, type.caption, c.accent) {
        measurer.measure("★", type.caption.copy(color = c.accent), maxLines = 1)
    }
    val cache = remember(data) { mutableStateMapOf<Long, List<GridProgramme>>() }
    // Text layouts keyed by (programme, style variant). Measured at the FULL cell width once, then
    // drawn clipped, so scrolling never re-measures (the draw pass was the jank source).
    val layouts = remember(data, density.density, density.fontScale, type, c) { HashMap<Long, androidx.compose.ui.text.TextLayoutResult>() }
    val channelLayouts = remember(data, density.density, density.fontScale, type, c) { HashMap<Long, androidx.compose.ui.text.TextLayoutResult>() }
    val headerLayouts = remember(density.density, density.fontScale, type, c, com.yodesla.omniverse.designsystem.ClockFormat.use24h) { HashMap<Long, androidx.compose.ui.text.TextLayoutResult>() }

    var selRow by remember(data) { mutableIntStateOf(initialRow.coerceIn(0, (data.channelCount - 1).coerceAtLeast(0))) }
    var selTime by remember(data) { mutableLongStateOf(nowMs) }
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    // Viewport: first visible row (animated as float for smooth scrolling) and window start time.
    val topRow = remember(data) { Animatable((selRow - 1).coerceAtLeast(0).toFloat()) }
    // Epoch ms don't fit a Float (~2 min precision at 2026 values), so animate an offset from a Long origin.
    val origin = remember(data) { floorToHalfHour(nowMs) }
    val viewOffset = remember(data) { Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scrollJobs = remember(data) { arrayOfNulls<kotlinx.coroutines.Job>(2) }

    fun programmeAt(row: Int, t: Long): GridProgramme? =
        cache[blockKey(row, t)]?.firstOrNull { t >= it.startMs && t < it.endMs }

    // No full-size translucent fill: at 4K every blended full-screen layer costs GPU time per frame.
    BoxWithConstraints(modifier) {
        val rowPx = with(density) { rowHeight.toPx() }
        val headerPx = with(density) { 40.dp.toPx() }
        val gridWidthPx = constraints.maxWidth - with(density) { channelColumn.toPx() }
        val visibleRows = ((constraints.maxHeight - headerPx) / rowPx).toInt().coerceAtLeast(1)
        val spanMs = (visibleHours * HOUR).toLong()
        val pxPerMs = gridWidthPx / spanMs
        val padPx = with(density) { OmniSpacing.m.toPx() }
        // Wide enough for 4-digit provider numbers (e.g. 8863); only longer numbers drop the logo.
        val numColPx = with(density) { 44.dp.toPx() }
        // Narrower logo slot (Kory picked "both": narrower logo + names on up to two lines).
        val logoWPx = with(density) { 36.dp.toPx() }
        // Logo box nearly fills the row; logos scale up to touch its edges, never stretched.
        val logoHPx = (rowPx - with(density) { 6.dp.toPx() }).coerceAtLeast(with(density) { 24.dp.toPx() })

        // Logo painters for the visible window only. Composition touches this map only when the window
        // moves (a key press that scrolls); painters load off-thread and invalidate just the canvas.
        // The window moves in steps of LOGO_STEP rows so ordinary row-by-row scrolling never recomposes
        // this block (it used to re-run on every key press, the main source of guide jank).
        val logoBase by remember(data) { androidx.compose.runtime.derivedStateOf { ((topRow.targetValue.roundToInt() - 2).coerceAtLeast(0) / LOGO_STEP) * LOGO_STEP } }
        val logoPainters = HashMap<Int, androidx.compose.ui.graphics.painter.Painter>()
        for (r in logoBase until (logoBase + visibleRows + LOGO_STEP + 4).coerceAtMost(data.channelCount)) {
            val url = data.channel(r).logoUrl ?: continue
            key(url) {
                // 256x128 is still ~2x the 64 dp slot on a 4K Shield; 480x240 per row bloated native memory.
                logoPainters[r] = coil3.compose.rememberAsyncImagePainter(
                    model = coil3.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(url).size(256, 128).build(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                )
            }
        }

        // Keep the selection on screen: rows pivot 2 rows from the top edge; time pages by half hours.
        fun ensureVisible(snap: Boolean = false) {
            val targetTop = when {
                selRow < topRow.targetValue + 1 -> (selRow - 1).coerceAtLeast(0).toFloat()
                selRow > topRow.targetValue + visibleRows - 2 -> (selRow - visibleRows + 2).toFloat()
                else -> topRow.targetValue
            }.coerceIn(0f, (data.channelCount - visibleRows).coerceAtLeast(0).toFloat())
            val vs = origin + viewOffset.targetValue.toLong()
            val targetStart = when {
                selTime < vs -> floorToHalfHour(selTime)
                selTime >= vs + spanMs - HALF_HOUR -> floorToHalfHour(selTime) - spanMs + 2 * HALF_HOUR
                else -> vs
            }
            if (targetTop != topRow.targetValue || (snap && topRow.value != targetTop)) {
                scrollJobs[0]?.cancel()
                scrollJobs[0] = scope.launch {
                    if (snap) topRow.snapTo(targetTop)
                    else topRow.animateTo(targetTop, spring(stiffness = 700f))
                }
            }
            val offset = (targetStart - origin).toFloat()
            if (offset != viewOffset.targetValue || (snap && viewOffset.value != offset)) {
                scrollJobs[1]?.cancel()
                scrollJobs[1] = scope.launch {
                    if (snap || kotlin.math.abs(targetStart - vs) > spanMs) viewOffset.snapTo(offset)
                    else viewOffset.animateTo(offset, spring(stiffness = 700f))
                }
            }
            onViewTime(selTime)
            onFocusedChange(selRow, programmeAt(selRow, selTime))
        }

        // Jump requests land on the parent's clamped time; the row selection is untouched, so the
        // viewer keeps the channel they were on.
        fun moveTo(timeMs: Long) {
            selTime = timeMs
            ensureVisible()
        }

        /** A jump key is handled only if the parent produced a landing time. */
        fun jumpKey(targetMs: Long?): Boolean {
            if (targetMs == null) return false
            moveTo(targetMs)
            return true
        }

        LaunchedEffect(jump) {
            val j = jump
            if (j != null && j.timeMs != selTime) moveTo(j.timeMs)
        }

        // Load data for visible rows (+ one screen of look-ahead) whenever the viewport settles.
        LaunchedEffect(data, visibleRows) {
            snapshotFlow { topRow.targetValue.roundToInt() to floorToBlock(origin + viewOffset.targetValue.toLong()) }
                .distinctUntilChanged()
                .collectLatest { (top, block) ->
                    val rows = (top - visibleRows).coerceAtLeast(0) until (top + 2 * visibleRows).coerceAtMost(data.channelCount)
                    val missing = buildList {
                        for (b in listOf(block, block + BLOCK)) for (r in rows) {
                            if (blockKey(r, b) !in cache) add(r to b)
                        }
                    }
                    if (missing.isEmpty()) return@collectLatest
                    // Load off the main thread, visible rows first, and publish in ONE snapshot write
                    // so the canvas redraws once instead of once per row.
                    val visibleFirst = missing.sortedBy { (r, _) -> if (r in top until top + visibleRows) 0 else 1 }
                    val loaded = withContext(Dispatchers.Default) {
                        visibleFirst.associate { (r, b) -> blockKey(r, b) to data.programmes(r, b, b + BLOCK) }
                    }
                    // Keep only nearby channel/time blocks; long guide sessions must plateau.
                    val retained = buildSet {
                        for (b in listOf(block - BLOCK, block, block + BLOCK)) for (r in rows) add(blockKey(r, b))
                        add(blockKey(selRow, selTime))
                    }
                    cache.keys.toList().filter { it !in retained }.forEach { cache.remove(it) }
                    cache.putAll(loaded)
                }
        }

        // Publish the initial selection, then refresh it when its EPG block arrives.
        // Previously the detail pane stayed on its placeholder until a D-pad move.
        // Read in a snapshotFlow, not in composition: reading selRow/selTime here recomposed the whole
        // grid on every D-pad press. Only a changed block (data arrived) re-publishes; moves publish
        // through ensureVisible().
        LaunchedEffect(data) {
            snapshotFlow { Triple(focused, selRow, cache[blockKey(selRow, selTime)]) }
                .distinctUntilChanged()
                .collect { (f, _, _) ->
                    if (f && data.channelCount > 0) {
                        onViewTimeNow(selTime)
                        onFocusedChangeNow(selRow, programmeAt(selRow, selTime))
                    }
                }
        }

        Row(Modifier.fillMaxSize()) {
            // Channel column: also a Canvas. No composition on key presses at all; names/numbers are
            // cached TextLayouts, logos are pre-decoded bitmaps loaded off-thread for visible rows.
            Canvas(Modifier.width(channelColumn).fillMaxHeight()) {
                val first = topRow.value.toInt()
                val frac = topRow.value - first
                clipRect(top = headerPx) {
                    for (i in 0..visibleRows + 1) {
                        val row = first + i
                        if (row >= data.channelCount) break
                        val y = headerPx + (i - frac) * rowPx
                        if (row == selRow && focused) {
                            drawRect(c.elevated.copy(alpha = 0.86f), Offset(0f, y), Size(size.width, rowPx))
                            drawRect(
                                c.focusBorder, Offset(1f, y + 1f), Size((size.width - 2f).coerceAtLeast(0f), (rowPx - 2f).coerceAtLeast(0f)),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                            )
                        }
                        val ch = data.channel(row)
                        val favoriteWidth = if (ch.favorite) favoriteLayout.size.width + padPx else 0f
                        val numLayout = channelLayouts.getOrPut(row.toLong() * 2) {
                            measurer.measure(ch.number?.toString() ?: "", type.numeric.copy(color = c.textTertiary), maxLines = 1)
                        }
                        drawText(numLayout, topLeft = Offset(padPx, y + (rowPx - numLayout.size.height) / 2f))
                        // Provider numbers can be five digits. Let the measured number claim its
                        // actual width; if it outgrows the usual slot, omit the logo so the name
                        // still has room instead of drawing all three on top of each other.
                        val wideNumber = numLayout.size.width > numColPx + padPx
                        var textX = padPx + maxOf(numColPx, numLayout.size.width + padPx / 2f)
                        val logo = logoPainters[row]
                        val logoTop = y + (rowPx - logoHPx) / 2f
                        val logoFailed = logo is coil3.compose.AsyncImagePainter &&
                            logo.state.value is coil3.compose.AsyncImagePainter.State.Error
                        if (!wideNumber) {
                            if (logo != null && !logoFailed) {
                                // Keep the logo's own proportions, centered in its slot (no stretching).
                                val intrinsic = logo.intrinsicSize
                                val fit = if (intrinsic != Size.Unspecified && intrinsic.width > 0f && intrinsic.height > 0f) {
                                    val k = minOf(logoWPx / intrinsic.width, logoHPx / intrinsic.height)
                                    Size(intrinsic.width * k, intrinsic.height * k)
                                } else Size(logoWPx, logoHPx)
                                translate(left = textX + (logoWPx - fit.width) / 2f, top = logoTop + (logoHPx - fit.height) / 2f) {
                                    with(logo) { draw(fit) }
                                }
                            } else {
                                // No logo (or it failed): the same initials tile the Live list shows.
                                drawRoundRect(
                                    com.yodesla.omniverse.designsystem.channelTint(ch.name).copy(alpha = 0.55f),
                                    Offset(textX, logoTop), Size(logoWPx, logoHPx), CornerRadius(12f),
                                )
                                val ini = channelLayouts.getOrPut(-(row.toLong() + 1)) {
                                    measurer.measure(com.yodesla.omniverse.designsystem.initials(ch.name), type.title.copy(color = c.textPrimary), maxLines = 1)
                                }
                                drawText(ini, topLeft = Offset(textX + (logoWPx - ini.size.width) / 2f, logoTop + (logoHPx - ini.size.height) / 2f))
                            }
                            textX += logoWPx + padPx / 2f
                        }
                        val nameMaxW = Constraints(maxWidth = (size.width - textX - padPx - favoriteWidth).toInt().coerceAtLeast(1))
                        val nameStyle = type.body.copy(color = c.textPrimary, fontSize = type.body.fontSize * 0.9f,
                            lineHeight = type.body.fontSize * 1.05f)
                        val nameLayout = channelLayouts.getOrPut((row.toLong() * 31 + nameMaxW.maxWidth) * 31 + ch.name.hashCode() + if (ch.favorite) 1_000_000_000L else 0L) {
                            measurer.measure(ch.name, nameStyle, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, constraints = nameMaxW)
                        }
                        clipRect(left = 0f, top = y, right = size.width, bottom = y + rowPx) {
                            drawText(nameLayout, topLeft = Offset(textX, y + ((rowPx - nameLayout.size.height) / 2f).coerceAtLeast(0f)))
                        }
                        if (ch.favorite) drawText(favoriteLayout, topLeft = Offset(size.width - padPx - favoriteLayout.size.width, y + (rowPx - favoriteLayout.size.height) / 2f))
                    }
                }
            }
            // Programme canvas: the single focus target.
            Canvas(
                Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter
                        // OK acts on release so a hold can mean something else (long press = onLongSelect).
                        if (ok && e.type == KeyEventType.KeyUp) {
                            if (!longFired) onSelect(selRow, programmeAt(selRow, selTime))
                            longFired = false
                            return@onPreviewKeyEvent true
                        }
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (ok) {
                            if (e.nativeKeyEvent.repeatCount > 0 && !longFired) { longFired = true; onLongSelect(selRow) }
                            return@onPreviewKeyEvent true
                        }
                        when (e.key) {
                            Key.DirectionDown -> if (selRow < data.channelCount - 1) { selRow++; ensureVisible(e.nativeKeyEvent.repeatCount > 0); true } else false
                            Key.DirectionUp -> if (selRow > 0) { selRow--; ensureVisible(e.nativeKeyEvent.repeatCount > 0); true } else false
                            Key.DirectionRight -> {
                                val p = programmeAt(selRow, selTime)
                                selTime = (p?.endMs ?: (floorToHalfHour(selTime) + HALF_HOUR)).coerceAtMost(nowMs + 7 * DAY)
                                ensureVisible(e.nativeKeyEvent.repeatCount > 0); true
                            }
                            Key.DirectionLeft -> {
                                val p = programmeAt(selRow, selTime)
                                val start = p?.startMs ?: floorToHalfHour(selTime)
                                // On what's airing now, Left returns to channels instead of
                                // walking back in time — unless the previous programme is playable catch-up.
                                val prev = programmeAt(selRow, start - 1)
                                if (start <= nowMs && prev?.hasArchive != true) {
                                    if (onExitLeft != null) { onExitLeft(); true } else false
                                }
                                else { selTime = start - 1; ensureVisible(e.nativeKeyEvent.repeatCount > 0); true }
                            }
                            // Task 85: media Fast-forward/Rewind (and the remote's Channel Up/Down,
                            // which the guide does not use for zapping) jump two hours at a time;
                            // Guide / Play-Pause returns to "now".
                            Key.MediaFastForward, Key.MediaNext, Key.ChannelUp -> jumpKey(onJump(selTime, TWO_HOURS))
                            Key.MediaRewind, Key.MediaPrevious, Key.ChannelDown -> jumpKey(onJump(selTime, -TWO_HOURS))
                            Key.Guide, Key.MediaPlayPause -> jumpKey(onNow())
                            else -> false
                        }
                    },
            ) {
                if (layouts.size > 4_000) layouts.clear()
                val vs = origin + viewOffset.value.toLong()
                val first = topRow.value.toInt()
                val frac = topRow.value - first
                clipRect(bottom = headerPx) {
                    drawHeader(vs, spanMs, pxPerMs, headerPx, measurer, type.caption.copy(color = c.textSecondary), headerLayouts)
                }
                val nowX = (nowMs - vs) * pxPerMs
                clipRect(top = headerPx) {
                    for (i in 0..visibleRows + 1) {
                        val row = first + i
                        if (row >= data.channelCount) break
                        val y = headerPx + (i - frac) * rowPx
                        val selected = programmeAt(row, selTime).takeIf { row == selRow }
                        val b1 = cache[blockKey(row, vs)]
                        val b2 = cache[blockKey(row, vs + spanMs)].takeIf { it !== b1 }
                        if (b1.isNullOrEmpty() && b2.isNullOrEmpty()) {
                            drawRoundRect(c.surface.copy(alpha = 0.76f), Offset(2f, y + 2f), Size(size.width - 4f, rowPx - 4f), CornerRadius(8f))
                            // Loaded but empty (vs. still loading): say so instead of a blank bar.
                            if (b1 != null) drawText(noInfoLayout, topLeft = Offset(padPx + 8f, y + (rowPx - noInfoLayout.size.height) / 2f))
                        }
                        for (list in arrayOf(b1, b2)) {
                            if (list == null) continue
                            for (idx in list.indices) {
                                val p = list[idx]
                                if (p.endMs <= vs || p.startMs >= vs + spanMs) continue
                                if (list === b2 && b1 != null && b1.isNotEmpty() && p.startMs < b1.last().endMs) continue
                                val fullX0 = (p.startMs - vs) * pxPerMs
                                val fullX1 = (p.endMs - vs) * pxPerMs
                                val x0 = fullX0.coerceAtLeast(0f)
                                val x1 = fullX1.coerceAtMost(size.width)
                                val isSel = focused && p == selected
                                val isNow = nowMs >= p.startMs && nowMs < p.endMs
                                val fill = when {
                                    // Selected: champagne tint + outline (Midnight Cinema), text stays light.
                                    isSel -> androidx.compose.ui.graphics.lerp(c.surface, c.accent, 0.22f).copy(alpha = 0.94f)
                                    isNow -> c.elevated.copy(alpha = 0.88f)
                                    else -> c.surface.copy(alpha = 0.76f)
                                }
                                drawRoundRect(fill, Offset(x0 + 2f, y + 2f), Size((x1 - x0 - 4f).coerceAtLeast(0f), rowPx - 4f), CornerRadius(12f))
                                if (isSel) {
                                    // Soft glow, then a crisp outline.
                                    drawRoundRect(c.accent.copy(alpha = 0.18f), Offset(x0 - 3f, y - 3f), Size((x1 - x0 + 6f).coerceAtLeast(0f), rowPx + 6f), CornerRadius(16f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f))
                                    drawRoundRect(c.focusBorder, Offset(x0 + 2f, y + 2f), Size((x1 - x0 - 4f).coerceAtLeast(0f), rowPx - 4f), CornerRadius(12f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                                }
                                if (markVersion >= 0 && isMarked(row, p.startMs) && x1 - x0 > 24f) {
                                    // Reminder set: small accent dot in the cell's top-right corner.
                                    drawCircle(c.accent, radius = 6f, center = Offset(x1 - 14f, y + 14f))
                                }
                                // A sliver of the airing cell at the window edge can be < 4 px wide: coerceIn would throw.
                                if (isNow && !isSel && x1 - x0 > 4f) {
                                    // Elapsed part of the airing programme: a soft tint instead of a line through the text.
                                    val px = nowX.coerceIn(x0 + 2f, x1 - 2f)
                                    drawRoundRect(c.accent.copy(alpha = 0.12f), Offset(x0 + 2f, y + 2f), Size((px - x0 - 2f).coerceAtLeast(0f), rowPx - 4f), CornerRadius(12f))
                                }
                                val fullW = (fullX1 - fullX0 - 24f).toInt()
                                if (fullW > 24 && x1 - x0 > 36f) {
                                    val variant = when {
                                        isSel -> 2
                                        p.endMs <= nowMs -> 1
                                        else -> 0
                                    }
                                    val key = ((p.startMs * 31 + p.title.hashCode()) * 31 + p.endMs) * 31 + fullW * 4 + variant + row.toLong() * 7_919
                                    val layout = layouts.getOrPut(key) {
                                        val color = when (variant) {
                                            2 -> c.textPrimary
                                            1 -> c.textTertiary
                                            else -> c.textPrimary
                                        }
                                        measurer.measure(
                                            p.title, type.body.copy(color = color), maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = fullW),
                                        )
                                    }
                                    // Keep the title readable when the cell starts off-screen: pin it to the left edge.
                                    val tx = (fullX0 + 12f).coerceAtLeast(12f).coerceAtMost((fullX1 - layout.size.width - 12f).coerceAtLeast(fullX0 + 12f))
                                    clipRect(left = x0 + 2f, right = x1 - 2f) {
                                        drawText(layout, topLeft = Offset(tx, y + (rowPx - layout.size.height) / 2f))
                                    }
                                }
                            }
                        }
                    }
                }
                // "Now" marker lives in the time header so it never crosses programme titles.
                if (nowX in 0f..size.width) {
                    drawLine(c.accent, Offset(nowX, headerPx * 0.82f), Offset(nowX, headerPx), strokeWidth = 3f)
                    drawCircle(c.accent, radius = 5f, center = Offset(nowX, headerPx - 1f))
                }
            }
        }
    }
}

private fun DrawScope.drawHeader(
    vs: Long, spanMs: Long, pxPerMs: Float, headerPx: Float,
    measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle,
    layouts: HashMap<Long, androidx.compose.ui.text.TextLayoutResult>,
) {
    if (layouts.size > 200) layouts.clear()
    // Start one slot early so the label of a slot that began just off-screen slides out smoothly.
    var t = floorToHalfHour(vs) - HALF_HOUR
    while (t < vs + spanMs) {
        val x = (t - vs) * pxPerMs
        val layout = layouts.getOrPut(t) { measurer.measure(com.yodesla.omniverse.designsystem.ClockFormat.format(t), style) }
        if (x + 8f + layout.size.width > 0f) {
            drawText(layout, topLeft = Offset(x + 8f, (headerPx - layout.size.height) / 2f))
        }
        t += HALF_HOUR
    }
}


private const val LOGO_STEP = 6
private const val HALF_HOUR = 30 * 60_000L
private const val HOUR = 60 * 60_000L
private const val TWO_HOURS = 2 * HOUR
private const val DAY = 24 * HOUR
private const val BLOCK = 6 * HOUR

private fun floorToHalfHour(t: Long) = t - Math.floorMod(t, HALF_HOUR)
private fun floorToBlock(t: Long) = t - Math.floorMod(t, BLOCK)
private fun blockKey(row: Int, t: Long): Long = row.toLong() * 1_000_000L + floorToBlock(t) / BLOCK
