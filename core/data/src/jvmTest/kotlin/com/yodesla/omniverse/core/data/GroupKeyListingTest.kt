package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.database.OmniverseDb
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 141: browse surfaces list one card per group_key (task 140's title group) instead of one card
 * per exact tmdb_id, and the count queries count the same groups the page queries list. The detail
 * alternatives still expose every copy of the group, whichever copy is on the card.
 */
class GroupKeyListingTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        OmniverseDb.Schema.create(it)
        it.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES " +
            "('plex','PLEX','Plex','{}',0),('iptv','XTREAM','IPTV','{}',1)", 0)
    }
    private val db = OmniverseDb(driver)

    private fun vodCards() = db.readQueries.vodPageAll(emptyList(), 10, 0).executeAsList().map { it.remote_id }
    private fun seriesCards() = db.readQueries.seriesPageAll(emptyList(), 10, 0).executeAsList().map { it.remote_id }
    private fun vodAlternatives(sourceId: String, remoteId: String) =
        db.readQueries.otherVodWithSameTmdb(sourceId, remoteId).executeAsList().map { it.remote_id }
    private fun seriesAlternatives(sourceId: String, remoteId: String) =
        db.readQueries.otherSeriesWithSameTmdb(sourceId, remoteId).executeAsList().map { it.remote_id }

    @Test fun plexTmdbTitleAndIptvCopyWithoutTmdbListAsOneCard() {
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,year,tmdb_id,match_key,sort_index,sync_gen) VALUES " +
            "('plex','27002','Akira','movies',1988,'149','akira|1988',0,1)," +
            "('iptv','5501','EN - Akira (1988)','movies',1988,NULL,'akira|1988',1,1)", 0)
        db.storeQueries.recomputeVodGroups()
        assertEquals("t:149", db.readQueries.vodByKey("plex", "27002").executeAsOne().group_key)
        assertEquals("t:149", db.readQueries.vodByKey("iptv", "5501").executeAsOne().group_key)
        assertEquals(1L, db.readQueries.countVodAll(emptyList()).executeAsOne())
        assertEquals(listOf("27002"), vodCards())
        assertEquals(listOf("5501"), vodAlternatives("plex", "27002"))
        assertEquals(listOf("27002"), vodAlternatives("iptv", "5501"))
    }

    @Test fun twoIptvCopiesWithoutTmdbListAsOneCardAndStayAlternatives() {
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,year,tmdb_id,match_key,sort_index,sync_gen) VALUES " +
            "('iptv','6','Matchless','movies',1999,NULL,'matchless|1999',0,1)," +
            "('iptv','7','Matchless','movies',1999,NULL,'matchless|1999',1,1)", 0)
        db.storeQueries.recomputeVodGroups()
        assertEquals("m:matchless|1999", db.readQueries.vodByKey("iptv", "6").executeAsOne().group_key)
        assertEquals("m:matchless|1999", db.readQueries.vodByKey("iptv", "7").executeAsOne().group_key)
        assertEquals(1L, db.readQueries.countVodAll(emptyList()).executeAsOne())
        assertEquals(1L, db.readQueries.countVod("iptv", "movies").executeAsOne())
        assertEquals(listOf("6"), vodCards())
        assertEquals(listOf("7"), vodAlternatives("iptv", "6"))
        assertEquals(listOf("6"), vodAlternatives("iptv", "7"))
    }

    @Test fun sameTitleAndYearWithDifferentTmdbIdsStaySeparateCards() {
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,year,tmdb_id,match_key,sort_index,sync_gen) VALUES " +
            "('plex','10','The Blob','movies',1988,'500','the blob|1988',0,1)," +
            "('iptv','11','The Blob (1988)','movies',1988,'600','the blob|1988',1,1)," +
            "('iptv','12','The Blob 1988','movies',1988,NULL,'the blob|1988',2,1)", 0)
        db.storeQueries.recomputeVodGroups()
        // Two distinct exact ids never merge, and the copy with no id cannot bridge across two ids.
        assertEquals("t:500", db.readQueries.vodByKey("plex", "10").executeAsOne().group_key)
        assertEquals("t:600", db.readQueries.vodByKey("iptv", "11").executeAsOne().group_key)
        assertEquals("m:the blob|1988", db.readQueries.vodByKey("iptv", "12").executeAsOne().group_key)
        assertEquals(3L, db.readQueries.countVodAll(emptyList()).executeAsOne())
        assertEquals(setOf("10", "11", "12"), vodCards().toSet())
        assertEquals(emptyList(), vodAlternatives("plex", "10"))
        assertEquals(emptyList(), vodAlternatives("iptv", "12"))
    }

    @Test fun aRowWithoutGroupKeyStillListsUntilTheNextRecompute() {
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,year,tmdb_id,sort_index,sync_gen) VALUES " +
            "('plex','1','Pre Resync Film','movies',2001,'777',0,1)," +
            "('iptv','2','Pre Resync Film','movies',2001,'777',1,1)", 0)
        // A resync clears group_key (the upserts do not carry it), so a page can be read before the
        // pass re-runs: nothing merges, and alternatives fall back to the exact-tmdb join.
        assertEquals(2L, db.readQueries.countVodAll(emptyList()).executeAsOne())
        assertEquals(setOf("1", "2"), vodCards().toSet())
        assertEquals(listOf("2"), vodAlternatives("plex", "1"))
        assertEquals(listOf("1"), vodAlternatives("iptv", "2"))
        db.storeQueries.recomputeVodGroups()
        assertEquals(1L, db.readQueries.countVodAll(emptyList()).executeAsOne())
        assertEquals(listOf("1"), vodCards())
        assertEquals(listOf("2"), vodAlternatives("plex", "1"))
    }

    @Test fun seriesPagesMergeTheSameWayAndKeepEveryAlternative() {
        driver.execute(null, "INSERT INTO series(source_id,remote_id,name,primary_category_id,year,tmdb_id,match_key,sort_index,sync_gen) VALUES " +
            "('plex','s1','Breaking Bad','shows',2008,'1000','breaking bad|2008',0,1)," +
            "('iptv','s2','Breaking Bad (2008)','shows',2008,NULL,'breaking bad|2008',1,1)," +
            "('iptv','s3','Breaking Bad','shows',2008,NULL,'breaking bad|2008',2,1)", 0)
        db.storeQueries.recomputeSeriesGroups()
        assertEquals("t:1000", db.readQueries.seriesByKey("plex", "s1").executeAsOne().group_key)
        assertEquals("t:1000", db.readQueries.seriesByKey("iptv", "s2").executeAsOne().group_key)
        assertEquals("t:1000", db.readQueries.seriesByKey("iptv", "s3").executeAsOne().group_key)
        assertEquals(1L, db.readQueries.countSeriesAll(emptyList()).executeAsOne())
        assertEquals(listOf("s1"), seriesCards())
        assertEquals(listOf("s2", "s3"), seriesAlternatives("plex", "s1"))
        assertEquals(listOf("s3", "s1"), seriesAlternatives("iptv", "s2"))
    }

    @Test fun seriesCopiesWithoutTmdbListAsOneCardAndStayAlternatives() {
        driver.execute(null, "INSERT INTO series(source_id,remote_id,name,primary_category_id,year,match_key,sort_index,sync_gen) VALUES " +
            "('iptv','s4','Oz','shows',2002,'oz|2002',0,1)," +
            "('iptv','s5','Oz (2002)','shows',2002,'oz|2002',1,1)", 0)
        db.storeQueries.recomputeSeriesGroups()
        assertEquals("m:oz|2002", db.readQueries.seriesByKey("iptv", "s4").executeAsOne().group_key)
        assertEquals("m:oz|2002", db.readQueries.seriesByKey("iptv", "s5").executeAsOne().group_key)
        assertEquals(1L, db.readQueries.countSeriesAll(emptyList()).executeAsOne())
        assertEquals(1L, db.readQueries.countSeries("iptv", "shows").executeAsOne())
        assertEquals(listOf("s4"), seriesCards())
        assertEquals(listOf("s5"), seriesAlternatives("iptv", "s4"))
        assertEquals(listOf("s4"), seriesAlternatives("iptv", "s5"))
    }
}
