package com.yodesla.omniverse.feature.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yodesla.omniverse.designsystem.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce

/**
 * Cinematic For You: the focused pick fills the top of the screen (landscape art dissolving into the
 * galaxy, the TMDB title logo, why it is here), personal rows underneath. Posters stay vertical;
 * landscape art is only ever the backdrop.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun ForYouCinematic(vm: ForYouViewModel, s: ForYouState, name: String?, actions: ForYouActions, focusSwitch: Boolean, allowNews: Boolean) {
    val c = OmniTheme.colors
    val first = remember { FocusRequester() }
    val switchFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Only the stage and the art read the focused pick, so a D-pad press never recomposes the rows.
    val hero = remember(vm) { vm.focused.debounce(OmniMotion.BACKDROP_DEBOUNCE_MS) }
    val firstRow = when {
        s.rightNow.isNotEmpty() -> "now"
        s.featured.isNotEmpty() -> "featured"
        else -> null
    }
    val focus = remember { ForYouFocus() }
    CosmicBackdrop(Modifier.fillMaxSize().trackForYouFocus(focus)) {
        CinematicArt(hero, vm.focused.value, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.82f).height(344.dp)
            .graphicsLayer {
                translationX = size.width * 0.05f
                translationY = -size.height * 0.04f
            })
        Column(Modifier.fillMaxSize()) {
            // 222.dp leaves a full poster row (header, poster, title) in view underneath.
            CinematicStage(hero, vm.focused.value, name, Modifier.fillMaxWidth().height(222.dp))
            // Enough headroom over the focused poster for its one-line row header, under the hero.
            FocusPivot(parentFraction = 0f, leading = 80.dp, trailing = 48.dp) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
                    contentPadding = PaddingValues(bottom = OmniSpacing.xxl),
                ) {
                    if (s.loading) item(key = "loading") { SkeletonRow(276.dp, 100.dp, 4) }
                    if (s.rightNow.isNotEmpty()) item(key = "now") {
                        PickRow("Right now", null, s.rightNow, TileStyle.NOW, actions, first.takeIf { firstRow == "now" })
                    }
                    if (s.featured.isNotEmpty()) item(key = "featured") {
                        PickRow(
                            if (s.personal) "Top picks for you" else "New for you",
                            if (s.personal) "From what you watch, save and search" else "Save a few favourites and this page learns your taste",
                            s.featured, TileStyle.POSTER, actions, first.takeIf { firstRow == "featured" },
                        )
                    } else if (s.picksLoading) item(key = "featured-loading") { SkeletonRow(124.dp, 186.dp) }
                    items(s.shelves, key = { it.id }) { shelf -> PickRow(shelf.title, shelf.caption, shelf.picks, TileStyle.POSTER, actions) }
                    if (!s.loading) item(key = "teams") { TeamsSection(s, actions, magazine = false) }
                    if (s.myList.isNotEmpty()) item(key = "list") { PickRow("Your list", "Saved for later", s.myList, TileStyle.POSTER, actions) }
                    if (s.channels.isNotEmpty()) item(key = "channels") { PickRow("Your channels", "On air right now", s.channels, TileStyle.CHANNEL, actions) }
                    if (allowNews && s.newsEnabled) item(key = "news") { NewsSection(s, actions, magazine = false) }
                }
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(top = OmniSpacing.l, end = OmniSpacing.tvSide), verticalAlignment = Alignment.CenterVertically) {
            ForYouViewSwitch(s.layout, actions, switchFocus, if (allowNews) s.newsEnabled else null)
            Spacer(Modifier.width(OmniSpacing.l))
            Text(rememberTimeText(), style = OmniTheme.type.numeric, color = c.textSecondary)
        }
    }
    ForYouInitialFocus(focusSwitch, firstRow != null, first, switchFocus, listState, focus, target = firstRow)
}

/** The art behind the stage: landscape backdrop for titles, a big logo for channels, a framed photo for headlines. */
@Composable
private fun CinematicArt(hero: Flow<ForYouPick?>, initial: ForYouPick?, modifier: Modifier) {
    val pick by hero.collectAsStateWithLifecycle(initial)
    Crossfade(pick, modifier, animationSpec = tween(OmniMotion.BACKDROP_CROSSFADE_MS), label = "forYouArt") { p ->
        Box(Modifier.fillMaxSize()) {
            if (p != null) when (p.kind) {
                PickKind.TITLE, PickKind.RESUME -> p.card.backdropUrl?.let { HeroArtwork(it, null, Modifier.fillMaxSize()) }
                PickKind.CHANNEL -> LogoImage(p.card.image, p.card.title, Modifier.align(Alignment.CenterEnd)
                    .padding(end = OmniSpacing.tvSide + 112.dp).size(160.dp, 90.dp), plain = true)
                PickKind.GAME, PickKind.STORY -> p.card.image?.let { FramedPhoto(it, Modifier.align(Alignment.CenterEnd)) }
            }
        }
    }
}

/** Feed photos are small (460 px): framed like a magazine picture rather than stretched full-bleed. */
@Composable
private fun FramedPhoto(url: String, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.padding(end = OmniSpacing.tvSide + 64.dp).size(352.dp, 198.dp).clip(shape)
            .border(1.dp, OmniTheme.colors.glassEdge, shape).background(OmniTheme.colors.surface))
}

@Composable
private fun CinematicStage(hero: Flow<ForYouPick?>, initial: ForYouPick?, name: String?, modifier: Modifier) {
    val c = OmniTheme.colors
    val pick by hero.collectAsStateWithLifecycle(initial)
    val nowMs = remember { System.currentTimeMillis() }
    Column(modifier.padding(start = OmniSpacing.tvSide, top = 22.dp, end = OmniSpacing.tvSide)) {
        Text(greeting(nowMs).uppercase() + (name?.takeIf { it.isNotBlank() }?.let { ", ${it.uppercase()}" } ?: ""),
            style = OmniTheme.type.overline, color = c.accent, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Crossfade(pick, Modifier.fillMaxWidth(0.6f), animationSpec = tween(OmniMotion.BACKDROP_CROSSFADE_MS), label = "forYouStage") { p ->
            HeroCopy(p)
        }
    }
}

@Composable
private fun HeroCopy(p: ForYouPick?) {
    val c = OmniTheme.colors; val t = OmniTheme.type
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (p == null) {
            Text("Your favourites. Your teams.\nYour next great find.", style = t.display, color = c.textPrimary, maxLines = 2)
        } else {
            ReasonPill(heroKicker(p), p.live)
            HeroTitle(p)
            heroMeta(p)?.let { Text(it, style = t.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            heroPlot(p)?.let { Text(it, style = t.body, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun heroKicker(p: ForYouPick): String = when (p.kind) {
    PickKind.STORY -> listOfNotNull("The Guardian", p.card.subtitle?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
    else -> p.reason
}

private fun heroMeta(p: ForYouPick): String? = when (p.kind) {
    // "Episode · 50 min left · 2011 · Drama": the time left already says what kind it is.
    PickKind.RESUME -> listOfNotNull(p.card.subtitle, p.meta?.substringAfter("  ·  ", "")?.ifBlank { null }).joinToString("  ·  ").ifBlank { null }
    PickKind.GAME -> p.card.subtitle
    PickKind.STORY -> null
    else -> p.meta
}

private fun heroPlot(p: ForYouPick): String? = when (p.kind) {
    PickKind.GAME -> p.meta
    else -> p.plot
}

@Composable
private fun ReasonPill(text: String, live: Boolean) {
    val c = OmniTheme.colors
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(Color.Black.copy(alpha = 0.35f)).border(1.dp, c.glassEdge, shape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) LiveDot() else Text("✦", style = OmniTheme.type.caption, color = c.accent)
        Spacer(Modifier.width(8.dp))
        Text(text, style = OmniTheme.type.caption, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The TMDB title logo when there is one (titles only), otherwise the title set large. */
@Composable
private fun HeroTitle(p: ForYouPick) {
    val c = OmniTheme.colors; val t = OmniTheme.type
    var logoFailed by remember(p.logoUrl) { mutableStateOf(false) }
    val logo = p.logoUrl?.takeIf { !logoFailed && (p.kind == PickKind.TITLE || p.kind == PickKind.RESUME) }
    if (logo != null) {
        AsyncImage(logo, contentDescription = p.card.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            onError = { logoFailed = true }, modifier = Modifier.height(64.dp).widthIn(max = 360.dp).fillMaxWidth())
    } else {
        val story = p.kind == PickKind.STORY
        Text(p.headline(), style = if (story) t.headline else t.browseHero, color = c.textPrimary,
            maxLines = if (story) 2 else 1, overflow = TextOverflow.Ellipsis)
    }
}
