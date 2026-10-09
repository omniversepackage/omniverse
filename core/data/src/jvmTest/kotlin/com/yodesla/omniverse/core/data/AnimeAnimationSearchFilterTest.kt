package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.SearchRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 130: the Search "Anime & Animation" chip. Rows shaped exactly like Kory's Plex library
 * after the full-genre import (Akira = "Science Fiction, Animation", Vampire Hunter D =
 * "Horror, Anime", a Crunchyroll-style "ANIME | SUB" category name, Megazone 23-style
 * "Cartoons"): with the chip on + 1980..1989 every one of them returns, while a 1990 animation
 * row (decade) and a 1985 "Drama" live-action row (no token) stay out. Same rule for series.
 */
class AnimeAnimationSearchFilterTest {
    private val db: OmniverseDb by lazy {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) }
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES ('plex','PLEX','Plex','{}',0)", 0)
        driver.execute(null, "INSERT INTO category(source_id,kind,remote_id,name,parent_id,sort_index,sync_gen) VALUES " +
            "('plex','VOD','2','Korys Movies',NULL,1,1)," +
            "('plex','VOD','cr','ANIME | SUB',NULL,2,1)," +
            "('plex','SERIES','2','Korys Shows',NULL,1,1)", 0)
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,poster_url,primary_category_id,rating,year,tmdb_id,genre,sort_index,sync_gen) VALUES " +
            "('plex','g1','Akira-like',NULL,'2',8.0,1988,NULL,'Science Fiction, Animation',1,1)," +
            "('plex','g2','Vampire Hunter D-like',NULL,'2',7.0,1985,NULL,'Horror, Anime',2,1)," +
            "('plex','g3','Category Anime Row',NULL,'cr',7.0,1986,NULL,NULL,3,1)," +
            "('plex','g4','Megazone 23-like',NULL,'2',7.0,1987,NULL,'Cartoons',4,1)," +
            "('plex','g5','Animated Stories',NULL,'2',7.0,1983,NULL,'Animated',5,1)," +
            "('plex','g6','90s Animation',NULL,'2',7.0,1990,NULL,'Animation',6,1)," +
            "('plex','g7','Out of Africa',NULL,'2',7.0,1985,NULL,'Drama',7,1)", 0)
        driver.execute(null, "INSERT INTO series(source_id,remote_id,name,poster_url,primary_category_id,plot,genre,rating,year,tmdb_id,sort_index,sync_gen) VALUES " +
            "('plex','s1','Gunbuster-like',NULL,'2',NULL,'Science Fiction, Animation',8.0,1988,NULL,1,1)," +
            "('plex','s2','Bubblegum Crisis-like',NULL,'2',NULL,'Anime',7.0,1987,NULL,2,1)," +
            "('plex','s3','Cartoon Show',NULL,'2',NULL,'Cartoons',7.0,1985,NULL,3,1)," +
            "('plex','s4','90s Toon',NULL,'2',NULL,'Animation',7.0,1990,NULL,4,1)," +
            "('plex','s5','Live Drama 85',NULL,'2',NULL,'Drama',7.0,1985,NULL,5,1)", 0)
        OmniverseDb(driver)
    }
    private val search: SearchRepositoryImpl get() = SearchRepositoryImpl(db, Dispatchers.Unconfined)

    private fun Map<ContentKind, SearchFilteredPage>.slots(kind: ContentKind) =
        this[kind]?.rows?.map { "${it.key.sourceId.value}/${it.key.remoteId.value}" } ?: emptyList()

    private val eightiesAnime = SearchFilters(movies = true, yearFrom = 1980, yearTo = 1989, anime = true)
    private val eightiesShowsAnime = SearchFilters(shows = true, yearFrom = 1980, yearTo = 1989, anime = true)

    @Test fun animeChipFindsEveryEightiesAnimationShape() = runTest {
        val slots = search.searchFiltered("", eightiesAnime).slots(ContentKind.VOD)
        assertEquals(listOf("plex/g1", "plex/g2", "plex/g3", "plex/g4", "plex/g5"), slots)
    }

    @Test fun animeChipStillRespectsTheDecadeAndNeedsAToken() = runTest {
        val slots = search.searchFiltered("", eightiesAnime).slots(ContentKind.VOD)
        assertFalse("plex/g6" in slots) // 1990 "Animation": the decade chip still narrows
        assertFalse("plex/g7" in slots) // 1985 "Drama": live-action carries none of the words
        val noChip = search.searchFiltered("", SearchFilters(movies = true, yearFrom = 1980, yearTo = 1989)).slots(ContentKind.VOD)
        assertTrue("plex/g7" in noChip) // the Drama row drops out because of the CHIP, not the pool
        val ninetiesAnime = search.searchFiltered("", eightiesAnime.copy(yearFrom = 1990, yearTo = 1999)).slots(ContentKind.VOD)
        assertEquals(listOf("plex/g6"), ninetiesAnime) // and the 1990 row is reachable under its own decade
    }

    @Test fun animeChipAppliesTheSameWayToSeries() = runTest {
        val slots = search.searchFiltered("", eightiesShowsAnime).slots(ContentKind.SERIES)
        assertEquals(listOf("plex/s1", "plex/s2", "plex/s3"), slots)
        assertFalse("plex/s4" in slots) // 1990 "Animation"
        assertFalse("plex/s5" in slots) // 1985 "Drama"
    }
}
