package com.yodesla.omniverse.feature.vod

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.paging.compose.LazyPagingItems
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.designsystem.BrandWordmark
import com.yodesla.omniverse.designsystem.CategoryBrand
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.PosterFocus
import com.yodesla.omniverse.designsystem.PosterPrefetch
import com.yodesla.omniverse.designsystem.displayTitle

/** How a service's home is arranged: row names in its voice and its signature touches. */
internal data class BrandPlan(
    val top10: String,
    val rated: String,
    val fresh: String,
    val vault: String,
    val more: String,
    /** Netflix-style giant rank numerals on the top row. */
    val rankNumerals: Boolean,
    /** Overline beside the service mark above the spotlight title, in the service's voice. */
    val overline: String,
)

internal fun plan(brand: CategoryBrand, kindLabel: String): BrandPlan {
    val kind = kindLabel.uppercase()
    return when (brand) {
        CategoryBrand.NETFLIX -> BrandPlan("Top 10 $kindLabel in this collection", "Critically acclaimed", "New releases", "Blockbusters from the vault", "Trending now", true, kind)
        CategoryBrand.DISNEY_PLUS -> BrandPlan("Recommended for you", "Top rated", "New to Disney+", "Timeless classics", "Explore more", false, kind)
        CategoryBrand.PRIME_VIDEO -> BrandPlan("Top 10 here", "Highly rated", "Recently released", "Throwbacks", "More to watch", true, kind)
        CategoryBrand.APPLE_TV_PLUS -> BrandPlan("Top chart", "Critics' picks", "New releases", "From the archive", "Explore", false, kind)
        CategoryBrand.HULU -> BrandPlan("Top 10 today in this collection", "Fan favourites", "New on Hulu", "Classics", "Keep exploring", true, kind)
        CategoryBrand.MAX -> BrandPlan("Top 10 here", "Critically acclaimed", "Just added", "Iconic classics", "Discover more", true, kind)
        CategoryBrand.PARAMOUNT_PLUS -> BrandPlan("Top 10 on Paramount+", "Popular picks", "New arrivals", "Throwback favourites", "More to explore", true, kind)
        CategoryBrand.PEACOCK -> BrandPlan("Top 10 on Peacock", "Highly rated", "New on Peacock", "Throwbacks", "Binge-worthy", true, kind)
        CategoryBrand.CRUNCHYROLL -> BrandPlan("Top picks for you", "Most popular", "Newly added", "Classics", "Browse all", false, kind)
    }
}

/** Fixed spotlight height: cinematic, but still leaves a full poster row in view below it. */
private val SpotlightHeight = 220.dp
/** Once focus leaves the top row the spotlight collapses to a slim strip: 2+ rows stay in view (Kory). */
private val SpotlightCompactHeight = 64.dp
private val PosterWidth = 104.dp

/** Every poster on a service page opens the same hold-OK menu without threading it through each row. */
internal val LocalPosterMenu = androidx.compose.runtime.staticCompositionLocalOf<(PosterRow) -> Unit> { {} }

/**
 * Service-style home for a branded category, laid out like the service's own TV app: a fixed
 * spotlight that follows whichever title has focus, over clean caption-free poster rows built
 * from the category's own titles (in provider order; "Top 10" means this collection's first ten,
 * not a popularity chart). Loads more pages as rows are built.
 */
@Composable
internal fun BrandLayout(
    brand: CategoryBrand,
    items: LazyPagingItems<PosterRow>,
    kindLabel: String,
    firstFocus: FocusRequester,
    onOpen: (ContentKey) -> Unit,
    /** Hold OK on any poster: the title menu (Resume / Start over / Details / My List). */
    onMenu: (PosterRow) -> Unit = {},
    /** Rotten Tomatoes scores for the spotlight title (null when unknown). */
    scores: suspend (PosterRow) -> com.yodesla.omniverse.core.data.metadata.RtScores? = { null },
    /** Netflix (84c) + Crunchyroll (86b): resume cards. */
    continueWatching: List<ContinuePosterUi> = emptyList(),
    /** Task 84c (Netflix only): My List posters, profile name, current selection, CW hold-OK menu. */
    myList: List<PosterRow> = emptyList(),
    profileName: String? = null,
    selection: Pair<com.yodesla.omniverse.core.model.SourceId?, String?>? = null,
    /** Task 92 (Crunchyroll only): open the full anime grid for this kind across all sources. */
    onAnimeLibrary: () -> Unit = {},
    /** Task 117 (every brand): open this destination's Library grid. */
    onLibrary: () -> Unit = {},
    onCwMenu: (ContinuePosterUi) -> Unit = {},
    /** Resume (false) / start over (true) — the hero's START / CONTINUE WATCHING button. */
    onPlay: (ContentKey, Boolean) -> Unit = { _, _ -> },
    inMyList: (PosterRow) -> kotlinx.coroutines.flow.Flow<Boolean> = { kotlinx.coroutines.flow.flowOf(false) },
    onToggleMyList: (PosterRow, Boolean) -> Unit = { _, _ -> },
    /** Every name the provider gives this title: its own, its category's, and its other copies. */
    titleNames: suspend (PosterRow) -> List<String> = { emptyList() },
    /** Live parental visibility (a locked title never reaches the billboard). */
    visible: (PosterRow) -> Boolean = { true },
    /**
     * Task 87b: TMDB art normally arrives a second or two after the rows are built (a first visit has
     * nothing cached). This re-fills the art on rows already on screen. It returns the same rows in the
     * same order, so paging, scroll position and focus are untouched — only the art appears.
     */
    remapArt: suspend (List<PosterRow>) -> List<PosterRow> = { it },
    /** Bumps when the TMDB enricher stores a batch of art; drives [remapArt]. */
    artVersion: Long = 0L,
): Unit {
    // Task 86b: Crunchyroll has its own layout (the Crunchyroll TV app's look). Other brands unchanged.
    if (brand == CategoryBrand.CRUNCHYROLL) {
        // Task 121: the Crunchyroll hero shows the same RT badges the spotlight and Netflix billboard do.
        CrunchyrollLayout(brand, items, kindLabel, firstFocus, onOpen, onMenu, continueWatching, onPlay, inMyList, onToggleMyList, titleNames, visible, remapArt, artVersion, selection = selection, onAnimeLibrary = onAnimeLibrary, scores = scores)
        return
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPosterMenu provides onMenu) {
    // Pull in enough of the category to fill the rows (paging loads lazily on access).
    LaunchedEffect(items.itemCount) {
        if (items.itemCount in 1 until 160) items[items.itemCount - 1]
    }
    // Paging snapshots can repeat an item while pages settle; lazy keys must be unique.
    val basePool = items.itemSnapshotList.items.distinctBy { it.uid() }
    val pool by androidx.compose.runtime.produceState(basePool, basePool.map { it.uid() }, artVersion) {
        value = remapArt(basePool)
    }
    val p = plan(brand, kindLabel)
    // Task 84c: Netflix gets its own native-app layout (billboard + landscape rows).
    if (brand == CategoryBrand.NETFLIX) {
        NetflixLayout(pool, p, kindLabel, firstFocus, onOpen, scores, continueWatching, myList, profileName, selection, onCwMenu, onLibrary)
        return@CompositionLocalProvider
    }
    val top = pool.take(10)
    // Task 102: the shelves in display order, built once per pool so the renderer and the prefetcher
    // walk the same rows — and the prefetch list keeps its identity across recompositions.
    val shelves = remember(pool, brand, kindLabel) { brandShelves(p, pool) }
    var focused by remember(brand) { mutableStateOf<PosterRow?>(null) }
    val shown = focused ?: pool.firstOrNull { !it.posterUrl.isNullOrBlank() } ?: pool.firstOrNull()
    val rank = shown?.let { s -> top.indexOfFirst { it.uid() == s.uid() }.takeIf { it >= 0 }?.plus(1) }
    var inTopRow by remember(brand) { mutableStateOf(true) }
    val onTopFocus: (PosterRow) -> Unit = { focused = it; inTopRow = true }
    val onFocus: (PosterRow) -> Unit = { focused = it; inTopRow = false }
    val spotlightHeight by androidx.compose.animation.core.animateDpAsState(
        if (inTopRow) SpotlightHeight else SpotlightCompactHeight, tween(260), label = "spotlightHeight")
    var posterFocus by remember(brand) { mutableStateOf<PosterFocus?>(null) }
    PosterPrefetch(
        rows = remember(shelves) { shelves.map { shelf -> shelf.rows.map { it.posterUrl } } },
        focus = posterFocus,
        cardWidth = PosterWidth,
    )

    Column(Modifier.fillMaxSize()) {
        Spotlight(shown, brand, p, rank, Modifier.fillMaxWidth().height(spotlightHeight), scores, compact = !inTopRow, onLibrary = onLibrary)
        // Rows scroll under the fixed spotlight; the focused row sits at the top of the list.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // Leading space keeps the focused row's title (and the focus-scaled poster) in view.
            FocusPivot(parentFraction = 0f, leading = 64.dp) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = OmniSpacing.s, top = OmniSpacing.s, bottom = OmniSpacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                ) {
                    itemsIndexed(shelves, key = { _, shelf -> shelf.key }) { shelfIndex, shelf ->
                        Shelf(
                            shelf.title,
                            leading = if (shelfIndex == 0) OmniSpacing.tvSide + 100.dp else OmniSpacing.tvSide,
                        ) {
                            itemsIndexed(shelf.rows, key = { _, row -> shelf.key + "-" + row.uid() }) { cardIndex, row ->
                                val mod = (if (shelfIndex == 0 && cardIndex == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                    .onFocusChanged { if (it.isFocused) posterFocus = PosterFocus(shelfIndex, cardIndex) }
                                if (shelf.ranked) RankedCard(cardIndex + 1, row, mod, onTopFocus) { onOpen(row.key) }
                                else BrandPoster(row, mod, if (shelfIndex == 0) onTopFocus else onFocus) { onOpen(row.key) }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** One poster row on a brand page, in display order (task 102: the prefetcher's rows). */
internal data class BrandShelf(val key: String, val title: String, val rows: List<PosterRow>, val ranked: Boolean)

/** The brand page's shelves: Top 10, then rated / fresh / vault when they have enough titles, then the rest. */
internal fun brandShelves(p: BrandPlan, pool: List<PosterRow>): List<BrandShelf> {
    val top = pool.take(10)
    val rated = pool.filter { (it.rating ?: 0f) >= 7f }.sortedByDescending { it.rating }.take(20)
    val fresh = pool.filter { it.year != null }.sortedByDescending { it.year }.take(20)
    val vault = pool.filter { (it.year ?: 9999) < 2005 }.take(20)
    val out = ArrayList<BrandShelf>()
    if (top.isNotEmpty()) out += BrandShelf("top", p.top10, top, p.rankNumerals)
    if (rated.size >= 4) out += BrandShelf("rated", p.rated, rated, false)
    if (fresh.size >= 4) out += BrandShelf("fresh", p.fresh, fresh, false)
    if (vault.size >= 4) out += BrandShelf("vault", p.vault, vault, false)
    pool.drop(10).chunked(20).forEachIndexed { i, chunk ->
        out += BrandShelf("more-$i", if (i == 0) p.more else "${p.more} · ${i + 1}", chunk, false)
    }
    return out
}

/** The service-app "preview header": key art, the service mark, and the focused title's details. */
@Composable
private fun Spotlight(row: PosterRow?, brand: CategoryBrand, p: BrandPlan, rank: Int?, modifier: Modifier,
                      scores: suspend (PosterRow) -> com.yodesla.omniverse.core.data.metadata.RtScores? = { null },
                      compact: Boolean = false, onLibrary: () -> Unit = {}) {
    val c = OmniTheme.colors
    // Looked up once focus settles on a title (fast scrolling cancels the pending lookup).
    val rt by androidx.compose.runtime.produceState<com.yodesla.omniverse.core.data.metadata.RtScores?>(null, row?.key) {
        value = null
        val r = row ?: return@produceState
        kotlinx.coroutines.delay(350)
        value = scores(r)
    }
    val t = OmniTheme.type
    Crossfade(row, animationSpec = tween(320), label = "spotlight", modifier = modifier) { r ->
        Box(Modifier.fillMaxSize().padding(end = OmniSpacing.tvSide)) {
            if (r == null) return@Box
            if (!compact && !r.posterUrl.isNullOrBlank()) {
                // Wide crop of the key art on the right, melted into the stage on the left and bottom.
                // The art fades itself out (alpha mask), so it melts into whatever stage is behind it
                // (gradients, glow) instead of sitting in a solid box.
                AsyncImage(
                    model = r.posterUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.62f)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.5f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                        },
                )
                // Task 69: the spotlight's overline/title/meta sit over this art. The art's own
                // left fade is not enough (still ~78% alpha where the meta row ends), so a scrim
                // holds the text column near-opaque and only clears past the text's right edge.
                Box(
                    Modifier.fillMaxSize()
                        .background(Brush.horizontalGradient(0f to c.background, 0.62f to c.background.copy(alpha = 0.92f), 0.8f to Color.Transparent)),
                )
            }
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = OmniSpacing.m).widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
            ) {
                if (!compact) Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandWordmark(brand, 22.dp)
                    Spacer(Modifier.width(OmniSpacing.s))
                    Text(p.overline, style = t.overline, color = c.textSecondary, maxLines = 1)
                    // Task 117: the Library entry rides the wordmark row; D-pad Up from the top
                    // shelf reaches it, OK opens the full paged grid.
                    com.yodesla.omniverse.designsystem.OmniButton(
                        "Library", onLibrary, Modifier.padding(start = OmniSpacing.l),
                    )
                }
                // Task 84d: the official TMDB title logo replaces the text title when cached
                // (fit, left-aligned, capped); the text stays as the fallback.
                if (r.logoUrl != null) {
                    AsyncImage(
                        model = r.logoUrl, contentDescription = r.name, contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier.heightIn(max = if (compact) 64.dp else 160.dp)
                            .widthIn(max = if (compact) 220.dp else 420.dp),
                    )
                } else {
                    Text(
                        displayTitle(r.name, null), maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, color = c.textPrimary,
                        style = t.display.copy(fontSize = if (compact) 26.sp else 44.sp, lineHeight = if (compact) 30.sp else 48.sp, fontWeight = FontWeight.Bold,
                            shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 12f)),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rank != null && p.rankNumerals) {
                        Box(Modifier.background(c.accent, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text("TOP 10", style = t.overline.copy(letterSpacing = 1.sp), color = Color.White)
                        }
                        Spacer(Modifier.width(OmniSpacing.s))
                        Text("#$rank in this collection", style = t.body.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                        Spacer(Modifier.width(OmniSpacing.m))
                    }
                    Text(
                        listOfNotNull(r.year?.toString(), r.rating?.takeIf { it > 0f }?.let { "★ %.1f".format(it) }).joinToString("   ·   "),
                        style = t.body, color = c.textSecondary,
                    )
                    rt?.takeIf { r.key == row?.key }?.let { s -> RtBadges(s, Modifier.padding(start = OmniSpacing.m)) }
                }
            }
        }
    }
}

@Composable
private fun Shelf(title: String, leading: androidx.compose.ui.unit.Dp = OmniSpacing.tvSide, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Text(title, style = OmniTheme.type.title.copy(fontWeight = FontWeight.Bold), color = OmniTheme.colors.textPrimary,
            modifier = Modifier.padding(start = OmniSpacing.s, bottom = OmniSpacing.xs))
        FocusPivot(leading = leading) {
            LazyRow(contentPadding = PaddingValues(start = OmniSpacing.s, end = OmniSpacing.tvSide, top = OmniSpacing.s, bottom = OmniSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), content = content)
        }
    }
}

/** Caption-free key art, like the services' own TV apps; the spotlight carries the title. */
@Composable
private fun BrandPoster(row: PosterRow, modifier: Modifier, onFocus: (PosterRow) -> Unit, textInset: androidx.compose.ui.unit.Dp = 0.dp, onClick: () -> Unit) {
    val menu = LocalPosterMenu.current
    FocusCard(
        onClick = onClick, glass = false, onLongClick = { menu(row) },
        modifier = modifier.width(PosterWidth).aspectRatio(2f / 3f).onFocusChanged { if (it.hasFocus) onFocus(row) },
    ) {
        if (row.posterUrl.isNullOrBlank()) {
            Box(Modifier.fillMaxSize().background(OmniTheme.colors.elevated).padding(start = OmniSpacing.s + textInset, end = OmniSpacing.s, top = OmniSpacing.s, bottom = OmniSpacing.s), contentAlignment = Alignment.BottomStart) {
                Text(displayTitle(row.name, row.year), style = OmniTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = OmniTheme.colors.textPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        } else {
            AsyncImage(model = row.posterUrl, contentDescription = row.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Netflix's signature: a giant outlined rank numeral overlapping the poster's left edge. Drawn
 * after the poster (and above it in z) so the artwork never hides the number.
 */
@Composable
internal fun RankedCard(rank: Int, row: PosterRow, modifier: Modifier, onFocus: (PosterRow) -> Unit, solidColor: Color? = null, onClick: () -> Unit) {
    // Kory: numerals may bite into the ARTWORK, never the title text, and must look crisp on a 4K TV.
    // So: a real Manrope Bold (no synthetic Black), a vector stroke outline with no blur halo, the
    // glyph's bottom pinned to the poster's bottom edge (trimmed line box), and text-only posters
    // indent their title past the overlap.
    val numeral = TextStyle(
        fontFamily = OmniTheme.type.family, fontWeight = FontWeight.Bold, fontSize = 128.sp, lineHeight = 128.sp,
        letterSpacing = (-6).sp,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            androidx.compose.ui.text.style.LineHeightStyle.Alignment.Bottom, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both),
    )
    Box(Modifier.width(PosterWidth + if (rank == 10) 92.dp else 58.dp).height(PosterWidth * 1.5f)) {
        Box(Modifier.align(Alignment.TopEnd)) { BrandPoster(row, modifier, onFocus, textInset = 26.dp, onClick = onClick) }
        RankNumeral(rank, solidColor, Modifier.align(Alignment.BottomStart).zIndex(1f).fillMaxHeight().width(if (rank == 10) 150.dp else 96.dp))
    }
}

/**
 * The Top 10 numeral as real vector geometry: the glyph outline is taken from Manrope Bold and drawn
 * as a path (anti-aliased fill + stroke) at the screen's own resolution. Large text drawn with a
 * stroke draw-style goes through the glyph cache and looked soft and grainy on a 4K TV.
 */
@Composable
private fun RankNumeral(rank: Int, solidColor: Color?, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val typeface = remember {
        runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, com.yodesla.omniverse.designsystem.R.font.manrope_bold) }.getOrNull()
            ?: android.graphics.Typeface.DEFAULT_BOLD
    }
    val glassTop = OmniTheme.colors.glassFocusTop
    val glassBottom = OmniTheme.colors.glassBottom
    val sheen = OmniTheme.colors.accent
    androidx.compose.foundation.Canvas(modifier) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = size.height * 0.8f
            letterSpacing = -0.05f
        }
        val text = rank.toString()
        val raw = android.graphics.Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, raw)
        val b = android.graphics.RectF().also { raw.computeBounds(it, true) }
        // Shrink only if the number would be wider than its slot; bottom-left aligned to the poster.
        val stroke = 2.dp.toPx()
        val scale = minOf(1f, (size.width - stroke * 2) / b.width())
        val m = android.graphics.Matrix().apply {
            postTranslate(-b.left, -b.bottom)
            postScale(scale, scale)
            postTranslate(stroke, size.height - stroke)
        }
        raw.transform(m)
        val path = raw.asComposePath()
        val top = size.height - b.height() * scale
        val h = size.height - top
        if (solidColor != null) {
            // Solid brand numeral (Netflix): full-strength colour, a darker crisp rim and an offset shadow. No glass.
            // Brand-red glass: true brand colour as the body (slightly see-through), then the same glass
            // treatment as the other pages — specular highlight on the upper half and a bright rim.
            translate(left = 3.dp.toPx(), top = 4.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.5f)) }
            drawPath(path, Brush.verticalGradient(listOf(solidColor.copy(alpha = 0.86f), solidColor.copy(alpha = 0.74f)), startY = top, endY = size.height))
            clipPath(path) {
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.30f), 0.42f to Color.White.copy(alpha = 0.06f), 0.43f to Color.Transparent,
                    startY = top, endY = top + h))
            }
            drawPath(path, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.85f), solidColor.copy(alpha = 0.9f)), startY = top, endY = size.height),
                style = Stroke(width = stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            return@Canvas
        }
        // 3D glass, like the Explore panels: a crisp offset shadow for depth, a translucent tinted
        // body, a soft brand sheen, a specular highlight across the upper half and a bright rim.
        translate(left = 3.dp.toPx(), top = 4.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.55f)) }
        drawPath(path, Brush.verticalGradient(listOf(glassTop.copy(alpha = 0.92f), glassBottom.copy(alpha = 0.88f)), startY = top, endY = size.height))
        drawPath(path, Brush.linearGradient(listOf(sheen.copy(alpha = 0.45f), Color.Transparent),
            start = androidx.compose.ui.geometry.Offset(0f, top), end = androidx.compose.ui.geometry.Offset(size.width, size.height)))
        clipPath(path) {
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.34f), 0.42f to Color.White.copy(alpha = 0.06f), 0.43f to Color.Transparent,
                startY = top, endY = top + h))
        }
        drawPath(path, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f)), startY = top, endY = size.height),
            style = Stroke(width = stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

internal fun PosterRow.uid() = "${key.sourceId.value}:${key.remoteId.value}"
