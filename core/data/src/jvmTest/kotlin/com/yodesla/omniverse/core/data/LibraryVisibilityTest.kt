package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.CatalogRepositoryImpl
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryVisibilityTest {
    @Test
    fun switchedOffLibraryLeavesRailAndAllButStaysListedForSettings() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES ('plex','PLEX','Plex','{}',0)", 0)
        driver.execute(null, "INSERT INTO category(source_id,kind,remote_id,name,sort_index,sync_gen) VALUES " +
            "('plex','VOD','1','Movies',0,1),('plex','VOD','2','Kids Movies',1,1)", 0)
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,sort_index,sync_gen) VALUES " +
            "('plex','a','Adult Film','1',0,1),('plex','k','Kids Film','2',1,1)", 0)
        var profile = "one"
        val db = OmniverseDb(driver)
        val io = UnconfinedTestDispatcher(testScheduler)
        val user = UserDataRepositoryImpl(db, io, Clock { 1L }) { profile }
        val catalog = CatalogRepositoryImpl(db, io) { profile }

        user.setCategoryHidden(SourceId("plex"), ContentKind.VOD, "2", hidden = true)
        val off = user.hiddenCategoryKeys().first()
        assertEquals(setOf("VOD|plex|2"), off)
        assertEquals(listOf("Movies"), catalog.categories(SourceId("plex"), ContentKind.VOD).first().map { it.name })
        assertEquals(2, catalog.categories(SourceId("plex"), ContentKind.VOD, includeHidden = true).first().size)
        assertEquals(listOf("a"), db.readQueries.vodPageAll(off, 10, 0).executeAsList().map { it.remote_id })

        profile = "two"
        assertEquals(emptySet(), user.hiddenCategoryKeys().first(), "per-profile preference")
        profile = "one"
        user.setCategoryHidden(SourceId("plex"), ContentKind.VOD, "2", hidden = false)
        assertEquals(emptySet(), user.hiddenCategoryKeys().first())
        driver.close()
    }
}
