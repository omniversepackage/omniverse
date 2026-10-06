package com.yodesla.omniverse.core.data.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeamDirectoryTest {
    private fun t(league: String, name: String, short: String) =
        DirectoryTeam(league, GameTeam(name, short, short.take(3).uppercase(), null, "https://x/$short.png", null, null), listOf(name, short).map { it.lowercase() })

    private val dir = TeamDirectory(listOf(
        t("NFL", "Cleveland Browns", "Browns"), t("NFL", "Pittsburgh Steelers", "Steelers"),
        t("NHL", "New York Rangers", "Rangers"), t("MLB", "Texas Rangers", "Rangers"),
    ))

    @Test fun findsByFullNameOrNickname() {
        assertEquals("Cleveland Browns", dir.find("Cleveland Browns")?.team?.name)
        assertEquals("Pittsburgh Steelers", dir.find("Steelers")?.team?.name)
        assertEquals("Cleveland Browns", dir.find("Browns postgame show")?.team?.name)
    }

    @Test fun leagueHintPicksBetweenSharedNicknames() {
        assertEquals("NHL", dir.find("Rangers", "NHL")?.league)
        assertEquals("MLB", dir.find("Rangers", "MLB")?.league)
    }

    @Test fun unrelatedTextFindsNothing() {
        assertNull(dir.find("Morning News"))
        assertNull(dir.find("ab"))
    }
}
