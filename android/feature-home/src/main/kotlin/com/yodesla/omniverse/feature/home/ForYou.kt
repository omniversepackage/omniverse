package com.yodesla.omniverse.feature.home
import com.yodesla.omniverse.designsystem.featheredBackground
import com.yodesla.omniverse.designsystem.channelFavoriteMenu
import com.yodesla.omniverse.designsystem.ChannelFavoriteButton
import com.yodesla.omniverse.designsystem.FavoriteChannel


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.*
import com.yodesla.omniverse.core.data.sports.*
import com.yodesla.omniverse.core.model.*
import com.yodesla.omniverse.designsystem.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PersonalShelf(val id: String, val title: String, val reason: String, val cards: List<HomeCard>)
data class ForYouState(val shelves: List<PersonalShelf> = emptyList(), val teams: List<FollowedTeam> = emptyList(),
    val games: List<MyTeamItem> = emptyList(), val stories: List<InterestStory> = emptyList(),
    val interests: List<String> = emptyList(), val newsEnabled: Boolean = true, val loading: Boolean = true)

@OptIn(ExperimentalCoroutinesApi::class)
class ForYouViewModel(private val catalog: CatalogRepository, private val user: UserDataRepository,
    search: SearchRepository, visibility: Flow<Visibility>, follows: FollowedTeamsStore,
    myTeams: MyTeamsRepository, excluded: Flow<Collection<String>>, private val allowNews: Boolean = true) : ViewModel() {
    private val _state = MutableStateFlow(ForYouState())
    val state = _state.asStateFlow()
    private val stories = MutableStateFlow<List<InterestStory>>(emptyList())
    private data class Input(val favorites: List<ContentKey>, val progress: List<Progress>, val visible: Visibility,
        val history: String?, val teams: List<FollowedTeam>)
    init {
        val input = combine(user.favorites(), user.savedProgress(60), visibility, user.setting(SEARCH_HISTORY_KEY), follows.teams) { f, p, v, h, t -> Input(f,p,v,h,t) }
        val newsOn = user.setting("for_you_news").map { allowNews && it != "false" }
        viewModelScope.launch {
            newsOn.collectLatest { enabled ->
                if (enabled) stories.value = ForYouNews.fetch() else stories.value = emptyList()
            }
        }
        viewModelScope.launch {
            combine(input, excluded, newsOn, stories) { i, e, n, s -> arrayOf(i,e,n,s) }.collectLatest { values ->
                val i = values[0] as Input
                @Suppress("UNCHECKED_CAST") val off = values[1] as Collection<String>
                val newsEnabled = values[2] as Boolean
                @Suppress("UNCHECKED_CAST") val news = values[3] as List<InterestStory>
                _state.update { it.copy(loading = true, newsEnabled = newsEnabled) }
                val built = withContext(Dispatchers.Default) {
                    suspend fun visiblePoster(key: ContentKey): PosterRow? = catalog.poster(key)?.takeIf { p -> p.categoryId?.let { i.visible(key.kind.name, key.sourceId.value, it.value) } == true }
                    fun PosterRow.card(reason: String) = HomeCard("${key.kind}/${key.sourceId.value}/${key.remoteId.value}", key, name,
                        reason.ifBlank { year?.toString().orEmpty() }, posterUrl, isChannel = key.kind == ContentKind.LIVE, categoryId = categoryId)
                    val favorites = i.favorites.mapNotNull { key ->
                        if (key.kind == ContentKind.LIVE) catalog.channel(key)?.takeIf { i.visible("LIVE", key.sourceId.value, it.categoryId.value) }?.let {
                            HomeCard("live/${key.sourceId.value}/${key.remoteId.value}", key, it.name, "Live TV", it.logoUrl, isChannel = true, categoryId = it.categoryId)
                        } else visiblePoster(key)?.card("")
                    }
                    val shelves = mutableListOf<PersonalShelf>()
                    for ((kind,label) in listOf(ContentKind.VOD to "Your movies",ContentKind.SERIES to "Your shows",ContentKind.LIVE to "Your channels")) {
                        val cards = favorites.filter { it.open.kind == kind }
                        if (cards.isNotEmpty()) shelves += PersonalShelf("saved-${kind.name}", label, "Saved by you", cards)
                    }
                    val watched = i.progress.map { p ->
                        if (p.key.kind == ContentKind.EPISODE && p.parentId != null) ContentKey(p.key.sourceId, ContentKind.SERIES, p.parentId!!) else p.key
                    }
                    val seeds = (watched.take(3) + i.favorites.filter { it.kind != ContentKind.LIVE }.take(3)).distinct()
                    val interests = mutableListOf<String>()
                    val seen = (favorites.map { it.open } + watched).toMutableSet()
                    val recs = mutableListOf<HomeCard>()
                    for (seed in seeds) {
                        val p = visiblePoster(seed) ?: continue
                        interests += p.name; p.genre?.let { interests += it }
                        for (candidate in catalog.similarTo(seed, 12)) {
                            if (candidate.key !in seen && candidate.categoryId?.let { i.visible(candidate.key.kind.name,candidate.key.sourceId.value,it.value) } == true) {
                                seen += candidate.key; recs += candidate.card("Because you like ${p.name}")
                            }
                        }
                    }
                    val history = i.history.orEmpty().lineSequence().filter { it.length >= 3 }.take(3).toList()
                    interests += history; interests += i.teams.map { it.name }
                    for (q in history) {
                        for (candidate in search.searchFiltered(q, SearchFilters.None, off, 8).values.flatMap { it.rows }) {
                            if (seen.add(candidate.key) && candidate.categoryId?.let { i.visible(candidate.key.kind.name,candidate.key.sourceId.value,it.value) } == true)
                                recs += candidate.card("From your search: $q")
                        }
                    }
                    if (recs.isNotEmpty()) shelves += PersonalShelf("picked", "Picked for you", "Inspired by your favourites, watches and searches", recs.distinctBy { it.open }.take(40))
                    if (recs.isEmpty()) {
                        val discoveries = (catalog.recentlyAdded(ContentKind.VOD, 12, off).first() + catalog.recentlyAdded(ContentKind.SERIES, 12, off).first())
                            .filter { p -> p.key !in seen && p.categoryId?.let { i.visible(p.key.kind.name,p.key.sourceId.value,it.value) } == true }
                        if (discoveries.isNotEmpty()) shelves += PersonalShelf("discover", "A little discovery", "Save a few favourites to make this more personal", discoveries.map { it.card("Recently added") })
                    }
                    val games = myTeams.items { a -> i.visible("LIVE",a.channel.sourceId.value,a.categoryId.value) }
                    ForYouState(shelves, i.teams, games, news.sortedByDescending { ForYouNews.relevance(it,interests) }.take(12), interests, newsEnabled, false)
                }
                _state.value = built
            }
        }
    }
    fun toggleNews() { viewModelScope.launch { user.putSetting("for_you_news", (!_state.value.newsEnabled).toString()) } }
}

@Composable
fun ForYouRoute(vm: ForYouViewModel, name: String?, onOpen: (HomeCard) -> Unit, onMenu: () -> Unit,
    onMovies: () -> Unit, onShows: () -> Unit, onSports: () -> Unit, allowNews: Boolean = true) {
    val s by vm.state.collectAsStateWithLifecycle()
    val c = OmniTheme.colors; val t = OmniTheme.type
    val first = remember { FocusRequester() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var article by remember { mutableStateOf<InterestStory?>(null) }
    CosmicBackdrop(Modifier.fillMaxSize()) {
        FocusPivot(parentFraction = 0.1f) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 64.dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.l)) {
                item(key = "welcome") {
                    Column(Modifier.fillMaxWidth().featheredBackground(Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.18f),c.background.copy(alpha = 0.1f))))
                        .padding(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.xl), verticalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                        Text("YOUR WORLD, TOGETHER", style = t.overline, color = c.accent)
                        Text("For you${name?.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()}", style = t.display.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold), color = c.textPrimary)
                        Text("Your favourites. Your teams. Your next great find.", style = t.body, color = c.textSecondary)
                        Row(Modifier.onFocusChanged { if (it.hasFocus) scope.launch { delay(80); list.animateScrollToItem(0) } }, horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                            OmniButton("Movies", onMovies, Modifier.focusRequester(first).onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onMenu(); true } else false })
                            OmniButton("Shows", onShows); OmniButton("Your sports", onSports)
                            if (allowNews) OmniButton(if (s.newsEnabled) "News: on" else "News: off", vm::toggleNews)
                        }
                    }
                }
                if (s.loading) item { Text("Gathering your favourites…", Modifier.padding(horizontal = OmniSpacing.tvSide), style = t.body, color = c.textSecondary) }
                if (!s.loading && s.shelves.none { it.id.startsWith("saved") }) item {
                    Column(Modifier.padding(horizontal = OmniSpacing.tvSide)) {
                        Text("Make this place yours", style = t.title, color = c.textPrimary)
                        Text("Add movies, shows and channels to My List. Follow teams in Live Sports. They’ll be waiting here.", style = t.body, color = c.textSecondary)
                    }
                }
                s.shelves.forEach { shelf -> item(key = shelf.id) {
                    Column {
                        Column(Modifier.padding(horizontal = OmniSpacing.tvSide)) {
                            Text(shelf.title, style = t.title, color = c.textPrimary)
                            Text(shelf.reason, style = t.caption, color = c.textSecondary)
                        }
                        FocusPivot { LazyRow(contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide, vertical = OmniSpacing.focusEdge), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.l)) {
                            items(shelf.cards, key = { it.id }) { card ->
                                if (card.isChannel) FocusCard(onClick = { onOpen(card) }, onLongClick = channelFavoriteMenu(card.open.sourceId.value, card.open.remoteId.value, card.title, card.image), modifier = Modifier.width(240.dp).height(136.dp)) {
                                    Column(Modifier.fillMaxSize().padding(OmniSpacing.m), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        LogoImage(card.image, card.title, Modifier.size(80.dp, 44.dp))
                                        Text(card.title, style = t.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Live TV", style = t.caption, color = c.accent)
                                    }
                                } else PosterCard(card.title,card.image,{onOpen(card)},Modifier.width(128.dp)
                                    .onPreviewKeyEvent { e -> if(card == shelf.cards.first() && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {onMenu();true}else false },card.subtitle)
                            }
                        } }
                    }
                } }
                item(key = "teams") {
                    Column(Modifier.padding(horizontal = OmniSpacing.tvSide), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                        Text("Your team colours", style = t.title, color = c.textPrimary)
                        Text(if (s.teams.isEmpty()) "Follow a team in Live Sports to bring its games here." else s.teams.joinToString("   ·   ") { it.name }, style = t.body, color = c.accent)
                    }
                }
                if (s.games.isNotEmpty()) item(key = "games") {
                    LazyRow(contentPadding = PaddingValues(horizontal = OmniSpacing.tvSide,vertical = OmniSpacing.focusEdge),horizontalArrangement = Arrangement.spacedBy(OmniSpacing.l)) {
                        items(s.games,key={it.id}) { game ->
                            FocusCard(onClick={ val option=game.options.firstOrNull(); if(option==null)onSports()else onOpen(HomeCard(game.id,option.key,game.title,game.league,game.imageUrl,isChannel=true,categoryId=option.categoryId)) },onLongClick=game.options.firstOrNull()?.let { channelFavoriteMenu(it.key.sourceId.value,it.key.remoteId.value,it.name) },modifier=Modifier.width(300.dp).height(132.dp)) {
                                Column(Modifier.padding(OmniSpacing.l),verticalArrangement=Arrangement.spacedBy(OmniSpacing.s)) {
                                    Text(game.league,style=t.overline,color=c.accent)
                                    Text(game.title,style=t.title,color=c.textPrimary,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Text(if(game.options.isEmpty()) "Schedule & coverage" else "Watch on ${game.options.first().name}",style=t.caption,color=c.textSecondary,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (s.newsEnabled && allowNews) item(key = "news") {
                    Column {
                        Text(if(s.stories.any { ForYouNews.relevance(it,s.interests)>0 }) "In your orbit" else "Film, TV & sports headlines",Modifier.padding(horizontal=OmniSpacing.tvSide),style=t.title,color=c.textPrimary)
                        if(s.stories.isEmpty()) Text("Headlines are unavailable right now. Your saved favourites are always here.",Modifier.padding(OmniSpacing.tvSide),style=t.body,color=c.textSecondary)
                        LazyRow(contentPadding=PaddingValues(horizontal=OmniSpacing.tvSide,vertical=OmniSpacing.focusEdge),horizontalArrangement=Arrangement.spacedBy(OmniSpacing.l)) {
                            items(s.stories,key={it.url}) { story -> FocusCard(onClick={article=story},modifier=Modifier.width(320.dp).height(170.dp)) {
                                Column(Modifier.padding(OmniSpacing.l),verticalArrangement=Arrangement.spacedBy(OmniSpacing.s)) {
                                    Text("THE GUARDIAN",style=t.overline,color=c.accent)
                                    Text(story.title,style=t.title,color=c.textPrimary,maxLines=3,overflow=TextOverflow.Ellipsis)
                                    Text(story.published.take(16),style=t.caption,color=c.textSecondary)
                                }
                            } }
                        }
                    }
                }
            }
        }
    }
    article?.let { story ->
        val uri=LocalUriHandler.current
        Dialog(onDismissRequest={article=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
            Column(Modifier.widthIn(max=600.dp).glassSurface().padding(OmniSpacing.tvSide),verticalArrangement=Arrangement.spacedBy(OmniSpacing.l)) {
                Text(story.title,style=t.title,color=c.textPrimary,maxLines=3,overflow=TextOverflow.Ellipsis)
                Text(story.summary,style=t.body,color=c.textSecondary,maxLines=6,overflow=TextOverflow.Ellipsis)
                Row(horizontalArrangement=Arrangement.spacedBy(OmniSpacing.m)) {
                    OmniButton("Close",{article=null})
                    OmniButton("Read article",{runCatching{uri.openUri(story.url)}},primary=true)
                }
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocusWhenReady() }
}
