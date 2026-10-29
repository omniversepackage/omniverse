package com.yodesla.omniverse.feature.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yodesla.omniverse.core.data.ForYouLayout
import com.yodesla.omniverse.designsystem.*
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---- Time of day: the greeting, the magazine's edition line, the feature's label.

private fun hourOf(nowMs: Long) = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)

internal fun greeting(nowMs: Long): String = when (hourOf(nowMs)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..23 -> "Good evening"
    else -> "Up late"
}

internal fun editionLabel(nowMs: Long): String = when (hourOf(nowMs)) {
    in 5..11 -> "Morning edition"
    in 12..16 -> "Afternoon edition"
    in 17..21 -> "Evening edition"
    else -> "Late edition"
}

internal fun pickLabel(nowMs: Long): String = hourOf(nowMs).let { if (it >= 17 || it < 5) "Tonight's pick" else "Today's pick" }

internal fun dateLabel(nowMs: Long): String = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(nowMs))

/** "Today 7:30 PM", "Tomorrow 1:00 PM", "Sat 3:00 PM", "4 Nov 3:00 PM". */
internal fun kickoffLabel(startMs: Long, nowMs: Long): String {
    val today = Calendar.getInstance().apply {
        timeInMillis = nowMs; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val time = ClockFormat.format(startMs)
    return when ((startMs - today).floorDiv(86_400_000L)) {
        0L -> "Today $time"
        1L -> "Tomorrow $time"
        in 2L..6L -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(startMs)) + " $time"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(startMs)) + " $time"
    }
}

// ---- What a pick says in a tile and in the hero.

/** The tile's big line: a channel tile leads with the programme, everything else with its own title. */
internal fun ForYouPick.headline(): String = if (kind == PickKind.CHANNEL) card.subtitle ?: card.title else card.title
internal fun ForYouPick.byline(): String? = if (kind == PickKind.CHANNEL) card.title else card.subtitle

internal fun nowKicker(pick: ForYouPick): String = when (pick.kind) {
    PickKind.RESUME -> "RESUME"
    PickKind.CHANNEL -> if (pick.live) "LIVE NOW" else "YOUR CHANNEL"
    PickKind.GAME -> (if (pick.live) "LIVE" else "UP NEXT") + "  ·  " + pick.card.section.uppercase()
    PickKind.STORY -> pick.card.subtitle?.takeIf { it.isNotBlank() }?.uppercase() ?: "THE GUARDIAN"
    PickKind.TITLE -> "ALSO FOR YOU"
}

internal fun Modifier.leftOpensMenu(onMenu: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onMenu(); true } else false
}

@Composable
internal fun kickerStyle(): TextStyle = OmniTheme.type.overline.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.6.sp)

// ---- Small parts.

@Composable
internal fun LiveDot(size: Dp = 8.dp) {
    val alpha = if (LocalVisualTier.current == VisualTier.CINEMATIC) {
        val pulse by rememberInfiniteTransition(label = "live").animateFloat(
            initialValue = 1f, targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
        )
        pulse
    } else 1f
    Box(Modifier.size(size).graphicsLayer { this.alpha = alpha }.background(OmniTheme.colors.live, CircleShape))
}

@Composable
internal fun KickerLine(text: String, live: Boolean, color: Color = if (live) OmniTheme.colors.live else OmniTheme.colors.accent) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (live) { LiveDot(); Spacer(Modifier.width(6.dp)) }
        Text(text, style = kickerStyle(), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ProgressLine(progress: Float?, live: Boolean, modifier: Modifier = Modifier) {
    val p = progress ?: return
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.14f))) {
        Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).fillMaxHeight().background(if (live) OmniTheme.colors.live else OmniTheme.colors.accent))
    }
}

@Composable
internal fun PickThumb(pick: ForYouPick, modifier: Modifier) {
    val c = OmniTheme.colors
    val shape = RoundedCornerShape(6.dp)
    if (pick.kind == PickKind.CHANNEL || pick.kind == PickKind.GAME) {
        Box(modifier.aspectRatio(1f).clip(shape).background(c.surface.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            LogoImage(pick.card.image, pick.channel ?: pick.card.title, Modifier.fillMaxSize().padding(8.dp), plain = true)
        }
    } else {
        Box(modifier.aspectRatio(2f / 3f).clip(shape).background(c.surface)) {
            pick.card.image?.let { AsyncImage(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/** Hold OK: the title menu for movies and shows, the channel menu for channels and games. */
@Composable
internal fun holdAction(pick: ForYouPick, actions: ForYouActions): (() -> Unit)? = when (pick.kind) {
    PickKind.TITLE, PickKind.RESUME -> ({ actions.onHold(pick) })
    PickKind.CHANNEL -> channelFavoriteMenu(pick.card.open.sourceId.value, pick.card.open.remoteId.value, pick.card.title, pick.card.image)
    PickKind.GAME -> if (pick.opensSports) null else channelFavoriteMenu(pick.card.open.sourceId.value, pick.card.open.remoteId.value, pick.channel ?: pick.card.title)
    PickKind.STORY -> null
}

// ---- Headers.

/**
 * Cinematic row header: Home's quiet style, so the hero stays the loudest thing on screen. One line
 * (title, then the caption on the same baseline): the rows sit right under the hero, and a two-line
 * header was clipped by it when its row took focus.
 */
@Composable
internal fun RowTitle(title: String, caption: String?) {
    val c = OmniTheme.colors
    Row(Modifier.padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, bottom = OmniSpacing.xs)) {
        Text(title, style = OmniTheme.type.title, color = c.textPrimary, maxLines = 1, modifier = Modifier.alignByBaseline())
        caption?.let {
            Spacer(Modifier.width(OmniSpacing.m))
            // The caption gives way first when space runs out; the title never ellipsizes for it.
            Text(it, style = OmniTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline().weight(1f, fill = false))
        }
    }
}

/** Magazine section head: kicker and a hairline rule, then a serif headline. */
@Composable
internal fun SectionMasthead(kicker: String, title: String, caption: String?) {
    val c = OmniTheme.colors
    Column(Modifier.padding(horizontal = OmniSpacing.tvSide)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kicker, style = kickerStyle(), color = c.accent, maxLines = 1)
            Spacer(Modifier.width(OmniSpacing.m))
            Box(Modifier.weight(1f).height(1.dp).background(c.glassEdge))
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = OmniTheme.type.headline, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        caption?.let { Text(it, style = OmniTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun SectionHead(title: String, caption: String?, kicker: String?) {
    if (kicker != null) SectionMasthead(kicker, title, caption) else RowTitle(title, caption)
}

// ---- Rows and tiles.

/** One titled row of tiles. [first] focuses its first tile; Left from that tile opens the main menu. */
@Composable
internal fun PickRow(
    title: String, caption: String?, picks: List<ForYouPick>, tile: TileStyle, actions: ForYouActions,
    first: FocusRequester? = null, kicker: String? = null, posterWidth: Dp = 124.dp, wideFirst: Boolean = false,
) {
    Column {
        SectionHead(title, caption, kicker)
        FocusPivot {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.focusEdge),
            ) {
                itemsIndexed(picks, key = { _, p -> p.card.id }) { index, pick ->
                    val mod = Modifier
                        .then(if (index == 0 && first != null) Modifier.focusRequester(first) else Modifier)
                        .then(if (index == 0) Modifier.leftOpensMenu(actions.onMenu) else Modifier)
                    val story = when {
                        !wideFirst -> DpSize(288.dp, 168.dp)
                        index == 0 -> DpSize(440.dp, 200.dp)
                        else -> DpSize(264.dp, 200.dp)
                    }
                    PickTile(pick, tile, actions, mod, posterWidth, story, wide = wideFirst && index == 0)
                }
            }
        }
    }
}

@Composable
internal fun PickTile(
    pick: ForYouPick, tile: TileStyle, actions: ForYouActions, modifier: Modifier,
    posterWidth: Dp = 124.dp, storySize: DpSize = DpSize(288.dp, 168.dp), wide: Boolean = false,
) {
    val focus = modifier.onFocusChanged { if (it.hasFocus) actions.onFocus(pick) }
    when (tile) {
        TileStyle.NOW -> NowTile(pick, actions, focus.size(276.dp, 100.dp))
        TileStyle.POSTER -> PosterCard(pick.card.title, pick.card.image, { actions.onPick(pick) }, focus.width(posterWidth),
            pick.card.subtitle, pick.card.progress, onLongClick = { actions.onHold(pick) })
        TileStyle.CHANNEL -> ChannelNowTile(pick, actions, focus.size(236.dp, 116.dp))
        TileStyle.GAME -> GameTile(pick, actions, focus.size(284.dp, 108.dp))
        TileStyle.STORY -> StoryCard(pick, actions, focus.size(storySize), wide)
    }
}

/** Resume, live channel or team game: thumbnail, kicker, title, a line of detail and progress. */
@Composable
internal fun NowTile(pick: ForYouPick, actions: ForYouActions, modifier: Modifier) {
    val c = OmniTheme.colors; val t = OmniTheme.type
    FocusCard(onClick = { actions.onPick(pick) }, onLongClick = holdAction(pick, actions), modifier = modifier) {
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PickThumb(pick, Modifier.fillMaxHeight())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                KickerLine(nowKicker(pick), pick.live)
                Spacer(Modifier.height(2.dp))
                Text(pick.headline(), style = t.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                pick.byline()?.let { Text(it, style = t.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (pick.card.progress != null) { Spacer(Modifier.height(6.dp)); ProgressLine(pick.card.progress, pick.live) }
            }
        }
    }
}

@Composable
internal fun ChannelNowTile(pick: ForYouPick, actions: ForYouActions, modifier: Modifier) {
    val c = OmniTheme.colors; val t = OmniTheme.type
    FocusCard(onClick = { actions.onPick(pick) }, onLongClick = holdAction(pick, actions), modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoImage(pick.card.image, pick.card.title, Modifier.size(60.dp, 34.dp), plain = true)
                Spacer(Modifier.weight(1f))
                if (pick.live) LiveDot()
            }
            Spacer(Modifier.height(6.dp))
            Text(pick.headline(), style = t.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pick.card.title, style = t.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            ProgressLine(pick.card.progress, live = true)
        }
    }
}

@Composable
internal fun GameTile(pick: ForYouPick, actions: ForYouActions, modifier: Modifier) {
    val c = OmniTheme.colors; val t = OmniTheme.type
    FocusCard(onClick = { actions.onPick(pick) }, onLongClick = holdAction(pick, actions), modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
            KickerLine(nowKicker(pick), pick.live)
            Spacer(Modifier.height(4.dp))
            Text(pick.card.title, style = t.title, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            pick.card.subtitle?.let { Text(it, style = t.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A headline over its own photo; the wide variant sets the headline in the serif face. */
@Composable
internal fun StoryCard(pick: ForYouPick, actions: ForYouActions, modifier: Modifier, wide: Boolean = false) {
    val c = OmniTheme.colors; val t = OmniTheme.type
    FocusCard(onClick = { actions.onPick(pick) }, glass = false, modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            val photo = pick.card.image
            if (photo != null) AsyncImage(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.glassFocusTop, c.background))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Transparent, 0.3f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.9f))))
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                KickerLine(nowKicker(pick), live = false)
                Spacer(Modifier.height(4.dp))
                Text(pick.card.title, style = if (wide) t.headline.copy(fontSize = 24.sp, lineHeight = 28.sp) else t.title,
                    color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---- Sections both layouts share.

@Composable
internal fun TeamsSection(s: ForYouState, actions: ForYouActions, magazine: Boolean) {
    val c = OmniTheme.colors
    val kicker = if (magazine) "MATCHDAY" else null
    val names = s.teams.joinToString("  ·  ") { it.name }
    when {
        s.games.isNotEmpty() -> PickRow("Your teams", names.ifBlank { null }, s.games, TileStyle.GAME, actions, kicker = kicker)
        s.teams.isEmpty() -> Column {
            SectionHead("Your teams", null, kicker)
            Row(Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.focusEdge)) {
                FocusCard(onClick = actions.onSports, modifier = Modifier.size(400.dp, 96.dp).leftOpensMenu(actions.onMenu)) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
                        KickerLine("FOLLOW YOUR TEAMS", live = false)
                        Spacer(Modifier.height(4.dp))
                        Text("Pick teams in Live Sports and their games show up here first.", style = OmniTheme.type.body,
                            color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        else -> Column {
            SectionHead("Your teams", names, kicker)
            Text("No games coming up in the next few days.", Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.s),
                style = OmniTheme.type.body, color = c.textTertiary)
        }
    }
}

@Composable
internal fun NewsSection(s: ForYouState, actions: ForYouActions, magazine: Boolean) {
    val c = OmniTheme.colors
    val title = if (s.storiesPersonal) "In your orbit" else "Film, TV & sport"
    val caption = "Headlines from The Guardian"
    val kicker = if (magazine) "THE BRIEFING" else null
    when {
        s.stories.isNotEmpty() -> PickRow(title, caption, s.stories, TileStyle.STORY, actions, kicker = kicker, wideFirst = magazine)
        s.newsLoading -> Column { SectionHead(title, caption, kicker); SkeletonRow(288.dp, 168.dp, 4) }
        else -> Column {
            SectionHead(title, caption, kicker)
            Text("Headlines are unavailable right now. Everything else here works offline.",
                Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.s), style = OmniTheme.type.body, color = c.textTertiary)
        }
    }
}

@Composable
internal fun SkeletonRow(width: Dp, height: Dp, count: Int = 6) {
    val shimmer = LocalVisualTier.current == VisualTier.CINEMATIC
    Row(Modifier.padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.focusEdge), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
        repeat(count) { SkeletonBox(Modifier.size(width, height), shimmer) }
    }
}

/** Cinematic | Magazine (and the headlines switch). The selected layout's button takes [selectedFocus]. */
@Composable
internal fun ForYouViewSwitch(layout: String, actions: ForYouActions, selectedFocus: FocusRequester, newsEnabled: Boolean?, modifier: Modifier = Modifier) {
    val cinematic = layout != ForYouLayout.MAGAZINE
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s), verticalAlignment = Alignment.CenterVertically) {
        OmniButton("Cinematic", { actions.onLayout(ForYouLayout.CINEMATIC) },
            if (cinematic) Modifier.focusRequester(selectedFocus) else Modifier, primary = cinematic, compact = true)
        OmniButton("Magazine", { actions.onLayout(ForYouLayout.MAGAZINE) },
            if (!cinematic) Modifier.focusRequester(selectedFocus) else Modifier, primary = !cinematic, compact = true)
        if (newsEnabled != null) OmniButton(if (newsEnabled) "Headlines on" else "Headlines off", actions.onToggleNews, compact = true)
    }
}

/** Where the page's focus is, and whether the viewer has pressed a key on it yet. */
@Stable
internal class ForYouFocus {
    var page by mutableStateOf(false)
    var viewerMoved by mutableStateOf(false)
}

/** Track [focus] on the page root: whether focus is inside, and the viewer's first key press. */
internal fun Modifier.trackForYouFocus(focus: ForYouFocus): Modifier =
    onFocusChanged { focus.page = it.hasFocus }
        .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown) focus.viewerMoved = true; false }

/**
 * First focus: the page's lead tile once there is one. Right after the layout switch was used, the
 * switch keeps focus instead; while nothing has loaded yet the switch holds it so the D-pad works.
 * The shell hands focus to whatever child it finds first as the menu closes, so this waits for that to
 * land and then moves it on to the lead — but never once the viewer has pressed a key here.
 */
@Composable
internal fun ForYouInitialFocus(
    focusSwitch: Boolean, contentReady: Boolean, first: FocusRequester, switchFocus: FocusRequester,
    listState: LazyListState, focus: ForYouFocus,
    /** What [first] is on right now: a better lead arriving later (the feature card) takes focus over. */
    target: Any?,
) {
    LaunchedEffect(Unit) { if (focusSwitch || !contentReady) switchFocus.requestFocusWhenReady() }
    LaunchedEffect(contentReady, target) {
        if (!contentReady || focusSwitch) return@LaunchedEffect
        kotlinx.coroutines.withTimeoutOrNull(1_500) { snapshotFlow { focus.page }.first { it } }
        if (!focus.viewerMoved) {
            listState.scrollToItem(0)
            first.requestFocusWhenReady()
        }
    }
}
