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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Audit 73 L9: UserDataRepositoryImpl used to call profileId() several times inside one operation,
 * so a profile switch landing mid-operation made one profile's read feed another profile's write.
 * The viewer is now resolved ONCE per operation.
 */
class ProfileSwitchRaceTest {
    private val io = UnconfinedTestDispatcher()
    private var now = 1_790_424_000_000L
    private val clock = Clock { now }
    private val movie = ContentKey(SourceId("plex"), ContentKind.VOD, RemoteId("77"))

    private fun newDb() = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })

    /** Returns [first] on the first call and [then] on every call after — a switch landing mid-operation. */
    private fun switchAfterFirstCall(first: String, then: String): () -> String {
        var calls = 0
        return { calls++; if (calls == 1) first else then }
    }

    @Test
    fun aProfileSwitchMidImportCannotWriteIntoTheNextProfile() = runTest(io) {
        val db = newDb()
        db.userDataQueries.upsertProgress("A", "plex", "VOD", "77", null, 10_000, 100_000, 1_000L, 0)
        val user = UserDataRepositoryImpl(db, io, clock, profileId = switchAfterFirstCall("A", "B"))

        user.importRemoteProgress(movie, null, 50_000, 100_000, atMs = 2_000L)

        val a = db.userOpsQueries.progressByKey("A", "plex", "VOD", "77").executeAsOneOrNull()
        assertNotNull(a, "the newer remote watch must land in the profile it was read for")
        assertEquals(50_000L, a.position_ms)
        assertNull(db.userOpsQueries.progressByKey("B", "plex", "VOD", "77").executeAsOneOrNull(), "the next profile must not gain a row")
    }

    @Test
    fun aProfileSwitchMidDismissalCannotTombstoneTheNextProfile() = runTest(io) {
        val db = newDb()
        db.userDataQueries.upsertProgress("A", "plex", "VOD", "77", null, 10_000, 100_000, 1_000L, 0)
        val user = UserDataRepositoryImpl(db, io, clock, profileId = switchAfterFirstCall("A", "B"))

        user.removeFromContinueWatching(movie)

        assertNull(db.userOpsQueries.progressByKey("A", "plex", "VOD", "77").executeAsOneOrNull(), "the progress is deleted from the profile it was read for")
        assertNotNull(db.userDataQueries.getSetting("cw_dismissed_A").executeAsOneOrNull(), "the tombstone must guard the profile whose card was removed")
        assertNull(db.userDataQueries.getSetting("cw_dismissed_B").executeAsOneOrNull(), "the next profile must not inherit the dismissal")
    }
}
