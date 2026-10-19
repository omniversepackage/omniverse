package com.yodesla.omniverse.core.data.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamChannelMatcherTest {
    private fun team(name: String, short: String) = GameTeam(name, short, "", null, null, null, null)
    private val browns = team("Cleveland Browns", "Browns")
    private val giants = team("New York Giants", "Giants")

    @Test fun dedicatedTeamChannelsMatchWithoutANetworkName() {
        assertTrue(TeamChannelMatcher.score("Cleveland Browns", browns, "NFL") >= 60)
        assertTrue(TeamChannelMatcher.score("NFL: Browns 24/7 HD", browns, "NFL") >= 50)
        assertTrue(TeamChannelMatcher.score("NFL: US Browns Network", browns, "NFL") > TeamChannelMatcher.score("NFL: Browns Network", browns, "NFL"))
        assertTrue(TeamChannelMatcher.score("NFL CLEVELAND BROWNS", browns, "NFL") >= 60)
    }

    @Test fun otherTeamsAndLeaguesDoNotMatch() {
        assertEquals(0, TeamChannelMatcher.score("ESPN HD", browns, "NFL"))
        assertEquals(0, TeamChannelMatcher.score("Pittsburgh Steelers", browns, "NFL"))
        assertEquals(0, TeamChannelMatcher.score("MLB: SF Giants", giants, "NFL"))
        assertEquals(0, TeamChannelMatcher.score("24/7: Mrs Browns Boys", browns, "NFL"))
        assertEquals(0, TeamChannelMatcher.score("Radio: Cleveland Browns", browns, "NFL"))
    }

    @Test fun searchTermsCoverFullNameAndNickname() {
        val terms = TeamChannelMatcher.termsFor(browns, "NFL")
        assertTrue("cleveland browns" in terms && "browns" in terms)
    }
}
