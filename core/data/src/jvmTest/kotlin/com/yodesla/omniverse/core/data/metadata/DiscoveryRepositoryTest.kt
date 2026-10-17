package com.yodesla.omniverse.core.data.metadata

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.*
import com.yodesla.omniverse.core.data.impl.*
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class DiscoveryRepositoryTest {
    private val io = UnconfinedTestDispatcher()
    private val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
    private val clock = Clock { 1000L }
    private val user = UserDataRepositoryImpl(db, io, clock)
    private val catalog = CatalogRepositoryImpl(db, io)
    private val repo = DiscoveryRepository(null, user, catalog, clock)
    private fun seed() {
        for (source in listOf("a", "b")) {
            db.catalogQueries.upsertSource(source, "XTREAM", source, "{}", 0, null, null, null, null, 0)
            for (id in 1..5) db.storeQueries.upsertVod(source, "m$id", "Movie $id", null, "c", 7.0, 2026, 1000, "mp4", "$id", id.toLong(), 1000, null, null)
            db.storeQueries.upsertSeries(source, "s1", "Show 1", null, null, "c", null, null, 7.0, 2026, 1000, 0, 1000, null, "1")
        }
    }
    private suspend fun feed(key: String, vararg entries: Pair<String, String>) {
        user.putSetting("discovery_cache_$key", Json.encodeToString(DiscoveryFeed(999, entries.map { DiscoveryTitle(it.first, it.second) })))
    }
    @Test fun shelvesAreDisjointAcrossProvidersAndRetainMovieShowIdentity() = runTest {
        seed()
        feed("day", "1" to "movie", "1" to "movie", "1" to "tv")
        feed("week", "1" to "movie", "2" to "movie", "3" to "movie")
        feed("recent", "1" to "tv", "2" to "movie", "4" to "movie")
        val rows = repo.home(null, flowOf(ShowEverything.first())).first()
        assertEquals(listOf("released", "trendingWeek", "popularToday"), rows.map { it.id })
        val ids = rows.flatMap { it.posters }.map { it.titleIdentity() }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(2, rows.last().posters.size)
        assertEquals(listOf("4"), rows.first().posters.map { it.tmdbId })
    }
    @Test fun hiddenCopyFallsBackToVisibleProviderAndKindScopesHold() = runTest {
        seed(); feed("day", "1" to "movie", "1" to "tv")
        user.setCategoryHidden(SourceId("a"), ContentKind.VOD, "c", true)
        val movies = repo.home(ContentKind.VOD, ShowEverything).first().single().posters
        assertEquals("b", movies.single().key.sourceId.value)
        assertEquals(ContentKind.VOD, movies.single().key.kind)
        val shows = repo.home(ContentKind.SERIES, ShowEverything).first().single().posters
        assertEquals(ContentKind.SERIES, shows.single().key.kind)
        assertTrue(repo.home(null, flowOf<Visibility>({ _, _, _ -> false })).first().isEmpty())
    }
    @Test fun missingKeyAndMalformedCacheKeepExistingGoodFeeds() = runTest {
        seed(); feed("day", "1" to "movie")
        user.putSetting("discovery_cache_week", "broken json")
        assertFalse(repo.refresh())
        assertEquals("1", repo.feed("day").first().titles.single().id)
        assertEquals(1, repo.home(null, ShowEverything).first().size)
    }
    @Test fun parserRejectsPeopleAdultAndInvalidIds() {
        val data = Json.parseToJsonElement("""{"results":[{"id":1,"media_type":"person"},{"id":2,"media_type":"movie","adult":true},{"id":-1,"media_type":"tv"},{"id":3,"media_type":"tv","first_air_date":"2026-10-01"}]}""").jsonObject
        assertEquals(listOf(DiscoveryTitle("3", "tv", "2026-10-01")), DiscoveryRepository.parse(data))
    }
    @Test fun netflixUsesLatestPublishedUSWeekAndOriginalRanks() {
        val tsv = "country_iso2\tweek\tcategory\tweekly_rank\tshow_title\nCA\t2026-10-11\tFilms\t1\tCanada only\nUS\t2026-10-11\tFilms\t3\tA film\nUS\t2026-10-11\tTV\t7\tA show\nUS\t2026-10-04\tFilms\t1\tOld film"
        val rows = NetflixCharts.parse(tsv.lineSequence(), "2026-10-17")
        assertEquals(listOf(3, 7), rows.map { it.rank })
        assertEquals(setOf("2026-10-11"), rows.map { it.week }.toSet())
        assertFails { NetflixCharts.parse(sequenceOf("bad header"), "2026-10-17") }
    }
    @Test fun updatePolicySanitizesSavedValues() {
        assertEquals(AutoRefreshPolicy(), AutoRefreshPolicy.parse(null))
        assertEquals(AutoRefreshPolicy("daily", 168, 1439), AutoRefreshPolicy.parse("daily|999|9999"))
        assertEquals("startup", AutoRefreshPolicy.parse("startup|6|180").mode)
    }
}
