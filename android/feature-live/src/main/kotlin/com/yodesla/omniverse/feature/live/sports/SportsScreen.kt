package com.yodesla.omniverse.feature.live.sports

import android.view.TextureView
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yodesla.omniverse.core.data.sports.FollowedTeams
import com.yodesla.omniverse.core.data.sports.GameState
import com.yodesla.omniverse.core.data.sports.GameTeam
import com.yodesla.omniverse.core.data.sports.HighlightClip
import com.yodesla.omniverse.core.data.sports.SportsAiring
import com.yodesla.omniverse.core.data.sports.Teams
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.designsystem.ClockFormat
import com.yodesla.omniverse.designsystem.FocusCard
import com.yodesla.omniverse.designsystem.FocusPivot
import com.yodesla.omniverse.designsystem.LogoImage
import com.yodesla.omniverse.designsystem.OmniColors
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.ProgressLine
import com.yodesla.omniverse.designsystem.ProvideOmniColors
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import java.util.Calendar

private val LiveRed = Color(0xFFFF3B30)
private val Turf = Color(0xFF34C77B)

@Composable
private fun sportsColors(): OmniColors = OmniTheme.colors.copy(
    background = Color(0xFF040A12), surface = Color(0xFF0D1A26), elevated = Color(0xFF162636),
    accent = Turf, textSecondary = Color(0xFFB7C6D3),
    glassTop = Color(0xD90F1E2C), glassBottom = Color(0xB3081119),
    glassFocusTop = Color(0xF21B5A44), glassFocusBottom = Color(0xE60D1A26),
    glassBlueSheen = Color(0x1A34C77B), glassWineSheen = Color.Transparent,
)

@Composable
fun SportsRoute(viewModel: SportsViewModel, onWatch: (ContentKey, RemoteId) -> Unit, onOpenNavigation: () -> Unit = {}) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.eventFlow.collect { e -> if (e is SportsEvent.Watch) onWatch(e.key, e.categoryId) } }
    val first = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // While a highlight has taken over the hero, Back stops the clip instead of leaving the page.
    androidx.activity.compose.BackHandler(enabled = s.playingClip != null) { viewModel.stopClip() }
    // The highlight under the remote's focus previews in its own box (with sound); the hero pauses.
    var preview by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<HighlightClip?>(null) }
    ProvideOmniColors(sportsColors()) {
        Box(Modifier.fillMaxSize().background(OmniTheme.colors.background)) {
            StadiumStage(Modifier.fillMaxSize())
            FocusPivot(parentFraction = 0.30f, leading = 0.dp) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 160.dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.l)) {
                    item(key = "header") { SportsHeader(s) }
                    // Task 103: the teams this profile follows come first, ahead of everything else.
                    val mine = s.myTeams.size + s.myTeamGroups.size + s.myTeamAirings.size
                    if (mine > 0) item(key = "myteams") {
                        Shelf("My teams", "$mine live or coming up  ·  OK reveals a hidden score  ·  hold OK to follow",
                            liveDot = s.myTeams.any { it.game.state == GameState.LIVE }) {
                            items(s.myTeams, key = { "mt-" + it.id }) { g ->
                                GameCard(g, s.nowMs, SportsViewModel.reminderId(g) in s.reminderIds, s.photos,
                                    hiddenScore = s.scoreHidden(g.id), onLongPress = { viewModel.openFollowMenu(g) }) { viewModel.open(g) }
                            }
                            items(s.myTeamGroups, key = { "mtg-" + it.id }) { grp ->
                                AiringCard(grp.sample, s.nowMs, false, channels = grp.options.size, dir = s.teams, clips = s.clips, photos = s.photos,
                                    onLongPress = { viewModel.openFollowMenu(grp.sample) }) { viewModel.openGroup(grp) }
                            }
                            items(s.myTeamAirings, key = { "mta-" + SportsViewModel.reminderId(it) }) { a ->
                                AiringCard(a, s.nowMs, SportsViewModel.reminderId(a) in s.reminderIds, dir = s.teams, clips = s.clips, photos = s.photos,
                                    onLongPress = { viewModel.openFollowMenu(a) }) { viewModel.openAiring(a) }
                            }
                        }
                    } else if (s.followed.isNotEmpty()) item(key = "myteams") {
                        Shelf("My teams", "Nothing on for them right now") {
                            item(key = "mt-none") {
                                Text(s.followed.joinToString(", ") { it.name }, style = OmniTheme.type.body, color = OmniTheme.colors.textSecondary,
                                    modifier = Modifier.padding(horizontal = OmniSpacing.tvSide))
                            }
                        }
                    }
                    s.featured?.let { g ->
                        item(key = "hero") {
                            GameHero(g, s.nowMs, s.playingClip, paused = preview != null, onStop = viewModel::stopClip, reminded = SportsViewModel.reminderId(g) in s.reminderIds,
                                hiddenScore = s.scoreHidden(g.id), onLongPress = { viewModel.openFollowMenu(g) },
                                modifier = Modifier.focusRequester(first), onLeft = onOpenNavigation) { viewModel.open(g) }
                        }
                    }
                    if (s.leagues.size > 1) item(key = "chips") { LeagueChips(s.leagues, s.selected, viewModel::select) }
                    s.message?.let { m -> item(key = "msg") { Text(m, style = OmniTheme.type.body, color = OmniTheme.colors.accent, modifier = Modifier.padding(horizontal = OmniSpacing.tvSide)) } }
                    val liveAll = s.liveGames.size + s.liveGroups.size
                    if (liveAll > 0) item(key = "live") {
                        Shelf("Live now", "$liveAll on air", liveDot = true) {
                            items(s.liveGames, key = { "g-" + it.id }) { g -> GameCard(g, s.nowMs, false, s.photos, hiddenScore = s.scoreHidden(g.id), onLongPress = { viewModel.openFollowMenu(g) }) { viewModel.open(g) } }
                                items(s.liveGroups, key = { "lg-" + it.id }) { grp -> AiringCard(grp.sample, s.nowMs, false, channels = grp.options.size, dir = s.teams, clips = s.clips, photos = s.photos, onLongPress = { viewModel.openFollowMenu(grp.sample) }) { viewModel.openGroup(grp) } }
                        }
                    }
                    if (s.clips.isNotEmpty() || s.clipsNote != null) item(key = "clips") {
                        Shelf("Highlights & previews", s.clipsNote ?: (if (s.selected != null) "${s.selected}  ·  " else "") + "Plays in the top box") {
                            if (s.clips.isEmpty()) item(key = "c-none") {
                                Text(s.clipsNote ?: "", style = OmniTheme.type.body, color = OmniTheme.colors.textSecondary,
                                    modifier = Modifier.padding(horizontal = OmniSpacing.tvSide))
                            } else items(s.clips, key = { "c-" + it.id }) { c ->
                                ClipCard(c, previewing = preview == c, onFocus = { focused -> preview = if (focused) c else preview?.takeIf { it != c } }) {
                                    // Play it big: send it to the hero, jump up there and focus it.
                                    preview = null
                                    viewModel.playClip(c)
                                    scope.launch { listState.animateScrollToItem(0); first.requestFocusWhenReady() }
                                }
                            }
                        }
                    }
                    s.days.forEach { day ->
                        item(key = "day-${day.label}") {
                            Shelf(day.label, "${day.games.size} games · OK sets a reminder") {
                                items(day.games, key = { "g-" + it.id }) { g -> GameCard(g, s.nowMs, SportsViewModel.reminderId(g) in s.reminderIds, s.photos, hiddenScore = s.scoreHidden(g.id), onLongPress = { viewModel.openFollowMenu(g) }) { viewModel.open(g) } }
                            }
                        }
                    }
                    if (s.coverage.isNotEmpty()) item(key = "coverage") {
                        Shelf("Studio, news & analysis", "From your channels' guides") {
                            items(s.coverage, key = { "v-" + SportsViewModel.reminderId(it) }) { a -> AiringCard(a, s.nowMs, SportsViewModel.reminderId(a) in s.reminderIds, dir = s.teams, clips = s.clips, photos = s.photos) { viewModel.openAiring(a) } }
                        }
                    }
                    if (s.channels.isNotEmpty()) item(key = "channels") {
                        Shelf("Your sports channels", "${s.channels.size} channels") {
                            items(s.channels, key = { "ch-${it.row.key.sourceId.value}-${it.row.key.remoteId.value}" }) { ch -> ChannelCard(ch, s.nowMs) { viewModel.watchChannel(ch.row) } }
                        }
                    }
                    item(key = "online") { OnlineFooter(s.online) { viewModel.setOnline(!s.online) } }
                }
            }
            s.picker?.let { pk -> WatchPickerOverlay(pk, onPick = viewModel::pick, onClose = viewModel::closePicker) }
            s.followMenu?.let { fm -> FollowMenuOverlay(fm, onToggle = viewModel::toggleFollow, onClose = viewModel::closeFollowMenu) }
        }
    }
    LaunchedEffect(s.featured != null) { if (s.featured != null) first.requestFocusWhenReady() }
}

/** Floodlit night stadium: two light towers, a soft turf glow and perspective pitch lines. */
@Composable
private fun StadiumStage(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1C2E), Color(0xFF050B14), Color(0xFF04120E))))
        for (x in listOf(0.08f, 0.92f)) drawRect(Brush.radialGradient(listOf(Color(0x40DDE9FF), Color.Transparent), center = Offset(w * x, -h * 0.05f), radius = w * 0.45f))
        drawRect(Brush.radialGradient(listOf(Color(0x3334C77B), Color.Transparent), center = Offset(w * 0.5f, h * 1.15f), radius = w * 0.7f))
        val horizon = h * 0.72f
        val vp = Offset(w * 0.5f, horizon - h * 0.25f)
        for (i in -6..6) { val bx = w * 0.5f + i * w * 0.16f; drawLine(Color(0x1434C77B), Offset(bx, h), Offset(vp.x + (bx - vp.x) * 0.25f, horizon), strokeWidth = 2f) }
        for (j in 0..4) { val y = horizon + (h - horizon) * (j / 4f) * (j / 4f); drawLine(Color(0x1034C77B), Offset(0f, y), Offset(w, y), strokeWidth = 2f) }
    }
}

@Composable
private fun SportsHeader(s: SportsUiState) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val now = if (s.nowMs > 0) s.nowMs else System.currentTimeMillis()
    val live = s.liveGames.size + s.liveGroups.size
    Row(Modifier.fillMaxWidth().padding(start = OmniSpacing.tvSide, end = OmniSpacing.tvSide, top = OmniSpacing.xl), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            // Real Manrope Bold (not a synthesized weight of the serif), so it stays crisp at TV size.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(LiveRed)); Spacer(Modifier.width(OmniSpacing.s))
                Text("LIVE SPORTS", style = t.overline.copy(fontFamily = t.family, fontWeight = FontWeight.Bold, letterSpacing = 4.sp), color = Turf)
            }
            Text(
                "GAME DAY",
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = t.family, fontWeight = FontWeight.Bold, fontSize = 72.sp, lineHeight = 80.sp, letterSpacing = (-1.5).sp,
                    brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFB9F5D4), Turf)),
                    shadow = Shadow(Turf.copy(alpha = 0.45f), offset = Offset(0f, 4f), blurRadius = 28f),
                ),
                modifier = Modifier.padding(end = OmniSpacing.m),
            )
            Text(String.format(java.util.Locale.getDefault(), "%1\$tA, %1\$tB %1\$te", Calendar.getInstance().apply { timeInMillis = now }) +
                "  ·  ${s.days.sumOf { it.games.size }} upcoming games this week", style = t.body, color = c.textSecondary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(ClockFormat.format(now), style = t.numeric, color = c.textPrimary)
            if (live > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(LiveRed)); Spacer(Modifier.width(OmniSpacing.s))
                Text("$live live now", style = t.body, color = c.textPrimary)
            }
        }
    }
}

private fun teamColor(t: GameTeam): Color = Color(t.color ?: Teams.find(t.name)?.color ?: 0xFF33465AL)

/** Darkens a club colour so white text and logos stay readable on it. */
private fun Color.deep(f: Float = 0.55f) = Color(red * f, green * f, blue * f, 1f)

/** Featured matchup: split club colours, giant logos, live score or kick-off, where to watch, muted highlight. */
@Composable
private fun GameHero(g: GameUi, now: Long, playing: HighlightClip?, paused: Boolean, onStop: () -> Unit = {}, reminded: Boolean, hiddenScore: Boolean = false, onLongPress: () -> Unit = {}, modifier: Modifier, onLeft: () -> Unit, onOpen: () -> Unit) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val game = g.game
    val clip = playing ?: g.clip
    FocusCard(onClick = onOpen, onLongClick = onLongPress, modifier = modifier.padding(horizontal = OmniSpacing.tvSide).fillMaxWidth().height(330.dp)
        .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onLeft(); true } else false }) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(teamColor(game.away).deep(), Color(0xFF070D16), teamColor(game.home).deep()), start = Offset.Zero, end = Offset.Infinite))) {
            if (playing != null) {
                // Takeover: the clip plays big; no scrim, score, badges or "where to watch" over it.
                Box(Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(16f / 9f)) {
                    ClipVideo(playing.videoUrl, muted = false, paused = paused, loop = false, onEnded = onStop, modifier = Modifier.fillMaxSize())
                }
                Row(Modifier.align(Alignment.BottomStart).padding(start = OmniSpacing.xl, bottom = OmniSpacing.m),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    Text(playing.headline, style = t.caption.copy(fontWeight = FontWeight.Bold), color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 560.dp))
                    Text("BACK  Stop", style = t.overline, color = c.textSecondary)
                }
            } else {
            if (clip != null) {
                Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().aspectRatio(16f / 9f)) {
                    ClipVideo(clip.videoUrl, muted = paused, paused = paused, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.38f to Color(0xF2070D16), 0.62f to Color(0x80070D16), 1f to Color.Transparent)))
            } else AsyncImage(model = game.imageUrl, contentDescription = null, contentScale = ContentScale.Fit, alpha = 0.35f,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().aspectRatio(16f / 9f))
            Column(Modifier.fillMaxSize().padding(OmniSpacing.xl), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    when (game.state) {
                        GameState.LIVE -> { LivePill(); Text(FollowedTeams.detailText(game.detail, hiddenScore, false), style = t.title.copy(fontWeight = FontWeight.Bold), color = c.textPrimary) }
                        GameState.FINAL -> Tag("FINAL", c.textSecondary)
                        GameState.PRE -> Tag(whenText(game.startMs, now).uppercase(), c.accent)
                    }
                    Tag(game.league.uppercase(), c.textSecondary)
                    if (clip != null) Tag(if (playing != null) "▶ ${clip.headline.take(48)}" else "▶ HIGHLIGHTS", c.textPrimary)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.l)) {
                    TeamBlock(game.away, 96)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (game.state != GameState.PRE) Text(FollowedTeams.scoreText(game.away.score, game.home.score, hiddenScore, false, " – "),
                            style = if (hiddenScore) t.headline.copy(fontWeight = FontWeight.Bold) else t.hero.copy(fontWeight = FontWeight.Black, fontSize = 56.sp), color = c.textPrimary)
                        else Text("AT", style = t.title.copy(fontWeight = FontWeight.Black), color = c.accent)
                    }
                    TeamBlock(game.home, 96)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    game.networks.take(3).forEach { NetworkChip(it) }
                    val ch = g.channels.firstOrNull()
                    Text(
                         when {
                             ch == null -> "Not on your channels"
                             hiddenScore -> "OK  Reveal the score  ·  hold OK to follow"
                             game.state == GameState.LIVE -> "OK  Watch on ${ch.name}"
                            reminded -> "Reminder set on ${ch.name}  ·  OK to cancel"
                            else -> "OK  Remind me  ·  on ${ch.name}"
                        },
                        style = t.title, color = if (ch == null) c.textSecondary else c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    game.venue?.let { Text("·  $it", style = t.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            }
        }
    }
}

@Composable
private fun TeamBlock(team: GameTeam, logo: Int) {
    val t = OmniTheme.type
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
        TeamLogo(team, logo)
        Column {
            Text(team.shortName, style = t.hero.copy(fontWeight = FontWeight.Black, shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 10f)),
                color = OmniTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
            team.record?.let { Text(it, style = t.body, color = OmniTheme.colors.textSecondary) }
        }
    }
}

@Composable
private fun TeamLogo(team: GameTeam, sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), teamColor(team).copy(alpha = 0.5f)))), contentAlignment = Alignment.Center) {
        if (team.logoUrl != null) AsyncImage(model = team.logoUrl, contentDescription = team.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding((sizeDp / 10).dp))
        else Text(team.abbreviation.take(3), style = OmniTheme.type.title.copy(fontWeight = FontWeight.Black, fontSize = (sizeDp * 0.3f).sp), color = Color.White)
    }
}

@Composable
private fun NetworkChip(name: String) {
    Text(name, style = OmniTheme.type.caption.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Matchup box: away/home club colours, logos, score or kick-off, network and your channel. */
@Composable
private fun GameCard(g: GameUi, now: Long, reminded: Boolean, photos: Map<com.yodesla.omniverse.core.data.sports.Sport, List<String>> = emptyMap(), hiddenScore: Boolean = false, onLongPress: () -> Unit = {}, onClick: () -> Unit) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val game = g.game
    FocusCard(onClick = onClick, onLongClick = onLongPress, modifier = Modifier.width(360.dp).height(200.dp)) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(teamColor(game.away).deep(0.6f), Color(0xFF0A111B), teamColor(game.home).deep(0.6f))))) {
            // A real photo behind the logos: this game's clip, else a recent photo from the sport.
            (g.clip?.thumbnailUrl ?: sportPhoto(photos, game.sport, game.id))?.let { url ->
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.55f)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(teamColor(game.away).deep(0.6f).copy(alpha = 0.75f), Color(0xB30A111B), teamColor(game.home).deep(0.6f).copy(alpha = 0.75f)))))
            }
            Column(Modifier.fillMaxSize().padding(OmniSpacing.m), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                    when (game.state) {
                        GameState.LIVE -> { LivePill(); Text(FollowedTeams.detailText(game.detail, hiddenScore, false), style = t.caption.copy(fontWeight = FontWeight.Bold), color = c.textPrimary, maxLines = 1) }
                        GameState.FINAL -> Text("FINAL", style = t.caption.copy(fontWeight = FontWeight.Bold), color = c.textSecondary)
                        GameState.PRE -> Text(ClockFormat.format(game.startMs), style = t.title.copy(fontWeight = FontWeight.Bold), color = c.textPrimary)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(game.league.uppercase(), style = t.overline, color = c.textSecondary, maxLines = 1)
                    if (reminded) Text("⏰", style = t.caption, color = c.accent)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MiniTeam(game.away, Modifier.weight(1f))
                    Text(if (game.state == GameState.PRE) "@" else FollowedTeams.scoreText(game.away.score, game.home.score, hiddenScore, false),
                        style = if (hiddenScore) t.caption.copy(fontWeight = FontWeight.Bold) else t.title.copy(fontWeight = FontWeight.Black),
                        color = c.textPrimary, textAlign = TextAlign.Center, maxLines = 1,
                        modifier = Modifier.width(if (hiddenScore) 116.dp else 72.dp))
                    MiniTeam(game.home, Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                    game.networks.firstOrNull()?.let { NetworkChip(it) }
                    val n = g.options.size
                    Text(when { n == 0 -> "Not on your channels"; n > 1 && game.state == GameState.LIVE -> "Live on $n channels  ·  OK to choose"; else -> g.options.first().name },
                        style = t.caption, color = if (n == 0) c.textTertiary else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MiniTeam(team: GameTeam, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TeamLogo(team, 56)
        Text(team.shortName, style = OmniTheme.type.body.copy(fontWeight = FontWeight.SemiBold), color = OmniTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ClipCard(clip: HighlightClip, previewing: Boolean, onFocus: (Boolean) -> Unit, onClick: () -> Unit) {
    val c = OmniTheme.colors
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    // A short dwell before previewing so scrolling past cards doesn't start every clip.
    LaunchedEffect(focused) { if (focused) { kotlinx.coroutines.delay(700); onFocus(true) } else onFocus(false) }
    val playing = previewing
    FocusCard(onClick = onClick, modifier = Modifier.width(320.dp).height(200.dp).onFocusChanged { focused = it.hasFocus }) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(model = clip.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (previewing) ClipVideo(clip.videoUrl, muted = false, paused = false, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xF2050B14))))
            if (!playing) Box(Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Text("▶", style = OmniTheme.type.title, color = Color.White)
            }
            Column(Modifier.align(Alignment.BottomStart).padding(OmniSpacing.m)) {
                Text("${clip.league}${clip.durationSec?.let { "  ·  ${it / 60}:${"%02d".format(it % 60)}" } ?: ""}", style = OmniTheme.type.overline, color = c.accent)
                Text(clip.headline, style = OmniTheme.type.body.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Logos/colours for a guide matchup: the directory when it knows the team, else a club-colour monogram. */
private fun guideTeams(a: SportsAiring, dir: com.yodesla.omniverse.core.data.sports.TeamDirectory?): Pair<GameTeam, GameTeam>? {
    val (x, y) = a.teams ?: return null
    fun team(n: String) = dir?.find(n, a.league)?.team
        ?: GameTeam(n, n, n.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(), Teams.find(n)?.color, null, null, null)
    return team(x) to team(y)
}

/** A team mentioned in a studio/news title ("Steelers postgame"), for its logo and photo. */
private fun teamIn(a: SportsAiring, dir: com.yodesla.omniverse.core.data.sports.TeamDirectory?): GameTeam? =
    dir?.find(a.title, a.league)?.team ?: a.description?.let { d -> dir?.find(d, a.league)?.team }

/** A sports airing from the viewer's guide: logos + club colours for matchups, a team photo when a clip has one. */
@Composable
private fun AiringCard(a: SportsAiring, now: Long, reminded: Boolean, channels: Int = 1, dir: com.yodesla.omniverse.core.data.sports.TeamDirectory? = null,
                       clips: List<HighlightClip> = emptyList(), photos: Map<com.yodesla.omniverse.core.data.sports.Sport, List<String>> = emptyMap(), onLongPress: () -> Unit = {}, onClick: () -> Unit) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val pair = guideTeams(a, dir)
    val solo = if (pair == null) teamIn(a, dir) else null
    val names = listOfNotNull(pair?.first?.name, pair?.second?.name, solo?.name)
    val photo = a.imageUrl ?: clips.firstOrNull { cl -> cl.teams.any { it in names } }?.thumbnailUrl ?: sportPhoto(photos, a.sport, a.title)
    val left = pair?.first ?: solo
    val right = pair?.second ?: solo
    FocusCard(onClick = onClick, onLongClick = onLongPress, modifier = Modifier.width(360.dp).height(200.dp)) {
        Box(Modifier.fillMaxSize().background(
            if (left != null && right != null) Brush.horizontalGradient(listOf(teamColor(left).deep(0.6f), Color(0xFF0A111B), teamColor(right).deep(0.6f)))
            else Brush.verticalGradient(listOf(Turf.copy(alpha = 0.25f), Color(0xFF0A111B))))) {
            photo?.let { url ->
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x80050B14), Color(0xEB050B14)))))
            }
            Column(Modifier.fillMaxSize().padding(OmniSpacing.m), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                    if (a.isLive(now)) LivePill() else Text(ClockFormat.format(a.startMs), style = t.title.copy(fontWeight = FontWeight.Bold), color = c.textPrimary)
                    Spacer(Modifier.weight(1f))
                    Text((a.league ?: a.sport.label).uppercase(), style = t.overline, color = c.textSecondary, maxLines = 1)
                    if (reminded) Text("⏰", style = t.caption, color = c.accent)
                }
                if (pair != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MiniTeam(pair.first, Modifier.weight(1f))
                    Text("vs", style = t.title.copy(fontWeight = FontWeight.Black), color = c.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
                    MiniTeam(pair.second, Modifier.weight(1f))
                } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                    solo?.let { TeamLogo(it, 52) }
                    Text(a.title, style = t.body.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                    LogoImage(a.logoUrl, a.channelName, Modifier.width(48.dp).height(28.dp))
                    Text(if (channels > 1) "Live on $channels channels  ·  OK to choose" else a.channelName, style = t.caption,
                        color = if (channels > 1) c.accent else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (a.isLive(now)) ProgressLine(((now - a.startMs).toFloat() / (a.endMs - a.startMs).coerceAtLeast(1)).coerceIn(0f, 1f), Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ChannelCard(ch: SportsChannelUi, now: Long, onClick: () -> Unit) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    FocusCard(onClick = onClick, modifier = Modifier.width(240.dp).height(140.dp)) {
        Column(Modifier.fillMaxSize().padding(OmniSpacing.m), verticalArrangement = Arrangement.SpaceBetween) {
            LogoImage(ch.row.logoUrl, ch.row.name, Modifier.width(80.dp).height(44.dp))
            Column {
                Text(ch.row.name, style = t.body.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ch.nowTitle ?: "No guide information", style = t.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val s = ch.nowStart; val e = ch.nowEnd
            if (s != null && e != null && e > s) ProgressLine(((now - s).toFloat() / (e - s)).coerceIn(0f, 1f), Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LeagueChips(leagues: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
        item { Chip("All sports", selected == null) { onSelect(null) } }
        items(leagues, key = { it }) { lg -> Chip(lg, selected == lg) { onSelect(lg) } }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = OmniTheme.colors
    FocusCard(onClick = onClick, modifier = Modifier.height(40.dp)) {
        Box(Modifier.fillMaxSize().background(if (selected) Turf.copy(alpha = 0.28f) else Color.Transparent).padding(horizontal = OmniSpacing.m), contentAlignment = Alignment.Center) {
            Text(label, style = OmniTheme.type.body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = if (selected) c.textPrimary else c.textSecondary)
        }
    }
}

@Composable
private fun Shelf(title: String, caption: String, liveDot: Boolean = false, content: LazyListScope.() -> Unit) {
    val c = OmniTheme.colors
    Column {
        Row(Modifier.padding(start = OmniSpacing.tvSide, bottom = OmniSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            if (liveDot) { Box(Modifier.size(10.dp).clip(CircleShape).background(LiveRed)); Spacer(Modifier.width(OmniSpacing.s)) }
            Text(title, style = OmniTheme.type.title.copy(fontWeight = FontWeight.Bold), color = c.textPrimary)
            Spacer(Modifier.width(OmniSpacing.m))
            Text(caption, style = OmniTheme.type.caption, color = c.textSecondary)
        }
        FocusPivot {
            LazyRow(contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.s), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m), content = content)
        }
    }
}

@Composable
private fun OnlineFooter(online: Boolean, onToggle: () -> Unit) {
    val c = OmniTheme.colors
    Row(Modifier.padding(horizontal = OmniSpacing.tvSide), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
        FocusCard(onClick = onToggle, modifier = Modifier.height(40.dp)) {
            Box(Modifier.fillMaxHeight().padding(horizontal = OmniSpacing.m), contentAlignment = Alignment.Center) {
                Text("Online schedule & highlights: ${if (online) "On" else "Off"}", style = OmniTheme.type.body, color = c.textPrimary)
            }
        }
        Text("Schedules, scores, team logos and clips come from ESPN's public feeds. Only league and date are requested — nothing about you or your providers.",
            style = OmniTheme.type.caption, color = c.textSecondary, modifier = Modifier.widthIn(max = 900.dp))
    }
}

@Composable
private fun LivePill() {
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(LiveRed).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White)); Spacer(Modifier.width(6.dp))
        Text("LIVE", style = OmniTheme.type.caption.copy(fontWeight = FontWeight.Black), color = Color.White)
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(text, style = OmniTheme.type.overline, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

/** Highlight clip on a TextureView (clipped with the card). Muted until the viewer picks it. */
@Composable
private fun ClipVideo(url: String, muted: Boolean, paused: Boolean = false, loop: Boolean = true, onEnded: () -> Unit = {}, modifier: Modifier) {
    val context = LocalContext.current
    val ended = androidx.compose.runtime.rememberUpdatedState(onEnded)
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url)); repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = 0f; prepare(); playWhenReady = true
        }
    }
    LaunchedEffect(player, muted, paused) { player.volume = if (muted) 0f else 1f; player.playWhenReady = !paused }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended.value() }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    AndroidView(factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } }, modifier = modifier)
}

private fun whenText(ms: Long, now: Long): String = "${SportsViewModel.dayLabel(ms, now)}  ${ClockFormat.format(ms)}"

/** "Where to watch": every channel carrying this live game. Back closes it. */
@Composable
private fun WatchPickerOverlay(p: WatchPicker, onPick: (WatchOption) -> Unit, onClose: () -> Unit) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val first = remember(p) { FocusRequester() }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color(0xD9040A12)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(20.dp)).background(c.elevated).padding(OmniSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
            Text("WHERE TO WATCH", style = t.overline, color = c.accent)
            Text(p.title, style = t.headline, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(p.subtitle, style = t.body, color = c.textSecondary)
            Spacer(Modifier.height(OmniSpacing.s))
            LazyColumn(Modifier.height((p.options.size.coerceAtMost(6) * 64).dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                items(p.options, key = { "${it.key.sourceId.value}-${it.key.remoteId.value}" }) { o ->
                    FocusCard(onClick = { onPick(o) }, modifier = Modifier.fillMaxWidth().height(56.dp).then(if (o == p.options.first()) Modifier.focusRequester(first) else Modifier)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                            LogoImage(o.logoUrl, o.name, Modifier.width(64.dp).height(36.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.name, style = t.body.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                o.note?.let { Text(it, style = t.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            Text("Watch", style = t.body, color = c.accent)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(p) { first.requestFocusWhenReady() }
}

/** Hold OK on a game: follow either team, or stop following it. Back closes it. */
@Composable
private fun FollowMenuOverlay(m: FollowMenu, onToggle: (FollowOption) -> Unit, onClose: () -> Unit) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val first = remember(m) { FocusRequester() }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color(0xD9040A12)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 700.dp).clip(RoundedCornerShape(20.dp)).background(c.elevated).padding(OmniSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
            Text("MY TEAMS", style = t.overline, color = c.accent)
            Text(m.title, style = t.headline, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("OK follows a team; its games then lead the Sports page and Home.", style = t.body, color = c.textSecondary)
            Spacer(Modifier.height(OmniSpacing.s))
            LazyColumn(Modifier.height((m.options.size.coerceAtMost(4) * 64).dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                items(m.options, key = { it.name }) { o ->
                    FocusCard(onClick = { onToggle(o) }, modifier = Modifier.fillMaxWidth().height(56.dp).then(if (o == m.options.first()) Modifier.focusRequester(first) else Modifier)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                            Column(Modifier.weight(1f)) {
                                Text(o.name, style = t.body.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                o.league?.let { Text(it, style = t.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            Text(if (o.followed) "Following  ·  OK to stop" else "Follow", style = t.body, color = if (o.followed) c.textSecondary else c.accent)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(m) { first.requestFocusWhenReady() }
}

/** A stable pick (same card → same photo) from the sport's recent photos, else any recent sports photo. */
private fun sportPhoto(photos: Map<com.yodesla.omniverse.core.data.sports.Sport, List<String>>, sport: com.yodesla.omniverse.core.data.sports.Sport, seed: String): String? {
    val list = photos[sport]?.takeIf { it.isNotEmpty() } ?: photos.values.flatten().takeIf { it.isNotEmpty() } ?: return null
    return list[(seed.hashCode() and 0x7fffffff) % list.size]
}
