package com.yodesla.omniverse.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.QueryResult
import kotlin.test.Test
import kotlin.test.assertEquals

class UnicodeSearchMigrationTest {
    @Test fun upgradeReindexesExistingTitlesAndPreservesTheirIdentity() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, "CREATE VIRTUAL TABLE search_index USING fts4(title,kind,source_id,remote_id,notindexed=kind,notindexed=source_id,notindexed=remote_id,prefix=\"2,3,4\")", 0)
            driver.execute(null, "INSERT INTO search_index(docid,title,kind,source_id,remote_id) VALUES (42,'SHŌGUN','SERIES','hulu','show'),(43,'Pokémon','SERIES','tv','anime')", 0)
            fun matches(query: String) = driver.executeQuery(null, "SELECT docid FROM search_index WHERE search_index MATCH '$query' ORDER BY docid", { cursor ->
                val ids = mutableListOf<Long>()
                while (cursor.next().value) ids += cursor.getLong(0)!!
                QueryResult.Value(ids)
            }, 0).value
            assertEquals(emptyList(), matches("shogun*"))
            OmniverseDb.Schema.migrate(driver, 17, 18)
            assertEquals(listOf(42L), matches("shogun*"))
            assertEquals(listOf(43L), matches("pokemon*"))
            assertEquals(listOf(42L), matches("SHŌGUN*"))
            driver.execute(null, "INSERT INTO search_index(title,kind,source_id,remote_id) VALUES ('Élite','SERIES','tv','new')", 0)
            assertEquals(1, matches("elite*").size)
        } finally {
            driver.close()
        }
    }
}
