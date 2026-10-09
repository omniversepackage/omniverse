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

/** Task 92: the anime library queries — token matching, kind isolation, dedup, exclusion, sort. */
class AnimeLibraryQueryTest {
    private val db: OmniverseDb by lazy {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) }
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES " +
            "('plex','PLEX','Plex','{}',0),('iptv','XTREAM','IPTV','{}',1)", 0)
        driver.execute(null, "INSERT INTO category(source_id,kind,remote_id,name,parent_id,sort_index,sync_gen) VALUES " +
            "('plex','VOD','cr','ANIME | SUB',NULL,1,1)," +
            "('plex','VOD','kids','Kids & Cartoons',NULL,2,1)," +
            "('iptv','VOD','animes','Animes',NULL,1,1)," +
            "('iptv','VOD','movies','Movies',NULL,2,1)," +
            "('plex','SERIES','anime','Anime',NULL,1,1)," +
            "('plex','SERIES','cartoons','Cartoons',NULL,2,1)," +
            "('iptv','SERIES','funi','Funimation',NULL,1,1)", 0)
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,poster_url,primary_category_id,rating,year,tmdb_id,genre,sort_index,sync_gen) VALUES " +
            "('plex','a1','Naruto Ship',NULL,'cr',8.0,2020,'ta1','Animation|Action',1,1)," +
            "('plex','k1','Sponge Thing',NULL,'kids',6.0,2015,'tk1','Kids|Cartoons',2,1)," +
            "('iptv','a2','Naruto Ship Dub',NULL,'animes',8.0,2020,'ta1','Anime',1,1)," +
            "('iptv','a3','Solo Level Manga',NULL,'movies',7.0,2021,NULL,'Action|anime',2,1)," +
            "('iptv','k2','Tom Cat',NULL,'movies',5.0,2019,NULL,'Cartoons',3,1)", 0)
        driver.execute(null, "INSERT INTO series(source_id,remote_id,name,poster_url,primary_category_id,plot,genre,rating,year,tmdb_id,sort_index,sync_gen) VALUES " +
            "('plex','sa1','Attack Titan',NULL,'anime',NULL,'Animation',8.5,2013,'tsa',1,1)," +
            "('plex','sc1','Sponge Toons',NULL,'cartoons',NULL,'Animation|Kids',6.0,2010,'tsc',2,1)," +
            "('iptv','sf1','One Piece',NULL,'funi',NULL,'Anime',8.0,1999,'tsf',1,1)", 0)
        val db = OmniverseDb(driver)
        db.storeQueries.recomputeVodGroups()
        db.storeQueries.recomputeSeriesGroups()
        db
    }
    private val catalog: CatalogRepositoryImpl get() = CatalogRepositoryImpl(db, Dispatchers.Unconfined)

    private suspend fun PagingSource<Int, PosterRow>.all(): List<PosterRow> =
        assertIs<PagingSource.LoadResult.Page<Int, PosterRow>>(load(PagingSource.LoadParams.Refresh(null, 500, false))).data

    private fun List<PosterRow>.slots() = map { "${it.key.sourceId.value}/${it.key.remoteId.value}" }

    @Test fun vodLibraryReturnsOnlyAnimeMovies() = runTest {
        val rows = catalog.animeLibrary(ContentKind.VOD).all()
        assertEquals(listOf("plex/a1", "iptv/a3"), rows.slots())
        assertEquals(listOf(ContentKind.VOD), rows.map { it.key.kind }.distinct())
    }

    @Test fun seriesLibraryReturnsOnlyAnimeShows() = runTest {
        val rows = catalog.animeLibrary(ContentKind.SERIES).all()
        assertEquals(listOf("plex/sa1", "iptv/sf1"), rows.slots())
        assertEquals(listOf(ContentKind.SERIES), rows.map { it.key.kind }.distinct())
    }

    @Test fun exactTmdbDedupLetsPlexWinAcrossCategories() = runTest {
        // ta1 sits in a Plex anime category and an IPTV "Animes" category: one card, Plex wins.
        val rows = catalog.animeLibrary(ContentKind.VOD).all()
        assertEquals(1, rows.count { it.tmdbId == "ta1" })
        assertEquals("plex", rows.first { it.tmdbId == "ta1" }.key.sourceId.value)
    }

    @Test fun excludedCategoryKeysHideLockedRowsAndFreeTheDuplicate() = runTest {
        val rows = catalog.animeLibrary(ContentKind.VOD, listOf("VOD|plex|cr")).all()
        assertEquals(listOf("iptv/a2", "iptv/a3"), rows.slots())
    }

    @Test fun alphabeticSortsByTitleProviderOrderOtherwise() = runTest {
        val az = catalog.animeLibrary(ContentKind.VOD, emptyList(), alphabetic = true).all()
        assertEquals(listOf("Naruto Ship", "Solo Level Manga"), az.map { it.name })
        val provider = catalog.animeLibrary(ContentKind.VOD).all()
        assertEquals(listOf("plex/a1", "iptv/a3"), provider.slots())
    }

    @Test fun categoryNameReturnsProviderLabel() = runTest {
        assertEquals("ANIME | SUB", catalog.categoryName(SourceId("plex"), ContentKind.VOD, RemoteId("cr")))
        assertEquals("Funimation", catalog.categoryName(SourceId("iptv"), ContentKind.SERIES, RemoteId("funi")))
        assertEquals(null, catalog.categoryName(SourceId("plex"), ContentKind.VOD, RemoteId("nope")))
    }
}
