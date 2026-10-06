package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.database.OmniverseDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllInterleaveTest {
    private val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })

    /** A: PLEX with 3 titles, B: XTREAM with 6, all distinct, match_key NULL. */
    private fun seed() {
        db.catalogQueries.upsertSource("a", "PLEX", "A", "{}", 0, null, null, null, null, 0)
        db.catalogQueries.upsertSource("b", "XTREAM", "B", "{}", 1, null, null, null, null, 0)
        repeat(3) { i ->
            db.storeQueries.upsertVod("a", "a$i", "AMovie $i", null, "c", 7.0, 2020, 0, null, null, i.toLong(), 0, null, null)
            db.storeQueries.upsertSeries("a", "a$i", "AShow $i", null, null, "c", null, null, 7.0, 2020, 0, i.toLong(), 0, null, null)
        }
        repeat(6) { i ->
            db.storeQueries.upsertVod("b", "b$i", "BMovie $i", null, "c", 7.0, 2020, 0, null, null, i.toLong(), 0, null, null)
            db.storeQueries.upsertSeries("b", "b$i", "BShow $i", null, null, "c", null, null, 7.0, 2020, 0, i.toLong(), 0, null, null)
        }
    }

    @Test
    fun vodPageAllInterleavesSources() {
        seed()
        val rows = db.readQueries.vodPageAll(emptyList(), 100, 0).executeAsList()
        assertEquals(9, rows.size)
        val firstThree = rows.take(3).map { it.source_id }.toSet()
        assertTrue("a" in firstThree && "b" in firstThree, "first 3 rows must contain each source: $firstThree")
        val firstB = rows.indexOfFirst { it.source_id == "b" }
        val lastA = rows.indexOfLast { it.source_id == "a" }
        assertTrue(lastA > firstB, "A's rows must not all come before B's: ${rows.map { it.source_id }}")
    }

    @Test
    fun seriesPageAllInterleavesSources() {
        seed()
        val rows = db.readQueries.seriesPageAll(emptyList(), 100, 0).executeAsList()
        assertEquals(9, rows.size)
        val firstThree = rows.take(3).map { it.source_id }.toSet()
        assertTrue("a" in firstThree && "b" in firstThree, "first 3 rows must contain each source: $firstThree")
        val firstB = rows.indexOfFirst { it.source_id == "b" }
        val lastA = rows.indexOfLast { it.source_id == "a" }
        assertTrue(lastA > firstB, "A's rows must not all come before B's: ${rows.map { it.source_id }}")
    }

    @Test
    fun vodPageAllPagedReadMatchesSingleRead() {
        seed()
        val all = db.readQueries.vodPageAll(emptyList(), 100, 0).executeAsList()
        val p1 = db.readQueries.vodPageAll(emptyList(), 4, 0).executeAsList()
        val p2 = db.readQueries.vodPageAll(emptyList(), 5, 4).executeAsList()
        assertEquals(all, p1 + p2, "paged read must equal the single read in the same order")
    }
}
