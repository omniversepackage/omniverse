package com.yodesla.omniverse.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Task 140: migration 14 (v14 -> v15) adds vod.group_key / series.group_key plus the
 * vod_by_group / series_by_group indexes. Purely additive: rows written under the old schema survive
 * with a NULL key, and the Store.sq recompute pass fills them in on the next sync.
 */
class GroupKeyMigrationTest {
    @Test fun populatedV1DatabaseGainsGroupKeyAndThePassFillsIt() {
        val snapshot = Path.of("src/commonMain/sqldelight/databases/1.db")
        val copy = Files.createTempFile("omniverse-group-key-migration-", ".db")
        Files.copy(snapshot, copy, StandardCopyOption.REPLACE_EXISTING)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.toAbsolutePath()}")
        try {
            driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,sort_index,sync_gen) VALUES ('plex','1','Akira','c',0,1)", 0)
            driver.execute(null, "INSERT INTO series(source_id,remote_id,name,primary_category_id,sort_index,sync_gen) VALUES ('plex','s','Saved show','c',0,1)", 0)

            OmniverseDb.Schema.migrate(driver, 1, OmniverseDb.Schema.version)
            val db = OmniverseDb(driver)

            // The column exists on a migrated database, and the migration already filled the key.
            val movie = assertNotNull(db.readQueries.vodByKey("plex", "1").executeAsOneOrNull())
            assertEquals("Akira", movie.name)
            assertEquals("r:plex|1", movie.group_key) // no tmdb, no year yet
            assertEquals("r:plex|s", db.readQueries.seriesByKey("plex", "s").executeAsOne().group_key)

            db.storeQueries.upsertVod("plex", "1", "Akira", null, "c", null, 1988L, null, null, "149", 0, 2, "akira|1988", null)
            db.storeQueries.upsertVod("iptv", "2", "EN - Akira (1988)", null, "c", null, 1988L, null, null, null, 0, 2, "akira|1988", null)
            db.storeQueries.recomputeVodGroups()
            assertEquals("t:149", db.readQueries.vodByKey("plex", "1").executeAsOne().group_key)
            assertEquals("t:149", db.readQueries.vodByKey("iptv", "2").executeAsOne().group_key)

            // A row with neither tmdb nor match_key falls to the never-merged 'r:' key.
            db.storeQueries.recomputeSeriesGroups()
            assertEquals("r:plex|s", db.readQueries.seriesByKey("plex", "s").executeAsOne().group_key)
        } finally {
            driver.close()
            Files.deleteIfExists(copy)
        }
    }

    @Test fun freshSchemaCarriesGroupKeyOnBothCatalogTables() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        assertEquals(15L, OmniverseDb.Schema.version) // 14: group_key (140)
        val db = OmniverseDb(driver)
        db.storeQueries.upsertSeries("s", "1", "Dark Matter", null, null, "c", null, null, null, 2024L, null, 0, 1, "darkmatter|2024", "550254")
        db.storeQueries.upsertSeries("s", "2", "EN - Dark Matter (2024)", null, null, "c", null, null, null, 2024L, null, 0, 1, "darkmatter|2024", null)
        db.storeQueries.recomputeSeriesGroups()
        assertEquals("t:550254", db.readQueries.seriesByKey("s", "1").executeAsOne().group_key)
        assertEquals("t:550254", db.readQueries.seriesByKey("s", "2").executeAsOne().group_key)
    }
}
