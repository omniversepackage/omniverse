package com.yodesla.omniverse.core.data.sports

import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class R(override val status: Int, t: String) : HttpResponse {
    override val headers: Map<String, String> = emptyMap()
    override val body: BufferedSource = Buffer().apply { writeUtf8(t) }
    override fun close() {}
}

private const val NFL = """{"events":[{"id":"401","date":"2026-10-02T00:15Z","name":"Pittsburgh Steelers at Cleveland Browns",
 "competitions":[{"date":"2026-10-02T00:15Z","status":{"type":{"state":"pre","shortDetail":"10/1 - 8:15 PM EDT"}},
  "venue":{"fullName":"Huntington Bank Field"},"broadcasts":[{"names":["Prime Video"]}],
  "competitors":[
   {"homeAway":"home","score":"0","records":[{"summary":"2-2"}],"team":{"displayName":"Cleveland Browns","shortDisplayName":"Browns","abbreviation":"CLE","color":"472a08","logo":"https://x/cle.png"}},
   {"homeAway":"away","score":"0","team":{"displayName":"Pittsburgh Steelers","shortDisplayName":"Steelers","abbreviation":"PIT","color":"000000","logo":"https://x/pit.png"}}]}]}]}"""

private const val CLIPS = """{"headlines":[{"id":"9","gameId":"401","headline":"Steelers-Browns preview","categories":[{"type":"team","description":"Pittsburgh Steelers"}],
 "video":[{"thumbnail":"https://x/t.jpg","duration":"115","links":{"source":{"HD":{"href":"https://x/clip.mp4"}}}}]},
 {"id":"10","headline":"No video here","video":[]}]}"""

private const val NFLVID = """{"headlines":[{"id":"n1","gameId":"401","published":"2026-10-02T10:00Z","headline":"Browns late TD","categories":[{"type":"team","description":"Cleveland Browns"}],
 "video":[{"thumbnail":"https://x/n1.jpg","duration":"95","links":{"source":{"HD":{"href":"https://x/n1.mp4"}}}}]}]}"""

private const val NBAVID = """{"headlines":[{"id":"b1","published":"2026-10-03T10:00Z","headline":"Celtics block","video":[{"duration":"80","links":{"source":{"HD":{"href":"https://x/b1.mp4"}}}}]}]}"""

class EspnFeedTest {
    private val http = object : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = when {
            "football/nfl/scoreboard" in url -> R(200, NFL)
            "league=nfl" in url && "type=video" in url -> R(200, CLIPS)
            else -> R(404, "")
        }
    }

    @Test fun parsesGamesWithNetworksLogosColoursAndState() = runTest {
        val feed = EspnSportsFeed(http, clock = { 1_790_000_000_000L })
        val g = feed.games().single()
        assertEquals("NFL", g.league)
        assertEquals(Sport.FOOTBALL, g.sport)
        assertEquals(GameState.PRE, g.state)
        assertEquals(listOf("Prime Video"), g.networks)
        assertEquals("Cleveland Browns", g.home.name)
        assertEquals("PIT", g.away.abbreviation)
        assertEquals(0xFF472A08, g.home.color)
        assertEquals("2-2", g.home.record)
        assertEquals(null, g.home.score, "no scores before kick-off")
        assertEquals(java.time.Instant.parse("2026-10-02T00:15:00Z").toEpochMilli(), g.startMs)
    }

    @Test fun parsesOnlyRealMp4Clips() = runTest {
        val clips = EspnSportsFeed(http).clips()
        assertEquals(listOf("https://x/clip.mp4"), clips.map { it.videoUrl })
        assertEquals(listOf("Pittsburgh Steelers"), clips.single().teams)
    }

    @Test fun networksMatchUsChannelsFirstAndIgnoreLookalikes() {
        val us = NetworkMatcher.score("US: Prime Video Sports", "Prime Video")
        val es = NetworkMatcher.score("ES: Prime Video Deportes", "Prime Video")
        assertTrue(us > es && es > 0)
        assertEquals(0, NetworkMatcher.score("US: Fox News", "FOX"))
        assertTrue(NetworkMatcher.score("US: FOX 5 New York", "FOX") > 0)
        assertEquals(0, NetworkMatcher.score("US: CBSN", "CBS"))
        assertTrue(NetworkMatcher.score("US| NFL Network HD", "NFL Net") > 0)
    }

    private class Vid(private val nfl: String = NFLVID, private val nba: String = NBAVID) : HttpClient {
        val hits = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            hits += url
            return when {
                "league=nfl" in url && "type=video" in url -> R(200, nfl)
                "league=nba" in url && "type=video" in url -> R(200, nba)
                else -> R(404, "")
            }
        }
    }

    @Test fun clipsForALeagueFetchOnlyThatLeague() = runTest {
        val h = Vid()
        val clips = EspnSportsFeed(h).clips("NBA")
        assertEquals(listOf("b1"), clips.map { it.id })
        assertEquals(listOf("NBA"), clips.map { it.league })
        assertTrue(h.hits.all { "league=nba" in it }, "must not touch other leagues")
    }

    @Test fun allClipsMixLeaguesNewestFirst() = runTest {
        val clips = EspnSportsFeed(Vid()).clips()
        assertEquals(listOf("b1", "n1"), clips.map { it.id })
        assertEquals(listOf("NBA", "NFL"), clips.map { it.league })
        assertEquals(java.time.Instant.parse("2026-10-02T10:00:00Z").toEpochMilli(), clips.last().publishedMs)
    }

    @Test fun clipsAreCachedPerLeague() = runTest {
        val h = Vid()
        val feed = EspnSportsFeed(h, clock = { 1_790_000_000_000L })
        feed.clips("NBA"); feed.clips("NBA")
        assertEquals(1, h.hits.count { "league=nba" in it }, "second call must come from the cache")
        feed.clips("NFL")
        assertEquals(1, h.hits.count { "league=nfl" in it })
        assertTrue(h.hits.none { "scoreboard" in it }, "clips must not fetch schedules")
    }

    @Test fun leaguesWithoutAVideoFeedReturnNothing() = runTest {
        val h = Vid()
        assertTrue(EspnSportsFeed(h).clips("Premier League").isEmpty())
        assertTrue(h.hits.isEmpty())
    }
}
