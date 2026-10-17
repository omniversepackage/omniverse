package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.SearchRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UnifiedSeriesSearchTest {
    @Test fun sameShowFromThreeSourcesReturnsOneSearchCardEvenWithConflictingIds() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        try {
            driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES " +
                "('plex','PLEX','Plex','{}',0),('iptv','XTREAM','IPTV','{}',1),('emby','PLEX','Emby','{}',2)", 0)
            driver.execute(null, "INSERT INTO series(source_id,remote_id,name,primary_category_id,year,tmdb_id,match_key,sort_index,sync_gen) VALUES " +
                "('plex','p','Attack on Titan','shows',2013,'1429','attackontitan|2013',0,1)," +
                "('iptv','i','Attack on Titan','shows',2013,'999999','attackontitan|2013',0,1)," +
                "('emby','e','Attack on Titan','shows',2013,NULL,'attackontitan|2013',0,1)", 0)
            driver.execute(null, "INSERT INTO search_index(title,kind,source_id,remote_id) VALUES " +
                "('Attack on Titan','SERIES','plex','p'),('Attack on Titan','SERIES','iptv','i')," +
                "('Attack on Titan','SERIES','emby','e')", 0)
            val db = OmniverseDb(driver)
            db.storeQueries.recomputeSeriesGroups()
            val rows = SearchRepositoryImpl(db, Dispatchers.Unconfined)
                .searchFiltered("Attack on Titan", SearchFilters(shows = true))[ContentKind.SERIES]!!.rows
            assertEquals(1, rows.size)
            assertEquals("plex", rows.single().key.sourceId.value)
            val plainRows = SearchRepositoryImpl(db, Dispatchers.Unconfined)
                .searchFiltered("Attack on Titan", SearchFilters.None)[ContentKind.SERIES]!!.rows
            assertEquals(1, plainRows.size)
            assertEquals("plex", plainRows.single().key.sourceId.value)
            val alternatives = db.readQueries.otherSeriesWithSameTmdb("plex", "p").executeAsList()
            assertEquals(setOf("iptv", "emby"), alternatives.map { it.source_id }.toSet())
        } finally {
            driver.close()
        }
    }
}
