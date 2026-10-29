package com.yodesla.omniverse.feature.home

import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yodesla.omniverse.designsystem.*
import kotlinx.coroutines.delay

/**
 * Editorial For You: a dated masthead, a feature spread (tonight's pick beside what is on right now),
 * then serif-headed sections down to a headlines briefing. Posters stay vertical; landscape art is
 * only the feature card's backdrop.
 */
@Composable
internal fun ForYouMagazine(s: ForYouState, name: String?, actions: ForYouActions, focusSwitch: Boolean, allowNews: Boolean) {
    val c = OmniTheme.colors
    val first = remember { FocusRequester() }
    val switchFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val nowMs = remember { System.currentTimeMillis() }
    val lead = s.featured.firstOrNull()
    val side = remember(s.rightNow, s.stories, s.featured, s.newsEnabled) { magazineSide(s) }
    val sideIds = remember(side) { side.map { it.card.id }.toSet() }
    val who = name?.takeIf { it.isNotBlank() }
    val focus = remember { ForYouFocus() }
    // The feature card is the page's lead: give it a moment to arrive before focus settles on the grid.
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(2_500); waited = true }
    val contentReady = lead != null || (side.isNotEmpty() && (waited || !s.picksLoading))
    CosmicBackdrop(Modifier.fillMaxSize().trackForYouFocus(focus)) {
        FocusPivot(parentFraction = 0.26f, leading = 0.dp, trailing = 48.dp) {
            LazyColumn(
                Modifier.fillMaxSize(), state = listState,
                verticalArrangement = Arrangement.spacedBy(OmniSpacing.l),
                contentPadding = PaddingValues(top = 22.dp, bottom = 56.dp),
            ) {
                item(key = "masthead") { Masthead(s, who, nowMs, actions, switchFocus, allowNews) }
                item(key = "spread") { FeatureSpread(s, lead, side, nowMs, actions, first) }
                val moreNow = s.rightNow.filter { it.card.id !in sideIds }
                if (moreNow.isNotEmpty()) item(key = "now") {
                    PickRow("Pick up, tune in", null, moreNow, TileStyle.NOW, actions, kicker = "RIGHT NOW")
                }
                val shortlist = s.featured.drop(1).filter { it.card.id !in sideIds }
                if (shortlist.isNotEmpty()) item(key = "shortlist") {
                    PickRow(
                        if (s.personal) "Picked for you" + (who?.let { ", $it" } ?: "") else "New on your sources",
                        if (s.personal) "From what you watch, save and search" else "Save a few favourites and this page learns your taste",
                        shortlist, TileStyle.POSTER, actions, kicker = "THE SHORTLIST", posterWidth = 132.dp,
                    )
                } else if (s.picksLoading) item(key = "shortlist-loading") { SkeletonRow(132.dp, 198.dp) }
                items(s.shelves, key = { it.id }) { shelf ->
                    PickRow(shelf.title, shelf.caption, shelf.picks, TileStyle.POSTER, actions, kicker = shelf.kicker, posterWidth = 132.dp)
                }
                if (!s.loading) item(key = "teams") { TeamsSection(s, actions, magazine = true) }
                if (s.myList.isNotEmpty()) item(key = "list") {
                    PickRow("Your list", null, s.myList, TileStyle.POSTER, actions, kicker = "SAVED FOR LATER", posterWidth = 132.dp)
                }
                if (s.channels.isNotEmpty()) item(key = "channels") {
                    PickRow("On your channels now", null, s.channels, TileStyle.CHANNEL, actions, kicker = "ON AIR")
                }
                if (allowNews && s.newsEnabled) item(key = "news") { NewsSection(s, actions, magazine = true) }
                item(key = "footer") {
                    Text("— That’s your ${editionLabel(nowMs).lowercase()}. —", Modifier.fillMaxWidth().padding(top = OmniSpacing.m),
                        style = OmniTheme.type.caption, color = c.textTertiary, textAlign = TextAlign.Center)
                }
            }
        }
    }
    ForYouInitialFocus(focusSwitch, contentReady, first, switchFocus, listState, focus,
        target = if (lead != null) "lead" else side.firstOrNull()?.card?.id)
}

/** Beside the feature: up to three right-now tiles and the top headline, topped up with more picks. */
private fun magazineSide(s: ForYouState): List<ForYouPick> =
    (s.rightNow.take(3) + listOfNotNull(s.stories.firstOrNull()?.takeIf { s.newsEnabled }) + s.featured.drop(1)).take(4)

@Composable
private fun Masthead(s: ForYouState, who: String?, nowMs: Long, actions: ForYouActions, switchFocus: FocusRequester, allowNews: Boolean) {
    val c = OmniTheme.colors; val t = OmniTheme.type
    Column(Modifier.padding(horizontal = OmniSpacing.tvSide)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("${dateLabel(nowMs)}  ·  ${editionLabel(nowMs)}".uppercase(), style = kickerStyle(), color = c.accent, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Text("For you" + (who?.let { ", $it" } ?: ""), style = t.display, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (s.personal) "Picked from what you watch, save, search and follow." else "Fresh arrivals, your channels and the day’s headlines.",
                    style = t.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(rememberTimeText(), style = t.numeric, color = c.textSecondary)
                Spacer(Modifier.height(OmniSpacing.s))
                ForYouViewSwitch(s.layout, actions, switchFocus, if (allowNews) s.newsEnabled else null)
            }
        }
        Spacer(Modifier.height(OmniSpacing.m))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.glassEdge))
    }
}

@Composable
private fun FeatureSpread(s: ForYouState, lead: ForYouPick?, side: List<ForYouPick>, nowMs: Long, actions: ForYouActions, first: FocusRequester) {
    Row(Modifier.fillMaxWidth().height(252.dp).padding(horizontal = OmniSpacing.tvSide), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
        if (lead != null) {
            FeatureCard(lead, nowMs, actions, Modifier.width(468.dp).fillMaxHeight().focusRequester(first).leftOpensMenu(actions.onMenu))
        } else if (s.picksLoading) {
            SkeletonBox(Modifier.width(468.dp).fillMaxHeight(), LocalVisualTier.current == VisualTier.CINEMATIC)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
            side.chunked(2).forEachIndexed { r, pair ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    pair.forEachIndexed { col, pick ->
                        val m = Modifier.weight(1f).fillMaxHeight()
                            .then(if (lead == null && r == 0 && col == 0) Modifier.focusRequester(first) else Modifier)
                            .then(if (lead == null && col == 0) Modifier.leftOpensMenu(actions.onMenu) else Modifier)
                        MiniTile(pick, actions, m)
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Tonight's pick: the vertical poster and the words on the left, its landscape art showing through on the right. */
@Composable
private fun FeatureCard(pick: ForYouPick, nowMs: Long, actions: ForYouActions, modifier: Modifier) {
    val c = OmniTheme.colors; val t = OmniTheme.type
    FocusCard(onClick = { actions.onPick(pick) }, onLongClick = { actions.onHold(pick) }, glass = false,
        modifier = modifier.onFocusChanged { if (it.hasFocus) actions.onFocus(pick) }) {
        Box(Modifier.fillMaxSize()) {
            val art = pick.card.backdropUrl
            if (art != null) AsyncImage(art, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = VibrantArt, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.glassFocusTop, c.background))))
            // Ink is deepest where the words sit, so they stay crisp over any art; the art breathes at the right edge.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.94f), 0.45f to Color.Black.copy(alpha = 0.82f),
                0.78f to Color.Black.copy(alpha = 0.5f), 1f to Color.Black.copy(alpha = 0.18f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f))))
            Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(112.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(c.surface)) {
                    pick.card.image?.let { AsyncImage(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f)) {
                    Text(pickLabel(nowMs).uppercase(), style = kickerStyle(), color = c.accent, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Text(pick.card.title, style = t.headline.copy(fontSize = 30.sp, lineHeight = 33.sp), color = Color.White,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(pick.reason, style = t.caption, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    pick.meta?.let { Text(it, style = t.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    pick.plot?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = t.body, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A compact tile for the spread: words only (the spread is narrow), or the headline over its photo. */
@Composable
private fun MiniTile(pick: ForYouPick, actions: ForYouActions, modifier: Modifier) {
    val focus = modifier.onFocusChanged { if (it.hasFocus) actions.onFocus(pick) }
    if (pick.kind == PickKind.STORY) {
        StoryCard(pick, actions, focus)
        return
    }
    val c = OmniTheme.colors; val t = OmniTheme.type
    FocusCard(onClick = { actions.onPick(pick) }, onLongClick = holdAction(pick, actions), modifier = focus) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center) {
            KickerLine(nowKicker(pick), pick.live)
            Spacer(Modifier.height(4.dp))
            Text(pick.headline(), style = t.title, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            pick.byline()?.let { Text(it, style = t.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (pick.card.progress != null) {
                Spacer(Modifier.height(6.dp))
                ProgressLine(pick.card.progress, pick.live)
            }
        }
    }
}
