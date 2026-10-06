package com.yodesla.omniverse.feature.live.sports

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.data.reminders.Reminder
import com.yodesla.omniverse.core.data.reminders.ReminderStore
import com.yodesla.omniverse.core.data.sports.EspnSportsFeed
import com.yodesla.omniverse.core.data.sports.FollowedTeam
import com.yodesla.omniverse.core.data.sports.FollowedTeams
import com.yodesla.omniverse.core.data.sports.FollowedTeamsStore
import com.yodesla.omniverse.core.data.sports.GameState
import com.yodesla.omniverse.core.data.sports.HighlightClip
import com.yodesla.omniverse.core.data.sports.NetworkMatcher
import com.yodesla.omniverse.core.data.sports.ScheduledGame
import com.yodesla.omniverse.core.data.sports.SportsAiring
import com.yodesla.omniverse.core.data.sports.SportsKind
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.RemoteId
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

@Immutable
data class SportsChannelUi(val row: ChannelRow, val nowTitle: String?, val nowStart: Long?, val nowEnd: Long?)

/** One place to watch something: a channel in one of the viewer's sources. */
@Immutable
data class WatchOption(val key: ContentKey, val categoryId: RemoteId, val name: String, val logoUrl: String?, val note: String? = null)

/** A scheduled game plus the viewer's channels that carry it (best match first). */
@Immutable
data class GameUi(val game: ScheduledGame, val channels: List<ChannelRow>, val clip: HighlightClip?, val extra: List<WatchOption> = emptyList()) {
    val id get() = game.id
    /** Network matches first, then guide listings of the same game on other channels. */
    val options: List<WatchOption> get() = (channels.map { WatchOption(it.key, it.categoryId, it.name, it.logoUrl) } + extra).distinctBy { it.key }
}

/** The same live game found on several of the viewer's channels, shown as ONE card. */
@Immutable
data class LiveGroup(val id: String, val sample: SportsAiring, val options: List<WatchOption>)

/** "Where to watch" chooser opened from a card with more than one option. */
@Immutable
data class WatchPicker(val title: String, val subtitle: String, val options: List<WatchOption>)

@Immutable
data class GameDay(val label: String, val games: List<GameUi>)

/** One team a card offers to follow, and whether this profile already follows it. */
@Immutable
data class FollowOption(val name: String, val league: String?, val followed: Boolean)

/** The follow chooser opened by holding OK on a game card. */
@Immutable
data class FollowMenu(val title: String, val options: List<FollowOption>)

@Immutable
data class SportsUiState(
    val loading: Boolean = true,
    val nowMs: Long = 0,
    val online: Boolean = true,
    val featured: GameUi? = null,
    val liveGames: List<GameUi> = emptyList(),
    val days: List<GameDay> = emptyList(),
    val clips: List<HighlightClip> = emptyList(),
    /** Clip playing (with sound) in the hero after the viewer picked it; null = muted preview. */
    val playingClip: HighlightClip? = null,
    /** What the hero showed when a clip took it over; handed back unchanged when the clip stops. */
    val heroReturn: GameUi? = null,
    /** "No highlights for <league> right now" when the selected league has none. */
    val clipsNote: String? = null,
    val liveGroups: List<LiveGroup> = emptyList(),
    val picker: WatchPicker? = null,
    /** Team names → logos/colours, for cards that come from the viewer's guide (titles only). */
    val teams: com.yodesla.omniverse.core.data.sports.TeamDirectory? = null,
    /** Recent news photos per sport: background art for cards with no picture of their own. */
    val photos: Map<com.yodesla.omniverse.core.data.sports.Sport, List<String>> = emptyMap(),
    val coverage: List<SportsAiring> = emptyList(),
    val channels: List<SportsChannelUi> = emptyList(),
    val leagues: List<String> = emptyList(),
    val selected: String? = null,
    val reminderIds: Set<String> = emptySet(),
    val message: String? = null,
    /** Task 103: the teams this profile follows. */
    val followed: List<FollowedTeam> = emptyList(),
    /** "My teams": the followed teams' live and upcoming games, live first. */
    val myTeams: List<GameUi> = emptyList(),
    val myTeamGroups: List<LiveGroup> = emptyList(),
    val myTeamAirings: List<SportsAiring> = emptyList(),
    /** Scores are hidden until the viewer reveals a card's score with OK. */
    val hideScores: Boolean = false,
    val revealed: Set<String> = emptySet(),
    val followMenu: FollowMenu? = null,
) {
    /** True while this card's score is being kept from the viewer. */
    fun scoreHidden(id: String): Boolean = hideScores && id !in revealed
}

sealed interface SportsEvent { data class Watch(val key: ContentKey, val categoryId: RemoteId) : SportsEvent }

/**
 * Live Sports: the public schedule (every league's games for the next week, live scores,
 * networks, logos, highlight clips) merged with the viewer's own guide and channels. Each game
 * is matched to the channels that carry its network; OK watches a live game there or sets a
 * reminder on it. Parental and switched-off-library visibility apply to every channel shown.
 */
class SportsViewModel(
    private val epg: EpgRepository,
    private val clock: Clock,
    private val visibility: Flow<Visibility> = ShowEverything,
    private val reminders: ReminderStore? = null,
    private val feed: EspnSportsFeed? = null,
    private val onlineSetting: Flow<Boolean> = flowOf(true),
    private val saveOnline: suspend (Boolean) -> Unit = {},
    /** Task 103: this profile's followed teams and score switch (null = following is off). */
    private val follows: FollowedTeamsStore? = null,
    /** Tests load once; the app keeps the page fresh on its own. */
    private val autoRefresh: Boolean = true,
) : ViewModel() {
    private val _state = MutableStateFlow(SportsUiState())
    val state: StateFlow<SportsUiState> = _state.asStateFlow()
    private val events = Channel<SportsEvent>(Channel.BUFFERED)
    val eventFlow: Flow<SportsEvent> = events.receiveAsFlow()
    private var games: List<GameUi> = emptyList()
    private var airings: List<SportsAiring> = emptyList()
    private val channelCache = HashMap<String, List<ChannelRow>>()
    private val hero = HeroPlayback()

    init {
        viewModelScope.launch {
            combine(visibility, onlineSetting) { v, o -> v to o }.collectLatest { (vis, online) ->
                channelCache.clear()
                while (true) {
                    load(vis, online)
                    if (!autoRefresh) break
                    delay(if (games.any { it.game.state == GameState.LIVE }) 45_000 else 120_000)
                }
            }
        }
        reminders?.let { store ->
            viewModelScope.launch { store.reminders.collect { list -> _state.update { it.copy(reminderIds = list.mapTo(HashSet()) { r -> r.id }) } } }
        }
        follows?.let { store ->
            viewModelScope.launch { store.teams.collect { list -> _state.update { it.copy(followed = list) }; regroup() } }
            viewModelScope.launch { store.hideScores.collect { hide -> _state.update { it.copy(hideScores = hide) } } }
        }
    }

    /**
     * Every part loads on its own and shows the moment it is ready: the online schedule (games
     * first, channel matches a moment later), highlight clips, the viewer's guide, and the sports
     * channels row. Nothing waits for the slowest part.
     */
    private suspend fun load(vis: Visibility, online: Boolean) = kotlinx.coroutines.coroutineScope {
        val now = clock.nowMs()
        _state.update { it.copy(loading = false, nowMs = now, online = online) }
        val f = feed?.takeIf { online }
        launch {
            fun show(list: List<ScheduledGame>) {
                games = list.map { g -> games.firstOrNull { it.id == g.id }?.copy(game = g) ?: GameUi(g, emptyList(), null) }
                attachClips(); publishLeagues(); regroup()
            }
            val raw = f?.let { feedNow ->
                runCatching { feedNow.games { partial -> viewModelScope.launch { show(partial) } } }.getOrDefault(emptyList())
            }.orEmpty()
            games = raw.map { g -> games.firstOrNull { it.id == g.id }?.copy(game = g) ?: GameUi(g, emptyList(), null) }
            attachClips(); publishLeagues(); regroup()
            games = games.map { g -> g.copy(channels = channelsFor(g.game, vis)) }
            regroup()
        }
        launch {
            val ph = f?.let { runCatching { it.photos() }.getOrDefault(emptyMap()) }.orEmpty()
            if (ph.isNotEmpty()) _state.update { it.copy(photos = ph) }
        }
        launch {
            val dir = f?.let { runCatching { it.teams() }.getOrNull() }
            if (dir != null && !dir.isEmpty) _state.update { it.copy(teams = dir) }
        }
        launch {
            if (f == null) clips = emptyList()
            else mergeClips(runCatching { f.clips(_state.value.selected) }.getOrDefault(emptyList()))
            publishClips(); attachClips(); regroup()
        }
        launch {
            airings = runCatching { epg.sportsSchedule(now) }.getOrDefault(emptyList()).filter { vis("LIVE", it.channel.sourceId.value, it.categoryId.value) }
            publishLeagues(); regroup()
        }
        launch {
            val chans = runCatching { epg.sportsChannels() }.getOrDefault(emptyList()).filter { vis("LIVE", it.key.sourceId.value, it.categoryId.value) }
            val nn = chans.groupBy { it.key.sourceId }.flatMap { (src, rows) ->
                val map = runCatching { epg.nowNext(src, rows.mapNotNull { it.epgKey }, now) }.getOrDefault(emptyMap())
                rows.map { r -> SportsChannelUi(r, r.epgKey?.let { map[it]?.now?.title }, r.epgKey?.let { map[it]?.now?.startMs }, r.epgKey?.let { map[it]?.now?.endMs }) }
            }
            _state.update { it.copy(channels = nn) }
        }
    }

    private var clips: List<HighlightClip> = emptyList()

    /** Fold freshly fetched clips into everything we've seen, newest first (the "All sports" order). */
    private fun mergeClips(got: List<HighlightClip>) {
        clips = (clips + got).distinctBy { it.id }.sortedByDescending { it.publishedMs ?: Long.MIN_VALUE }
    }

    /** The Highlights row follows the league chip: that league's clips, or all mixed newest first. */
    private fun publishClips() {
        val s = _state.value
        val sel = s.selected
        val vis = if (sel == null) clips else clips.filter { it.league.equals(sel, ignoreCase = true) }
        val note = if (vis.isEmpty() && sel != null && s.online && !s.loading) "No highlights for $sel right now" else null
        _state.update { it.copy(clips = vis.take(30), clipsNote = note) }
    }

    /** After a selection change: show what we already have for it at once, then fetch that league. */
    private fun refreshClips() {
        val f = feed ?: return
        viewModelScope.launch {
            if (!_state.value.online) return@launch
            mergeClips(runCatching { f.clips(_state.value.selected) }.getOrDefault(emptyList()))
            publishClips()
        }
    }

    private fun attachClips() {
        if (clips.isEmpty()) return
        games = games.map { g ->
            g.copy(clip = clips.firstOrNull { c -> c.gameId == g.id } ?: clips.firstOrNull { c -> c.teams.any { it == g.game.home.name || it == g.game.away.name } })
        }
    }

    private fun publishLeagues() {
        _state.update { it.copy(leagues = (games.map { g -> g.game.league } + airings.mapNotNull { a -> a.league }).distinct()) }
    }

    /** Viewer channels for the game's networks, best match first (US feeds before other regions). */
    private suspend fun channelsFor(g: ScheduledGame, vis: Visibility): List<ChannelRow> {
        val scored = HashMap<ContentKey, Pair<ChannelRow, Int>>()
        for (net in g.networks) {
            val rows = channelCache.getOrPut(net.lowercase()) {
                NetworkMatcher.termsFor(net).flatMap { t -> runCatching { epg.channelsNamed(t) }.getOrDefault(emptyList()) }
            }
            for (r in rows) {
                if (!vis("LIVE", r.key.sourceId.value, r.categoryId.value)) continue
                val s = NetworkMatcher.score(r.name, net)
                if (s > 0 && s > (scored[r.key]?.second ?: 0)) scored[r.key] = r to s
            }
        }
        return scored.values.sortedByDescending { it.second }.map { it.first }.take(4)
    }

    private fun regroup() {
        val s = _state.value
        val now = clock.nowMs()
        val pick = games.filter { s.selected == null || it.game.league == s.selected }
        val live = pick.filter { it.game.state == GameState.LIVE }
        val upcoming = pick.filter { it.game.state == GameState.PRE && it.game.startMs > now - 15 * 60_000L }
        // Hero: a live game you can watch, else the next game you can watch, else the next game.
        val airPick = airings.filter { s.selected == null || it.league == s.selected }
        // Guide listings of the same live game on many channels collapse to one card; a group that
        // is the same game as a scheduled one folds into that game's "where to watch" list instead.
        val groups = airPick.filter { a -> a.kind == SportsKind.EVENT && a.isLive(now) }.groupBy(::groupKey).map { (k, list) ->
            LiveGroup(k, list.first(), list.map { a -> WatchOption(a.channel, a.categoryId, a.channelName, a.logoUrl, a.title) }.distinctBy { it.key })
        }
        val absorbed = HashSet<String>()
        val liveMerged = live.map { g ->
            val mine = groups.filter { grp -> sameGame(grp.sample, g.game) }
            absorbed += mine.map { it.id }
            if (mine.isEmpty()) g else g.copy(extra = mine.flatMap { it.options })
        }
        games = games.map { g -> liveMerged.firstOrNull { it.id == g.id } ?: g }
        // Hero: a live game you can watch, else the next game you can watch, else the next game.
        val computed = live.firstOrNull { it.channels.isNotEmpty() } ?: live.firstOrNull()
            ?: upcoming.firstOrNull { it.channels.isNotEmpty() && it.game.startMs - now < 36 * 3_600_000L } ?: upcoming.firstOrNull()
        val newFeatured = computed?.let { f -> liveMerged.firstOrNull { m -> m.id == f.id } ?: f }
        // While a clip has taken the hero over, its parked content stays exactly as it was.
        val featured = if (s.playingClip != null) s.heroReturn else newFeatured
        // Task 103: the same schedule narrowed to the teams this profile follows (live first, then
        // the next kick-offs), including guide-only games no scheduled feed carried.
        val followed = s.followed
        val mine = if (followed.isEmpty()) emptyList() else
            (liveMerged + upcoming).filter { FollowedTeams.match(followed, it.game) != null }.distinctBy { it.id }
        val mineGroups = if (followed.isEmpty()) emptyList() else
            groups.filter { grp -> grp.id !in absorbed && FollowedTeams.match(followed, grp.sample) != null }
        val mineAirings = if (followed.isEmpty()) emptyList() else
            airPick.filter { a -> a.kind == SportsKind.EVENT && !a.isLive(now) && a.endMs > now && FollowedTeams.match(followed, a) != null }
                .distinctBy { reminderId(it) }.take(12)
        _state.update {
            it.copy(
                nowMs = now,
                featured = featured,
                liveGames = liveMerged,
                liveGroups = groups.filter { grp -> grp.id !in absorbed },
                days = upcoming.groupBy { g -> dayLabel(g.game.startMs, now) }.map { (label, list) -> GameDay(label, list) },
                coverage = airPick.filter { a -> a.kind == SportsKind.COVERAGE && a.endMs > now }.distinctBy { a -> a.title + a.channel }.take(30),
                myTeams = mine,
                myTeamGroups = mineGroups,
                myTeamAirings = mineAirings,
            )
        }
    }

    fun select(league: String?) {
        _state.update { it.copy(selected = league) }
        publishClips(); regroup(); refreshClips()
    }

    /** OK on a highlight: it takes the hero over (sound on); the hero's content is parked. */
    fun playClip(clip: HighlightClip) {
        hero.play(clip, _state.value.featured)
        _state.update { it.copy(playingClip = clip, heroReturn = hero.parked) }
    }

    /** Back or the clip ending: the hero goes back to exactly what it showed before. */
    fun stopClip() {
        if (!hero.isPlaying) return
        val was = hero.stop()
        _state.update { it.copy(playingClip = null, heroReturn = null, featured = was) }
    }

    fun setOnline(on: Boolean) { viewModelScope.launch { saveOnline(on) } }

    /** Live → watch on the best channel. Upcoming → reminder on that channel. Else explain. */
    fun open(g: GameUi) {
        // Task 103: with scores hidden, the first OK on a card that shows one reveals it instead.
        if (g.game.state != GameState.PRE && _state.value.scoreHidden(g.id)) { revealScore(g.id); return }
        val ch = g.channels.firstOrNull()
        val nets = g.game.networks.joinToString(", ").ifEmpty { "a network not listed yet" }
        when {
            g.game.state == GameState.FINAL -> g.clip?.let { playClip(it) } ?: say("${matchup(g.game)} has finished.")
            g.game.state == GameState.LIVE && g.options.size > 1 -> _state.update { it.copy(picker = WatchPicker(matchup(g.game), "Live now · ${g.options.size} places to watch", g.options)) }
            g.game.state == GameState.LIVE && g.options.size == 1 -> g.options.first().let { o -> events.trySend(SportsEvent.Watch(o.key, o.categoryId)) }
            ch == null -> say("${matchup(g.game)} is on $nets — none of your channels carry it.")
            else -> reminders?.let { store ->
                viewModelScope.launch {
                    val set = store.toggle(Reminder(ch.key.sourceId.value, ch.key.remoteId.value, ch.categoryId.value,
                        g.game.startMs, g.game.startMs + 3 * 3_600_000L, matchup(g.game), ch.name))
                    say(if (set) "Reminder set: ${matchup(g.game)} on ${ch.name}" else "Reminder removed: ${matchup(g.game)}")
                }
            }
        }
    }

    /** A grouped live game from the guide: straight in when there's one channel, else choose. */
    fun openGroup(g: LiveGroup) {
        if (g.options.size == 1) g.options.first().let { o -> events.trySend(SportsEvent.Watch(o.key, o.categoryId)) }
        else _state.update { it.copy(picker = WatchPicker(g.sample.teams?.let { (a, b) -> "$a vs $b" } ?: g.sample.title, "Live now · ${g.options.size} channels", g.options)) }
    }

    fun pick(o: WatchOption) { _state.update { it.copy(picker = null) }; events.trySend(SportsEvent.Watch(o.key, o.categoryId)) }

    fun closePicker() { _state.update { it.copy(picker = null) } }

    /** Show this card's score for the rest of the visit (task 103's "OK reveals the score"). */
    fun revealScore(id: String) { _state.update { it.copy(revealed = it.revealed + id) } }

    /** Hold OK on a scheduled game: follow or unfollow either side of it. */
    fun openFollowMenu(g: GameUi) = followMenuFor(listOf(g.game.away.name, g.game.home.name), g.game.league, matchup(g.game))

    /** Hold OK on a game found in the viewer's own guide. */
    fun openFollowMenu(a: SportsAiring) = followMenuFor(a.teams?.let { listOf(it.first, it.second) } ?: listOf(a.title), a.league, a.teams?.let { (x, y) -> "$x vs $y" } ?: a.title)

    private fun followMenuFor(names: List<String>, league: String?, title: String) {
        val followed = _state.value.followed
        _state.update {
            it.copy(followMenu = FollowMenu(title, names.map { n ->
                FollowOption(n, league, FollowedTeams.match(followed, n, league) != null)
            }))
        }
    }

    fun closeFollowMenu() { _state.update { it.copy(followMenu = null) } }

    /** Follow [o] (or stop following it); the followed list and every "My teams" row refresh after. */
    fun toggleFollow(o: FollowOption) {
        val store = follows ?: return
        viewModelScope.launch {
            val nowFollowing = store.toggle(o.name, o.league)
            _state.update { s ->
                s.copy(
                    followed = store.current(),
                    followMenu = s.followMenu?.let { menu ->
                        FollowMenu(menu.title, menu.options.map { if (it.name == o.name) it.copy(followed = nowFollowing) else it })
                    },
                )
            }
            regroup()
            say(if (nowFollowing) "Following ${o.name}" else "Stopped following ${o.name}")
        }
    }

    /** Settings > Display switch (task 103). */
    fun setHideScores(hide: Boolean) {
        val store = follows ?: return
        _state.update { it.copy(hideScores = hide, revealed = emptySet()) }
        viewModelScope.launch { store.setHideScores(hide) }
    }

    fun openAiring(a: SportsAiring) {
        if (a.startMs <= clock.nowMs()) { events.trySend(SportsEvent.Watch(a.channel, a.categoryId)); return }
        val store = reminders ?: return
        viewModelScope.launch {
            val set = store.toggle(Reminder(a.channel.sourceId.value, a.channel.remoteId.value, a.categoryId.value, a.startMs, a.endMs, a.title, a.channelName))
            say(if (set) "Reminder set: ${a.title}" else "Reminder removed: ${a.title}")
        }
    }

    fun watchChannel(row: ChannelRow) { events.trySend(SportsEvent.Watch(row.key, row.categoryId)) }

    private fun say(m: String) { _state.update { it.copy(message = m) } }

    companion object {
        private fun norm(s: String) = s.lowercase().replace(Regex("""^[^:|]{1,30}[:|]\s*"""), "").replace(Regex("[^a-z0-9]"), "")

        /** Same game regardless of channel prefix, punctuation or "vs"/"at" wording. */
        fun groupKey(a: SportsAiring): String = a.teams?.let { (x, y) -> listOf(norm(x), norm(y)).sorted().joinToString("|") } ?: norm(a.title)

        /** A guide airing is the scheduled game when both teams' nicknames appear in it. */
        fun sameGame(a: SportsAiring, g: ScheduledGame): Boolean {
            val text = (a.teams?.let { "${it.first} ${it.second}" } ?: a.title).lowercase()
            return listOf(g.home.shortName, g.away.shortName).all { it.isNotBlank() && it.lowercase() in text }
        }

        fun matchup(g: ScheduledGame) = "${g.away.shortName} at ${g.home.shortName}"

        fun reminderId(g: GameUi) = g.channels.firstOrNull()?.let { "${it.key.sourceId.value}|${it.key.remoteId.value}|${g.game.startMs}" }
        fun reminderId(a: SportsAiring) = "${a.channel.sourceId.value}|${a.channel.remoteId.value}|${a.startMs}"

        fun dayLabel(ms: Long, nowMs: Long): String {
            val day = Calendar.getInstance().apply { timeInMillis = ms }
            val today = Calendar.getInstance().apply { timeInMillis = nowMs }
            val diff = ((startOfDay(day) - startOfDay(today)) / 86_400_000L).toInt()
            return when (diff) {
                0 -> if (day.get(Calendar.HOUR_OF_DAY) >= 17) "Tonight" else "Today"
                1 -> "Tomorrow"
                else -> String.format(java.util.Locale.getDefault(), "%1\$tA, %1\$tb %1\$te", day)
            }
        }

        private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
