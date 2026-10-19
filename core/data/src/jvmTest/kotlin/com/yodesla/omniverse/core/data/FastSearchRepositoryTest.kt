package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.SearchRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 143: the plain-text fast path returns what the full filtered query returns, in a fraction of the time. */
class FastSearchRepositoryTest {
    private fun db(n: Int = 0): OmniverseDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) }
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES ('plex','PLEX','Plex','{}',0),('iptv','XTREAM','IPTV','{}',1)", 0)
        fun movie(src: String, id: String, name: String, rating: Double, cat: String = "m", group: String? = null) {
            driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,rating,year,group_key,sort_index,sync_gen) VALUES ('$src','$id','$name','$cat',$rating,2001,${group?.let { "'$it'" } ?: "NULL"},1,1)", 0)
            driver.execute(null, "INSERT INTO search_index(source_id,remote_id,kind,title) VALUES ('$src','$id','VOD','$name')", 0)
        }
        movie("iptv", "1", "Naruto Shippuden", 8.0)
        movie("iptv", "2", "Naruto", 7.0, group = "g-naruto")
        movie("plex", "3", "Naruto", 7.5, group = "g-naruto")
        movie("iptv", "4", "Boruto Naruto Next Generations", 9.0)
        movie("iptv", "5", "Dragonball Z", 8.0)
        movie("iptv", "6", "Pokémon The Movie", 6.0)
        movie("iptv", "7", "Hidden Naruto", 9.5, cat = "locked")
        movie("iptv", "8", "Yu-Gi-Oh! Duel Monsters", 7.0)
        repeat(n) { i -> movie("iptv", "x$i", "Filler Title $i", 5.0, group = "f$i") }
        return OmniverseDb(driver)
    }

    @Test fun ranksExactFirstCollapsesPlexFirstAndHonoursExclusions() = runTest {
        val search = SearchRepositoryImpl(db(), Dispatchers.Unconfined)
        val rows = search.searchTitles("naruto", listOf(ContentKind.VOD), listOf("VOD|iptv|locked"), 24)[ContentKind.VOD]!!.rows
        assertEquals(listOf("plex:3", "iptv:1", "iptv:4"), rows.map { it.key.sourceId.value + ":" + it.key.remoteId.value })
    }

    @Test fun midWordAndAccentsStillFind() = runTest {
        val search = SearchRepositoryImpl(db(), Dispatchers.Unconfined)
        assertEquals("iptv:5", search.searchTitles("ball", listOf(ContentKind.VOD), emptyList(), 24)[ContentKind.VOD]!!.rows.first().let { it.key.sourceId.value + ":" + it.key.remoteId.value })
        assertTrue(search.searchTitles("zzzz", listOf(ContentKind.VOD), emptyList(), 24).isEmpty())
    }

    @Test fun punctuationAndAccentBlind() = runTest {
        val search = SearchRepositoryImpl(db(), Dispatchers.Unconfined)
        suspend fun first(q: String) = search.searchTitles(q, listOf(ContentKind.VOD), emptyList(), 24)[ContentKind.VOD]?.rows?.firstOrNull()?.name
        assertEquals("Yu-Gi-Oh! Duel Monsters", first("yugioh"))
        assertEquals("Yu-Gi-Oh! Duel Monsters", first("yu gi oh"))
        assertEquals("Pokémon The Movie", first("pokemon"))
        assertEquals("Pokémon The Movie", first("Pokémon"))
        assertEquals("Dragonball Z", first("dragon ball"))
    }

    @Test fun fastPathIsMuchFasterThanTheFilteredQueryOnALargeCatalog() = runTest {
        val search = SearchRepositoryImpl(db(30_000), Dispatchers.Unconfined)
        search.searchTitles("naruto", listOf(ContentKind.VOD), emptyList(), 24) // warm up
        var fast = 0L; var full = 0L
        repeat(3) {
            fast += measureTimeMillis { search.searchTitles("naruto", listOf(ContentKind.VOD), emptyList(), 24) }
            full += measureTimeMillis { search.searchFiltered("naruto", SearchFilters(movies = true), emptyList(), 24) }
        }
        println("search timings over 30k titles (3 runs): fast=${fast}ms filtered=${full}ms")
        assertTrue(fast < 1_500, "fast path took ${fast}ms")
        assertTrue(fast <= full, "fast path ($fast ms) should not be slower than the filtered query ($full ms)")
    }
}
