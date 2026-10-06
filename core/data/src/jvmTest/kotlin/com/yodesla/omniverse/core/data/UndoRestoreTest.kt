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
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Task 90: undo for Continue Watching and My List removals. A removal returns an [UndoToken] that
 * captures the exact rows deleted; restoring it puts every row back verbatim (position, duration,
 * completed flag, updated time, favourite order) and clears the re-import tombstone.
 */
class UndoRestoreTest {
    private val io = UnconfinedTestDispatcher()
    private var now = 1_790_424_000_000L
    private val clock = Clock { now }
    private val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
    private val user = UserDataRepositoryImpl(db, io, clock)

    private val plex = SourceId("plex")
    private val jellyfin = SourceId("jellyfin")

    private fun seedVod(source: SourceId, remote: String, tmdb: String?) =
        db.storeQueries.upsertVod(source.value, remote, "Movie $remote", null, "c", 7.0, 2020, 1_000L, "mp4", tmdb, 0L, 1L, null, null)

    private fun seedSeries(source: SourceId, remote: String, tmdb: String?) =
        db.storeQueries.upsertSeries(source.value, remote, "Show $remote", null, null, "c", null, null, 7.0, 2020, 1_000L, 0L, 1L, null, tmdb)

    private fun key(source: SourceId, kind: ContentKind, remote: String) = ContentKey(source, kind, RemoteId(remote))

    @Test
    fun undoRestoresTheDismissedMovieAtItsExactPosition() = runTest(io) {
        seedVod(plex, "77", "tt77")
        user.saveProgress(key(plex, ContentKind.VOD, "77"), null, 12_345L, 100_000L)

        val token = user.removeFromContinueWatching(key(plex, ContentKind.VOD, "77"))
        assertNull(user.progress(key(plex, ContentKind.VOD, "77")), "dismissed")

        user.restoreContinueWatching(token)
        val restored = assertNotNull(user.progress(key(plex, ContentKind.VOD, "77")), "undo brings it back")
        assertEquals(12_345L, restored.positionMs, "exact position restored")
        assertEquals(100_000L, restored.durationMs, "exact duration restored")
        assertEquals(listOf(key(plex, ContentKind.VOD, "77")), user.continueWatching().first().map { it.key })
    }

    @Test
    fun undoRestoresEveryCopyAndEpisodeOfATitleDismissal() = runTest(io) {
        seedVod(plex, "77", "tt77")
        seedVod(jellyfin, "m-77", "tt77")
        user.saveProgress(key(plex, ContentKind.VOD, "77"), null, 10_000L, 100_000L)
        user.saveProgress(key(jellyfin, ContentKind.VOD, "m-77"), null, 20_000L, 100_000L)

        val token = user.removeFromContinueWatching(key(plex, ContentKind.VOD, "77"))
        assertNull(user.progress(key(jellyfin, ContentKind.VOD, "m-77")), "title-level dismissal removed the other copy")

        user.restoreContinueWatching(token)
        assertEquals(10_000L, user.progress(key(plex, ContentKind.VOD, "77"))?.positionMs)
        assertEquals(20_000L, user.progress(key(jellyfin, ContentKind.VOD, "m-77"))?.positionMs, "the other source's copy is restored too")
    }

    @Test
    fun undoClearsTheTombstoneSoOldProgressIsAcceptedAgain() = runTest(io) {
        seedVod(jellyfin, "m-77", "tt77")
        now = 1_000L
        user.importRemoteProgress(key(jellyfin, ContentKind.VOD, "m-77"), null, 30_000L, 100_000L, atMs = 1_000L)
        now = 3_000L
        val token = user.removeFromContinueWatching(key(jellyfin, ContentKind.VOD, "m-77"))
        user.restoreContinueWatching(token)
        // With the tombstone cleared, the same old remote progress is no longer suppressed.
        user.importRemoteProgress(key(jellyfin, ContentKind.VOD, "m-77"), null, 30_000L, 100_000L, atMs = 1_000L)
        assertNotNull(user.progress(key(jellyfin, ContentKind.VOD, "m-77")))
    }

    @Test
    fun undoRestoresAFavouriteToItsExactOrderAndAddedTime() = runTest(io) {
        val a = key(plex, ContentKind.VOD, "a")
        val b = key(plex, ContentKind.VOD, "b")
        now = 100L
        user.setFavorite(a, true)
        now = 200L
        user.setFavorite(b, true)

        val token = user.removeFromMyList(a)
        assertEquals(listOf(b), user.favorites().first(), "a left My List")

        // A new favourite added while the undo window is open must not steal a's slot.
        val c = key(plex, ContentKind.VOD, "c")
        now = 300L
        user.setFavorite(c, true)

        user.restoreMyList(token)
        assertEquals(listOf(a, b, c), user.favorites().first(), "a returns to its original first slot")
    }

    @Test
    fun dismissedTitlesAreListedInSettingsAndCanBeRestored() = runTest(io) {
        seedVod(plex, "77", "tt77")
        user.saveProgress(key(plex, ContentKind.VOD, "77"), null, 42_000L, 100_000L)

        user.removeFromContinueWatching(key(plex, ContentKind.VOD, "77"))
        val hidden = user.hiddenContinueWatching().first()
        assertTrue(hidden.any { it.key == key(plex, ContentKind.VOD, "77") }, "dismissed title shows in the hidden list")

        user.restoreHiddenContinueWatching(key(plex, ContentKind.VOD, "77"))
        assertEquals(42_000L, user.progress(key(plex, ContentKind.VOD, "77"))?.positionMs, "restore puts the card back in Continue Watching")
        assertTrue(user.hiddenContinueWatching().first().none { it.key == key(plex, ContentKind.VOD, "77") }, "restored title leaves the hidden list")
    }
}
