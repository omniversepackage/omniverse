package com.yodesla.omniverse.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Task 59: the profile table arrives via migration 9; existing data stays under "default". */
class ProfileMigrationTest {
    @Test
    fun populatedRelease011SchemaUpgradesWithoutChangingViewerRows() {
        val snapshot = Path.of("src/commonMain/sqldelight/databases/1.db")
        val copy = Files.createTempFile("omniverse-v011-profile-", ".db")
        Files.copy(snapshot, copy, StandardCopyOption.REPLACE_EXISTING)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.toAbsolutePath()}")
        try {
            // release 0.1.1 used schema v9; build that exact shape before adding representative data.
            OmniverseDb.Schema.migrate(driver, 1, 9)
            driver.execute(null, "INSERT INTO favorite(profile_id,list_id,source_id,kind,remote_id,sort_index,added_ms) VALUES ('default','favorites','s','VOD','m',0,11)", 0)
            driver.execute(null, "INSERT INTO progress(profile_id,source_id,kind,remote_id,position_ms,updated_ms,completed) VALUES ('default','s','VOD','m',4321,12,0)", 0)
            driver.execute(null, "INSERT INTO setting(key,value) VALUES ('search_history','legacy search')", 0)

            OmniverseDb.Schema.migrate(driver, 9, OmniverseDb.Schema.version)
            val migrated = OmniverseDb(driver)
            assertEquals(1L, migrated.profilesQueries.countProfiles().executeAsOne())
            val row = assertNotNull(migrated.profilesQueries.profileById("default").executeAsOneOrNull())
            // Task 84b: migration 10 adds is_guest with default 0 - existing profiles stay normal.
            assertEquals(0L, row.is_guest)
            assertEquals("m", migrated.userOpsQueries.favoritesByKind("default", null).executeAsOne().remote_id)
            assertEquals(4321L, migrated.userOpsQueries.progressByKey("default", "s", "VOD", "m").executeAsOne().position_ms)
            assertEquals("legacy search", migrated.userDataQueries.getSetting("search_history").executeAsOne())
        } finally {
            driver.close()
            Files.deleteIfExists(copy)
        }
    }

    @Test
    fun populatedV1DatabaseMigratesAndSeedsDefaultProfile() {
        val snapshot = Path.of("src/commonMain/sqldelight/databases/1.db")
        val copy = Files.createTempFile("omniverse-profile-migration-", ".db")
        Files.copy(snapshot, copy, StandardCopyOption.REPLACE_EXISTING)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.toAbsolutePath()}")
        try {
            driver.execute(null, "INSERT INTO favorite(source_id,kind,remote_id,sort_index,added_ms) VALUES ('s','VOD','m',0,1)", 0)
            driver.execute(null, "INSERT INTO progress(source_id,kind,remote_id,position_ms,updated_ms) VALUES ('s','VOD','m',1234,1)", 0)
            driver.execute(null, "INSERT INTO setting(key,value) VALUES ('parental_pin','test-value')", 0)

            OmniverseDb.Schema.migrate(driver, 1, OmniverseDb.Schema.version)
            val migrated = OmniverseDb(driver)

            // "default" becomes the first profile: name "Me", avatar 0, not kids.
            val me = assertNotNull(migrated.profilesQueries.profileById("default").executeAsOneOrNull())
            assertEquals("Me", me.name)
            assertEquals(0L, me.avatar)
            assertEquals(0L, me.is_kids)
            assertEquals(0L, me.is_guest, "task 84b: the seeded profile is not the Guest")
            assertEquals(1L, migrated.profilesQueries.countProfiles().executeAsOne())

            // Pre-profiles user data is untouched and still readable under "default".
            assertEquals(1, migrated.userOpsQueries.favoritesByKind("default", null).executeAsList().size)
            assertEquals(1234L, migrated.userOpsQueries.progressByKey("default", "s", "VOD", "m").executeAsOne().position_ms)
            assertEquals("test-value", migrated.userDataQueries.getSetting("parental_pin").executeAsOne())
        } finally {
            driver.close()
            Files.deleteIfExists(copy)
        }
    }

    @Test
    fun freshSchemaHasProfileTableButRepositorySeedsIt() {
        // Schema.create runs the .sq DDL only (migrations do not run on a fresh install), so
        // the table exists but is empty until ProfileRepositoryImpl.ensureDefault() inserts it.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        val d = OmniverseDb(driver)
        assertEquals(0L, d.profilesQueries.countProfiles().executeAsOne())
        d.profilesQueries.insertProfile("default", "Me", 0, 0, 0, 1L)
        assertEquals(1L, d.profilesQueries.countProfiles().executeAsOne())
        driver.close()
    }
}
