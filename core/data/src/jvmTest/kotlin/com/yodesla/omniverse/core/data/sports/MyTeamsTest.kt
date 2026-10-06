package com.yodesla.omniverse.core.data.sports

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MyTeamsTest {
    private val now = 1_000_000L
    private val chiefs = FollowedTeam("l:NFL|chiefs", "Kansas City Chiefs", "NFL")

    private fun game(
        id: String,
        league: String,
        home: String,
        away: String,
        startMs: Long = now + 60_000,
        state: GameState = GameState.PRE,
    ) = ScheduledGame(
        id = id, league = league, sport = Sport.FOOTBALL, startMs = startMs, state = state, detail = "",
        home = GameTeam(home, home.substringAfterLast(' '), "H", null, null, null, null),
        away = GameTeam(away, away.substringAfterLast(' '), "A", null, null, null, null),
        networks = listOf("CBS"), venue = null, imageUrl = null, headline = null,
    )

    private fun airing(
        title: String,
        channel: String,
        league: String? = "NFL",
        startMs: Long = now - 60_000,
        endMs: Long = now + 3_600_000,
        kind: SportsKind = SportsKind.EVENT,
        sourceId: String = "s1",
    ) = SportsAiring(
        channel = ContentKey(SourceId(sourceId), ContentKind.LIVE, RemoteId(channel)),
        categoryId = RemoteId("sports"), channelName = channel, logoUrl = null,
        title = title, description = null, startMs = startMs, endMs = endMs,
        sport = Sport.FOOTBALL, kind = kind, teams = null, league = league,
    )

    @Test fun liveGamesLeadThenUpcomingKickoffsAndFinishedGamesDropOff() {
        val items = MyTeams.fromGames(
            listOf(
                game("soon", "NFL", "Denver Broncos", "Kansas City Chiefs", startMs = now + 7_200_000),
                game("live", "NFL", "Las Vegas Raiders", "Kansas City Chiefs", startMs = now - 600_000, state = GameState.LIVE),
                game("final", "NFL", "Los Angeles Rams", "Kansas City Chiefs", startMs = now - 7_200_000, state = GameState.FINAL),
                game("old", "NFL", "Cincinnati Bengals", "Kansas City Chiefs", startMs = now - 3_600_000),
                game("other", "NFL", "Cincinnati Bengals", "Cleveland Browns", startMs = now + 600_000),
            ),
            listOf(chiefs), now,
        )
        assertEquals(listOf("live", "soon"), items.map { it.id })
        assertEquals("Chiefs at Raiders", items.first().title)
        assertEquals(listOf("CBS"), items.first().networks)
        assertTrue(items.first().options.isEmpty())
    }

    @Test fun nothingShowsUpWithoutAFollowedTeam() {
        assertEquals(emptyList(), MyTeams.fromGames(listOf(game("live", "NFL", "Las Vegas Raiders", "Kansas City Chiefs", state = GameState.LIVE)), emptyList(), now))
        assertEquals(emptyList(), MyTeams.fromAirings(listOf(airing("NFL: Chiefs at Raiders", "ESPN")), emptyList(), now))
    }

    @Test fun oneCardPerMatchupCarriesEveryChannelThatHasIt() {
        val items = MyTeams.fromAirings(
            listOf(
                airing("NFL: Chiefs at Raiders", "ESPN2", sourceId = "s2"),
                airing("NFL: Chiefs at Raiders", "ESPN"),
                airing("NFL: Chiefs at Raiders", "Locked"),
            ),
            listOf(chiefs), now,
            visible = { it.channelName != "Locked" },
        )
        val item = items.single()
        assertEquals(GameState.LIVE, item.state)
        assertEquals(listOf("ESPN2", "ESPN"), item.networks)
        assertEquals(listOf("ESPN2", "ESPN"), item.options.map { it.name })
        assertEquals("s1", item.options.last().key.sourceId.value)
    }

    @Test fun hiddenChannelsEndedGamesAndStudioShowsNeverBecomeOptions() {
        val items = MyTeams.fromAirings(
            listOf(
                airing("NFL: Chiefs at Raiders", "Locked"),
                airing("NFL: Broncos at Raiders", "ESPN", startMs = now - 7_200_000, endMs = now - 600_000),
                airing("SportsCenter", "ESPN"),
            ),
            listOf(chiefs), now,
            visible = { it.channelName != "Locked" },
        )
        assertEquals(emptyList(), items)
    }

    @Test fun upcomingGuideGamesWaitForTheirKickoff() {
        val items = MyTeams.fromAirings(listOf(airing("NFL: Chiefs at Raiders", "ESPN", startMs = now + 3_600_000)), listOf(chiefs), now)
        assertEquals(GameState.PRE, items.single().state)
    }

    @Test fun theRowIsCappedAtItsLimit() {
        val games = (1..30).map { game("g$it", "NFL", "Las Vegas Raiders", "Kansas City Chiefs", startMs = now + it * 60_000L) }
        assertEquals(20, MyTeams.fromGames(games, listOf(chiefs), now).size)
        assertEquals(3, MyTeams.fromGames(games, listOf(chiefs), now, limit = 3).size)
    }
}
