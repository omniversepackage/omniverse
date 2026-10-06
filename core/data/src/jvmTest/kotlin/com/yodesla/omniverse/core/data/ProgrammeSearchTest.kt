package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.SearchRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ProgrammeSearchTest {
    @Test
    fun findsAiringAndUpcomingProgrammesOnTheirChannelsOnly() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO channel(source_id,remote_id,name,epg_channel_id,primary_category_id,sort_index,sync_gen) VALUES " +
            "('iptv','10','Sports One','sp1','sports',0,1),('iptv','11','Gone','sp2','sports',1,1)", 0)
        driver.execute(null, "UPDATE channel SET removed_ms = 5 WHERE remote_id = '11'", 0)
        driver.execute(null, "INSERT INTO programme(source_id,channel_key,start_ms,end_ms,title) VALUES " +
            "('iptv','sp1',0,1000,'Football Past')," +           // ended
            "('iptv','sp1',1000,3000,'Live Football')," +        // airing at 2000
            "('iptv','sp1',5000,6000,'Football Tonight')," +     // upcoming
            "('iptv','sp1',99000000,99001000,'Football Later')," + // beyond 24 h
            "('iptv','sp2',5000,6000,'Football Removed')," +     // channel removed
            "('iptv','sp1',7000,8000,'100% Fun')", 0)
        val repo = SearchRepositoryImpl(OmniverseDb(driver), UnconfinedTestDispatcher(testScheduler))
        val hits = repo.searchProgrammes("football", nowMs = 2000)
        assertEquals(listOf("Live Football", "Football Tonight"), hits.map { it.title })
        assertEquals("Sports One", hits.first().channelName)
        assertEquals("10", hits.first().channel.remoteId.value)
        // Wildcards from user text are inert; one-character queries are ignored.
        assertEquals(emptyList(), repo.searchProgrammes("%", nowMs = 2000).map { it.title })
        assertEquals(listOf("100% Fun"), repo.searchProgrammes("100%", nowMs = 2000).map { it.title })
        assertEquals(emptyList(), repo.searchProgrammes("f", nowMs = 2000))
        driver.close()
    }
}
