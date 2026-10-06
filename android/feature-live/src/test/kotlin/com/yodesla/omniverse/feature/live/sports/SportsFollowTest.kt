package com.yodesla.omniverse.feature.live.sports

import com.yodesla.omniverse.core.data.*
import com.yodesla.omniverse.core.data.sports.*
import com.yodesla.omniverse.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SportsFollowTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = 1_700_000_000_000L

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun airing(
        title: String,
        channel: String,
        startMs: Long,
        endMs: Long,
        league: String? = "NFL",
        teams: Pair<String, String>? = null,
    ) = SportsAiring(
        channel = ContentKey(SourceId("s1"), ContentKind.LIVE, RemoteId(channel)),
        categoryId = RemoteId("sports"), channelName = channel, logoUrl = null,
        title = title, description = null, startMs = startMs, endMs = endMs,
        sport = Sport.FOOTBALL, kind = SportsKind.EVENT, teams = teams, league = league,
    )

    private val chiefsLive = airing(
        "NFL: Kansas City Chiefs at Las Vegas Raiders", "ESPN", now - 600_000, now + 3_000_000,
        teams = "Kansas City Chiefs" to "Las Vegas Raiders",
    )
    private val chiefsSoon = airing(
        "NFL: Denver Broncos at Kansas City Chiefs", "ESPN2", now + 7_200_000, now + 10_800_000,
        teams = "Denver Broncos" to "Kansas City Chiefs",
    )
    private val othersLive = airing(
        "NFL: Cincinnati Bengals at Cleveland Browns", "FS1", now - 600_000, now + 3_000_000,
        teams = "Cincinnati Bengals" to "Cleveland Browns",
    )

    private class FakeEpg(private val airings: List<SportsAiring>) : EpgRepository {
        override suspend fun nowNext(sourceId: SourceId, epgKeys: Collection<String>, atMs: Long): Map<String, NowNext> = emptyMap()
        override suspend fun programmes(sourceId: SourceId, epgKey: String, window: TimeWindow): List<ProgrammeRecord> = emptyList()
        override suspend fun sportsSchedule(nowMs: Long, windowMs: Long, limit: Int): List<SportsAiring> = airings
    }

    private inner class FakeUserData(initial: Map<String, String> = emptyMap()) : UserDataRepository {
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

    private val chiefs = FollowedTeam("l:NFL|chiefs", "Kansas City Chiefs", "NFL")

    private fun model(userData: FakeUserData) = SportsViewModel(
        FakeEpg(listOf(chiefsLive, chiefsSoon, othersLive)), Clock { now }, ShowEverything,
        follows = FollowedTeamsStore(userData), autoRefresh = false,
    )

    private fun liveGame(id: String) = GameUi(
        ScheduledGame(
            id = id, league = "NFL", sport = Sport.FOOTBALL, startMs = now - 600_000, state = GameState.LIVE,
            detail = "Live", home = GameTeam("Kansas City Chiefs", "Chiefs", "KC", null, null, "24", null),
            away = GameTeam("Las Vegas Raiders", "Raiders", "LV", null, null, "17", null),
            networks = listOf("ESPN"), venue = null, imageUrl = null, headline = null,
        ), emptyList(), null,
    )

    @Test fun followedTeamsPullTheirGamesOutOfTheViewersOwnGuide() = runTest(dispatcher) {
        val vm = model(FakeUserData(mapOf(FollowedTeams.KEY to FollowedTeams.encode(listOf(chiefs)))))
        advanceUntilIdle()
        assertEquals(listOf("l:NFL|chiefs"), vm.state.value.followed.map { it.key })
        assertEquals(listOf("ESPN"), vm.state.value.myTeamGroups.map { it.sample.channelName })
        assertEquals(listOf("ESPN2"), vm.state.value.myTeamAirings.map { it.channelName })
    }

    @Test fun nothingIsGroupedAsMyTeamsUntilSomethingIsFollowed() = runTest(dispatcher) {
        val vm = model(FakeUserData())
        advanceUntilIdle()
        assertTrue(vm.state.value.myTeams.isEmpty())
        assertTrue(vm.state.value.myTeamGroups.isEmpty())
        assertTrue(vm.state.value.myTeamAirings.isEmpty())
    }

    @Test fun holdOkOnAGuideGameFollowsItAndTheRowRefreshes() = runTest(dispatcher) {
        val vm = model(FakeUserData())
        advanceUntilIdle()

        vm.openFollowMenu(chiefsLive)
        val menu = vm.state.value.followMenu ?: error("no follow menu")
        assertEquals(listOf("Kansas City Chiefs", "Las Vegas Raiders"), menu.options.map { it.name })
        assertFalse(menu.options.first().followed)

        vm.toggleFollow(menu.options.first())
        advanceUntilIdle()
        assertEquals(listOf("l:NFL|chiefs"), vm.state.value.followed.map { it.key })
        assertEquals(listOf("ESPN"), vm.state.value.myTeamGroups.map { it.sample.channelName })
        assertTrue((vm.state.value.followMenu ?: error("menu closed")).options.first().followed)

        vm.toggleFollow(menu.options.first())
        advanceUntilIdle()
        assertTrue(vm.state.value.followed.isEmpty())
        assertTrue(vm.state.value.myTeamGroups.isEmpty())
    }

    @Test fun aHiddenScoreIsRevealedByTheFirstOkInsteadOfWatching() = runTest(dispatcher) {
        val vm = model(FakeUserData(mapOf(FollowedTeams.HIDE_SCORES_KEY to "1")))
        advanceUntilIdle()
        assertTrue(vm.state.value.hideScores)
        assertTrue(vm.state.value.scoreHidden("g1"))

        vm.open(liveGame("g1"))
        assertTrue(vm.state.value.revealed.contains("g1"))
        assertFalse(vm.state.value.scoreHidden("g1"))
        assertNull(vm.state.value.message)

        vm.open(liveGame("g1"))
        assertTrue((vm.state.value.message ?: "").startsWith("Raiders at Chiefs is on ESPN"))
    }

    @Test fun upcomingGamesAreNotRevealGatedAndScoresShowWhenTheSwitchIsOff() = runTest(dispatcher) {
        val vm = model(FakeUserData(mapOf(FollowedTeams.HIDE_SCORES_KEY to "1")))
        advanceUntilIdle()
        val base = liveGame("g2")
        val upcoming = base.copy(game = base.game.copy(state = GameState.PRE, startMs = now + 7_200_000))
        vm.open(upcoming)
        assertTrue(vm.state.value.revealed.isEmpty())

        vm.setHideScores(false)
        advanceUntilIdle()
        assertFalse(vm.state.value.hideScores)
        assertFalse(vm.state.value.scoreHidden("g1"))
    }

    @Test fun turningTheSwitchOffWritesItAndUnhidesEveryCard() = runTest(dispatcher) {
        val userData = FakeUserData(mapOf(FollowedTeams.HIDE_SCORES_KEY to "1"))
        val vm = model(userData)
        advanceUntilIdle()
        vm.revealScore("g1")
        assertTrue(vm.state.value.scoreHidden("g2"))
        vm.setHideScores(false)
        advanceUntilIdle()
        assertFalse(vm.state.value.hideScores)
        assertTrue(vm.state.value.revealed.isEmpty())
        assertEquals("0", userData.setting(FollowedTeams.HIDE_SCORES_KEY).first())
    }
}
