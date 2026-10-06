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

/**
 * Task 63 (M6): Continue Watching shows one card per trustworthy title identity, so removing that
 * card must dismiss EVERY copy of the title (and its episodes), while progress without an identity
 * keeps the old per-copy behavior.
 */
class ContinueWatchingDismissalTest {
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
    fun dismissingAMovieCardRemovesEverySourceCopyOfTheTitle() = runTest(io) {
        seedVod(plex, "77", "tt77")
        seedVod(jellyfin, "m-77", "tt77")
        seedVod(plex, "99", "tt99") // a different title
        user.saveProgress(key(plex, ContentKind.VOD, "77"), null, 10_000, 100_000)
        user.saveProgress(key(jellyfin, ContentKind.VOD, "m-77"), null, 20_000, 100_000)
        user.saveProgress(key(plex, ContentKind.VOD, "99"), null, 30_000, 100_000)

        user.removeFromContinueWatching(key(plex, ContentKind.VOD, "77"))

        assertNull(user.progress(key(plex, ContentKind.VOD, "77")), "the dismissed copy is gone")
        assertNull(user.progress(key(jellyfin, ContentKind.VOD, "m-77")), "the other source's copy of the same title is gone too")
        assertNotNull(user.progress(key(plex, ContentKind.VOD, "99")), "other titles keep their progress")
        assertEquals(
            listOf(key(plex, ContentKind.VOD, "99")),
            user.continueWatching().first().map { it.key },
            "no copy of the dismissed title remains in Continue Watching",
        )
    }

    @Test
    fun dismissingAShowCardRemovesItsCopiesAndTheirEpisodes() = runTest(io) {
        seedSeries(plex, "50", "tt50")
        seedSeries(jellyfin, "s-50", "tt50")
        user.saveProgress(key(plex, ContentKind.SERIES, "50"), null, 1, 100)
        user.saveProgress(key(jellyfin, ContentKind.SERIES, "s-50"), null, 1, 100)
        user.saveProgress(key(plex, ContentKind.EPISODE, "e1"), RemoteId("50"), 1, 100)
        user.saveProgress(key(jellyfin, ContentKind.EPISODE, "je1"), RemoteId("s-50"), 1, 100)

        user.removeFromContinueWatching(key(plex, ContentKind.SERIES, "50"))

        assertEquals(
            listOf(null, null, null, null),
            listOf(
                user.progress(key(plex, ContentKind.SERIES, "50")),
                user.progress(key(jellyfin, ContentKind.SERIES, "s-50")),
                user.progress(key(plex, ContentKind.EPISODE, "e1")),
                user.progress(key(jellyfin, ContentKind.EPISODE, "je1")),
            ),
            "the whole title — every copy and every episode — is dismissed at once",
        )
    }

    @Test
    fun dismissedTitleStaysGoneWhenAnotherSourcesOnDeckStillListsIt() = runTest(io) {
        seedVod(plex, "77", "tt77")
        seedVod(jellyfin, "m-77", "tt77")
        now = 1_000L
        user.importRemoteProgress(key(jellyfin, ContentKind.VOD, "m-77"), null, 30_000, 100_000, atMs = 1_000L)
        now = 3_000L
        user.removeFromContinueWatching(key(plex, ContentKind.VOD, "77")) // removed via the OTHER source's card
        user.importRemoteProgress(key(jellyfin, ContentKind.VOD, "m-77"), null, 30_000, 100_000, atMs = 1_000L)
        assertNull(user.progress(key(jellyfin, ContentKind.VOD, "m-77")), "the title tombstone suppresses every copy's old progress")
        user.importRemoteProgress(key(jellyfin, ContentKind.VOD, "m-77"), null, 40_000, 100_000, atMs = 4_000L)
        assertEquals(40_000L, user.progress(key(jellyfin, ContentKind.VOD, "m-77"))?.positionMs, "watching again after the dismissal brings it back")
    }

    @Test
    fun dismissingAShowCardSuppressesOtherSourcesEpisodesOfTheSameTitle() = runTest(io) {
        seedSeries(plex, "50", "tt50")
        seedSeries(jellyfin, "s-50", "tt50")
        now = 1_000L
        user.importRemoteProgress(key(jellyfin, ContentKind.EPISODE, "je1"), RemoteId("s-50"), 30_000, 60_000, atMs = 1_000L)
        now = 3_000L
        user.removeFromContinueWatching(key(plex, ContentKind.SERIES, "50"))
        user.importRemoteProgress(key(jellyfin, ContentKind.EPISODE, "je1"), RemoteId("s-50"), 30_000, 60_000, atMs = 1_000L)
        assertNull(user.progress(key(jellyfin, ContentKind.EPISODE, "je1")), "episodes of the dismissed title stay suppressed via the title tombstone")
        user.importRemoteProgress(key(jellyfin, ContentKind.EPISODE, "je1"), RemoteId("s-50"), 40_000, 60_000, atMs = 4_000L)
        assertEquals(40_000L, user.progress(key(jellyfin, ContentKind.EPISODE, "je1"))?.positionMs)
    }

    @Test
    fun dismissalWithoutTrustworthyIdentityTouchesOnlyThatCopy() = runTest(io) {
        seedVod(plex, "77", null) // no TMDB id: no trustworthy identity
        seedVod(jellyfin, "m-77", null)
        user.saveProgress(key(plex, ContentKind.VOD, "77"), null, 10_000, 100_000)
        user.saveProgress(key(jellyfin, ContentKind.VOD, "m-77"), null, 20_000, 100_000)

        user.removeFromContinueWatching(key(plex, ContentKind.VOD, "77"))

        assertNull(user.progress(key(plex, ContentKind.VOD, "77")))
        assertNotNull(user.progress(key(jellyfin, ContentKind.VOD, "m-77")), "unidentified copies are independent, as before")
    }
}
