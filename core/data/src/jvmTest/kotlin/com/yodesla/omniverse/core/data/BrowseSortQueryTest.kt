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

/** Task 115: the four browse sorts run in SQL before paging, with nulls last and stable ties. */
class BrowseSortQueryTest {
    private val db: OmniverseDb by lazy {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) }
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES " +
            "('plex','PLEX','Plex','{}',0),('iptv','XTREAM','IPTV','{}',1)", 0)
        // Plex movies: Alpha 2015/7.5, Bravo 1995/5.0, Charlie 2020/8.0, Delta 2021/8.5,
        // Echo null year+rating, Foxtrot 2020/6.5 (year-ties Charlie, rating sits below Alpha).
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,poster_url,primary_category_id,rating,year,tmdb_id,genre,sort_index,sync_gen) VALUES " +
            "('plex','m1','Alpha One',NULL,'movies',7.5,2015,'t1','Action|Adventure',1,1)," +
            "('plex','m2','Bravo Old',NULL,'movies',5.0,1995,'t2','Drama',2,1)," +
            "('plex','m3','Charlie Quick',NULL,'movies',8.0,2020,'t3','Action',3,1)," +
            "('plex','m4','Delta Epic',NULL,'movies',8.5,2021,'t4','Sci-Fi',4,1)," +
            "('plex','m5','Echo Nulls',NULL,'movies',NULL,NULL,NULL,'Comedy',5,1)," +
            "('plex','m6','Foxtrot Mid',NULL,'movies',6.5,2020,NULL,'Action',6,1)," +
            "('iptv','i1','Echo Dup',NULL,'movies',7.5,2015,'t1','Action',1,1)," +
            "('iptv','i2','Golf NoYear',NULL,'movies',9.9,NULL,NULL,'Comedy',2,1)", 0)
        driver.execute(null, "INSERT INTO series(source_id,remote_id,name,poster_url,primary_category_id,plot,genre,rating,year,tmdb_id,sort_index,sync_gen) VALUES " +
            "('plex','s1','Show One',NULL,'shows',NULL,'Drama',7.0,2010,'ts1',1,1)," +
            "('plex','s2','Show Two',NULL,'shows',NULL,'Action',8.0,2022,'ts2',2,1)," +
            "('iptv','s3','Show Three',NULL,'shows',NULL,'Comedy',NULL,NULL,NULL,1,1)", 0)
        OmniverseDb(driver)
    }
    private val animeDb: OmniverseDb by lazy {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) }
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES " +
            "('plex','PLEX','Plex','{}',0),('iptv','XTREAM','IPTV','{}',1)", 0)
        driver.execute(null, "INSERT INTO category(source_id,kind,remote_id,name,parent_id,sort_index,sync_gen) VALUES " +
            "('plex','VOD','cr','ANIME | SUB',NULL,1,1),('iptv','VOD','movies','Movies',NULL,2,1)", 0)
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,poster_url,primary_category_id,rating,year,tmdb_id,genre,sort_index,sync_gen) VALUES " +
            "('plex','a1','Naruto Ship',NULL,'cr',8.0,2020,'ta1','Animation|Action',1,1)," +
            "('iptv','a3','Solo Level Manga',NULL,'movies',7.0,2021,NULL,'Action|anime',2,1)", 0)
        OmniverseDb(driver)
    }
    private val catalog: CatalogRepositoryImpl get() = CatalogRepositoryImpl(db, Dispatchers.Unconfined)
    private val animeCatalog: CatalogRepositoryImpl get() = CatalogRepositoryImpl(animeDb, Dispatchers.Unconfined)

    private suspend fun PagingSource<Int, PosterRow>.all(): List<PosterRow> =
        assertIs<PagingSource.LoadResult.Page<Int, PosterRow>>(load(PagingSource.LoadParams.Refresh(null, 500, false))).data

    private fun List<PosterRow>.slots() = map { "${it.key.sourceId.value}/${it.key.remoteId.value}" }

    @Test fun providerSortMatchesTheLegacyQueriesExactly() = runTest {
        assertEquals(catalog.vod(SourceId("plex"), RemoteId("movies")).all().slots(),
            catalog.vodSorted(SourceId("plex"), RemoteId("movies"), BrowseSort.PROVIDER).all().slots())
        assertEquals(catalog.series(SourceId("plex"), RemoteId("shows")).all().slots(),
            catalog.seriesSorted(SourceId("plex"), RemoteId("shows"), BrowseSort.PROVIDER).all().slots())
        assertEquals(catalog.vodAll().all().slots(), catalog.vodAllSorted(sort = BrowseSort.PROVIDER).all().slots())
        assertEquals(catalog.seriesAll().all().slots(), catalog.seriesAllSorted(sort = BrowseSort.PROVIDER).all().slots())
        assertEquals(catalog.vodFiltered(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(yearFrom = 2000)).all().slots(),
            catalog.vodFilteredSorted(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(yearFrom = 2000), sort = BrowseSort.PROVIDER).all().slots())
        assertEquals(catalog.animeLibrary(ContentKind.VOD).all().slots(),
            animeCatalog.animeLibrarySorted(ContentKind.VOD).all().slots())
    }

    @Test fun alphabeticSortMatchesTheLegacyAlphabeticQueries() = runTest {
        assertEquals(catalog.vodAllAlphabetic().all().map { it.name },
            catalog.vodAllSorted(sort = BrowseSort.ALPHABETICAL).all().map { it.name })
        assertEquals(catalog.seriesAllAlphabetic().all().map { it.name },
            catalog.seriesAllSorted(sort = BrowseSort.ALPHABETICAL).all().map { it.name })
        assertEquals(listOf("Alpha One", "Bravo Old", "Charlie Quick", "Delta Epic", "Echo Nulls", "Foxtrot Mid"),
            catalog.vodSorted(SourceId("plex"), RemoteId("movies"), BrowseSort.ALPHABETICAL).all().map { it.name })
    }

    @Test fun releaseYearNewestFirstNullsLastWithStableTies() = runTest {
        // 2021, then the 2020 pair tie-broken by name (Charlie < Foxtrot), then 2015, 1995, null year last.
        assertEquals(listOf("plex/m4", "plex/m3", "plex/m6", "plex/m1", "plex/m2", "plex/m5"),
            catalog.vodSorted(SourceId("plex"), RemoteId("movies"), BrowseSort.RELEASE_YEAR).all().slots())
    }

    @Test fun ratingHighestFirstNullsLastWithStableTies() = runTest {
        assertEquals(listOf("plex/m4", "plex/m3", "plex/m1", "plex/m6", "plex/m2", "plex/m5"),
            catalog.vodSorted(SourceId("plex"), RemoteId("movies"), BrowseSort.RATING).all().slots())
    }

    @Test fun allSourcesYearSortKeepsDedupAndSortsNullsLast() = runTest {
        // iptv Echo Dup drops (plex wins t1); Golf (null year) lands after Echo Nulls by name tie.
        assertEquals(listOf("plex/m4", "plex/m3", "plex/m6", "plex/m1", "plex/m2", "plex/m5", "iptv/i2"),
            catalog.vodAllSorted(sort = BrowseSort.RELEASE_YEAR).all().slots())
        assertEquals(listOf("iptv/i2", "plex/m4", "plex/m3", "plex/m1", "plex/m6", "plex/m2", "plex/m5"),
            catalog.vodAllSorted(sort = BrowseSort.RATING).all().slots())
    }

    @Test fun filteredBrowseSortsOnBothScopes() = runTest {
        assertEquals(listOf("plex/m3", "plex/m6", "plex/m1"),
            catalog.vodFilteredSorted(null, null, SmartCollectionFilter(genres = listOf("Action")), sort = BrowseSort.RELEASE_YEAR).all().slots())
        assertEquals(listOf("plex/m4", "plex/m3", "plex/m6", "plex/m1"),
            catalog.vodFilteredSorted(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(yearFrom = 2000), sort = BrowseSort.RELEASE_YEAR).all().slots())
        assertEquals(listOf("plex/m4", "plex/m3", "plex/m1", "plex/m6"),
            catalog.vodFilteredSorted(SourceId("plex"), RemoteId("movies"), SmartCollectionFilter(yearFrom = 2000), sort = BrowseSort.RATING).all().slots())
    }

    @Test fun seriesSortsOnBothScopes() = runTest {
        assertEquals(listOf("plex/s2", "plex/s1"),
            catalog.seriesSorted(SourceId("plex"), RemoteId("shows"), BrowseSort.RATING).all().slots())
        assertEquals(listOf("plex/s2", "plex/s1", "iptv/s3"),
            catalog.seriesAllSorted(sort = BrowseSort.RELEASE_YEAR).all().slots())
        assertEquals(listOf("plex/s2", "plex/s1", "iptv/s3"),
            catalog.seriesAllSorted(sort = BrowseSort.RATING).all().slots())
    }

    @Test fun pagingKeepsTheGlobalSortAcrossPages() = runTest {
        val paging = catalog.vodSorted(SourceId("plex"), RemoteId("movies"), BrowseSort.RELEASE_YEAR)
        val first = assertIs<PagingSource.LoadResult.Page<Int, PosterRow>>(paging.load(PagingSource.LoadParams.Refresh(null, 2, false)))
        assertEquals(listOf("plex/m4", "plex/m3"), first.data.slots())
        val second = assertIs<PagingSource.LoadResult.Page<Int, PosterRow>>(paging.load(PagingSource.LoadParams.Append(first.nextKey!!, 2, false)))
        assertEquals(listOf("plex/m6", "plex/m1"), second.data.slots())
        val third = assertIs<PagingSource.LoadResult.Page<Int, PosterRow>>(paging.load(PagingSource.LoadParams.Append(second.nextKey!!, 2, false)))
        assertEquals(listOf("plex/m2", "plex/m5"), third.data.slots())
        assertEquals(null, third.nextKey)
    }

    @Test fun animeLibraryHonoursAllFourSorts() = runTest {
        assertEquals(listOf("plex/a1", "iptv/a3"), animeCatalog.animeLibrarySorted(ContentKind.VOD, sort = BrowseSort.ALPHABETICAL).all().slots())
        assertEquals(listOf("iptv/a3", "plex/a1"), animeCatalog.animeLibrarySorted(ContentKind.VOD, sort = BrowseSort.RELEASE_YEAR).all().slots())
        assertEquals(listOf("plex/a1", "iptv/a3"), animeCatalog.animeLibrarySorted(ContentKind.VOD, sort = BrowseSort.RATING).all().slots())
    }
}
