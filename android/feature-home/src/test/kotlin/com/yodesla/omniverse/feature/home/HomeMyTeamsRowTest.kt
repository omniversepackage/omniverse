package com.yodesla.omniverse.feature.home

import androidx.paging.PagingSource
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.NowNext
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.data.sports.FollowedTeam
import com.yodesla.omniverse.core.data.sports.FollowedTeams
import com.yodesla.omniverse.core.data.sports.FollowedTeamsStore
import com.yodesla.omniverse.core.data.sports.MyTeamsRepository
import com.yodesla.omniverse.core.data.sports.Sport
import com.yodesla.omniverse.core.data.sports.SportsAiring
import com.yodesla.omniverse.core.data.sports.SportsKind
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.TimeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeMyTeamsRowTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val src = SourceId("s1")
    private val sportsCat = RemoteId("sports")
    private val now = 1_700_000_000_000L
    private val chiefs = FollowedTeam("l:NFL|chiefs", "Kansas City Chiefs", "NFL")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val live = SportsAiring(
        channel = ContentKey(src, ContentKind.LIVE, RemoteId("espn")),
        categoryId = sportsCat, channelName = "ESPN", logoUrl = null,
        title = "NFL: Kansas City Chiefs at Las Vegas Raiders", description = null,
        startMs = now - 600_000, endMs = now + 3_600_000,
        sport = Sport.FOOTBALL, kind = SportsKind.EVENT,
        teams = "Kansas City Chiefs" to "Las Vegas Raiders", league = "NFL",
    )

    private val followed = mapOf(FollowedTeams.KEY to FollowedTeams.encode(listOf(chiefs)))

    private fun TestScope.home(settings: Map<String, String>, visibility: Flow<Visibility> = ShowEverything): HomeViewModel {
        val userData = FakeUserData(settings)
        val epg = FakeEpg(listOf(live))
        return HomeViewModel(
            FakeCatalog(), epg, userData, Clock { now }, visibility,
            myTeams = MyTeamsRepository(epg, Clock { now }, FollowedTeamsStore(userData)),
            compute = StandardTestDispatcher(testScheduler),
        )
    }

    @Test fun myTeamsLeadsHomeAndOpensTheChannelCarryingTheGame() = runTest(dispatcher) {
        val vm = home(followed)
        advanceUntilIdle()
        assertEquals("myTeams", vm.state.value.rows.first().id)
        val card = assertNotNull(vm.state.value.rows.firstOrNull { it.id == "myTeams" }).cards.single()
        assertEquals(live.channel, card.open)
        assertEquals(sportsCat, card.categoryId)
        assertEquals("My teams", card.section)
        assertTrue(card.isChannel)
        assertTrue((card.subtitle ?: "").startsWith("Live now"))
        assertTrue((card.subtitle ?: "").contains("ESPN"))
    }

    @Test fun theRowVanishesWhenTheChannelIsHiddenFromThisProfile() = runTest(dispatcher) {
        val vm = home(followed, visibility = flowOf<Visibility>({ _, _, category -> category != "sports" }))
        advanceUntilIdle()
        assertNull(vm.state.value.rows.firstOrNull { it.id == "myTeams" })
    }

    @Test fun noFollowedTeamsMeansNoRow() = runTest(dispatcher) {
        val vm = home(emptyMap())
        advanceUntilIdle()
        assertNull(vm.state.value.rows.firstOrNull { it.id == "myTeams" })
    }

    @Test fun aGameCardNeverBecomesTheHeroWhileRealContentExists() = runTest(dispatcher) {
        val movie = ContentKey(src, ContentKind.VOD, RemoteId("m1"))
        val userData = FakeUserData(followed)
        val epg = FakeEpg(listOf(live))
        val vm = HomeViewModel(
            FakeCatalog(recentVod = listOf(PosterRow(movie, "Some Movie", null, null, null, RemoteId("movies"), null))),
            epg, userData, Clock { now }, ShowEverything,
            myTeams = MyTeamsRepository(epg, Clock { now }, FollowedTeamsStore(userData)),
            compute = StandardTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        assertEquals("myTeams", vm.state.value.rows.first().id)
        assertEquals("Some Movie", assertNotNull(vm.state.value.hero).title)
    }

    private class FakeEpg(private val airings: List<SportsAiring>) : EpgRepository {
        override suspend fun nowNext(sourceId: SourceId, epgKeys: Collection<String>, atMs: Long): Map<String, NowNext> = emptyMap()
        override suspend fun programmes(sourceId: SourceId, epgKey: String, window: TimeWindow): List<ProgrammeRecord> = emptyList()
        override suspend fun sportsSchedule(nowMs: Long, windowMs: Long, limit: Int): List<SportsAiring> = airings
    }

    private class FakeUserData(initial: Map<String, String> = emptyMap()) : UserDataRepository {
        private val settings = MutableStateFlow(initial)
        override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
        override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
        override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
        override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = Unit
        override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
        override suspend fun progress(key: ContentKey): Progress? = null
        override suspend fun recordChannelWatched(key: ContentKey) = Unit
        override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
        override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
        override fun setting(key: String): Flow<String?> = settings.map { it[key] }
        override suspend fun putSetting(key: String, value: String) { settings.value = settings.value + (key to value) }
    }

    private class FakeCatalog(private val recentVod: List<PosterRow> = emptyList()) : CatalogRepository {
        override suspend fun poster(key: ContentKey): PosterRow? = null
        override suspend fun channel(key: ContentKey): ChannelRow? = null
        override suspend fun similarTo(seed: ContentKey, limit: Int): List<PosterRow> = emptyList()
        override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> =
            if (kind == ContentKind.VOD) flowOf(recentVod) else flowOf(emptyList())

        override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<com.yodesla.omniverse.core.model.Category>> = TODO()
        override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = TODO()
        override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): PagingSource<Int, ChannelRow> = TODO()
        override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow> = emptyList()
        override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey> = emptyList()
        override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = TODO()
        override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = TODO()
        override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = TODO()
        override fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = TODO()
        override fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = TODO()
        override fun vodAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = TODO()
        override fun seriesAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = TODO()
        override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> = TODO()
    }
}
