package com.yodesla.omniverse.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class SchemaTest {
    private fun db(): OmniverseDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        return OmniverseDb(driver)
    }

    @Test
    fun schemaCreatesAndFtsPrefixSearchWorks() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        val d = OmniverseDb(driver)
        driver.execute(null, "INSERT INTO search_index(title, kind, source_id, remote_id) VALUES ('BBC News HD','LIVE','s','1')", 0)
        driver.execute(null, "INSERT INTO search_index(title, kind, source_id, remote_id) VALUES ('Sky Sports News','LIVE','s','2')", 0)
        driver.execute(null, "INSERT INTO search_index(title, kind, source_id, remote_id) VALUES ('Nature Docs','VOD','s','3')", 0)
        assertEquals(2, d.catalogQueries.searchTitles("new*", 10).executeAsList().size)
        assertEquals(1, d.catalogQueries.searchTitles("sp*", 10).executeAsList().size)
    }

    @Test
    fun resyncSoftDeletesMissingChannels() {
        val d = db()
        val q = d.catalogQueries
        q.upsertChannel("s", "1", 1, "A", null, null, "c", 0, null, 0, 1)
        q.upsertChannel("s", "2", 2, "B", null, null, "c", 0, null, 1, 1)
        // generation 2 only sees channel 1
        q.upsertChannel("s", "1", 1, "A", null, null, "c", 0, null, 0, 2)
        q.markMissingChannelsRemoved(now = 99, sourceId = "s", gen = 2)
        assertEquals(1L, q.countChannels("s").executeAsOne())
    }

    @Test
    fun populatedV1DatabaseMigratesWithoutLosingCatalogOrUserData() {
        val snapshot = Path.of("src/commonMain/sqldelight/databases/1.db")
        val copy = Files.createTempFile("omniverse-v1-migration-", ".db")
        Files.copy(snapshot, copy, StandardCopyOption.REPLACE_EXISTING)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.toAbsolutePath()}")
        try {
            driver.execute(null, "INSERT INTO source(id, kind, name, config_json) VALUES ('s','XTREAM','Saved source','sealed-test-value')", 0)
            driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,sort_index,sync_gen) VALUES ('s','m','Saved movie','c',0,1)", 0)
            driver.execute(null, "INSERT INTO series(source_id,remote_id,name,primary_category_id,sort_index,sync_gen) VALUES ('s','show','Saved show','c',0,1)", 0)
            driver.execute(null, "INSERT INTO favorite(source_id,kind,remote_id,sort_index,added_ms) VALUES ('s','VOD','m',0,1)", 0)
            driver.execute(null, "INSERT INTO progress(source_id,kind,remote_id,position_ms,updated_ms) VALUES ('s','VOD','m',1234,1)", 0)
            driver.execute(null, "INSERT INTO setting(key,value) VALUES ('parental_pin','test-value')", 0)

            OmniverseDb.Schema.migrate(driver, 1, OmniverseDb.Schema.version)
            val migrated = OmniverseDb(driver)
            val movie = assertNotNull(migrated.readQueries.vodByKey("s", "m").executeAsOneOrNull())
            assertEquals("Saved movie", movie.name)
            assertEquals(null, movie.match_key)
            assertEquals(null, movie.genre)
            val show = assertNotNull(migrated.readQueries.seriesByKey("s", "show").executeAsOneOrNull())
            assertEquals(null, show.tmdb_id)
            migrated.storeQueries.upsertSeries("s", "show", "Saved show", null, null, "c", null, null,
                null, null, null, 0, 2, null, "123")
            assertEquals("123", migrated.readQueries.seriesByKey("s", "show").executeAsOne().tmdb_id)
            assertEquals("sealed-test-value", migrated.catalogQueries.allSources().executeAsOne().config_json)
            assertEquals(1, migrated.userDataQueries.favorites("default", "favorites").executeAsList().size)
            assertEquals(1234L, migrated.userOpsQueries.progressByKey("default", "s", "VOD", "m").executeAsOne().position_ms)
            assertEquals("test-value", migrated.userDataQueries.getSetting("parental_pin").executeAsOne())
            migrated.userOpsQueries.upsertLocalSkipPoint("default", "s", "VOD", "m", "intro_end", 45_000)
            assertEquals(45_000L, migrated.userOpsQueries.localSkipPoints("default", "s", "VOD", "m")
                .executeAsOne().position_ms)
            migrated.userOpsQueries.upsertTitleOverride("default", "VOD", "tmdb", "42", "Display", "Sort", null)
            assertEquals("Display", migrated.userOpsQueries.titleOverrideByKey("default", "VOD", "tmdb", "42")
                .executeAsOne().display_title)
            assertEquals(null, migrated.userOpsQueries.titleOverrideByKey("guest", "VOD", "tmdb", "42")
                .executeAsOneOrNull())
            migrated.userOpsQueries.addCollectionTitleMembership("default", "saved", "VOD", "tmdb", "42", 0)
            assertEquals("42", migrated.userOpsQueries.collectionTitleMemberships("default", "saved")
                .executeAsOne().external_id)
            assertEquals(0, migrated.userOpsQueries.collectionTitleMemberships("guest", "saved")
                .executeAsList().size)
        } finally {
            driver.close()
            Files.deleteIfExists(copy)
        }
    }
}
