package com.yodesla.omniverse.feature.vod

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.designsystem.BrandWordmark
import com.yodesla.omniverse.designsystem.CategoryBrand
import com.yodesla.omniverse.designsystem.displayTitle
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.LocalVisualTier
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.ProgressLine
import com.yodesla.omniverse.designsystem.touchClick
import com.yodesla.omniverse.designsystem.VisualTier

/** The native Netflix Android TV canvas: flat near-black, no stage gradients behind it. */
private val NetflixCanvas = Color(0xFF141414)
private val NetflixRed = Color(0xFFE50914)
private val NetflixCardShape = RoundedCornerShape(4.dp)

/**
 * Task 84c: the Netflix category page rebuilt as the native Netflix Android TV app — a focus-
 * following billboard over landscape 16:9 rows (Continue Watching → Top 10 → My List → curated
 * rows → Browse all) on a flat #141414 canvas. Reuses the shared Top 10 numerals, hold-OK menu
 * (LocalPosterMenu), rail auto-hide and focus restore from the caller; Crunchyroll and every
 * other brand keep the generic [BrandLayout].
 */
@Composable
internal fun NetflixLayout(
    pool: List<PosterRow>,
    plan: BrandPlan,
    kindLabel: String,
    firstFocus: FocusRequester,
    onOpen: (ContentKey) -> Unit,
    scores: suspend (PosterRow) -> com.yodesla.omniverse.core.data.metadata.RtScores?,
    continueWatching: List<ContinuePosterUi>,
    myList: List<PosterRow>,
    profileName: String?,
    selection: Pair<SourceId?, String?>?,
    onCwMenu: (ContinuePosterUi) -> Unit,
    /** Task 117: open this destination's Library grid (billboard button row). */
    onLibrary: () -> Unit = {},
) {
    val c = OmniTheme.colors
    val cinematic = LocalVisualTier.current == VisualTier.CINEMATIC
    // Task 84l (Kory): CW on a brand page keeps only titles that belong to this category — the
    // poster's own category, or the same title (exact key / exact TMDB id) in the category's pool.
    val cw = cwForBrowseCategory(continueWatching, pool, selection)
    val mine = myList.filter { inBrowseCategory(it, selection) }
    val rows = remember(cw, mine, pool, plan, profileName) { netflixRows(cw, mine, pool, plan, profileName) }
    val topUids = remember(pool) { pool.take(10).map { it.uid() }.toSet() }

    var focused by remember { mutableStateOf<PosterRow?>(null) }
    // Task 119: the browse billboard no longer carries Play / More info, so poster focus only drives
    // which title the billboard previews — there is no hero-button state left to track.
    val onFocus: (PosterRow) -> Unit = { focused = it }
    val shown = focused
        ?: cw.firstOrNull { !it.poster.posterUrl.isNullOrBlank() || !it.poster.backdropUrl.isNullOrBlank() }?.poster
        ?: pool.firstOrNull { !it.posterUrl.isNullOrBlank() || !it.backdropUrl.isNullOrBlank() }
        ?: pool.firstOrNull()

    // 400 ms fade in from black when the page appears (skipped on the LITE visual tier).
    var entered by remember { mutableStateOf(!cinematic) }
    LaunchedEffect(Unit) { entered = true }
    val entryAlpha by animateFloatAsState(if (entered) 0f else 1f, tween(400), label = "netflixEntry")

    Box(Modifier.fillMaxSize().background(NetflixCanvas)) {
        // The billboard art lives behind the whole top of the page (not inside the billboard box), so it bleeds
        // softly under the first row instead of stopping at a hard edge.
        NetflixBackdrop(shown, cinematic, Modifier.align(Alignment.TopEnd).fillMaxWidth().fillMaxHeight(0.80f))
        Column(Modifier.fillMaxSize()) {
            NetflixBillboard(shown, kindLabel, scores, cinematic, Modifier.fillMaxWidth().weight(0.55f), onLibrary)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                FocusPivot(parentFraction = 0f, leading = 44.dp) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = OmniSpacing.s, bottom = OmniSpacing.xxl),
                        verticalArrangement = Arrangement.spacedBy(OmniSpacing.l),
                    ) {
                        items(rows, key = { rowKey(it) }) { row ->
                            val first = rows.firstOrNull() == row
                            when (row) {
                                is NetflixRow.ContinueWatching -> NetflixShelf(row.title) {
                                    items(row.cards, key = { "cw-" + it.poster.uid() }) { card ->
                                        val mod = if (first && row.cards.first() == card) Modifier.focusRequester(firstFocus) else Modifier
                                        NetflixCwCard(card, topUids, mod, onFocus, onCwMenu) { onOpen(card.poster.key) }
                                    }
                                }
                                is NetflixRow.Top10 -> NetflixShelf(row.title, ranked = true) {
                                    items(row.rows.withIndex().toList(), key = { "t-" + it.value.uid() }) { (i, r) ->
                                        val mod = if (first && i == 0) Modifier.focusRequester(firstFocus) else Modifier
                                        RankedCard(i + 1, r, mod, onFocus, solidColor = NetflixRed) { onOpen(r.key) }
                                    }
                                }
                                is NetflixRow.MyList -> NetflixShelf(row.title) {
                                    items(row.rows, key = { "ml-" + it.uid() }) { r ->
                                        val mod = if (first && row.rows.first() == r) Modifier.focusRequester(firstFocus) else Modifier
                                        NetflixLandscapeCard(r, topUids, mod, onFocus, onClick = { onOpen(r.key) })
                                    }
                                }
                                is NetflixRow.Shelf -> NetflixShelf(row.title) {
                                    items(row.rows, key = { row.key + "-" + it.uid() }) { r ->
                                        val mod = if (first && row.rows.first() == r) Modifier.focusRequester(firstFocus) else Modifier
                                        NetflixLandscapeCard(r, topUids, mod, onFocus, onClick = { onOpen(r.key) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (entryAlpha > 0.001f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = entryAlpha)).zIndex(4f))
        }
    }
}

private fun rowKey(row: NetflixRow): String = when (row) {
    is NetflixRow.ContinueWatching -> "cw"
    is NetflixRow.Top10 -> "top10"
    is NetflixRow.MyList -> "mylist"
    is NetflixRow.Shelf -> row.key
}

/**
 * Billboard art behind the top of the page. The image itself is alpha-masked (left edge and bottom fade to
 * transparent), so it melts into the canvas with no visible edge, and the slow Ken Burns zoom happens inside a
 * clip so the scaled picture can never poke out past the fades.
 */
@Composable
private fun NetflixBackdrop(shown: PosterRow?, cinematic: Boolean, modifier: Modifier) {
    var zoom by remember { mutableStateOf(1f) }
    LaunchedEffect(shown?.uid()) {
        zoom = 1f
        if (cinematic && shown != null) {
            kotlinx.coroutines.delay(32)
            zoom = 1.06f
        }
    }
    val zoomNow by animateFloatAsState(zoom, tween(12_000, easing = LinearEasing), label = "kenBurns")
    Crossfade(shown, animationSpec = tween(350), label = "netflixBackdrop", modifier = modifier) { r ->
        val art = r?.let { netflixBillboardArt(it) }
        if (art !is NetflixArt.Backdrop) return@Crossfade
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Left: fully hidden under the text column, easing in across the middle of the screen.
                    drawRect(
                        Brush.horizontalGradient(
                            0.24f to Color.Transparent, 0.50f to Color.Black.copy(alpha = 0.6f), 0.78f to Color.Black,
                            startX = 0f, endX = size.width,
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                    // Bottom: a long, soft fade so the art bleeds under the first row.
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black, 0.45f to Color.Black, 0.75f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent,
                            startY = 0f, endY = size.height,
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                }
                .clipToBounds(),
        ) {
            AsyncImage(
                model = art.url, contentDescription = r.name,
                contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.78f)
                    .graphicsLayer { scaleX = zoomNow; scaleY = zoomNow; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.7f, 0.3f) },
            )
        }
    }
}

/** The billboard: the focused title's details bottom-left, buttons in hero state (art is drawn by [NetflixBackdrop]). */
@Composable
private fun NetflixBillboard(
    shown: PosterRow?,
    kindLabel: String,
    scores: suspend (PosterRow) -> com.yodesla.omniverse.core.data.metadata.RtScores?,
    cinematic: Boolean,
    modifier: Modifier,
    onLibrary: () -> Unit = {},
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val rt by androidx.compose.runtime.produceState<com.yodesla.omniverse.core.data.metadata.RtScores?>(null, shown?.key) {
        value = null
        val r = shown ?: return@produceState
        kotlinx.coroutines.delay(350)
        value = scores(r)
    }
    Box(modifier) {
        Crossfade(shown, animationSpec = tween(250), label = "netflixBillboard", modifier = Modifier.fillMaxSize()) { r ->
            Box(Modifier.fillMaxSize()) {
                if (r == null) return@Crossfade
                // Task 119: the billboard text is bounded to the billboard box (fillMaxHeight) and the
                // synopsis takes only the space that is actually left (weight + ellipsis). Before this the
                // Column was wrap-height and bottom-aligned, so a tall title (logo + meta + 3-line plot)
                // overflowed below the box into the rows region — the synopsis got sliced at that hard
                // boundary and the Play/More info pills ended up covered and unfocusable-looking.
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxHeight()
                        .padding(start = OmniSpacing.tvSide, bottom = OmniSpacing.l)
                        .widthIn(max = 620.dp),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandWordmark(CategoryBrand.NETFLIX, 20.dp)
                        Spacer(Modifier.width(OmniSpacing.s))
                        Text(
                            netflixKindLabel(kindLabel), style = t.overline.copy(letterSpacing = 2.5.sp),
                            color = c.accent, maxLines = 1,
                        )
                        // Library is a destination action, not a title action: it stays in the always-
                        // present brand row so it is visible and focusable on every shelf focus state.
                        NetflixPill("Library", filled = false) { onLibrary() }
                    }
                    // Task 84d: the official TMDB title logo replaces the text title when cached
                    // (fit, left-aligned, capped at 420x140 so the synopsis line below still fits);
                    // the text stays as the fallback.
                    if (r.logoUrl != null) {
                        AsyncImage(
                            model = r.logoUrl, contentDescription = r.name, contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = Modifier.heightIn(max = 140.dp).widthIn(max = 420.dp),
                        )
                    } else {
                        Text(
                            displayTitle(r.name, null), maxLines = 2, overflow = TextOverflow.Ellipsis, color = c.textPrimary,
                            style = t.display.copy(fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOfNotNull(r.year?.toString(), qualityTag(r.name)).joinToString("  ·  "),
                            style = t.body, color = c.textSecondary,
                        )
                        rt?.takeIf { r.key == shown?.key }?.let { s -> RtBadges(s, Modifier.padding(start = OmniSpacing.m)) }
                    }
                    // Task 84f: the synopsis under the meta line. PosterRow.plot already carries the
                    // precedence (provider plot first, cached TMDB overview as fallback), so most
                    // IPTV movies get a plot here for the first time.
                    // Task 119: it is weighted so it fills exactly the height the logo/meta/brand row
                    // left in the billboard box and ellipsizes there — it can never spill past the box
                    // into the first shelf row, so it stays legible instead of being sliced.
                    if (!r.plot.isNullOrBlank()) {
                        Text(
                            r.plot!!, style = t.body, color = c.textPrimary.copy(alpha = 0.92f),
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 4, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Task 119 (Kory): the browse billboard drops Play / More info entirely — they were
                    // focusable but drawn under the synopsis, so they read as invisible controls. The
                    // billboard is a preview here; Play/Info live on the detail screen the card opens.
                }
            }
        }
    }
}

@Composable
private fun NetflixPill(label: String, filled: Boolean, onClick: () -> Unit) {
    val c = OmniTheme.colors
    val shape = RoundedCornerShape(percent = 50)
    Card(
        onClick = onClick,
        modifier = Modifier.height(44.dp).touchClick(onClick),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        colors = CardDefaults.colors(
            containerColor = if (filled) Color.White else Color(0xB3606060),
            focusedContainerColor = if (filled) Color.White else Color(0xCC707070),
            pressedContainerColor = if (filled) Color.White else Color(0xB3606060),
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(2.dp, SolidColor(Color.White)), 0.dp, shape),
        ),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = OmniSpacing.l), contentAlignment = Alignment.Center) {
            Text(
                label, style = OmniTheme.type.title.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                color = if (filled) Color.Black else Color.White, maxLines = 1,
            )
        }
    }
}

/** Row title aligned with the billboard text; landscape cards, no captions. */
@Composable
private fun NetflixShelf(
    title: String,
    ranked: Boolean = false,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column {
        Text(
            title, style = OmniTheme.type.title.copy(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
            color = OmniTheme.colors.textPrimary,
            modifier = Modifier.padding(start = OmniSpacing.tvSide, bottom = OmniSpacing.s), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        FocusPivot(parentFraction = 0f, leading = if (ranked) OmniSpacing.tvSide + 100.dp else OmniSpacing.tvSide) {
            LazyRow(
                contentPadding = PaddingValues(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, top = OmniSpacing.s, bottom = OmniSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                content = content,
            )
        }
    }
}

/** A landscape 16:9 card: wide art when the source has it, else blurred poster fill + centered poster. */
@Composable
private fun NetflixLandscapeCard(
    row: PosterRow,
    topUids: Set<String>,
    modifier: Modifier,
    onFocus: (PosterRow) -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val c = OmniTheme.colors
    val menu = LocalPosterMenu.current
    val art = netflixCardArt(row)
    val showTitle = overlay == null && art.needsTitleOverlay()
    Card(
        onClick = onClick,
        onLongClick = onLongClick ?: { menu(row) },
        modifier = modifier.width(260.dp).height(146.dp).onFocusChanged { if (it.hasFocus) onFocus(row) }.touchClick(onClick, onLongClick),
        shape = CardDefaults.shape(NetflixCardShape),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        colors = CardDefaults.colors(containerColor = c.elevated, focusedContainerColor = c.elevated),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(3.dp, SolidColor(Color.White)), 0.dp, NetflixCardShape),
        ),
    ) {
        Box(Modifier.fillMaxSize().clip(NetflixCardShape)) {
            when (art) {
                is NetflixArt.Backdrop, is NetflixArt.BannerReuse -> AsyncImage(
                    model = art.url, contentDescription = row.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                is NetflixArt.PosterFill -> {
                    AsyncImage(
                        model = art.url, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                            .then(if (supportsRuntimeBlur()) Modifier.blur(24.dp) else Modifier),
                    )
                    if (!supportsRuntimeBlur()) Box(Modifier.fillMaxSize().background(NetflixCanvas.copy(alpha = 0.72f)))
                    AsyncImage(
                        model = art.url, contentDescription = row.name, contentScale = ContentScale.Fit,
                        alignment = Alignment.Center, modifier = Modifier.align(Alignment.Center).fillMaxHeight(),
                    )
                }
                NetflixArt.Blank -> Unit
            }
            // Reused banner art gets the official title logo (Netflix's own card treatment); a
            // fallback poster gets the plain title scrim.
            if (showTitle) {
                val logo = if (art is NetflixArt.BannerReuse) row.logoUrl else null
                if (logo != null) CardLogo(row, logo) else TitleScrim(row)
            }
            overlay?.invoke(this)
            if (row.uid() in topUids) {
                Box(Modifier.align(Alignment.TopStart).padding(OmniSpacing.s).background(c.accent, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text("TOP 10", style = OmniTheme.type.overline.copy(fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 1.sp), color = Color.White)
                }
            }
        }
    }
}

/** A Continue Watching card: landscape art, red progress, "S2:E5 · 1h 12m left". */
@Composable
private fun NetflixCwCard(
    card: ContinuePosterUi,
    topUids: Set<String>,
    modifier: Modifier,
    onFocus: (PosterRow) -> Unit,
    onMenu: (ContinuePosterUi) -> Unit,
    onClick: () -> Unit,
) {
    NetflixLandscapeCard(
        row = card.poster, topUids = topUids, modifier = modifier, onFocus = onFocus, onClick = onClick,
        onLongClick = { onMenu(card) },
    ) {
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.92f), 1f to Color.Black.copy(alpha = 0.95f)))
                .padding(start = OmniSpacing.m, end = OmniSpacing.m, top = OmniSpacing.l, bottom = OmniSpacing.s),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(displayTitle(card.poster.name, card.poster.year), style = OmniTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                color = OmniTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                card.upNextLabel ?: listOfNotNull(card.episodeLabel, timeLeftLabel(card.remainingMs)).joinToString("  ·  ")
                    .ifEmpty { if (card.episode) "Episode in progress" else "Movie in progress" },
                style = OmniTheme.type.caption, color = OmniTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // An "Up next" card has nothing in progress: no bar.
            if (card.upNextLabel == null) ProgressLine(card.progress, Modifier.fillMaxWidth())
        }
    }
}

/** Title over fallback art: the bottom-left gradient Netflix uses when the art carries no title. */
@Composable
private fun BoxScope.TitleScrim(row: PosterRow) {
    Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Black.copy(alpha = 0.85f), 1f to Color.Black.copy(alpha = 0.92f)))
            .padding(start = OmniSpacing.m, end = OmniSpacing.m, top = OmniSpacing.l, bottom = OmniSpacing.s),
    ) {
        Text(displayTitle(row.name, row.year), style = OmniTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
            color = OmniTheme.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Task 87b: the title has one backdrop only, so the card shows it with the official TMDB title logo
 * bottom-left — Netflix's own card treatment, and what keeps such a card looking different from the
 * billboard above it.
 */
@Composable
private fun BoxScope.CardLogo(row: PosterRow, logoUrl: String) {
    Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Black.copy(alpha = 0.62f), 1f to Color.Black.copy(alpha = 0.8f)))
            .padding(start = OmniSpacing.m, end = OmniSpacing.m, top = OmniSpacing.m, bottom = OmniSpacing.s),
    ) {
        AsyncImage(
            model = logoUrl, contentDescription = row.name, contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            modifier = Modifier.heightIn(max = 56.dp).widthIn(max = 180.dp),
        )
    }
}
