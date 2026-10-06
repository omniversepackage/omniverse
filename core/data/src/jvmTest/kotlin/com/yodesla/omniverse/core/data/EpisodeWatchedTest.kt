package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** UserDataRepositoryImpl.setWatched: watched rows are completed (never in Continue Watching); unwatched rows are gone. */
class EpisodeWatchedTest {
    private val io = UnconfinedTestDispatcher()
    private var now = 1_790_424_000_000L
    private val clock = Clock { now }
    private val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
    private val user = UserDataRepositoryImpl(db, io, clock)

    private val series = ContentKey(SourceId("plex"), ContentKind.SERIES, RemoteId("50"))
    private val episode = ContentKey(SourceId("plex"), ContentKind.EPISODE, RemoteId("e1"))

    @Test
    fun watchedEpisodeIsCompletedAndAbsentFromContinueWatching() = runTest(io) {
        user.setWatched(episode, series.remoteId, watched = true, durationMs = 60_000)
        val p = user.progress(episode)
        assertNotNull(p)
        assertEquals(60_000L, p.positionMs, "a watched row stores the full duration")
        assertEquals(60_000L, p.durationMs)
        // completed = 1 rows are filtered out of Continue Watching.
        assertEquals(emptyList(), user.continueWatching().first().map { it.key })
    }

    @Test
    fun watchedWithUnknownDurationStoresTheSentinelAndStaysOutOfContinueWatching() = runTest(io) {
        user.setWatched(episode, series.remoteId, watched = true, durationMs = null)
        val p = user.progress(episode)
        assertNotNull(p)
        assertEquals(1L, p.positionMs)
        assertNull(p.durationMs)
        assertEquals(emptyList(), user.continueWatching().first().map { it.key })
    }

    @Test
    fun unwatchedDeletesTheProgressRow() = runTest(io) {
        user.saveProgress(episode, series.remoteId, 30_000, 60_000) // partial: shows in Continue Watching
        assertEquals(listOf(episode), user.continueWatching().first().map { it.key })
        user.setWatched(episode, series.remoteId, watched = false, durationMs = null)
        assertNull(user.progress(episode), "unwatching must delete the row")
        assertEquals(emptyList(), user.continueWatching().first().map { it.key })
    }

    @Test
    fun unwatchingOneEpisodeLeavesOtherEpisodesOfTheShowIntact() = runTest(io) {
        val other = ContentKey(SourceId("plex"), ContentKind.EPISODE, RemoteId("e2"))
        user.saveProgress(episode, series.remoteId, 30_000, 60_000)
        user.saveProgress(other, series.remoteId, 20_000, 60_000)
        user.setWatched(episode, series.remoteId, watched = false, durationMs = null)
        assertNull(user.progress(episode))
        assertNotNull(user.progress(other), "only the exact key's row is deleted")
    }
}
