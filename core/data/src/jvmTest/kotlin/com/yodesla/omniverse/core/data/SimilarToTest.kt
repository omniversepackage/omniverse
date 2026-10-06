package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.CatalogRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SimilarToTest {
    private fun dbWith(rows: String, series: String): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES ('iptv','XTREAM','IPTV','{}',0)", 0)
        if (rows.isNotBlank()) driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,genre,rating,sort_index,sync_gen,removed_ms) VALUES $rows", 0)
        if (series.isNotBlank()) driver.execute(null, "INSERT INTO series(source_id,remote_id,name,primary_category_id,genre,rating,sort_index,sync_gen) VALUES $series", 0)
        return driver
    }

    @Test
    fun movieSeedRanksBySharedGenreTokensThenRatingAndSkipsSeedKindMismatchAndRemoved() = runTest {
        val driver = dbWith(
            rows = "('iptv','seed','Seed Movie','movies','Action, Thriller',6.0,0,1,NULL)," +
                "('iptv','two','Two Shared','movies','Action|Thriller',5.0,1,1,NULL)," +
                "('iptv','one-high','One Shared High','movies','Thriller',9.0,2,1,NULL)," +
                "('iptv','one-low','One Shared Low','movies','action',7.0,3,1,NULL)," +
                "('iptv','none','No Shared','movies','Comedy',10.0,4,1,NULL)," +
                "('iptv','gone','Removed Shared','movies','Action',10.0,5,1,99)",
            series = "('iptv','s1','Series Shares Everything','shows','Action, Thriller',10.0,0,1)",
        )
        val catalog = CatalogRepositoryImpl(OmniverseDb(driver), UnconfinedTestDispatcher(testScheduler))
        val seed = ContentKey(SourceId("iptv"), ContentKind.VOD, RemoteId("seed"))
        val got = catalog.similarTo(seed)
        assertEquals(listOf("two", "one-high", "one-low"), got.map { it.key.remoteId.value })
        assertTrue(got.all { it.key.kind == ContentKind.VOD })
        assertEquals(1, catalog.similarTo(seed, limit = 1).size)
        driver.close()
    }

    @Test
    fun seriesSeedUsesPipeSplitTokensAndNeverReturnsMovies() = runTest {
        val driver = dbWith(
            rows = "('iptv','v1','Movie Shares','movies','Drama',10.0,0,1,NULL)",
            series = "('iptv','seed','Seed Show','shows','Drama | Crime',5.0,0,1)," +
                "('iptv','three','Three Tokens','shows','Drama, Crime, Mystery',4.0,1,1)," +
                "('iptv','one','One Token','shows','Crime',8.0,2,1)," +
                "('iptv','none','No Token','shows','Comedy',9.0,3,1)",
        )
        val catalog = CatalogRepositoryImpl(OmniverseDb(driver), UnconfinedTestDispatcher(testScheduler))
        val seed = ContentKey(SourceId("iptv"), ContentKind.SERIES, RemoteId("seed"))
        val got = catalog.similarTo(seed)
        assertEquals(listOf("three", "one"), got.map { it.key.remoteId.value })
        assertTrue(got.all { it.key.kind == ContentKind.SERIES })
        driver.close()
    }

    @Test
    fun seedWithoutGenreHasNoCandidates() = runTest {
        val driver = dbWith(
            rows = "('iptv','seed','No Genre','movies',NULL,6.0,0,1,NULL)," +
                "('iptv','other','Has Genre','movies','Action',6.0,1,1,NULL)",
            series = "",
        )
        val catalog = CatalogRepositoryImpl(OmniverseDb(driver), UnconfinedTestDispatcher(testScheduler))
        val seed = ContentKey(SourceId("iptv"), ContentKind.VOD, RemoteId("seed"))
        assertEquals(emptyList(), catalog.similarTo(seed))
        assertEquals(emptyList(), catalog.similarTo(ContentKey(SourceId("iptv"), ContentKind.LIVE, RemoteId("seed"))))
        driver.close()
    }
}
