package com.yodesla.omniverse.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class TmdbMigrationTest {
    @Test fun populatedV1DatabaseGainsTmdbTablesThroughMigration() {
        val snapshot = Path.of("src/commonMain/sqldelight/databases/1.db")
        val copy = Files.createTempFile("omniverse-tmdb-migration-", ".db")
        Files.copy(snapshot, copy, StandardCopyOption.REPLACE_EXISTING)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.toAbsolutePath()}")
        try {
            driver.execute(null, "INSERT INTO source(id, kind, name, config_json) VALUES ('s','XTREAM','Saved','x')", 0)
            driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,sort_index,sync_gen) VALUES ('s','m','Saved movie','c',0,1)", 0)
            OmniverseDb.Schema.migrate(driver, 1, OmniverseDb.Schema.version)
            val d = OmniverseDb(driver)

            d.tmdbQueries.upsertTmdbMeta("550", "MOVIE", "/b.jpg", "/l.png", "/p.jpg", "R", 139L, "Drama", "tag", 100L, "A synopsis.", "/c.jpg")
            val row = assertNotNull(d.tmdbQueries.tmdbMeta("550", "MOVIE").executeAsOneOrNull())
            assertEquals("/b.jpg", row.backdrop_path)
            assertEquals("R", row.certification)
            assertEquals(139L, row.runtime_min)
            // Task 84f: the overview column round-trips; task 87b: so does the second (card) backdrop.
            assertEquals("A synopsis.", row.overview)
            assertEquals("/c.jpg", row.card_backdrop_path)

            // A failed attempt on a never-seen title: all-null row, then the touch keeps it stamped.
            d.tmdbQueries.insertTmdbMetaMiss("999", "MOVIE", 200L)
            d.tmdbQueries.touchTmdbMeta(300L, "999", "MOVIE")
            val miss = assertNotNull(d.tmdbQueries.tmdbMeta("999", "MOVIE").executeAsOneOrNull())
            assertEquals(null, miss.backdrop_path)
            assertEquals(null, miss.overview)
            assertEquals(300L, miss.fetched_ms)

            d.tmdbQueries.upsertTmdbEpisode("1396", 1L, 1L, "/s1.jpg", "Pilot", "p")
            d.tmdbQueries.upsertTmdbEpisode("1396", 1L, 2L, null, "Caveat", null)
            assertEquals(2, d.tmdbQueries.tmdbEpisodeStills("1396", 1L).executeAsList().size)
            d.tmdbQueries.deleteTmdbEpisodes("1396", 1L)
            assertEquals(0, d.tmdbQueries.tmdbEpisodeStills("1396", 1L).executeAsList().size)

            val batch = d.readQueries.tmdbMetaForIds("MOVIE", listOf("550", "999", "missing")).executeAsList()
            assertEquals(2, batch.size)
            assertEquals("/l.png", batch.first { it.tmdb_id == "550" }.logo_path)
            assertEquals("A synopsis.", batch.first { it.tmdb_id == "550" }.overview)
            assertEquals("/c.jpg", batch.first { it.tmdb_id == "550" }.card_backdrop_path)
            assertEquals(null, batch.first { it.tmdb_id == "999" }.card_backdrop_path)

            // Pre-existing data survived the migration.
            assertEquals("Saved movie", assertNotNull(d.readQueries.vodByKey("s", "m").executeAsOneOrNull()).name)
        } finally {
            driver.close()
            Files.deleteIfExists(copy)
        }
    }

    /** Task 84f: the v12 -> v13 migration only appends the overview column to cached rows. */
    @Test fun cachedTmdbRowsSurviveTheOverviewMigrationWithABlankSynopsis() {
        val snapshot = Path.of("src/commonMain/sqldelight/databases/1.db")
        val copy = Files.createTempFile("omniverse-tmdb-overview-migration-", ".db")
        Files.copy(snapshot, copy, StandardCopyOption.REPLACE_EXISTING)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.toAbsolutePath()}")
        try {
            OmniverseDb.Schema.migrate(driver, 1, 12L)
            driver.execute(
                null,
                "INSERT INTO tmdb_meta(tmdb_id,kind,backdrop_path,logo_path,poster_path,certification," +
                    "runtime_min,genres,tagline,fetched_ms) VALUES ('550','MOVIE','/b.jpg','/l.png','/p.jpg'," +
                    "'R',139,'Drama','tag',100)",
                0,
            )
            OmniverseDb.Schema.migrate(driver, 12L, OmniverseDb.Schema.version)
            val row = assertNotNull(OmniverseDb(driver).tmdbQueries.tmdbMeta("550", "MOVIE").executeAsOneOrNull())
            assertEquals("/b.jpg", row.backdrop_path)
            assertEquals("Drama", row.genres)
            assertEquals(100L, row.fetched_ms)
            assertEquals(null, row.overview)
        } finally {
            driver.close()
            Files.deleteIfExists(copy)
        }
    }

    @Test fun freshSchemaIsAtTheTmdbVersion() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        assertEquals(14L, OmniverseDb.Schema.version) // 12: overview (84f), 13: card backdrop (87b)
        val d = OmniverseDb(driver)
        d.tmdbQueries.upsertTmdbMeta("1", "SERIES", null, null, null, null, null, null, null, 1L, null, null)
        assertEquals(1L, d.tmdbQueries.tmdbMeta("1", "SERIES").executeAsOne().fetched_ms)
    }
}
