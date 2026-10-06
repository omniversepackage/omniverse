package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Audit L4: a `src_sync_*` row means "a sync is running in this process", so none may survive a restart. */
class StaleSyncStageTest {
    private val io = UnconfinedTestDispatcher()
    private var now = 1_790_424_000_000L
    private val clock = Clock { now }
    private val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
    private val user = UserDataRepositoryImpl(
        db, io, clock,
        profileId = { ProfileRepository.DEFAULT_ID },
        profileIds = MutableStateFlow(ProfileRepository.DEFAULT_ID),
    )

    @Test
    fun aStageLeftByAKilledSyncIsGoneOnTheNextStartup() = runTest {
        val key = SourceStatusKeys.sync(SourceId("s1"))
        user.putSetting(key, "Running")
        assertNotNull(user.setting(key).first())

        user.clearStaleSyncStages()
        assertNull(user.setting(key).first())
    }

    @Test
    fun clearingStagesTouchesNothingElse() = runTest {
        user.putSetting("home_layout", "compact")
        user.putSetting("search_history::s1", "film")
        user.putSetting(SourceStatusKeys.sync(SourceId("s1")), "Running")
        user.putSetting(SourceStatusKeys.sync(SourceId("s2")), "Running")

        user.clearStaleSyncStages()

        assertNull(user.setting(SourceStatusKeys.sync(SourceId("s1"))).first())
        assertNull(user.setting(SourceStatusKeys.sync(SourceId("s2"))).first())
        assertEquals("compact", user.setting("home_layout").first())
        assertEquals("film", user.setting("search_history::s1").first())
    }
}
