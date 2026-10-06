package com.yodesla.omniverse.feature.live.sports

import com.yodesla.omniverse.core.data.*
import com.yodesla.omniverse.core.data.sports.*
import com.yodesla.omniverse.core.model.*
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Buffer
import okio.BufferedSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class R(override val status: Int, t: String) : HttpResponse {
    override val headers: Map<String, String> = emptyMap()
    override val body: BufferedSource = Buffer().apply { writeUtf8(t) }
    override fun close() {}
}

/** One PRE NFL game kicking off at exactly 1_700_000_000_000, so it becomes the hero. */
private const val SCOREBOARD = """{"events":[{"id":"401","date":"2023-11-14T22:13:20Z","name":"Cleveland Browns at Pittsburgh Steelers",
 "competitions":[{"date":"2023-11-14T22:13:20Z","status":{"type":{"state":"pre","shortDetail":"8:13 PM ET"}},
  "competitors":[
   {"homeAway":"home","team":{"displayName":"Pittsburgh Steelers","shortDisplayName":"Steelers","abbreviation":"PIT"}},
   {"homeAway":"away","team":{"displayName":"Cleveland Browns","shortDisplayName":"Browns","abbreviation":"CLE"}}]}]}]}"""

private const val NFLVID = """{"headlines":[{"id":"n1","gameId":"401","published":"2026-10-02T10:00Z","headline":"Browns late TD",
 "video":[{"duration":"95","links":{"source":{"HD":{"href":"https://x/n1.mp4"}}}}]}]}"""

private const val NBAVID = """{"headlines":[{"id":"b1","published":"2026-10-03T10:00Z","headline":"Celtics block",
 "video":[{"duration":"80","links":{"source":{"HD":{"href":"https://x/b1.mp4"}}}}]}]}"""

private class Feed : HttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = when {
        "football/nfl/scoreboard" in url -> R(200, SCOREBOARD)
        "league=nfl" in url && "type=video" in url -> R(200, NFLVID)
        "league=nba" in url && "type=video" in url -> R(200, NBAVID)
        else -> R(404, "")
    }
}

private class FakeEpg : EpgRepository {
    override suspend fun nowNext(sourceId: SourceId, epgKeys: Collection<String>, atMs: Long): Map<String, NowNext> = emptyMap()
    override suspend fun programmes(sourceId: SourceId, epgKey: String, window: TimeWindow): List<ProgrammeRecord> = emptyList()
}

@OptIn(ExperimentalCoroutinesApi::class)
class SportsHighlightsTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = 1_700_000_000_000L

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun model() = SportsViewModel(
        FakeEpg(), Clock { now }, ShowEverything,
        feed = EspnSportsFeed(Feed(), clock = { now }), autoRefresh = false,
    )

    private fun clip(id: String) = HighlightClip(id, null, emptyList(), "Clip $id", null, "https://x/$id.mp4", 60, "NFL")

    private fun gameUi(id: String) = GameUi(
        ScheduledGame(id, "NFL", Sport.FOOTBALL, now, GameState.PRE, "",
            GameTeam("Home", "Home", "HOM", null, null, null, null),
            GameTeam("Away", "Away", "AWY", null, null, null, null),
            emptyList(), null, null, null),
        emptyList(), null,
    )

    @Test fun heroPlaybackParksTheHeroAndHandsItBackUnchanged() {
        val hero = HeroPlayback()
        val game = gameUi("1")
        assertFalse(hero.isPlaying)
        hero.play(clip("a"), game)
        assertTrue(hero.isPlaying)
        assertEquals("a", hero.clip?.id)
        assertEquals(game, hero.parked)
        hero.play(clip("b"), gameUi("2"))
        assertEquals("b", hero.clip?.id)
        assertEquals(game, hero.parked, "switching clips keeps the original parked content")
        assertEquals(game, hero.stop())
        assertFalse(hero.isPlaying)
        assertNull(hero.clip); assertNull(hero.parked)
        assertNull(hero.stop(), "already back: nothing to restore")
    }

    @Test fun highlightsFollowTheSelectedLeagueAndNoteWhenThereAreNone() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        assertEquals(listOf("b1", "n1"), vm.state.value.clips.map { it.id }, "All sports mixes leagues newest first")
        assertNull(vm.state.value.clipsNote)

        vm.select("NBA")
        assertEquals(listOf("b1"), vm.state.value.clips.map { it.id })
        assertNull(vm.state.value.clipsNote)

        vm.select("NFL")
        assertEquals(listOf("n1"), vm.state.value.clips.map { it.id })

        vm.select("NHL")
        assertTrue(vm.state.value.clips.isEmpty())
        assertEquals("No highlights for NHL right now", vm.state.value.clipsNote)

        vm.select(null)
        assertEquals(listOf("b1", "n1"), vm.state.value.clips.map { it.id })
        assertNull(vm.state.value.clipsNote)
    }

    @Test fun playingAClipTakesOverTheHeroAndStoppingReturnsTheSameGame() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        val before = vm.state.value.featured ?: error("no featured game")
        assertNull(vm.state.value.playingClip)

        val clip = vm.state.value.clips.first()
        vm.playClip(clip)
        assertEquals(clip, vm.state.value.playingClip)
        assertEquals(before, vm.state.value.featured, "the hero stays on its game while the clip plays")
        assertEquals(before, vm.state.value.heroReturn)

        vm.select("NBA")
        assertEquals(clip, vm.state.value.playingClip)
        assertEquals(before, vm.state.value.featured, "a league change while playing must not move the hero")

        vm.stopClip()
        assertNull(vm.state.value.playingClip)
        assertNull(vm.state.value.heroReturn)
        assertEquals(before, vm.state.value.featured, "the hero shows exactly what it showed before")
    }

    @Test fun stoppingWithoutAClipChangesNothing() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        val before = vm.state.value.featured
        vm.stopClip()
        assertEquals(before, vm.state.value.featured)
        assertNull(vm.state.value.playingClip)
    }
}
