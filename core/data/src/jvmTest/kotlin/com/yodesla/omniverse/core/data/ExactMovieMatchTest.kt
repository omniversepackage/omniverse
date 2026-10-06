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

@OptIn(ExperimentalCoroutinesApi::class)
class ExactMovieMatchTest {
    @Test
    fun savedTitleResolvesOnlyAvailableExactIdVariantsInPreferredOrder() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES " +
            "('iptv','XTREAM','IPTV','{}',0),('plex','PLEX','Plex','{}',1)", 0)
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,tmdb_id,sort_index,sync_gen,removed_ms) VALUES " +
            "('iptv','i1','Film','movies','42',0,1,NULL)," +
            "('plex','p1','Film','movies','42',0,1,NULL)," +
            "('plex','gone','Film','movies','42',1,1,99)," +
            "('iptv','other','Film','movies','99',1,1,NULL)", 0)
        val catalog = CatalogRepositoryImpl(OmniverseDb(driver), UnconfinedTestDispatcher(testScheduler))
        assertEquals(listOf("p1", "i1"), catalog.titleCandidates(TitleIdentity(ContentKind.VOD, "tmdb", "42"))
            .map { it.key.remoteId.value })
        assertEquals(emptyList(), catalog.titleCandidates(TitleIdentity(ContentKind.VOD, "tmdb", "")))
        assertEquals(emptyList(), catalog.titleCandidates(TitleIdentity(ContentKind.SERIES, "tmdb", "42")))
        driver.close()
    }

    @Test
    fun allPagesChooseOneVisibleExactIdRepresentativeAcrossEverySource() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES " +
            "('plexA','PLEX','Plex A','{}',0),('plexB','PLEX','Plex B','{}',1)," +
            "('iptvA','XTREAM','IPTV A','{}',2),('iptvB','XTREAM','IPTV B','{}',3)", 0)
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,tmdb_id,sort_index,sync_gen) VALUES " +
            "('plexA','pa','Plex A title','locked','42',0,1)," +
            "('plexB','pb','Plex B title','movies','42',0,1)," +
            "('iptvA','ia','IPTV A title','movies','42',0,1)," +
            "('iptvB','ib','IPTV B title','movies','42',0,1)," +
            "('iptvA','blank','Unidentified','movies',NULL,1,1)," +
            "('iptvB','other','Other ID','movies','99',1,1)", 0)
        driver.execute(null, "INSERT INTO series(source_id,remote_id,name,primary_category_id,tmdb_id,sort_index,sync_gen) VALUES " +
            "('plexA','spa','Plex A show','locked','42',0,1)," +
            "('plexB','spb','Plex B show','shows','42',0,1)," +
            "('iptvA','sia','IPTV A show','shows','42',0,1)," +
            "('iptvB','sib','IPTV B show','shows','42',0,1)," +
            "('iptvA','sblank','Unidentified show','shows',NULL,1,1)", 0)
        val q = OmniverseDb(driver).readQueries
        fun movies(excluded: List<String>) = q.vodPageAll(excluded, 20, 0).executeAsList().map { it.remote_id }
        fun shows(excluded: List<String>) = q.seriesPageAll(excluded, 20, 0).executeAsList().map { it.remote_id }
        assertEquals(3L, q.countVodAll(emptyList()).executeAsOne())
        assertEquals(setOf("pa", "blank", "other"), movies(emptyList()).toSet())
        assertEquals(2L, q.countSeriesAll(emptyList()).executeAsOne())
        assertEquals(setOf("spa", "sblank"), shows(emptyList()).toSet())

        val firstLocked = listOf("VOD|plexA|locked", "SERIES|plexA|locked")
        assertEquals(setOf("pb", "blank", "other"), movies(firstLocked).toSet())
        assertEquals(setOf("spb", "sblank"), shows(firstLocked).toSet())
        val bothPlexLocked = firstLocked + listOf("VOD|plexB|movies", "SERIES|plexB|shows")
        assertEquals(setOf("ia", "blank", "other"), movies(bothPlexLocked).toSet())
        assertEquals(setOf("sia", "sblank"), shows(bothPlexLocked).toSet())
        assertEquals(movies(bothPlexLocked), q.vodPageAll(bothPlexLocked, 1, 0).executeAsList().map { it.remote_id } +
            q.vodPageAll(bothPlexLocked, 1, 1).executeAsList().map { it.remote_id } +
            q.vodPageAll(bothPlexLocked, 1, 2).executeAsList().map { it.remote_id })
        assertEquals(movies(bothPlexLocked).toSet(),
            q.vodPageAllAlphabetic("default", bothPlexLocked, 20, 0).executeAsList().map { it.remote_id }.toSet())
        assertEquals(shows(bothPlexLocked).toSet(),
            q.seriesPageAllAlphabetic("default", bothPlexLocked, 20, 0).executeAsList().map { it.remote_id }.toSet())
        driver.execute(null, "UPDATE vod SET removed_ms=123 WHERE source_id='iptvA' AND remote_id='ia'", 0)
        driver.execute(null, "UPDATE series SET removed_ms=123 WHERE source_id='iptvA' AND remote_id='sia'", 0)
        assertEquals(setOf("ib", "blank", "other"), movies(bothPlexLocked).toSet())
        assertEquals(setOf("sib", "sblank"), shows(bothPlexLocked).toSet())
        driver.close()
    }

    @Test
    fun smartCollectionFiltersLocalCatalog() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,year,rating,genre,sort_index,sync_gen) VALUES " +
            "('plex','p1','Star Voyage','movies',2020,8.5,'Science Fiction',0,1)," +
            "('iptv','i1','Star Voyage','movies',2020,8.5,'Science Fiction',0,1)," +
            "('plex','p2','Star Voyage II','movies',1990,9.0,'Science Fiction',1,1)," +
            "('plex','p3','Other','movies',2020,9.0,'Drama',2,1)", 0)
        driver.execute(null, "INSERT INTO progress(profile_id,source_id,kind,remote_id,position_ms,updated_ms,completed) VALUES " +
            "('default','plex','VOD','p1',60000,1,0)", 0)
        val catalog = CatalogRepositoryImpl(OmniverseDb(driver), UnconfinedTestDispatcher(testScheduler))
        val filter = SmartCollectionFilter("star", yearFrom = 2000, ratingAtLeast = 8f, sourceId = "plex", genreContains = "fiction", started = true)
        assertEquals(listOf("p1"), catalog.smartCollectionItems(ContentKind.VOD, filter).map { it.key.remoteId.value })
        assertEquals(emptyList(), catalog.smartCollectionItems(ContentKind.VOD, filter.copy(started = false)))
        driver.close()
    }

    @Test
    fun editionLabelFilterSelectsLabeledAndUnlabeledCandidates() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,year,rating,sort_index,sync_gen) VALUES " +
            "('iptv','m-labeled','Labeled Film','movies',2020,7.5,0,1)," +
            "('iptv','m-unlabeled','Unlabeled Film','movies',2020,7.0,1,1)," +
            "('iptv','m-blank','Blank Label Film','movies',2020,7.0,2,1)," +
            "('iptv','m-kind','Wrong Kind Film','movies',2020,7.0,3,1)", 0)
        driver.execute(null, "INSERT INTO series(source_id,remote_id,name,primary_category_id,year,rating,sort_index,sync_gen) VALUES " +
            "('iptv','s-labeled','Labeled Show','shows',2020,7.5,0,1)," +
            "('iptv','s-unlabeled','Unlabeled Show','shows',2020,7.0,1,1)", 0)
        driver.execute(null, "INSERT INTO item_override(profile_id,source_id,kind,remote_id,edition_label) VALUES " +
            "('default','iptv','VOD','m-labeled','Director''s Cut')," +
            "('default','iptv','VOD','m-blank','   ')," +
            "('default','iptv','SERIES','m-kind','Wrong Kind')," +
            "('default','iptv','SERIES','s-labeled','Extended Cut')," +
            "('default','iptv','VOD','s-unlabeled','Wrong Kind')", 0)
        val catalog = CatalogRepositoryImpl(OmniverseDb(driver), UnconfinedTestDispatcher(testScheduler))
        val labeled = SmartCollectionFilter(hasEditionLabel = true)
        val unlabeled = SmartCollectionFilter(hasEditionLabel = false)
        // Blank labels count as unlabeled; a SERIES label never labels a VOD item and vice versa.
        assertEquals(listOf("m-labeled"), catalog.smartCollectionItems(ContentKind.VOD, labeled).map { it.key.remoteId.value })
        assertEquals(setOf("m-unlabeled", "m-blank", "m-kind"),
            catalog.smartCollectionItems(ContentKind.VOD, unlabeled).map { it.key.remoteId.value }.toSet())
        assertEquals(4, catalog.smartCollectionItems(ContentKind.VOD, SmartCollectionFilter()).size)
        assertEquals(listOf("s-labeled"), catalog.smartCollectionItems(ContentKind.SERIES, labeled).map { it.key.remoteId.value })
        assertEquals(listOf("s-unlabeled"), catalog.smartCollectionItems(ContentKind.SERIES, unlabeled).map { it.key.remoteId.value })
        driver.close()
    }
    @Test
    fun matchingShowTitlesDoNotHideAnIptvSourceWithoutExternalIds() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES ('plex','PLEX','Plex','{}',0),('iptv','XTREAM','IPTV','{}',1)", 0)
        driver.execute(null, "INSERT INTO series(source_id,remote_id,name,primary_category_id,year,match_key,sort_index,sync_gen) VALUES " +
            "('plex','p1','The Show','shows',2020,'the show|2020',0,1)," +
            "('iptv','i1','The Show','shows',2020,'the show|2020',0,1)", 0)
        val db = OmniverseDb(driver)
        assertEquals(2L, db.readQueries.countSeriesAll(emptyList()).executeAsOne())
        assertEquals(2, db.readQueries.seriesPageAll(emptyList(), 10, 0).executeAsList().size)
        driver.close()
    }

    @Test
    fun showAlternativesRequireTheSameExternalId() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES ('plex','PLEX','Plex','{}',0),('iptv','XTREAM','IPTV','{}',1)", 0)
        driver.execute(null, "INSERT INTO series(source_id,remote_id,name,primary_category_id,year,tmdb_id,sort_index,sync_gen) VALUES " +
            "('plex','p1','The Show','shows',2020,'42',0,1)," +
            "('iptv','i1','The Show','shows',2020,'42',0,1)," +
            "('iptv','i2','The Show','shows',2020,'99',1,1)", 0)
        val catalog = CatalogRepositoryImpl(OmniverseDb(driver), UnconfinedTestDispatcher(testScheduler))
        val plex = ContentKey(SourceId("plex"), ContentKind.SERIES, RemoteId("p1"))
        assertEquals(listOf("i1"), catalog.exactSeriesMatches(plex).map { it.key.remoteId.value })
        assertEquals("42", catalog.poster(plex)?.tmdbId)
        val db = OmniverseDb(driver)
        assertEquals(listOf("p1", "i2"), db.readQueries.seriesPageAll(emptyList(), 10, 0).executeAsList().map { it.remote_id })
        assertEquals(listOf("i1", "i2"), db.readQueries.seriesPageAll(listOf("SERIES|plex|shows"), 10, 0).executeAsList().map { it.remote_id })
        driver.close()
    }

    @Test
    fun onlyExactExternalIdSuppressesIptvAndAppearsAsAlternate() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES ('plex','PLEX','Plex','{}',0),('iptv','XTREAM','IPTV','{}',1)", 0)
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,year,tmdb_id,sort_index,sync_gen) VALUES " +
            "('plex','p1','Film','movies',2020,'42',0,1)," +
            "('iptv','i1','Film','movies',2020,'42',0,1)," +
            "('iptv','i2','Film','movies',2020,NULL,1,1)," +
            "('iptv','i3','Film','movies',2020,'99',2,1)", 0)
        val db = OmniverseDb(driver)
        val catalog = CatalogRepositoryImpl(db, UnconfinedTestDispatcher(testScheduler))
        val plex = ContentKey(SourceId("plex"), ContentKind.VOD, RemoteId("p1"))
        assertEquals(listOf("i1"), catalog.exactMovieMatches(plex).map { it.key.remoteId.value })
        assertEquals("42", catalog.poster(plex)?.tmdbId)
        // i1 is represented under Plex's exact match; title/year-only i2 and different-id i3 remain visible.
        assertEquals(3L, db.readQueries.countVodAll(emptyList()).executeAsOne())
        assertEquals(listOf("p1", "i2", "i3"), db.readQueries.vodPageAll(emptyList(), 10, 0).executeAsList().map { it.remote_id })
        assertEquals(listOf("i1", "i2", "i3"), db.readQueries.vodPageAll(listOf("VOD|plex|movies"), 10, 0).executeAsList().map { it.remote_id })
        assertEquals(3L, db.readQueries.countVodAll(listOf("VOD|plex|movies")).executeAsOne())
        driver.close()
    }
}
