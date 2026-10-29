package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.SearchRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AccentedSeriesSearchTest {
    @Test fun plainFilteredAndUnifiedSearchFindAccentedSeries() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            OmniverseDb.Schema.create(driver)
            driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES ('tv','XTREAM','TV','{}',0)", 0)
            val titles = listOf("Shōgun", "SHŌGUN (2024)", "Sho\u0304gun", "Pokémon", "Élite")
            titles.forEachIndexed { i, title ->
                driver.execute(null, "INSERT INTO series(source_id,remote_id,name,primary_category_id,sort_index,sync_gen) VALUES ('tv','$i','$title','shows',$i,1)", 0)
                driver.execute(null, "INSERT INTO search_index(title,kind,source_id,remote_id) VALUES ('$title','SERIES','tv','$i')", 0)
            }
            val search = SearchRepositoryImpl(OmniverseDb(driver), Dispatchers.Unconfined)
            for (query in listOf("shogun", "Shōgun", "SHŌGUN", "sho")) {
                val expected = setOf("0", "1", "2")
                assertEquals(expected, search.searchTitles(query, listOf(ContentKind.SERIES), emptyList(), 24)[ContentKind.SERIES]!!.rows.map { it.key.remoteId.value }.toSet(), query)
                assertEquals(expected, search.searchFiltered(query, SearchFilters(shows = true), emptyList(), 24)[ContentKind.SERIES]!!.rows.map { it.key.remoteId.value }.toSet(), "filtered $query")
                assertEquals(expected, search.search(query, 24)[ContentKind.SERIES]!!.map { it.key.remoteId.value }.toSet(), "unified $query")
            }
            for ((query, id) in listOf("pokemon" to "3", "elite" to "4")) {
                assertEquals(id, search.searchTitles(query, listOf(ContentKind.SERIES), emptyList(), 24)[ContentKind.SERIES]!!.rows.single().key.remoteId.value)
            }
        } finally {
            driver.close()
        }
    }
}
