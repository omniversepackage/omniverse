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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** UserDataRepositoryImpl.importRemoteProgress: newer remote wins, older remote does not. */
class RemoteProgressImportTest {
    private val io = UnconfinedTestDispatcher()
    private var now = 1_790_424_000_000L
    private val clock = Clock { now }
    private val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
    private val user = UserDataRepositoryImpl(db, io, clock)

    private val movie = ContentKey(SourceId("plex"), ContentKind.VOD, RemoteId("77"))
    private val episode = ContentKey(SourceId("plex"), ContentKind.EPISODE, RemoteId("e1"))

    @Test
    fun newerRemoteProgressOverwritesLocal() = runTest(io) {
        now = 1_000L
        user.saveProgress(movie, null, 10_000, 100_000) // local row updated at 1_000
        user.importRemoteProgress(movie, null, 50_000, 100_000, atMs = 2_000L) // remote is newer
        val p = user.progress(movie)
        assertNotNull(p)
        assertEquals(50_000L, p.positionMs)
        assertEquals(2_000L, p.updatedMs)
    }

    @Test
    fun olderRemoteProgressDoesNotOverwriteLocal() = runTest(io) {
        now = 5_000L
        user.saveProgress(movie, null, 10_000, 100_000) // local row updated at 5_000
        user.importRemoteProgress(movie, null, 50_000, 100_000, atMs = 2_000L) // remote is older
        val p = user.progress(movie)
        assertNotNull(p)
        assertEquals(10_000L, p.positionMs, "stale remote must not clobber a local watch")
        assertEquals(5_000L, p.updatedMs)
    }

    @Test
    fun importWritesProgressWhenThereIsNoLocalRow() = runTest(io) {
        user.importRemoteProgress(episode, RemoteId("50"), 30_000, 60_000, atMs = 1_000L)
        val p = user.progress(episode)
        assertNotNull(p)
        assertEquals(30_000L, p.positionMs)
        assertEquals(RemoteId("50"), p.parentId)
        // It now shows up in Continue Watching.
        assertEquals(listOf(episode), user.continueWatching().first().map { it.key })
    }

    @Test
    fun removedFromContinueWatchingIsNotReimportedUntilWatchedAgain() = runTest(io) {
        val show = ContentKey(SourceId("plex"), ContentKind.SERIES, RemoteId("50"))
        now = 1_000L
        user.importRemoteProgress(episode, RemoteId("50"), 30_000, 60_000, atMs = 1_000L)
        now = 3_000L
        user.removeFromContinueWatching(show) // the viewer hides the show
        user.importRemoteProgress(episode, RemoteId("50"), 30_000, 60_000, atMs = 1_000L) // same old onDeck entry
        assertEquals(null, user.progress(episode), "a dismissed title must not come back from the source")
        user.importRemoteProgress(episode, RemoteId("50"), 40_000, 60_000, atMs = 4_000L) // watched again later in Plex
        assertEquals(40_000L, user.progress(episode)?.positionMs)
    }
}
