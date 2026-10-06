package com.yodesla.omniverse.feature.vod

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.designsystem.BrandWordmark
import com.yodesla.omniverse.designsystem.CategoryBrand
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.OmniType
import com.yodesla.omniverse.designsystem.LocalVisualTier
import com.yodesla.omniverse.designsystem.VisualTier
import com.yodesla.omniverse.designsystem.displayTitle
import com.yodesla.omniverse.designsystem.touchClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * Crunchyroll's own home layout (task 86b), reached from [BrandLayout] for CRUNCHYROLL only: a
 * black page, a rotating billboard of five titles, and square poster rows — the Crunchyroll TV
 * app's shape, not the app's usual spotlight-and-glass look. Rules live in [CrunchyrollRules].
 */
@Composable
internal fun CrunchyrollLayout(
    brand: CategoryBrand,
    items: LazyPagingItems<PosterRow>,
    kindLabel: String,
    firstFocus: FocusRequester,
    onOpen: (ContentKey) -> Unit,
    /** Hold OK on any card: the same title menu every other service page uses. */
    onMenu: (PosterRow) -> Unit,
    continueWatching: List<ContinuePosterUi>,
    onPlay: (ContentKey, Boolean) -> Unit,
    inMyList: (PosterRow) -> Flow<Boolean>,
    onToggleMyList: (PosterRow, Boolean) -> Unit,
    titleNames: suspend (PosterRow) -> List<String>,
    visible: (PosterRow) -> Boolean,
    /** Task 87b: re-fill art on rows already on screen (see [BrandLayout]). */
    remapArt: suspend (List<PosterRow>) -> List<PosterRow> = { it },
    /** Bumps when the TMDB enricher stores art; drives [remapArt]. */
    artVersion: Long = 0L,
    /** Task 84l (Kory): the page's (source, category); CW cards are filtered to this brand category. */
    selection: Pair<com.yodesla.omniverse.core.model.SourceId?, String?>? = null,
    /** Task 92: open the full anime grid — every anime title of this kind across all sources. */
    onAnimeLibrary: () -> Unit = {},
) {
    LaunchedEffect(items.itemCount) { if (items.itemCount in 1 until 160) items[items.itemCount - 1] }
    val basePool = items.itemSnapshotList.items.distinctBy { it.uid() }
    // Task 87b: on a first visit the rows are built before the TMDB prefetch has finished, so they
    // arrive with no art. When the enricher stores a batch it bumps [artVersion] and the rows already
    // on screen are re-mapped in place — same rows, same order, same keys, so paging, scroll position
    // and focus are untouched and the art simply appears.
    val pool by produceState(basePool, basePool.map { it.uid() }, artVersion) {
        value = remapArt(basePool)
    }
    val p = plan(brand, kindLabel)
    val featured = remember(pool) { crunchyrollFeatured(pool, visible) }
    // Task 84l (Kory): Continue Watching on this page shows only titles that belong to this
    // category — the poster's own category, or the same title (exact key / exact TMDB id) in it.
    val cw = cwForBrowseCategory(continueWatching, pool, selection)

    var heroIndex by remember(selection) { mutableIntStateOf(0) }
    var heroFocused by remember { mutableStateOf(false) }
    var focusedPoster by remember(selection) { mutableStateOf<PosterRow?>(null) }
    // The billboard is D-pad controlled, not a timer. An idle timer also ran while detail screens
    // or the category rail had focus, making the art appear to cycle on return.
    val onHeroPage: (Int) -> Unit = { delta ->
        if (featured.size > 1) {
            heroIndex = crunchyrollHeroStep(heroIndex, featured.size, delta)
        }
    }
    val hero = featured.getOrNull(heroIndex.coerceIn(0, (featured.size - 1).coerceAtLeast(0)))
    // Art enrichment can replace a row without changing its identity; use the newest row so a
    // focused poster gains its backdrop as soon as the artwork arrives.
    val focusedArt = focusedPoster?.let { focused ->
        pool.firstOrNull { it.uid() == focused.uid() } ?: focused
    }
    val backdrop = crunchyrollBackdropRow(hero, focusedArt, heroFocused)
    val heroCard = cw.firstOrNull { it.poster.uid() == hero?.uid() }
    // The category's own name counts too: providers label audio in the category ("Crunchyroll (dub)").
    val namesFor: suspend (PosterRow) -> List<String> = remember(kindLabel) { { row -> listOfNotNull(kindLabel) + titleNames(row) } }

    // Page entry: a 300 ms fade, nothing else.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(300), label = "crEntry")
    val cinematic = LocalVisualTier.current == VisualTier.CINEMATIC

    val rows = crunchyrollRows(pool, cw, p, stringResource(R.string.crunchyroll_continue_watching))
    // Task 84l (Kory): ONE vertical scroll — the hero is the first item (~58% of the screen) and
    // scrolls away into the rows; the backdrop stays behind the top of the page and fades as the
    // viewer scrolls down.
    val listState = rememberLazyListState()
    val scrollProgress by remember { derivedStateOf {
        val heroInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == CrunchHeroKey }
        when {
            heroInfo == null -> 1f
            heroInfo.offset <= 0 -> 0f
            else -> (heroInfo.offset.toFloat() / heroInfo.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    } }
    // The page's row pivot pulls a focused item to the very top; for the hero that hid the title and
    // left START WATCHING at y = 0. Whenever the hero has focus, show the whole hero.
    LaunchedEffect(heroFocused) { if (heroFocused) { delay(40); listState.animateScrollToItem(0) } }
    val backdropAlpha by animateFloatAsState(1f - 0.55f * scrollProgress, tween(200), label = "crScrollFade")
    Box(Modifier.fillMaxSize().background(Color.Black).alpha(alpha)) {
        // Task 87b: the hero art is a page-level layer behind the top ~80% of the screen (not inside
        // the hero box), alpha-masked with a left fade and a long bottom fade, Ken Burns kept inside a
        // clip — so it bleeds softly under the first row and has no visible edge anywhere.
        CrunchyrollHeroBackdrop(backdrop, cinematic, Modifier.align(Alignment.TopEnd).fillMaxWidth().fillMaxHeight(0.80f).alpha(backdropAlpha))
        BoxWithConstraints(
            Modifier.fillMaxSize(),
        ) {
            val heroHeight = maxHeight * 0.64f
            FocusPivot(parentFraction = 0f, leading = 64.dp) /* room for the focused row's title */ {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = CrunchSide, end = CrunchSide, top = 0.dp, bottom = 64.dp),
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.l),
                ) {
                    item(key = CrunchHeroKey) {
                        CrunchyrollHero(
                            row = hero, brand = brand, card = heroCard, featuredCount = featured.size,
                            index = heroIndex, titleNames = namesFor, categoryName = kindLabel,
                            onPlay = onPlay, inMyList = inMyList, onToggleMyList = onToggleMyList,
                            onHeroFocus = { heroFocused = it }, onHeroPage = onHeroPage, firstFocus = firstFocus,
                            onAnimeLibrary = onAnimeLibrary,
                            modifier = Modifier.fillMaxWidth().height(heroHeight),
                        )
                    }
                    rows.forEach { spec ->
                        item(key = spec.key) {
                            CrunchyrollShelf(spec.title) {
                                if (spec.landscape) {
                                    items(spec.items.map { it to cw.firstOrNull { c -> c.poster.uid() == it.uid() } },
                                        key = { "cw-" + it.first.uid() }) { (row, card) ->
                                        CrunchyrollContinueCard(
                                            row = row, card = card, modifier = Modifier,
                                            titleNames = namesFor,
                                            onFocus = { focusedPoster = row },
                                            onClick = { onOpen(row.key) }, onMenu = { onMenu(row) },
                                        )
                                    }
                                } else {
                                    items(spec.items, key = { spec.key + "-" + it.uid() }) { row ->
                                        CrunchyrollPosterCard(
                                            row = row, titleNames = namesFor, modifier = Modifier,
                                            onFocus = { focusedPoster = row },
                                            onClick = { onOpen(row.key) }, onMenu = { onMenu(row) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
/**
 * Task 87b: the hero art as a page-level layer, drawn the way Netflix's billboard art is — the image
 * itself alpha-masked (left edge and a long bottom fade to transparent) so it melts into the black
 * page with no visible edge, and the slow Ken Burns zoom happening inside a clip so the scaled
 * picture can never poke out past the fades. Same crossfade the hero carousel already had.
 */
@Composable
private fun CrunchyrollHeroBackdrop(row: PosterRow?, cinematic: Boolean, modifier: Modifier) {
    var zoom by remember { mutableStateOf(1f) }
    LaunchedEffect(row?.uid()) {
        zoom = 1f
        if (cinematic && row != null) {
            delay(32)
            zoom = 1.06f
        }
    }
    val zoomNow by animateFloatAsState(zoom, tween(12_000, easing = LinearEasing), label = "crKenBurns")
    Crossfade(row, animationSpec = tween(320), label = "crHeroArt", modifier = modifier) { r ->
        val art = r?.let { crunchyrollKeyArt(it) }
        if (art == null) return@Crossfade
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Left: hidden under the hero text, easing in across the middle of the screen.
                    drawRect(
                        Brush.horizontalGradient(
                            0.20f to Color.Transparent, 0.46f to Color.Black.copy(alpha = 0.6f), 0.74f to Color.Black,
                            startX = 0f, endX = size.width,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                    // Bottom: a long, soft fade so the art bleeds under the first row.
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black, 0.45f to Color.Black, 0.78f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent,
                            startY = 0f, endY = size.height,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .clipToBounds(),
        ) {
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                // Full width: the DstIn mask above does the left fade. The old 72%-wide image started at
                // x = 28% where the mask was already ~30% opaque, which drew a hard vertical edge.
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = zoomNow; scaleY = zoomNow; transformOrigin = TransformOrigin(0.7f, 0.3f) },
            )
        }
    }
}

/** The billboard: key art on the right, the title's own metadata and actions on the left. */
@Composable
private fun CrunchyrollHero(
    row: PosterRow?,
    brand: CategoryBrand,
    card: ContinuePosterUi?,
    featuredCount: Int,
    index: Int,
    titleNames: suspend (PosterRow) -> List<String>,
    categoryName: String,
    onPlay: (ContentKey, Boolean) -> Unit,
    inMyList: (PosterRow) -> Flow<Boolean>,
    onToggleMyList: (PosterRow, Boolean) -> Unit,
    onHeroFocus: (Boolean) -> Unit,
    /** Task 84l (Kory): Right past the "+" button / Left from START WATCHING pages the billboard. */
    onHeroPage: (Int) -> Unit,
    firstFocus: FocusRequester,
    /** Task 92: the "Anime library" button beside the wordmark. */
    onAnimeLibrary: () -> Unit,
    modifier: Modifier,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    // Keep one set of focus targets alive as the featured title changes. Crossfade composes both
    // old and new button rows briefly, which can steal D-pad focus and break Left/Right paging.
    Box(modifier.onFocusChanged { onHeroFocus(it.hasFocus) }, contentAlignment = Alignment.Center) {
            val r = row
            if (r == null) {
                Text(stringResource(R.string.crunchyroll_empty), style = t.body, color = c.textSecondary)
                return@Box
            }
            // The art itself is a page-level layer ([CrunchyrollHeroBackdrop]); this box only keeps
            // the text readable over it. Task 84l: the scrim now fades out before every edge of the
            // hero — the old one stopped at a hard bottom line, which read as a black block.
            Box(
                Modifier.fillMaxSize()
                    // The scrim is drawn INSIDE the offscreen layer so the DstIn fade below actually
                    // softens it (as a .background() before graphicsLayer it was never masked and its
                    // bottom edge showed as a hard line across the rows).
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawRect(Brush.horizontalGradient(0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.85f), 0.75f to Color.Transparent))
                        drawContent()
                        // Long, gentle bottom fade: the scrim must end with no visible line under the dots.
                        drawRect(
                            Brush.verticalGradient(0f to Color.Black, 0.40f to Color.Black, 0.75f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
            val subDub = rememberSubDubLabel(r, titleNames)
            val age = ageRatingLabel(r.name, categoryName)
            val genres = crunchyrollGenres(r.genre)
            val saved by remember(r.uid()) { inMyList(r) }.collectAsStateWithLifecycle(initialValue = false)
            val progress = card?.progress ?: 0f
            val episode = crunchyrollEpisodeLabel(card?.episodeSeason, card?.episodeNumber)
            val playLabel = if (progress > 0f) {
                stringResource(R.string.crunchyroll_continue_watching_button) + (episode?.let { " $it" } ?: "")
            } else {
                    stringResource(R.string.crunchyroll_start_watching) +
                        (if (r.key.kind == ContentKind.SERIES) " S1 E1" else "")
            }
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = CrunchSide).widthIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
            ) {
                // Task 92: the wordmark row carries the "Anime library" button — the one way into
                // every anime title of this kind, reachable with D-pad Up from the first row.
                // Focus on the Library button counts as "on the hero": the billboard must not rotate (and
                // redraw this button) while the viewer is sitting on it.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    BrandWordmark(brand, 24.dp)
                    CrunchyrollPlayButton(stringResource(R.string.crunchyroll_anime_library), Modifier) { onAnimeLibrary() }
                }
                Text(
                    displayTitle(r.name, r.year), maxLines = 2, overflow = TextOverflow.Ellipsis, color = c.textPrimary,
                    style = TextStyle(fontFamily = t.family, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-1.0).sp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                    age?.let { CrunchyrollAgeBox(it) }
                    subDub?.let { Text(it.uppercase(), style = CrunchKicker(t), color = c.textSecondary, maxLines = 1) }
                    if (genres.isNotEmpty()) Text(genres.joinToString(", "), style = t.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    r.year?.let { Text(it.toString(), style = t.body, color = c.textSecondary) }
                }
                r.plot?.let {
                    Text(it, style = t.body.copy(fontSize = 15.sp, lineHeight = 22.sp), color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                var playFocused by remember { mutableStateOf(false) }
                var bookmarkFocused by remember { mutableStateOf(false) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                    modifier = Modifier
                        .onPreviewKeyEvent { e ->
                            // Task 84l (Kory): Right past the "+" button pages to the next featured
                            // title, Left from START WATCHING pages back; both wrap and restart the timer.
                            when {
                                e.type != KeyEventType.KeyDown -> false
                                e.key == Key.DirectionRight && bookmarkFocused -> { onHeroPage(1); true }
                                e.key == Key.DirectionLeft && playFocused -> { onHeroPage(-1); true }
                                else -> false
                            }
                        },
                ) {
                    Box(Modifier.onFocusChanged { playFocused = it.hasFocus }) {
                        CrunchyrollPlayButton(playLabel, Modifier.focusRequester(firstFocus)) { onPlay(r.key, progress <= 0f) }
                    }
                    Box(Modifier.onFocusChanged { bookmarkFocused = it.hasFocus }) {
                        CrunchyrollBookmarkButton(saved, stringResource(if (saved) R.string.crunchyroll_my_list_remove else R.string.crunchyroll_my_list_add)) { onToggleMyList(r, saved) }
                    }
                }
                // Under the buttons (like the Crunchyroll app), never pinned to the box bottom where a
                // 3-line synopsis pushed the buttons into the dashes.
                CrunchyrollCarouselDots(featuredCount, index, Modifier.padding(top = OmniSpacing.s))
            }
    }
}

@Composable
private fun CrunchyrollAgeBox(label: String) {
    val c = OmniTheme.colors
    Box(Modifier.background(c.elevated, CrunchShape).padding(horizontal = OmniSpacing.s, vertical = 2.dp)) {
        Text(label, style = CrunchKicker(OmniTheme.type).copy(fontSize = 12.sp), color = c.textSecondary, maxLines = 1)
    }
}

@Composable
private fun CrunchyrollCarouselDots(count: Int, active: Int, modifier: Modifier) {
    if (count < 2) return
    val c = OmniTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
        repeat(count) { i ->
            val resting = when {
                i < active -> c.accent.copy(alpha = 0.55f)
                i > active -> c.textSecondary.copy(alpha = 0.35f)
                else -> c.textSecondary.copy(alpha = 0.25f)
            }
            Box(Modifier.width(44.dp).height(4.dp).background(resting, RoundedCornerShape(2.dp))) {
                if (i == active) Box(Modifier.fillMaxSize().background(c.accent, RoundedCornerShape(2.dp)))
            }
        }
    }
}

/** Orange rectangular button (Crunchyroll's, not the app's pill) with the uppercase kicker type. */
@Composable
private fun CrunchyrollPlayButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = OmniTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp).touchClick(onClick),
        shape = ButtonDefaults.shape(CrunchShape),
        colors = ButtonDefaults.colors(
            containerColor = c.accent, contentColor = Color.Black,
            focusedContainerColor = c.accent, focusedContentColor = Color.Black,
            pressedContainerColor = c.accent.copy(alpha = 0.85f), pressedContentColor = Color.Black,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.03f),
        contentPadding = PaddingValues(horizontal = OmniSpacing.l),
    ) {
        Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(label.uppercase(), style = CrunchKicker(OmniTheme.type).copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The square outlined bookmark: Add to My List (the same action the title menu uses). */
@Composable
private fun CrunchyrollBookmarkButton(inList: Boolean, description: String, onClick: () -> Unit) {
    val c = OmniTheme.colors
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.width(52.dp).height(52.dp).semantics { contentDescription = description }
            .onFocusChanged { focused = it.hasFocus }.touchClick(onClick),
        shape = CardDefaults.shape(shape = CrunchShape),
        scale = CardDefaults.scale(focusedScale = 1.03f),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent, contentColor = c.textPrimary,
            focusedContainerColor = c.accent, focusedContentColor = Color.Black,
            pressedContainerColor = c.elevated, pressedContentColor = c.textPrimary,
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None),
        glow = CardDefaults.glow(focusedGlow = Glow(elevationColor = c.accent.copy(alpha = 0.35f), elevation = 6.dp)),
    ) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                // Crunchyroll's outline: grey at rest, accent when focused.
                val w = if (focused) 3.dp.toPx() else 1.5.dp.toPx()
                drawRect(color = if (focused) c.accent else c.textSecondary, style = Stroke(width = w))
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (inList) "✓" else "+", style = CrunchKicker(OmniTheme.type).copy(fontSize = 22.sp), maxLines = 1)
        }
    }
}

@Composable
private fun CrunchyrollShelf(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val c = OmniTheme.colors
    Column {
        Text(
            title, color = c.textPrimary, maxLines = 1,
            style = OmniTheme.type.title.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp),
            modifier = Modifier.padding(bottom = OmniSpacing.s),
        )
        // Task 84l: in the single-scroll page a focused shelf settles 35% down the screen, so the
        // row and its title / Sub|Dub captions are fully visible (bottom padding is 64 dp).
        // Kory (2026-10-03): no sliver of a card at the right edge. Cards are sized from the row's real
        // width so a whole number fits exactly (posters ~150 dp, landscape ~280 dp).
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = OmniSpacing.m
            val poster = fitWidth(maxWidth, CrunchPosterWidth, gap)
            val landscape = fitWidth(maxWidth, CrunchLandscapeWidth, gap)
            androidx.compose.runtime.CompositionLocalProvider(LocalCrunchCardWidths provides (poster to landscape)) {
                FocusPivot(parentFraction = 0.35f, leading = 0.dp) {
                    LazyRow(
                        contentPadding = PaddingValues(bottom = OmniSpacing.s),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        content = content,
                    )
                }
            }
        }
    }
}

/** Square 2:3 poster card: 3 dp orange border and 1.05 scale on focus, title and audio line below. */
@Composable
private fun CrunchyrollPosterCard(
    row: PosterRow,
    titleNames: suspend (PosterRow) -> List<String>,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onMenu: (PosterRow) -> Unit,
) {
    val c = OmniTheme.colors
    val subDub = rememberSubDubLabel(row, titleNames)
    Column(modifier.width(LocalCrunchCardWidths.current.first)) {
        CrunchyrollCard(onClick = onClick, onLongClick = { onMenu(row) }, onFocus = onFocus,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            if (row.posterUrl.isNullOrBlank()) {
                Box(Modifier.fillMaxSize().background(c.elevated).padding(OmniSpacing.s), contentAlignment = Alignment.BottomStart) {
                    Text(displayTitle(row.name, row.year), style = OmniTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = c.textPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            } else {
                AsyncImage(model = row.posterUrl, contentDescription = row.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(OmniSpacing.xs))
        Text(displayTitle(row.name, row.year), style = OmniTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
            color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        subDub?.let {
            Text(it.uppercase(), style = CrunchKicker(OmniTheme.type).copy(fontSize = 11.sp), color = c.textSecondary, maxLines = 1)
        }
    }
}

/** Continue Watching: 16:9 episode-style card with the orange bar along its bottom edge. */
@Composable
private fun CrunchyrollContinueCard(
    row: PosterRow,
    card: ContinuePosterUi?,
    modifier: Modifier,
    titleNames: suspend (PosterRow) -> List<String>,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onMenu: (PosterRow) -> Unit,
) {
    val c = OmniTheme.colors
    val subDub = rememberSubDubLabel(row, titleNames)
    val episode = crunchyrollEpisodeLabel(card?.episodeSeason, card?.episodeNumber)
    val minutes = card?.let { crunchyrollMinutesLeft(it.positionMs, it.durationMs) }
    // Task 87b: cards use the title's second backdrop, never the hero's own art. When only one
    // backdrop exists the card keeps it but carries the title logo bottom-left so the card and the
    // hero above it still read as two different pictures.
    val art = crunchyrollCardArt(row)
    Column(modifier.width(LocalCrunchCardWidths.current.second)) {
        CrunchyrollCard(onClick = onClick, onLongClick = { onMenu(row) }, onFocus = onFocus,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            when (art) {
                is CrunchArt.WithUrl -> AsyncImage(
                    model = art.url, contentDescription = row.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                CrunchArt.None -> Box(Modifier.fillMaxSize().background(c.elevated), contentAlignment = Alignment.Center) {
                    Text("▶", style = OmniTheme.type.title, color = c.accent)
                }
            }
            if (art.needsTitleOverlay() && !row.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = row.logoUrl, contentDescription = null, contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = OmniSpacing.s, bottom = OmniSpacing.xs)
                        .heightIn(max = 44.dp).widthIn(max = 150.dp),
                )
            }
            // The resume bar sits on the card's bottom edge, in the accent.
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
                .background(c.textSecondary.copy(alpha = 0.35f))) {
                Box(Modifier.fillMaxWidth((card?.progress ?: 0f).coerceIn(0.001f, 1f)).fillMaxHeight().background(c.accent))
            }
        }
        Spacer(Modifier.height(OmniSpacing.xs))
        Text(displayTitle(row.name, row.year), style = OmniTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
            color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(episode, minutes, subDub).joinToString("  ·  "),
            style = CrunchKicker(OmniTheme.type).copy(fontSize = 11.sp), color = c.textSecondary, maxLines = 1,
        )
    }
}

/** The shared focus treatment: sharp 3 dp corners, 3 dp accent border on focus, scale 1.05. */
@Composable
private fun CrunchyrollCard(
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = OmniTheme.colors
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { state ->
            focused = state.hasFocus
            if (state.isFocused) onFocus()
        }.touchClick(onClick, onLongClick),
        shape = CardDefaults.shape(shape = CrunchShape),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        colors = CardDefaults.colors(
            containerColor = c.surface, contentColor = c.textPrimary,
            focusedContainerColor = c.elevated, pressedContainerColor = c.elevated,
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None),
        glow = CardDefaults.glow(focusedGlow = Glow(elevationColor = c.accent.copy(alpha = 0.35f), elevation = 8.dp)),
    ) {
        Box(
            Modifier.drawBehind { if (focused) drawRect(color = c.accent, style = Stroke(width = 3.dp.toPx())) },
            content = content,
        )
    }
}

/** UPPERCASE kicker: Crunchyroll's buttons and row labels, +0.5sp tracking. */
private fun CrunchKicker(t: OmniType) =
    TextStyle(fontFamily = t.family, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)

/**
 * The "Sub | Dub" line, looked up off the main thread once the card settles (fast scrolling
 * cancels the pending lookup, exactly like the spotlight's score lookup).
 */
@Composable
private fun rememberSubDubLabel(row: PosterRow, titleNames: suspend (PosterRow) -> List<String>): String? {
    val label by produceState<String?>(null, row.uid()) {
        value = null
        delay(180)
        value = subDubLabel(titleNames(row))
    }
    return label
}

/** The hero's LazyColumn item key (task 84l: the backdrop fade tracks this item's scroll). */
private const val CrunchHeroKey = "hero"

private val CrunchShape = RoundedCornerShape(3.dp)
private val CrunchSide = OmniSpacing.tvSide
private val CrunchPosterWidth = 150.dp
private val CrunchLandscapeWidth = 280.dp

/** Card widths for the current shelf (poster, landscape), sized so whole cards fill the row. */
private val LocalCrunchCardWidths = androidx.compose.runtime.staticCompositionLocalOf { CrunchPosterWidth to CrunchLandscapeWidth }

/** Width for cards near [target] wide so an exact whole number of them (with [gap]s) fills [row]. */
internal fun fitWidth(row: androidx.compose.ui.unit.Dp, target: androidx.compose.ui.unit.Dp, gap: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
    if (row <= 0.dp) return target
    val n = kotlin.math.max(1, kotlin.math.round((row + gap) / (target + gap)).toInt())
    return (row - gap * (n - 1)) / n
}
