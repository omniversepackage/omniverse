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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Task 97: the "Play next" queue. Viewer-ordered per profile, stored in the favourite table under
 * list_id 'playnext' so it must never disturb My List, and re-adding must not reorder.
 */
class PlayNextQueueTest {
    private val io = UnconfinedTestDispatcher()
    private var now = 1_790_424_000_000L
    private val clock = Clock { now }
    private val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
    private val user = UserDataRepositoryImpl(db, io, clock)

    private val plex = SourceId("plex")

    private fun key(remote: String, kind: ContentKind = ContentKind.VOD) = ContentKey(plex, kind, RemoteId(remote))

    @Test
    fun queueKeepsViewerOrderAndReAddDoesNotMoveToTheBack() = runTest(io) {
        val a = key("a")
        val b = key("b")
        val c = key("c")
        user.setQueued(a, true)
        user.setQueued(b, true)
        user.setQueued(c, true)
        assertEquals(listOf(a, b, c), user.playNext().first())

        user.setQueued(a, true)
        assertEquals(listOf(a, b, c), user.playNext().first(), "re-adding must not move the title to the back")
    }

    @Test
    fun removeLeavesTheRestInOrderAndIsQueuedFollows() = runTest(io) {
        val a = key("a")
        val b = key("b")
        val c = key("c")
        listOf(a, b, c).forEach { user.setQueued(it, true) }

        user.setQueued(b, false)
        assertEquals(listOf(a, c), user.playNext().first())
        assertTrue(user.isQueued(a).first())
        assertFalse(user.isQueued(b).first(), "removed from the queue")
    }

    @Test
    fun moveUpAndDownSwapViewerOrderAndEndsAreNoOps() = runTest(io) {
        val a = key("a")
        val b = key("b")
        val c = key("c")
        listOf(a, b, c).forEach { user.setQueued(it, true) }

        user.moveQueued(c, up = true)
        assertEquals(listOf(a, c, b), user.playNext().first())

        user.moveQueued(c, up = true)
        assertEquals(listOf(c, a, b), user.playNext().first())

        user.moveQueued(c, up = true)
        assertEquals(listOf(c, a, b), user.playNext().first(), "already first: move up is a no-op")

        user.moveQueued(b, up = false)
        assertEquals(listOf(c, a, b), user.playNext().first(), "already last: move down is a no-op")

        user.moveQueued(a, up = false)
        assertEquals(listOf(c, b, a), user.playNext().first())
    }

    @Test
    fun moveOfAnUnqueuedTitleIsANoOp() = runTest(io) {
        val a = key("a")
        val b = key("b")
        user.setQueued(a, true)
        user.moveQueued(b, up = false)
        assertEquals(listOf(a), user.playNext().first())
    }

    @Test
    fun queuesArePerProfileAndNeverTouchMyList() = runTest(io) {
        val kid = UserDataRepositoryImpl(db, io, clock, profileId = { "kid" })
        val a = key("a")
        val b = key("b")

        user.setQueued(a, true)
        kid.setQueued(b, true)
        user.setFavorite(b, true)

        assertEquals(listOf(a), user.playNext().first(), "the main profile's queue ignores the kid's")
        assertEquals(listOf(b), kid.playNext().first(), "the kid's queue ignores the main profile's")
        assertEquals(listOf(b), user.favorites().first(), "queueing never lands in My List")
        assertTrue(user.isFavorite(b).first(), "My List is unchanged")
        assertFalse(kid.isQueued(a).first(), "the kid never sees the queued title")
    }

    @Test
    fun seriesAndMoviesShareOneQueueInInsertionOrder() = runTest(io) {
        val movie = key("m", ContentKind.VOD)
        val series = key("s", ContentKind.SERIES)
        user.setQueued(series, true)
        user.setQueued(movie, true)
        assertEquals(listOf(series, movie), user.playNext().first())

        user.moveQueued(movie, up = true)
        assertEquals(listOf(movie, series), user.playNext().first())
    }
}
