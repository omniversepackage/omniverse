package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.database.OmniverseDb
import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryTitleGroupingTest {
    @Test
    fun categoryPagesShowOneCardPerExactIdAndKeepUnmatchedItems() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,tmdb_id,sort_index,sync_gen) VALUES " +
            "('iptv','a1','Film','new','550',0,1)," +      // representative
            "('iptv','a2','Film 4K','new','550',1,1)," +   // same id, same category: collapsed
            "('iptv','b','Other','new',NULL,2,1)," +       // no id: never grouped
            "('iptv','c','Blank','new','',3,1)," +
            "('iptv','a3','Film','classics','550',4,1)", 0) // other category keeps its own card
        driver.execute(null, "INSERT INTO series(source_id,remote_id,name,primary_category_id,tmdb_id,sort_index,sync_gen) VALUES " +
            "('iptv','s1','Show','tv','1399',5,1),('iptv','s2','Show HD','tv','1399',2,1)", 0)
        val q = OmniverseDb(driver).readQueries
        assertEquals(listOf("a1", "b", "c"), q.vodPage("iptv", "new", 10, 0).executeAsList().map { it.remote_id })
        assertEquals(3L, q.countVod("iptv", "new").executeAsOne())
        assertEquals(listOf("a3"), q.vodPage("iptv", "classics", 10, 0).executeAsList().map { it.remote_id })
        // Whole-source view (no category) groups across categories.
        assertEquals(listOf("a1", "b", "c"), q.vodPage("iptv", null, 10, 0).executeAsList().map { it.remote_id })
        assertEquals(listOf("s2"), q.seriesPage("iptv", "tv", 10, 0).executeAsList().map { it.remote_id })
        driver.close()
    }
}
