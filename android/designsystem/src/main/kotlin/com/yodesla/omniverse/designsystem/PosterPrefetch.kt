package com.yodesla.omniverse.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Poster pre-loading (task 102). Lazy rows only ever start loading a poster when the card enters the
 * composition, so on a TV the artwork pops in a beat after the focus lands on it. This warms the
 * posters the focus is about to reach: [POSTER_PREFETCH_AHEAD] cards onward in the focused row, plus
 * the first [POSTER_PREFETCH_SIDE_TAKE] of the rows directly above and below it.
 *
 * The window maths is pure (no Coil, no Compose) so it is unit-testable; only
 * [rememberPosterPrefetcher] touches Coil, and it enqueues at exactly the pixel size the cards
 * request — Coil's memory cache is keyed by URL but its hit test also requires the cached bitmap to
 * satisfy the requested size, so a differently sized prefetch would miss.
 */

/** Posters warmed onward from the focus in its own row. */
const val POSTER_PREFETCH_AHEAD = 8

/** Posters warmed from the start of each neighbouring row. */
const val POSTER_PREFETCH_SIDE_TAKE = 6

/** Rows warmed on each side of the focused row. */
const val POSTER_PREFETCH_SIDE_ROWS = 1

/** Hard ceiling on one prefetch pass, whatever the row sizes. */
const val POSTER_PREFETCH_MAX = 24

/** Requests in flight at once: the cap that keeps prefetch from out-loading what is on screen. */
const val POSTER_PREFETCH_IN_FLIGHT = 12

/** A focus jump this large counts as fast scrolling: pending prefetch is dropped instead. */
const val POSTER_FAST_SCROLL_JUMP = 3

/** Focus has to sit still this long before anything is fetched. */
const val POSTER_PREFETCH_SETTLE_MS = 120L

/** Where the viewer is: which row, and which card in it. */
@Immutable
data class PosterFocus(val row: Int, val index: Int)

/**
 * Travel direction at [now] relative to [prev]: +1 forward, -1 backward, 0 unchanged. A row change
 * counts as forward when it goes down the page (the viewer keeps going that way).
 */
fun focusDirection(prev: PosterFocus?, now: PosterFocus): Int = when {
    prev == null -> 1
    now.row != prev.row -> if (now.row > prev.row) 1 else -1
    now.index > prev.index -> 1
    now.index < prev.index -> -1
    else -> 0
}

/** True when focus moved far enough in one step that prefetching the skipped cards is pointless. */
fun isFastPosterScroll(
    prev: PosterFocus?,
    now: PosterFocus,
    rowJump: Int = POSTER_FAST_SCROLL_JUMP,
    indexJump: Int = POSTER_FAST_SCROLL_JUMP,
): Boolean = prev != null && (abs(now.row - prev.row) >= rowJump || abs(now.index - prev.index) >= indexJump)

/**
 * The (row, index) pairs to warm for one focus position: [ahead] cards onward in the focused row
 * (walking backwards when the viewer is going backwards), then the first [sideTake] of each of the
 * [sideRows] rows above and below. Out-of-range positions are dropped, and the result never exceeds
 * [maxTotal].
 */
fun posterPrefetchIndices(
    rowSizes: List<Int>,
    focus: PosterFocus,
    direction: Int = 1,
    ahead: Int = POSTER_PREFETCH_AHEAD,
    sideTake: Int = POSTER_PREFETCH_SIDE_TAKE,
    sideRows: Int = POSTER_PREFETCH_SIDE_ROWS,
    maxTotal: Int = POSTER_PREFETCH_MAX,
): List<Pair<Int, Int>> {
    if (focus.row !in rowSizes.indices || maxTotal <= 0) return emptyList()
    val out = ArrayList<Pair<Int, Int>>()
    val rowSize = rowSizes[focus.row]
    val step = if (direction < 0) -1 else 1
    var i = focus.index + step
    while (i in 0 until rowSize && out.size < ahead && out.size < maxTotal) {
        out += focus.row to i
        i += step
    }
    for (offset in 1..sideRows) {
        for (row in intArrayOf(focus.row - offset, focus.row + offset)) {
            if (row !in rowSizes.indices) continue
            val take = minOf(sideTake, rowSizes[row], maxTotal - out.size)
            for (c in 0 until take) out += row to c
        }
    }
    return out
}

/**
 * The URLs to warm for one focus position, in card form: [posterPrefetchIndices] resolved against
 * [rows], blanks/nulls dropped, TMDB artwork rewritten to its card variant (the same rewrite
 * [PosterCard] applies, so the prefetch lands in the same cache entry), deduped, capped.
 */
fun posterPrefetchUrls(
    rows: List<List<String?>>,
    focus: PosterFocus,
    prev: PosterFocus? = null,
    cardWidthDp: Int,
    ahead: Int = POSTER_PREFETCH_AHEAD,
    sideTake: Int = POSTER_PREFETCH_SIDE_TAKE,
    sideRows: Int = POSTER_PREFETCH_SIDE_ROWS,
    maxTotal: Int = POSTER_PREFETCH_MAX,
): List<String> =
    posterPrefetchIndices(
        rows.map { it.size }, focus, focusDirection(prev, focus), ahead, sideTake, sideRows, maxTotal,
    ).mapNotNull { (row, index) -> rows[row][index] }
        .mapNotNull { tmdbPosterUrlForCard(it, cardWidthDp) }
        .filter { it.isNotBlank() }
        .distinct()
        .take(maxTotal)

/** Columns an `GridCells.Adaptive(minSize)` grid actually lays out in [widthDp]. */
fun adaptiveColumnCount(
    widthDp: Float,
    minSizeDp: Float,
    spacingDp: Float,
    startPaddingDp: Float,
    endPaddingDp: Float,
): Int {
    val usable = widthDp - startPaddingDp - endPaddingDp
    if (usable < minSizeDp) return 1
    return ((usable + spacingDp) / (minSizeDp + spacingDp)).toInt().coerceAtLeast(1)
}

/** The cell width such a grid gives each card (the size the cards request their art at). */
fun adaptiveCellWidthDp(
    widthDp: Float,
    columns: Int,
    spacingDp: Float,
    startPaddingDp: Float,
    endPaddingDp: Float,
): Dp {
    val usable = (widthDp - startPaddingDp - endPaddingDp).coerceAtLeast(0f)
    val spacing = spacingDp * (columns - 1).coerceAtLeast(0)
    return ((usable - spacing) / columns).coerceAtLeast(0f).dp
}

/** A flat, reading-order poster list as rows of [columns] (the grid's own layout). */
fun posterRowsFromFlat(urls: List<String?>, columns: Int): List<List<String?>> {
    if (columns <= 0) return emptyList()
    return urls.chunked(columns)
}

/** Enqueues one card-sized poster fetch; returns the handle that cancels it, or null for nothing.
 *  Implementations must call [onComplete] when the request finishes (success or failure) so the
 *  queue frees its in-flight slot (task 111 M1). */
fun interface PosterEnqueue {
    fun enqueue(url: String, widthPx: Int, heightPx: Int, onComplete: () -> Unit): (() -> Unit)?
}

/**
 * The prefetch queue: card-sized requests, at most [maxInFlight] in flight, one request per URL per
 * session (Coil would serve a repeat from cache anyway), and [cancelPending] for when the viewer is
 * moving faster than the artwork can land. An entry leaves [pending] the moment its request completes,
 * so a finished fetch never occupies an in-flight slot (task 111 M1).
 */
class PosterPrefetcher(
    private val widthPx: Int,
    private val heightPx: Int,
    private val enqueue: PosterEnqueue,
    private val maxInFlight: Int = POSTER_PREFETCH_IN_FLIGHT,
) {
    private val pending = HashMap<String, () -> Unit>()
    private val requested = LinkedHashSet<String>()

    val pendingCount: Int get() = pending.size

    fun prefetch(urls: List<String>) {
        for (url in urls) {
            if (url.isBlank() || url in pending || url in requested) continue
            if (pending.size >= maxInFlight) break
            requested.add(url)
            var completed = false
            val cancel = runCatching {
                enqueue.enqueue(url, widthPx, heightPx) {
                    completed = true
                    pending.remove(url)
                }
            }.getOrNull()
            if (cancel != null && !completed) pending[url] = cancel
        }
        // A long session must not grow the dedupe set without limit.
        while (requested.size > maxInFlight * 8) {
            val it = requested.iterator()
            it.next()
            it.remove()
        }
    }

    /** Drop everything still in flight (fast scroll, rows rebuilt, screen left). */
    fun cancelPending() {
        pending.values.forEach { runCatching { it() } }
        requested.removeAll(pending.keys)
        pending.clear()
    }

    fun dispose() {
        cancelPending()
        requested.clear()
    }
}

/** The app-wide Coil loader, warmed at the pixel size a [cardWidth] card decodes at (2:3 posters). */
@Composable
fun rememberPosterPrefetcher(cardWidth: Dp, maxInFlight: Int = POSTER_PREFETCH_IN_FLIGHT): PosterPrefetcher {
    val context = LocalContext.current
    val density = LocalDensity.current
    val loader = remember(context) { SingletonImageLoader.get(context) }
    val widthPx = with(density) { cardWidth.roundToPx() }
    val heightPx = with(density) { (cardWidth * 1.5f).roundToPx() }
    return remember(loader, widthPx, heightPx, maxInFlight) {
        PosterPrefetcher(
            widthPx = widthPx,
            heightPx = heightPx,
            maxInFlight = maxInFlight,
            enqueue = PosterEnqueue { url, w, h, onComplete ->
                val job = loader.enqueue(ImageRequest.Builder(context).data(url).size(w, h).build())
                val cancel: () -> Unit = { job.dispose() }
                job.job.invokeOnCompletion { onComplete() }
                cancel
            },
        )
    }
}

/**
 * Warms the posters around [focus] while the viewer moves through [rows]. Debounced by
 * [POSTER_PREFETCH_SETTLE_MS]; a fast jump cancels what is still in flight first, and everything
 * outstanding is cancelled when the screen goes.
 */
@Composable
fun PosterPrefetch(rows: List<List<String?>>, focus: PosterFocus?, cardWidth: Dp) {
    val prefetcher = rememberPosterPrefetcher(cardWidth)
    var previous by remember(prefetcher) { mutableStateOf<PosterFocus?>(null) }
    DisposableEffect(prefetcher) { onDispose { prefetcher.dispose() } }
    LaunchedEffect(focus, rows) {
        val now = focus ?: return@LaunchedEffect
        if (isFastPosterScroll(previous, now)) prefetcher.cancelPending()
        previous = now
        delay(POSTER_PREFETCH_SETTLE_MS)
        prefetcher.prefetch(posterPrefetchUrls(rows, now, cardWidthDp = cardWidth.value.toInt()))
    }
}
