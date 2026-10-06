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
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** UserDataRepositoryImpl.completedShows: shows whose latest progress row is a completed episode (task 57). */
class CompletedShowsTest {
    private val io = UnconfinedTestDispatcher()
    private var now = 1_790_424_000_000L
    private val clock = Clock { now }
    private val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
    private val user = UserDataRepositoryImpl(db, io, clock)

    private val series = ContentKey(SourceId("plex"), ContentKind.SERIES, RemoteId("50"))
    private val otherSeries = ContentKey(SourceId("plex"), ContentKind.SERIES, RemoteId("51"))
    private val ep1 = ContentKey(SourceId("plex"), ContentKind.EPISODE, RemoteId("e1"))
    private val ep2 = ContentKey(SourceId("plex"), ContentKind.EPISODE, RemoteId("e2"))
    private val movie = ContentKey(SourceId("plex"), ContentKind.VOD, RemoteId("m1"))

    private suspend fun shows(sinceMs: Long = 0L) = user.completedShows(sinceMs).first()

    @Test
    fun completedEpisodeSurfacesItsShow() = runTest(io) {
        user.setWatched(ep1, series.remoteId, watched = true, durationMs = 60_000)
        val rows = shows()
        assertEquals(1, rows.size)
        assertEquals(series, rows.single().seriesKey)
        assertEquals(ep1, rows.single().lastEpisodeKey)
        assertEquals(now, rows.single().updatedMs)
    }

    @Test
    fun partialLatestEpisodeKeepsTheShowOutOfCompletedShows() = runTest(io) {
        user.setWatched(ep1, series.remoteId, watched = true, durationMs = 60_000)
        now += 1_000
        user.saveProgress(ep2, series.remoteId, 30_000, 60_000) // newer partial watch: still in Continue Watching
        assertEquals(emptyList(), shows())
    }

    @Test
    fun activityOlderThanTheWindowIsNotSurfaced() = runTest(io) {
        user.setWatched(ep1, series.remoteId, watched = true, durationMs = 60_000) // at 'now'
        assertEquals(listOf(series), shows(now - 1).map { it.seriesKey })
        assertEquals(emptyList(), shows(now + 1), "last activity must be at or after sinceMs")
    }

    @Test
    fun completedMoviesAreNotShows() = runTest(io) {
        user.setWatched(movie, null, watched = true, durationMs = 60_000)
        assertEquals(emptyList(), shows())
    }

    @Test
    fun newestShowComesFirst() = runTest(io) {
        user.setWatched(ep1, series.remoteId, watched = true, durationMs = 60_000)
        now += 1_000
        user.setWatched(ep2, otherSeries.remoteId, watched = true, durationMs = 60_000)
        val rows = shows()
        assertEquals(2, rows.size)
        assertEquals(otherSeries, rows.first().seriesKey)
    }

    @Test
    fun tiedCompletionsProduceOneDeterministicCardForTheShow() = runTest(io) {
        // Season mark: several episodes completed in the same millisecond (task 63, M12).
        user.setWatched(ep1, series.remoteId, watched = true, durationMs = 60_000)
        user.setWatched(ep2, series.remoteId, watched = true, durationMs = 60_000) // same 'now'
        val rows = shows()
        assertEquals(1, rows.size, "a same-millisecond mark yields exactly one card, not one per tied episode")
        assertEquals(ep2, rows.single().lastEpisodeKey, "the last-written episode deterministically wins the tie")
    }

    @Test
    fun tiedShowsOrderDeterministically() = runTest(io) {
        user.setWatched(ep1, series.remoteId, watched = true, durationMs = 60_000)
        user.setWatched(ep2, otherSeries.remoteId, watched = true, durationMs = 60_000) // same 'now'
        val first = shows().map { it.seriesKey }
        val second = shows().map { it.seriesKey }
        assertEquals(first, second, "the tie-break is stable across calls")
        assertEquals(otherSeries, first.first(), "the later-written show wins the same-millisecond ordering")
    }

    @Test
    fun limitCountsShowsEvenWhenCompletionsTie() = runTest(io) {
        val ep3 = ContentKey(SourceId("plex"), ContentKind.EPISODE, RemoteId("e3"))
        user.setWatched(ep1, series.remoteId, watched = true, durationMs = 60_000)
        user.setWatched(ep2, series.remoteId, watched = true, durationMs = 60_000) // tied with ep1
        now += 1_000
        user.setWatched(ep3, otherSeries.remoteId, watched = true, durationMs = 60_000)
        val rows = user.completedShows(0L, 1).first()
        assertEquals(1, rows.size)
        assertEquals(otherSeries, rows.single().seriesKey, "tied rows must not burn the :limit slot of another show")
    }

    @Test
    fun progressReportsTheCompletedFlag() = runTest(io) {
        user.saveProgress(ep1, series.remoteId, 30_000, 60_000)
        assertNotNull(user.progress(ep1))
        assertTrue(!user.progress(ep1)!!.completed)
        user.setWatched(ep1, series.remoteId, watched = true, durationMs = 60_000)
        assertTrue(user.progress(ep1)!!.completed)
    }
}
