package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.MatchKey
import com.yodesla.omniverse.core.database.OmniverseDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Task 140: the group_key pass (Store.sq recomputeVodGroups / recomputeSeriesGroups).
 *
 * Rows are written exactly the way SyncEngineImpl writes them (upsertVod/upsertSeries with
 * MatchKey.of(name, year)), which is also why the pass has to re-run after every sync: the upserts
 * don't carry group_key, so INSERT OR REPLACE wipes it.
 *
 * Rule under test, in order: own tmdb -> 't:<tmdb>'; else match_key shared with exactly one distinct
 * tmdb among live rows -> 't:<that tmdb>' (the bridge that pulls an IPTV copy into its Plex group);
 * else match_key -> 'm:<match_key>'; else 'r:<source>|<remote>' (never merged).
 */
class GroupKeyRecomputeTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) }
    private val db = OmniverseDb(driver)

    private fun vod(sourceId: String, remoteId: String, name: String, year: Int?, tmdbId: String?) {
        db.storeQueries.upsertVod(
            sourceId, remoteId, name, null, "cat", null, year?.toLong(), null, null, tmdbId, 0, 1,
            MatchKey.of(name, year), null,
        )
    }

    private fun series(sourceId: String, remoteId: String, name: String, year: Int?, tmdbId: String?) {
        db.storeQueries.upsertSeries(
            sourceId, remoteId, name, null, null, "cat", null, null, null, year?.toLong(), null, 0, 1,
            MatchKey.of(name, year), tmdbId,
        )
    }

    private fun vodGroup(sourceId: String, remoteId: String) =
        db.readQueries.vodByKey(sourceId, remoteId).executeAsOne().group_key

    private fun seriesGroup(sourceId: String, remoteId: String) =
        db.readQueries.seriesByKey(sourceId, remoteId).executeAsOne().group_key

    @Test fun plexTmdbBridgesTheIptvCopyOfTheSameMovie() {
        vod("plex", "27002", "Akira", 1988, "149")
        vod("iptv", "5501", "EN - Akira (1988)", 1988, null)
        db.storeQueries.recomputeVodGroups()
        assertEquals("t:149", vodGroup("plex", "27002"))
        assertEquals("t:149", vodGroup("iptv", "5501"))
    }

    @Test fun tmdbIdsAreNormalizedBeforeTheyBecomeTheGroupKey() {
        vod("plex", "1", "Akira", 1988, "149")
        vod("iptv", "2", "EN - Akira (1988)", 1988, "149.0")
        vod("iptv", "3", "Akira", 1988, " 149 ")
        vod("iptv", "4", "Akira", 1988, "")
        vod("iptv", "5", "Akira", 1988, "0")
        vod("iptv", "6", "Akira", 1988, ".0")
        db.storeQueries.recomputeVodGroups()
        assertEquals("t:149", vodGroup("plex", "1"))
        assertEquals("t:149", vodGroup("iptv", "2"))
        assertEquals("t:149", vodGroup("iptv", "3"))
        // '', '0' and '.0' are not identities: those rows join through the match_key bridge instead.
        assertEquals("t:149", vodGroup("iptv", "4"))
        assertEquals("t:149", vodGroup("iptv", "5"))
        assertEquals("t:149", vodGroup("iptv", "6"))
    }

    @Test fun rowsWithNoTmdbAnywhereShareTheMatchKeyGroup() {
        vod("iptv", "1", "EN - Akira (1988)", 1988, null)
        vod("iptv2", "2", "Akira 4K", 1988, null)
        db.storeQueries.recomputeVodGroups()
        assertEquals("m:akira|1988", vodGroup("iptv", "1"))
        assertEquals("m:akira|1988", vodGroup("iptv2", "2"))
    }

    @Test fun oneMatchKeyWithTwoDifferentTmdbIdsIsNotBridged() {
        vod("plex", "1", "Dune", 1984, "194")
        vod("iptv", "2", "Dune", 1984, "999")
        vod("iptv", "3", "EN - Dune (1984)", 1984, null)
        db.storeQueries.recomputeVodGroups()
        assertEquals("t:194", vodGroup("plex", "1"))
        assertEquals("t:999", vodGroup("iptv", "2"))
        assertEquals("m:dune|1984", vodGroup("iptv", "3"))
    }

    @Test fun aTitleWithNoYearIsNeverMerged() {
        vod("plex", "1", "Some Untitled Film", null, null)
        vod("iptv", "2", "EN - Some Untitled Film", null, null)
        db.storeQueries.recomputeVodGroups()
        assertEquals("r:plex|1", vodGroup("plex", "1"))
        assertEquals("r:iptv|2", vodGroup("iptv", "2"))
        assertNotEquals(vodGroup("plex", "1"), vodGroup("iptv", "2"))
    }

    @Test fun aSoftDeletedTmdbRowDoesNotBridge() {
        vod("plex", "1", "Ghost", 1999, "321")
        vod("iptv", "2", "EN - Ghost (1999)", 1999, null)
        driver.execute(null, "UPDATE vod SET removed_ms = 5 WHERE source_id = 'plex'", 0)
        db.storeQueries.recomputeVodGroups()
        assertEquals("t:321", vodGroup("plex", "1"))
        assertEquals("m:ghost|1999", vodGroup("iptv", "2"))
    }

    @Test fun seriesAreGroupedTheSameWay() {
        series("plex", "s1", "Dark Matter", 2024, "550254")
        series("iptv", "s2", "EN - Dark Matter (2024)", 2024, null)
        series("iptv", "s3", "Dark Matter", 2024, "550254.0")
        db.storeQueries.recomputeSeriesGroups()
        assertEquals("t:550254", seriesGroup("plex", "s1"))
        assertEquals("t:550254", seriesGroup("iptv", "s2"))
        assertEquals("t:550254", seriesGroup("iptv", "s3"))
    }

    @Test fun resyncClearsTheKeyAndThePassRecomputesIt() {
        vod("plex", "27002", "Akira", 1988, "149")
        vod("iptv", "5501", "EN - Akira (1988)", 1988, null)
        db.storeQueries.recomputeVodGroups()
        val before = Pair(vodGroup("plex", "27002"), vodGroup("iptv", "5501"))
        assertEquals(Pair("t:149", "t:149"), before)

        // The next sync re-upserts both rows: INSERT OR REPLACE drops group_key (it isn't in the list).
        vod("plex", "27002", "Akira", 1988, "149")
        vod("iptv", "5501", "EN - Akira (1988)", 1988, null)
        assertNull(vodGroup("plex", "27002"))
        assertNull(vodGroup("iptv", "5501"))

        db.storeQueries.recomputeVodGroups()
        assertEquals(before, Pair(vodGroup("plex", "27002"), vodGroup("iptv", "5501")))

        // Idempotent: running the pass again changes nothing and leaves no NULL key behind.
        db.storeQueries.recomputeVodGroups()
        assertEquals(before, Pair(vodGroup("plex", "27002"), vodGroup("iptv", "5501")))
        assertEquals("t:149", vodGroup("plex", "27002"))
        assertEquals("t:149", vodGroup("iptv", "5501"))
    }
}
