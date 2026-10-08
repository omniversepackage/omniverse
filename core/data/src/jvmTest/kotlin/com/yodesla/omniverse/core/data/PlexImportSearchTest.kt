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
 * The 2026-10-07 Plex 1980s anime report: rows written exactly as PlexSource maps Kory's real
 * "Korys Movies" items (optiplex section 2, added 2026-10-07) — a Plex library section name with
 * no anime token, genre NULL because Plex never matched the file (guid local://, no Genre tag,
 * no tmdb Guid), and a real release year.
 *
 * What this pins down: the Plex source IS in the filtered main-search pool and blank-query
 * discovery does find these titles under a decade filter; the Anime chip and the Animation genre
 * chip cannot see them because the row carries no genre/category token at all. A Plex row that
 * does carry "Animation" passes the same chip, so the gap is the imported metadata, not the query.
 */
class PlexImportSearchTest {
    private val db: OmniverseDb by lazy {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) }
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES " +
            "('plex','PLEX','Plex','{}',0),('iptv','XTREAM','IPTV','{}',1)", 0)
        driver.execute(null, "INSERT INTO category(source_id,kind,remote_id,name,parent_id,sort_index,sync_gen) VALUES " +
            "('plex','VOD','2','Korys Movies',NULL,1,1)," +
            "('iptv','VOD','animes','Animes',NULL,1,1)", 0)
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,poster_url,primary_category_id,rating,year,tmdb_id,genre,sort_index,sync_gen) VALUES " +
            "('plex','27002','09 Akira 30th Anniversary Edition',NULL,'2',NULL,1988,NULL,NULL,9,1)," +
            "('plex','26913','04a Angel''s Egg',NULL,'2',NULL,1985,NULL,NULL,4,1)," +
            "('plex','16658','Back to the Future',NULL,'2',8.0,1985,'t194','Adventure, Comedy',12,1)," +
            "('plex','26990','Matched Animation Film',NULL,'2',7.0,1986,'tanim','Animation',13,1)," +
            "('iptv','catonly','80s Anime Dub',NULL,'animes',7.0,1980,'t80','Anime',1,1)", 0)
        OmniverseDb(driver)
    }
    private val search: SearchRepositoryImpl get() = SearchRepositoryImpl(db, Dispatchers.Unconfined)

    private fun Map<ContentKind, SearchFilteredPage>.slots(kind: ContentKind) =
        this[kind]?.rows?.map { "${it.key.sourceId.value}/${it.key.remoteId.value}" } ?: emptyList()

    private val eighties = SearchFilters(movies = true, yearFrom = 1980, yearTo = 1989)

    @Test fun plexTitlesAreInTheFilteredPoolAndDecadesComeFromThem() = runTest {
        val found = search.searchFiltered("", eighties)
        assertTrue("plex/27002" in found.slots(ContentKind.VOD))
        assertTrue("plex/26913" in found.slots(ContentKind.VOD))
        assertTrue(1980 in search.decadeOptions(listOf(ContentKind.VOD)))
    }

    @Test fun unmatchedPlexTitlesMissTheAnimeChipBecauseNothingCarriesAnAnimeToken() = runTest {
        val slots = search.searchFiltered("", eighties.copy(anime = true)).slots(ContentKind.VOD)
        assertFalse("plex/27002" in slots)
        assertFalse("plex/26913" in slots)
        assertTrue("iptv/catonly" in slots) // the IPTV copy still shows: the filter itself works
    }

    @Test fun unmatchedPlexTitlesMissTheAnimationChipButMatchedOnesPassIt() = runTest {
        val slots = search.searchFiltered("", eighties.copy(genres = listOf("Animation"))).slots(ContentKind.VOD)
        assertFalse("plex/27002" in slots) // genre NULL: nothing to match, not a query defect
        assertFalse("plex/26913" in slots)
        assertTrue("plex/26990" in slots) // same source, same section: Plex's own "Animation" tag passes
    }

    @Test fun everyPlexGenreTagBecomesAChip() = runTest {
        // PlexSource joins a movie's tags as "Adventure, Comedy"; the chip list must split them.
        val chips = com.yodesla.omniverse.core.data.impl.CatalogRepositoryImpl(db, Dispatchers.Unconfined)
            .genreOptions(ContentKind.VOD, null, null, emptyList())
        assertEquals(listOf("Adventure", "Animation", "Anime", "Comedy"), chips)
    }
}
