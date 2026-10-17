package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.SearchRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchPerformanceTest {
    @Test fun textSearchMatchesIndexOnceForLargeCatalog() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES('iptv','XTREAM','IPTV','{}',0)", 0)
        repeat(6_000) { i ->
            driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,genre,year,primary_category_id,group_key,sort_index,sync_gen) " +
                "VALUES('iptv','$i','${if (i % 10 == 0) "Attack Title" else "Movie"} $i','Action',2000,'movies','m:movie-$i|2000',$i,1)", 0)
            driver.execute(null, "INSERT INTO search_index(source_id,remote_id,kind,title) " +
                "VALUES('iptv','$i','VOD','${if (i % 10 == 0) "Attack Title" else "Movie"} $i')", 0)
        }
        val search = SearchRepositoryImpl(OmniverseDb(driver), Dispatchers.Unconfined)
        var count = 0
        val elapsedMs = measureTimeMillis {
            val result = search.searchFiltered("attack", SearchFilters(movies = true), limitPerKind = 24)
            count = result[ContentKind.VOD]?.rows?.size ?: 0
        }
        assertEquals(24, count)
        assertTrue(elapsedMs < 3_000, "Text search took ${elapsedMs}ms for 6,000 titles")
    }

    @Test fun blankFilteredSearchDoesNotCompareEveryCopyWithEveryOtherCopy() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES('iptv','XTREAM','IPTV','{}',0)", 0)
        repeat(6_000) { i ->
            driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,genre,year,primary_category_id,group_key,sort_index,sync_gen) " +
                "VALUES('iptv','$i','Movie $i','Action',2000,'movies','m:movie-${i / 2}|2000',$i,1)", 0)
        }
        val search = SearchRepositoryImpl(OmniverseDb(driver), Dispatchers.Unconfined)
        var count = 0
        val elapsedMs = measureTimeMillis {
            val result = search.searchFiltered("", SearchFilters(movies = true), limitPerKind = 24)
            count = result[ContentKind.VOD]?.rows?.size ?: 0
        }
        assertEquals(24, count)
        assertTrue(elapsedMs < 3_000, "Blank filtered search took ${elapsedMs}ms for 6,000 titles")
    }
}
