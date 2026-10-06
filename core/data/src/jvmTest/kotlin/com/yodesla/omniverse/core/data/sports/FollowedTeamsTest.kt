package com.yodesla.omniverse.core.data.sports

import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowedTeamsTest {
    private val now = 1_000_000L

    private fun airing(
        title: String,
        league: String? = null,
        channel: String = "ESPN",
        startMs: Long = now,
        endMs: Long = now + 3_600_000,
        kind: SportsKind = SportsKind.EVENT,
    ) = SportsAiring(
        channel = ContentKey(SourceId("s1"), ContentKind.LIVE, RemoteId(channel)),
        categoryId = RemoteId("sports"), channelName = channel, logoUrl = null,
        title = title, description = null, startMs = startMs, endMs = endMs,
        sport = Sport.FOOTBALL, kind = kind, teams = null, league = league,
    )

    private fun game(
        id: String,
        league: String,
        home: String,
        away: String,
        startMs: Long = now + 60_000,
        state: GameState = GameState.PRE,
        detail: String = "",
    ) = ScheduledGame(
        id = id, league = league, sport = Sport.FOOTBALL, startMs = startMs, state = state, detail = detail,
        home = GameTeam(home, home.substringAfterLast(' '), "H", null, null, null, null),
        away = GameTeam(away, away.substringAfterLast(' '), "A", null, null, null, null),
        networks = listOf("CBS"), venue = null, imageUrl = null, headline = null,
    )

    private inner class SettingsOnly(initial: Map<String, String> = emptyMap()) : UserDataRepository {
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

    @Test fun recognisedTeamsAreStoredLeagueQualified() {
        val keys = FollowedTeams.candidateKeys("Kansas City Chiefs")
        assertTrue("l:NFL|chiefs" in keys)
        assertTrue("n:kansas city chiefs" in keys)
    }

    @Test fun unknownTeamsAreStoredUnderTheirPlainName() {
        assertEquals(setOf("n:riverton"), FollowedTeams.candidateKeys("Riverton"))
        assertEquals("riverton", FollowedTeams.normalize("Riverton (WA)"))
    }

    @Test fun aLeagueHintKeepsSharedNicknamesApart() {
        val mlb = listOf(FollowedTeam("l:MLB|cardinals", "Arizona Cardinals", "MLB"))
        assertNull(FollowedTeams.match(mlb, "Arizona Cardinals", "NFL"))
        assertEquals("l:MLB|cardinals", FollowedTeams.match(mlb, "Arizona Cardinals", "MLB")?.key)
    }

    @Test fun unknownTeamsMatchOnWholeWords() {
        val f = listOf(FollowedTeam("n:riverton", "Riverton", null))
        assertEquals("n:riverton", FollowedTeams.match(f, "Riverton Riveters at Lakeview Hornets")?.key)
        assertNull(FollowedTeams.match(f, "Lakers at Celtics"))
    }

    @Test fun aFollowedTeamIsFoundEvenWhenTheTitleNamesTheOtherSideFirst() {
        val f = listOf(FollowedTeam("l:NFL|chiefs", "Kansas City Chiefs", "NFL"))
        assertEquals("l:NFL|chiefs", FollowedTeams.match(f, "NFL: Denver Broncos at Kansas City Chiefs", "NFL")?.key)
        assertNull(FollowedTeams.match(f, "NFL: Denver Broncos at Las Vegas Raiders", "NFL"))
    }

    @Test fun gamesAndAiringsMatchEitherSideOfAMatchup() {
        val f = listOf(FollowedTeam("l:NFL|chiefs", "Kansas City Chiefs", "NFL"))
        assertEquals("l:NFL|chiefs", FollowedTeams.match(f, game("g", "NFL", "Las Vegas Raiders", "Kansas City Chiefs"))?.key)
        assertEquals("l:NFL|chiefs", FollowedTeams.match(f, airing("NFL: Chiefs at Raiders", league = "NFL"))?.key)
        assertNull(FollowedTeams.match(f, airing("SportsCenter")))
        assertNull(FollowedTeams.match(f, game("g", "MLB", "New York Yankees", "Boston Red Sox")))
    }

    @Test fun encodeDecodeRoundTripsAndStripsControlCharacters() {
        val raw = FollowedTeams.encode(
            listOf(FollowedTeam("l:NFL|chiefs", "Kansas  City\u001FChiefs", "NFL"), FollowedTeam("n:riverton", "Riverton", null)),
        )
        val back = FollowedTeams.decode(raw)
        assertEquals(listOf("l:NFL|chiefs", "n:riverton"), back.map { it.key })
        assertEquals("Kansas  City Chiefs", back[0].name)
        assertEquals("NFL", back[0].league)
        assertNull(back[1].league)
    }

    @Test fun decodeFallsBackToTheKeyWhenANameIsMissing() {
        val back = FollowedTeams.decode("l:NFL|chiefs\u001F\n\u001Fjunk")
        assertEquals(1, back.size)
        assertEquals("chiefs", back.single().name)
    }

    @Test fun scoresAreHiddenUntilTheCardIsRevealed() {
        assertEquals(FollowedTeams.HIDDEN_TEXT, FollowedTeams.scoreText("24", "17", hidden = true, revealed = false))
        assertEquals("24-17", FollowedTeams.scoreText("24", "17", hidden = true, revealed = true))
        assertEquals("24-17", FollowedTeams.scoreText("24", "17", hidden = false, revealed = false))
        assertEquals("0-0", FollowedTeams.scoreText(null, null, hidden = false, revealed = false))
    }

    @Test fun scoresInsideFeedStatusLinesAreHiddenButClocksAreNot() {
        assertEquals(FollowedTeams.HIDDEN_TEXT, FollowedTeams.detailText("Final 24-17", hidden = true, revealed = false))
        assertEquals("Final", FollowedTeams.detailText("Final", hidden = true, revealed = false))
        assertEquals("8:15 PM", FollowedTeams.detailText("8:15 PM", hidden = true, revealed = false))
        assertEquals("Q3 5:12", FollowedTeams.detailText("Q3 5:12", hidden = true, revealed = false))
        assertEquals("2024-25 Season", FollowedTeams.detailText("2024-25 Season", hidden = true, revealed = false))
        assertEquals("Final 24-17", FollowedTeams.detailText("Final 24-17", hidden = true, revealed = true))
    }

    @Test fun togglingAFollowWritesAndClearsTheProfileSetting() = runTest {
        val store = FollowedTeamsStore(SettingsOnly())
        assertTrue(store.toggle("Kansas City Chiefs", "NFL"))
        assertEquals(listOf("l:NFL|chiefs"), store.current().map { it.key })
        assertTrue(store.toggle("Riverton"))
        assertEquals(listOf("l:NFL|chiefs", "n:riverton"), store.current().map { it.key })
        assertFalse(store.toggle("Chiefs"))
        assertEquals(listOf("n:riverton"), store.current().map { it.key })
    }

    @Test fun theScoreSwitchRoundTripsAndReadsBothSpellingsOfTrue() = runTest {
        val store = FollowedTeamsStore(SettingsOnly())
        assertFalse(store.hideScores.first())
        store.setHideScores(true)
        assertTrue(store.hideScores.first())
        store.setHideScores(false)
        assertFalse(store.hideScores.first())
        assertTrue(FollowedTeamsStore(SettingsOnly(mapOf(FollowedTeams.HIDE_SCORES_KEY to "true"))).hideScores.first())
        assertFalse(FollowedTeamsStore(SettingsOnly(mapOf(FollowedTeams.HIDE_SCORES_KEY to "false"))).hideScores.first())
    }
}
