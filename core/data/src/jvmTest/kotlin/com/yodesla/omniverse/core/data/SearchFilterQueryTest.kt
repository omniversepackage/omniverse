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
 * Task 122: the MAIN search filter queries — decade inclusivity, null-year drop-out, whole-word
 * anime (task 130: the chip means "Anime & Animation", so animation/animated/cartoon/cartoons
 * whole words pass too), exact-TMDB dedup chosen AFTER every filter (a nonmatching Plex copy
 * never hides a matching IPTV copy), excluded-key visibility, the FTS join for text+filters,
 * blank-query discovery and the "more matches exist" flag that drives "Show more".
 */
class SearchFilterQueryTest {
    private val db: OmniverseDb by lazy {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) }
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES " +
            "('plex','PLEX','Plex','{}',0),('iptv','XTREAM','IPTV','{}',1)", 0)
        driver.execute(null, "INSERT INTO category(source_id,kind,remote_id,name,parent_id,sort_index,sync_gen) VALUES " +
            "('plex','VOD','cr','ANIME | SUB',NULL,1,1)," +
            "('plex','VOD','kids','Kids & Cartoons',NULL,2,1)," +
            "('plex','VOD','movies','Movies',NULL,3,1)," +
            "('iptv','VOD','animes','Animes',NULL,1,1)," +
            "('iptv','VOD','movies','Movies',NULL,2,1)," +
            "('iptv','VOD','off','Switched Off',NULL,3,1)," +
            "('plex','SERIES','anime','Anime',NULL,1,1)," +
            "('iptv','SERIES','funi','Funimation',NULL,1,1)", 0)
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,poster_url,primary_category_id,rating,year,tmdb_id,genre,sort_index,sync_gen) VALUES " +
            "('plex','a80','80s Anime',NULL,'cr',8.0,1980,'ta80','Animation',1,1)," +
            "('plex','a89','Late 80 Anime',NULL,'cr',7.0,1989,'ta89','Anime',2,1)," +
            "('plex','a79','79 Anime',NULL,'cr',7.0,1979,'ta79','Anime',3,1)," +
            "('plex','a90','90 Anime',NULL,'cr',7.0,1990,'ta90','Anime',4,1)," +
            "('plex','ny','No Year Anime',NULL,'cr',7.0,NULL,'tny','Anime',5,1)," +
            "('plex','anim','Animation Only',NULL,'kids',6.0,1985,'tanim','Animation',6,1)," +
            "('plex','p90','90s Duplex',NULL,'cr',7.0,1990,'tdup','Anime',7,1)," +
            "('iptv','dup','80s Anime Dub',NULL,'animes',8.0,1980,'ta80','Anime',1,1)," +
            "('iptv','catonly','Category Only',NULL,'animes',7.0,1983,NULL,NULL,2,1)," +
            "('iptv','act85','Action 85',NULL,'movies',7.0,1985,NULL,'Action',3,1)," +
            "('iptv','off85','Off Library 85',NULL,'off',7.0,1985,NULL,'Action',4,1)," +
            "('iptv','i80','80s Duplex',NULL,'animes',7.0,1980,'tdup','Anime',5,1)", 0)
        driver.execute(null, "INSERT INTO series(source_id,remote_id,name,poster_url,primary_category_id,plot,genre,rating,year,tmdb_id,sort_index,sync_gen) VALUES " +
            "('plex','s80','Show 80',NULL,'anime',NULL,'Anime',8.0,1980,'ts80',1,1)," +
            "('iptv','sf88','Show 88',NULL,'funi',NULL,'Anime',7.0,1988,'tsf',1,1)", 0)
        driver.execute(null, "INSERT INTO search_index(title,kind,source_id,remote_id) VALUES " +
            "('80s Anime','VOD','plex','a80'),('Late 80 Anime','VOD','plex','a89')," +
            "('Action 85','VOD','iptv','act85'),('Show 80','SERIES','plex','s80')," +
            "('90s Duplex','VOD','plex','p90'),('80s Duplex','VOD','iptv','i80')", 0)
        val db = OmniverseDb(driver)
        db.storeQueries.recomputeVodGroups()
        db.storeQueries.recomputeSeriesGroups()
        db
    }
    private val search: SearchRepositoryImpl get() = SearchRepositoryImpl(db, Dispatchers.Unconfined)

    private fun Map<ContentKind, SearchFilteredPage>.slots(kind: ContentKind) =
        this[kind]?.rows?.map { "${it.key.sourceId.value}/${it.key.remoteId.value}" } ?: emptyList()

    private val movies80sAnime = SearchFilters(movies = true, yearFrom = 1980, yearTo = 1989, anime = true)

    @Test fun blankQueryWithFiltersDiscoversTitles() = runTest {
        // The headline UX: no text at all, Movies + 1980s + Anime & Animation → a real grid, not "nothing found".
        val found = search.searchFiltered("", movies80sAnime)
        assertEquals(listOf("plex/a80", "plex/a89", "iptv/catonly", "plex/anim", "iptv/i80"), found.slots(ContentKind.VOD))
        assertEquals(emptyList(), found.slots(ContentKind.SERIES))
    }

    @Test fun decadeIsInclusiveAndNullYearDropsOut() = runTest {
        val found = search.searchFiltered("", movies80sAnime)
        val slots = found.slots(ContentKind.VOD)
        assertTrue("plex/a80" in slots) // 1980 = first year of the decade
        assertTrue("plex/a89" in slots) // 1989 = last year, not 1990
        assertFalse("plex/a79" in slots) // 1979 belongs to the 70s chip
        assertFalse("plex/a90" in slots) // 1990 belongs to the 90s chip
        assertFalse("plex/ny" in slots) // unknown year: silently excluded by a decade filter (documented)
    }

    @Test fun animeChipIsAnimePlusAnimationWholeWordsOnly() = runTest {
        val found = search.searchFiltered("", SearchFilters(movies = true, anime = true))
        val slots = found.slots(ContentKind.VOD)
        assertTrue("plex/a80" in slots) // category name "ANIME | SUB", genre "Animation" — matches on both
        assertTrue("plex/a89" in slots) // genre "Anime"
        assertTrue("iptv/catonly" in slots) // category name only, genre NULL
        assertTrue("plex/ny" in slots) // genre "Anime", no year needed when no decade is set
        assertTrue("plex/anim" in slots) // task 130: genre "Animation" / category "Kids & Cartoons" now pass
        assertFalse("iptv/act85" in slots) // "Action" carries none of the tokens
    }

    @Test fun genreChipMatchesWholeWordSoAnimationStaysSeparate() = runTest {
        val found = search.searchFiltered("", SearchFilters(movies = true, genres = listOf("Animation")))
        assertEquals(listOf("plex/a80", "plex/anim"), found.slots(ContentKind.VOD))
        val anime = search.searchFiltered("", SearchFilters(movies = true, genres = listOf("anime")))
        // Dedup runs after the genre filter (task 122 review): "80s Anime Dub" (genre Anime) now wins
        // its own tmdb slot because the Plex twin's genre is "Animation" and fails the chip.
        assertEquals(listOf("iptv/dup", "plex/a89", "plex/a79", "plex/a90", "plex/ny", "plex/p90"), anime.slots(ContentKind.VOD)) // "Anime" ≠ "Animation", case-insensitive
    }

    @Test fun exactTmdbDedupLetsPlexWinUnderAFilter() = runTest {
        val found = search.searchFiltered("", movies80sAnime)
        assertEquals(1, found[ContentKind.VOD]!!.rows.count { it.tmdbId == "ta80" })
        assertEquals("plex", found[ContentKind.VOD]!!.rows.first { it.tmdbId == "ta80" }.key.sourceId.value)
    }

    @Test fun nonmatchingPlexDuplicateNeverSuppressesAMatchingIptvCopy() = runTest {
        // Task 122 review: the tmdb winner is chosen AFTER year/anime/genre/text. plex/p90 shares
        // tmdb "tdup" with iptv/i80 but fails the 1980s chip — the matching IPTV copy must survive.
        val slots = search.searchFiltered("", movies80sAnime).slots(ContentKind.VOD)
        assertTrue("iptv/i80" in slots)
        assertFalse("plex/p90" in slots)
        // Same through the FTS join: the Plex copy matches the text, the IPTV copy matches the decade.
        val text = search.searchFiltered("duplex", SearchFilters(movies = true, yearFrom = 1980, yearTo = 1989))
        assertEquals(listOf("iptv/i80"), text.slots(ContentKind.VOD))
        // And when both copies match every filter, Plex still wins the slot.
        val both = search.searchFiltered("", SearchFilters(movies = true, anime = true))
        assertEquals("plex", both[ContentKind.VOD]!!.rows.first { it.tmdbId == "tdup" }.key.sourceId.value)
    }

    @Test fun excludedKeysHideLockedRowsAndFreeTheDuplicate() = runTest {
        // Same rule as browse: excluding the Plex copy (locked) frees its IPTV TMDB twin.
        val found = search.searchFiltered("", movies80sAnime, listOf("VOD|plex|cr"))
        assertEquals(listOf("iptv/dup", "iptv/catonly", "plex/anim", "iptv/i80"), found.slots(ContentKind.VOD))
    }

    @Test fun switchedOffLibraryRowsAreExcludedAtTheSqlLevel() = runTest {
        // Settings›Libraries switches arrive as excluded keys (searchExcludedCategoryKeys adds them).
        val all = search.searchFiltered("", SearchFilters(movies = true))
        assertTrue("iptv/off85" in all.slots(ContentKind.VOD))
        val off = search.searchFiltered("", SearchFilters(movies = true), listOf("VOD|iptv|off"))
        assertFalse("iptv/off85" in off.slots(ContentKind.VOD))
    }

    @Test fun textNarrowsTheFilteredSet() = runTest {
        // FTS join: the text narrows the same filtered candidate set, not a separate FTS page.
        val action = search.searchFiltered("action", SearchFilters(movies = true, yearFrom = 1980, yearTo = 1989))
        assertEquals(listOf("iptv/act85"), action.slots(ContentKind.VOD))
        val late = search.searchFiltered("late", movies80sAnime)
        assertEquals(listOf("plex/a89"), late.slots(ContentKind.VOD))
        val none = search.searchFiltered("zzz", movies80sAnime)
        assertTrue(none.isEmpty())
    }

    @Test fun showsKindFiltersIndependentlyOfMovies() = runTest {
        val found = search.searchFiltered("", SearchFilters(shows = true, anime = true, yearFrom = 1980, yearTo = 1989))
        assertEquals(listOf("plex/s80", "iptv/sf88"), found.slots(ContentKind.SERIES))
        assertEquals(emptyList(), found.slots(ContentKind.VOD))
        val both = search.searchFiltered("", SearchFilters(anime = true, yearFrom = 1980, yearTo = 1989))
        assertTrue(both.containsKey(ContentKind.VOD) && both.containsKey(ContentKind.SERIES)) // neither chip = both kinds
    }

    @Test fun capReportsMoreMatchesInsteadOfLying() = runTest {
        val page = search.searchFiltered("", movies80sAnime, limitPerKind = 1)[ContentKind.VOD]!!
        assertEquals(1, page.rows.size)
        assertTrue(page.more) // 5 match, 1 shown — the UI must say so
        val all = search.searchFiltered("", movies80sAnime, limitPerKind = 24)[ContentKind.VOD]!!
        assertFalse(all.more)
    }

    @Test fun sortIsAppliedBeforeTheResultLimitAndMissingMetadataComesLast() = runTest {
        val allMovies = SearchFilters(movies = true)
        assertEquals("plex/a90", search.searchFiltered("", allMovies, limitPerKind = 1, sort = SearchSort.YEAR_NEWEST).slots(ContentKind.VOD).single())
        assertEquals("plex/a79", search.searchFiltered("", allMovies, limitPerKind = 1, sort = SearchSort.YEAR_OLDEST).slots(ContentKind.VOD).single())
        assertEquals("plex/a79", search.searchFiltered("", allMovies, limitPerKind = 1, sort = SearchSort.TITLE_ASC).slots(ContentKind.VOD).single())
        assertEquals("plex/a80", search.searchFiltered("", allMovies, limitPerKind = 1, sort = SearchSort.RATING).slots(ContentKind.VOD).single())
        assertEquals("plex/a89", search.searchFiltered("anime", allMovies, limitPerKind = 1, sort = SearchSort.YEAR_NEWEST).slots(ContentKind.VOD).single())
        assertEquals("plex/ny", search.searchFiltered("", allMovies, sort = SearchSort.YEAR_NEWEST).slots(ContentKind.VOD).last())
    }

    @Test fun blankQueryWithoutFiltersDiscoversNothing() = runTest {
        assertTrue(search.searchFiltered("", SearchFilters.None).isEmpty())
    }

    @Test fun decadeOptionsOnlyOfferDecadesWithTitledYears() = runTest {
        val decades = search.decadeOptions(listOf(ContentKind.VOD, ContentKind.SERIES))
        assertEquals(listOf(1990, 1980, 1970), decades) // newest first; the null-year row contributes nothing
        val vodOnly = search.decadeOptions(listOf(ContentKind.VOD))
        assertEquals(listOf(1990, 1980, 1970), vodOnly)
        val seriesOnly = search.decadeOptions(listOf(ContentKind.SERIES))
        assertEquals(listOf(1980), seriesOnly)
    }
}
