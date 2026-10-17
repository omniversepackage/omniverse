package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.data.impl.SearchRepositoryImpl
import com.yodesla.omniverse.core.model.ContentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SmartTitleSearchQueryTest {
    @Test fun fragmentAndFranchiseMatchesRespectRankingAndVisibility() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null,"INSERT INTO source(id,kind,name,config_json,sort_index) VALUES('s','XTREAM','Test','{}',0)",0)
        val titles = listOf("Batman", "The Dark Knight", "The Dark Knight Rises", "Visionquest", "Quest for Fire", "Unrelated")
        titles.forEachIndexed { index, title ->
            driver.execute(null,"INSERT INTO vod(source_id,remote_id,name,primary_category_id,sort_index,sync_gen) VALUES('s','$index','$title','c',$index,1)",0)
            driver.execute(null,"INSERT INTO search_index(source_id,remote_id,kind,title) VALUES('s','$index','VOD','$title')",0)
        }
        val search = SearchRepositoryImpl(OmniverseDb(driver),Dispatchers.Unconfined)
        val batman = search.searchFiltered("batman",SearchFilters.None)[ContentKind.VOD]!!.rows.map { it.name }
        assertEquals("Batman",batman.first())
        assertTrue("The Dark Knight" in batman && "The Dark Knight Rises" in batman)
        assertFalse("Unrelated" in batman)
        val quest = search.searchFiltered("quest",SearchFilters.None)[ContentKind.VOD]!!.rows.map { it.name }
        assertTrue("Visionquest" in quest)
        assertEquals("Quest for Fire",quest.first())
        assertTrue(search.searchFiltered("batman",SearchFilters.None,listOf("VOD|s|c")).values.all { it.rows.isEmpty() })
        driver.close()
    }
}
