package com.yodesla.omniverse.core.data

import androidx.paging.PagingSource
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.CatalogRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Task 84i: the browse filter queries (watched state, year, genre, rating, runtime, 4K, Plex). */
class BrowseFilterQueryTest {
    private fun lit(v: String?): String = if (v == null) "NULL" else "'" + v.replace("'", "''") + "'"
    private fun num(v: Any?): String = v?.toString() ?: "NULL"

    private val db: OmniverseDb by lazy {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) }
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES " +
            "('plex','PLEX','Plex','{}',0),('iptv','XTREAM','IPTV','{}',1)", 0)
        // Plex movies: Alpha (2015 Action/Adventure, 100min), Bravo (1995 Drama, watched, 95min),
        // Charlie (2020 Action, in progress, 80min), Delta (2021 Sci-Fi 4K, 150min).
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,poster_url,primary_category_id,rating,year,tmdb_id,genre,sort_index,sync_gen) VALUES " +
            "('plex','m1','Alpha One',NULL,'movies',7.5,2015,'t1','Action|Adventure',1,1)," +
            "('plex','m2','Bravo Old',NULL,'movies',5.0,1995,'t2','Drama',2,1)," +
            "('plex','m3','Charlie Quick',NULL,'movies',8.0,2020,'t3','Action',3,1)," +
            "('plex','m4','Delta Epic 4K',NULL,'movies',8.5,2021,'t4','Sci-Fi',4,1)," +
            "('iptv','i1','Echo Dup',NULL,'movies',7.5,2015,'t1','Action',1,1)," +
            "('iptv','i2','Foxtrot Solo',NULL,'movies',6.0,2019,NULL,'Comedy',2,1)", 0)
        driver.execute(null, "INSERT INTO tmdb_meta(tmdb_id,kind,runtime_min,fetched_ms) VALUES " +
            "('t1','MOVIE',100,1),('t2','MOVIE',95,1),('t3','MOVIE',80,1),('t4','MOVIE',150,1)," +
            "('ts1','SERIES',45,1),('ts2','SERIES',30,1)", 0)
        driver.execute(null, "INSERT INTO progress(profile_id,source_id,kind,remote_id,parent_id,position_ms,duration_ms,updated_ms,completed) VALUES " +
            "('default','plex','VOD','m2',NULL,10,10,1,1)," +
            "('default','plex','VOD','m3',NULL,600000,4800000,1,0)," +
            "('default','plex','EPISODE','e1','s1',10,10,1,1)," +
            "('default','plex','EPISODE','e2','s2',600000,1800000,1,0)", 0)
        driver.execute(null, "INSERT INTO series(source_id,remote_id,name,poster_url,primary_category_id,plot,genre,rating,year,tmdb_id,sort_index,sync_gen) VALUES " +
            "('plex','s1','Show One',NULL,'shows',NULL,'Drama|Crime',7.0,2010,'ts1',1,1)," +
            "('plex','s2','Show Two',NULL,'shows',NULL,'Action',8.0,2022,'ts2',2,1)", 0)
        OmniverseDb(driver)
    }
    private val catalog: CatalogRepositoryImpl get() = CatalogRepositoryImpl(db, Dispatchers.Unconfined)

    private suspend fun PagingSource<Int, PosterRow>.all(): List<PosterRow> =
        assertIs<PagingSource.LoadResult.Page<Int, PosterRow>>(load(PagingSource.LoadParams.Refresh(null, 500, false))).data

    private fun List<PosterRow>.slots() = map { "${it.key.sourceId.value}/${it.key.remoteId.value}" }

    @Test fun watchedStatesOnOneSource() = runTest {
        val unwatched = catalog.vodFiltered(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(started = false, completed = false)).all()
        assertEquals(listOf("plex/m1", "plex/m4"), unwatched.slots())
        val watched = catalog.vodFiltered(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(completed = true)).all()
        assertEquals(listOf("plex/m2"), watched.slots())
        val inProgress = catalog.vodFiltered(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(started = true, completed = false)).all()
        assertEquals(listOf("plex/m3"), inProgress.slots())
    }

    @Test fun yearDecadeAndRating() = runTest {
        val decade = catalog.vodFiltered(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(yearFrom = 2010, yearTo = 2019)).all()
        assertEquals(listOf("plex/m1"), decade.slots())
        val rated = catalog.vodFiltered(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(ratingAtLeast = 8f)).all()
        assertEquals(listOf("plex/m3", "plex/m4"), rated.slots())
    }

    @Test fun genresMatchAnyToken() = runTest {
        val one = catalog.vodFiltered(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(genres = listOf("Action"))).all()
        assertEquals(listOf("plex/m1", "plex/m3"), one.slots())
        val two = catalog.vodFiltered(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(genres = listOf("action", "DRAMA"))).all()
        assertEquals(listOf("plex/m1", "plex/m2", "plex/m3"), two.slots())
    }

    @Test fun runtimeBoundsUseCachedTmdbRuntime() = runTest {
        val short = catalog.vodFiltered(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(runtimeAtMostMin = 89)).all()
        assertEquals(listOf("plex/m3"), short.slots())
        val mid = catalog.vodFiltered(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(runtimeAtLeastMin = 90, runtimeAtMostMin = 120)).all()
        assertEquals(listOf("plex/m1", "plex/m2"), mid.slots())
        val long = catalog.vodFiltered(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(runtimeAtLeastMin = 121)).all()
        assertEquals(listOf("plex/m4"), long.slots())
    }

    @Test fun uhdOnlyMatchesTitleMarkers() = runTest {
        val uhd = catalog.vodFiltered(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(uhdOnly = true)).all()
        assertEquals(listOf("plex/m4"), uhd.slots())
    }

    @Test fun allSourcesMergesDedupsAndFilters() = runTest {
        // Plex wins the t1 duplicate; Echo Dup drops out; Foxtrot (no tmdb) stays.
        val unwatched = catalog.vodFiltered(null, null, SmartCollectionFilter(started = false, completed = false)).all()
        assertEquals(listOf("plex/m1", "plex/m4", "iptv/i2"), unwatched.slots())
        val plexOnly = catalog.vodFiltered(null, null, SmartCollectionFilter(plexOnly = true)).all()
        assertEquals(listOf("plex/m1", "plex/m2", "plex/m3", "plex/m4"), plexOnly.slots())
        val alpha = catalog.vodFiltered(null, null, SmartCollectionFilter(genres = listOf("Action")), alphabetic = true).all()
        assertEquals(listOf("Alpha One", "Charlie Quick"), alpha.map { it.name })
    }

    @Test fun allSourcesHonoursExcludedCategoryKeys() = runTest {
        // Excluding the Plex copy also frees its iptv TMDB duplicate (dedup only counts visible winners).
        val rows = catalog.vodFiltered(null, null, SmartCollectionFilter(), listOf("VOD|plex|movies")).all()
        assertEquals(listOf("iptv/i1", "iptv/i2"), rows.slots())
    }

    @Test fun genreOptionsSplitTrimAndSort() = runTest {
        assertEquals(listOf("Action", "Adventure", "Drama", "Sci-Fi"), catalog.genreOptions(ContentKind.VOD, SourceId("plex"), null))
        assertEquals(listOf("Action"), catalog.genreOptions(ContentKind.VOD, SourceId("plex"), RemoteId("movies")).filter { it == "Action" })
    }

    @Test fun seriesWatchedStateCorrelatesEpisodeProgress() = runTest {
        val watched = catalog.seriesFiltered(null, null, SmartCollectionFilter(completed = true)).all()
        assertEquals(listOf("plex/s1"), watched.slots())
        val inProgress = catalog.seriesFiltered(null, null, SmartCollectionFilter(started = true, completed = false)).all()
        assertEquals(listOf("plex/s2"), inProgress.slots())
        val crime = catalog.seriesFiltered(SourceId("plex"), RemoteId("shows"), SmartCollectionFilter(genres = listOf("Crime"))).all()
        assertEquals(listOf("plex/s1"), crime.slots())
    }

    @Test fun filteredPagingPagesWithCountQuery() = runTest {
        val paging = catalog.vodFiltered(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(genres = listOf("Action")))
        val first = assertIs<PagingSource.LoadResult.Page<Int, PosterRow>>(paging.load(PagingSource.LoadParams.Refresh(null, 1, false)))
        assertEquals(listOf("plex/m1"), first.data.slots())
        assertEquals(1, first.nextKey)
        val second = assertIs<PagingSource.LoadResult.Page<Int, PosterRow>>(paging.load(PagingSource.LoadParams.Append(first.nextKey!!, 1, false)))
        assertEquals(listOf("plex/m3"), second.data.slots())
        assertEquals(null, second.nextKey)
    }
}
