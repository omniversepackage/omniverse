package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.SourceRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.source.SourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRenameTest {
    @Test fun renameChangesTheDisplayedSourceNameAndKeepsItsConnection() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        try {
            val sources = SourceRepositoryImpl(OmniverseDb(driver), emptyList(), Dispatchers.Unconfined, { "source" })
            val id = SourceId("plex")
            sources.add(SourceConfig.Plex(id, "Old server name", "https://plex.invalid", "private-token", "machine", "client"))
            assertTrue(sources.rename(id, " Korys Theatre "))
            assertEquals("Korys Theatre", sources.sources().first().single().name)
            val config = sources.config(id) as SourceConfig.Plex
            assertEquals("Korys Theatre", config.displayName)
            assertEquals("https://plex.invalid", config.server)
            assertEquals("private-token", config.token)
            assertEquals("machine", config.machineId)
        } finally {
            driver.close()
        }
    }
}
