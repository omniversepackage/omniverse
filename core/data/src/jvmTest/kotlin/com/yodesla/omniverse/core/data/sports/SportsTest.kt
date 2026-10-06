package com.yodesla.omniverse.core.data.sports

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.EpgRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SportsTest {
    @Test fun matchupsOnSportsChannelsAreEventsWithTeams() {
        val c = assertNotNull(SportsClassifier.classify("Riverton vs. Westbrook", null, null, "US: Sports HD", "Sports HD"))
        assertEquals(SportsKind.EVENT, c.kind)
        assertEquals("Riverton" to "Westbrook", c.teams)
    }

    @Test fun leaguesAndSportsAreRecognised() {
        val c = assertNotNull(SportsClassifier.classify("NBA: Lakers @ Celtics", null, "Sports", "ESPN", null))
        assertEquals(Sport.BASKETBALL, c.sport)
        assertEquals("NBA", c.league)
        assertEquals(Sport.SOCCER, SportsClassifier.classify("Arsenal v Chelsea", "Premier League matchday", null, "Sky Sports Main Event", null)?.sport)
        assertEquals(Sport.FIGHTING, SportsClassifier.classify("UFC 300: Main Card", null, null, "PPV 1", null)?.sport)
    }

    @Test fun studioShowsAreCoverage() {
        assertEquals(SportsKind.COVERAGE, SportsClassifier.classify("SportsCenter", null, "Sports news", "ESPN", null)?.kind)
        assertEquals(SportsKind.COVERAGE, SportsClassifier.classify("Matchday Highlights", null, null, "US: Sports HD", null)?.kind)
    }

    @Test fun nonSportsVsTitlesAreIgnored() {
        assertNull(SportsClassifier.classify("Kramer vs. Kramer", "Drama", "Movie", "Cinema One", "Movies"))
        assertNull(SportsClassifier.classify("Morning News", null, "News", "News 24", "News"))
    }

    @Test fun scheduleComesFromEverySourceAndSkipsEndedAndNonSports() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO category(source_id,kind,remote_id,name,sort_index,sync_gen) VALUES ('a','LIVE','s','Sports HD',0,1),('b','LIVE','n','News',0,1)", 0)
        driver.execute(null, "INSERT INTO channel(source_id,remote_id,name,epg_channel_id,primary_category_id,sort_index,sync_gen) VALUES " +
            "('a','1','US: Sports HD','sp','s',0,1),('b','2','News 24','nw','n',0,1),('b','3','ESPN 2','e2','n',1,1)", 0)
        driver.execute(null, "INSERT INTO programme(source_id,channel_key,start_ms,end_ms,title) VALUES " +
            "('a','sp',0,500,'Old Game vs Older')," +
            "('a','sp',900,2000,'Riverton vs. Westbrook')," +
            "('b','nw',900,2000,'Morning News')," +
            "('b','e2',3000,4000,'NFL: Bears @ Packers')", 0)
        val repo = EpgRepositoryImpl(OmniverseDb(driver), UnconfinedTestDispatcher(testScheduler))
        val list = repo.sportsSchedule(nowMs = 1000)
        assertEquals(listOf("Riverton vs. Westbrook", "NFL: Bears @ Packers"), list.map { it.title })
        assertEquals(Sport.FOOTBALL, list[1].sport)
        assertEquals(listOf("US: Sports HD", "ESPN 2"), repo.sportsChannels().map { it.name })
        driver.close()
    }

    @Test fun proTeamMatchupIsAGameEvenWithoutSportsGenreOrChannel() {
        val c = assertNotNull(SportsClassifier.classify("Cleveland Browns at Pittsburgh Steelers", null, null, "CBS", "Local"))
        assertEquals(SportsKind.EVENT, c.kind)
        assertEquals(Sport.FOOTBALL, c.sport)
        assertEquals("NFL", c.league)
    }

    @Test fun fillerTitlesAreNeverSports() {
        assertNull(SportsClassifier.classify("No Game Today", null, "Sports", "NFL Network", "Sports"))
        assertNull(SportsClassifier.classify("Off Air", null, null, "ESPN+ 07", "Sports"))
        assertNull(SportsClassifier.classify("Event starts at 1:00 PM", null, null, "NFL 03", "Sports"))
    }

    @Test fun eventChannelNamesCarryTheGameAndTime() {
        val c = assertNotNull(SportsClassifier.classifyEventChannel("NFL 03: Cleveland Browns vs Pittsburgh Steelers 1:00 PM ET", "NFL Sunday"))
        assertEquals("Cleveland Browns" to "Pittsburgh Steelers", c.teams)
        assertEquals(13 * 60, SportsClassifier.clockMinutes("NFL 03: Cleveland Browns vs Pittsburgh Steelers 1:00 PM ET"))
        assertNull(SportsClassifier.classifyEventChannel("NFL 04: No Game Today", "NFL Sunday"))
    }
}
