package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.model.Capability
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ChannelRecord
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.PlaybackSpec
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SeriesDetail
import com.yodesla.omniverse.core.model.SeriesRecord
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.model.VodDetail
import com.yodesla.omniverse.core.model.VodRecord
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.source.SourceException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 105: the health store (settings-backed snapshots) and the probe, on the real settings layer. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SourceHealthStoreTest {
    private val io = UnconfinedTestDispatcher()
    private var now = 1_790_424_000_000L
    private val clock = Clock { now }
    private val day = SourceHealth.DAY
    private val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })

    private fun users(profile: String = "default") = UserDataRepositoryImpl(db, io, clock, profileId = { profile })

    private fun store(profile: String = "default") = SourceHealthStore(users(profile), clock)

    private fun obs(
        account: AccountInfo? = AccountInfo(AccountStatus.ACTIVE, null, 3, 1, false, listOf("ts"), "UTC", now),
        movies: Long = 100,
        shows: Long = 50,
        channels: Long = 100,
        lastSuccessSyncMs: Long? = now - day,
        error: SourceErrorKind? = null,
    ) = SourceHealthObservation(
        account = account,
        counts = mapOf(ContentKind.VOD to movies, ContentKind.SERIES to shows, ContentKind.LIVE to channels),
        lastSuccessSyncMs = lastSuccessSyncMs,
        error = error,
    )

    @Test
    fun aCleanPassWithNothingWrongStoresNoWarnings() = runTest {
        val s = store()
        s.record(SourceId("a"), "IPTV", obs())
        assertTrue(s.warnings().first().isEmpty())
        assertNull(s.alert().first())
    }

    @Test
    fun previousCountsBecomeTheDropBaseline() = runTest {
        val s = store()
        s.record(SourceId("a"), "IPTV", obs(channels = 100))
        s.record(SourceId("a"), "IPTV", obs(channels = 40))
        val snap = s.snapshots().first().single()
        assertEquals(100L, snap.prevChannels)
        assertEquals(40L, snap.channels)
        assertEquals(listOf("Channels dropped 60%"), s.warnings().first().map { it.text })
    }

    @Test
    fun aPassThatReadNoCountsIsNeverMeasuredAsADrop() = runTest {
        val s = store()
        s.record(SourceId("a"), "IPTV", obs(channels = 500))
        s.record(SourceId("a"), "IPTV", SourceHealthObservation(counts = emptyMap(), lastSuccessSyncMs = now - day))
        val snap = s.snapshots().first().single()
        assertEquals(500L, snap.channels, "an unreadable pass keeps the last known counts")
        assertTrue(s.warnings().first().none { it.kind == SourceWarningKind.COUNT_DROP })
    }

    @Test
    fun failuresCountUpAndACleanPassResetsThem() = runTest {
        val s = store()
        s.record(SourceId("a"), "IPTV", obs(error = SourceErrorKind.NETWORK))
        s.record(SourceId("a"), "IPTV", obs(error = SourceErrorKind.NETWORK))
        assertEquals(2, s.snapshots().first().single().failCount)
        assertTrue(s.warnings().first().none { it.kind == SourceWarningKind.REPEATED_FAILURE })
        s.record(SourceId("a"), "IPTV", obs(error = SourceErrorKind.NETWORK))
        assertEquals(listOf("Keeps failing (3 checks in a row)"), s.warnings().first().map { it.text })
        s.record(SourceId("a"), "IPTV", obs())
        assertEquals(0, s.snapshots().first().single().failCount)
        assertTrue(s.warnings().first().isEmpty())
    }

    @Test
    fun accountExpiryAndConnectionSlotsComeFromTheProviderAndWarn() = runTest {
        val s = store()
        s.record(
            SourceId("a"), "IPTV",
            obs(account = AccountInfo(AccountStatus.ACTIVE, now + 5 * day, 2, 2, false, listOf("ts"), "UTC", now)),
        )
        val snap = s.snapshots().first().single()
        assertEquals(now + 5 * day, snap.expiresAtMs)
        assertEquals(2, snap.maxConnections)
        assertEquals(2, snap.activeConnections)
        assertEquals("Expires in 5 days", assertNotNull(s.alert().first()).text)
    }

    @Test
    fun aRenewedSubscriptionStopsWarningAndCanWarnAgainWhenItExpires() = runTest {
        val s = store()
        val soon = AccountInfo(AccountStatus.ACTIVE, now + 3 * day, 2, 0, false, listOf("ts"), "UTC", now)
        s.record(SourceId("a"), "IPTV", obs(account = soon))
        val first = assertNotNull(s.alert().first())
        s.record(SourceId("a"), "IPTV", obs(account = AccountInfo(AccountStatus.ACTIVE, now + 60 * day, 2, 0, false, listOf("ts"), "UTC", now)))
        assertNull(s.alert().first(), "renewed: nothing to warn about")
        s.record(SourceId("a"), "IPTV", obs(account = AccountInfo(AccountStatus.EXPIRED, now - day, 2, 0, false, listOf("ts"), "UTC", now)))
        val again = assertNotNull(s.alert().first())
        assertTrue(again.text == "Expired" || again.text == "Disabled by provider")
        assertTrue(again.fingerprint != first.fingerprint)
    }

    @Test
    fun dismissalSilencesThisProblemForThisProfileOnly() = runTest {
        val a = store("kory")
        val b = store("kids")
        a.record(SourceId("a"), "IPTV", obs(account = AccountInfo(AccountStatus.ACTIVE, now + 2 * day, 2, 0, false, listOf("ts"), "UTC", now)))
        val alert = assertNotNull(a.alert().first())
        a.dismissAlert(alert.fingerprint)
        assertNull(a.alert().first(), "dismissed for this profile")
        assertNotNull(b.alert().first(), "another profile still sees it")
        // A different problem (a new expiry) is a new alert, not the dismissed one.
        a.record(SourceId("a"), "IPTV", obs(account = AccountInfo(AccountStatus.ACTIVE, now + 4 * day, 2, 0, false, listOf("ts"), "UTC", now)))
        val renewed = assertNotNull(a.alert().first())
        assertTrue(renewed.fingerprint != alert.fingerprint)
    }

    @Test
    fun lastCheckDrivesTheWeeklyGate() = runTest {
        val s = store()
        assertTrue(s.isDue())
        s.markChecked(now)
        assertTrue(!s.isDue())
        now += 8 * day
        assertTrue(s.isDue())
    }

    @Test
    fun snapshotsRoundTripThroughOneSettingsRow() = runTest {
        val s = store()
        s.record(SourceId("a"), "IPTV", obs())
        s.record(SourceId("b"), "Plex", obs(movies = 10, shows = 5, channels = 0))
        val raw = users().setting(SourceHealthKeys.snapshots).first()
        assertNotNull(raw)
        assertTrue(!raw.contains("http") && !raw.contains("token"), "the stored row holds counts and times only")
        assertEquals(listOf("a", "b"), s.snapshots().first().map { it.sourceId })
    }

    @Test
    fun probeChecksEverySourceRecordsFailuresAndPrunesRemovedOnes() = runTest {
        val s = store()
        val live = SourceId("live")
        val dead = SourceId("dead")
        val gone = SourceId("gone")
        val probe = SourceHealthProbe(
            store = s,
            clock = clock,
            sources = { listOf(live to "IPTV", dead to "Broken") },
            contentSource = { id -> if (id == live) FakeSource(live, AccountInfo(AccountStatus.ACTIVE, now + 6 * day, 2, 1, false, listOf("ts"), "UTC", now)) else FakeSource(id, null) },
            counts = { id -> if (id == live) mapOf(ContentKind.VOD to 100L, ContentKind.SERIES to 50L, ContentKind.LIVE to 100L) else emptyMap() },
            lastSuccessSyncMs = { if (it == live) now - day else null },
        )
        s.record(gone, "Removed", obs())
        val warnings = probe.checkAll()
        assertEquals(listOf("dead", "live"), s.snapshots().first().map { it.sourceId }.sorted(), "the removed source is pruned")
        assertNotNull(s.lastCheckedMsNow())
        val bySource = warnings.groupBy { it.sourceId }
        assertEquals("Expires in 6 days", bySource.getValue("live").single().text)
        assertEquals(listOf("Login failed", "Never synced successfully"), bySource.getValue("dead").map { it.text })
        assertEquals("Login failed", assertNotNull(s.alert().first()).text)
    }

    /** Scriptable provider: answers accountInfo, optionally failing, everything else unused. */
    private class FakeSource(private val sourceId: SourceId, private val info: AccountInfo?) : ContentSource {
        override val id = sourceId
        override val kind = SourceKind.XTREAM
        override val capabilities = emptySet<Capability>()
        override suspend fun accountInfo(): AccountInfo =
            info ?: throw SourceException.AuthFailed("bad login")
        override fun liveCategories(): Flow<Category> = flowOf()
        override fun liveChannels(diagnostics: com.yodesla.omniverse.core.source.SyncDiagnostics): Flow<ChannelRecord> = flowOf()
        override fun vodCategories(): Flow<Category> = flowOf()
        override fun vodItems(diagnostics: com.yodesla.omniverse.core.source.SyncDiagnostics): Flow<VodRecord> = flowOf()
        override fun seriesCategories(): Flow<Category> = flowOf()
        override fun series(diagnostics: com.yodesla.omniverse.core.source.SyncDiagnostics): Flow<SeriesRecord> = flowOf()
        override suspend fun vodDetail(id: RemoteId): VodDetail = error("unused")
        override suspend fun seriesDetail(id: RemoteId): SeriesDetail = error("unused")
        override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: com.yodesla.omniverse.core.source.SyncDiagnostics): Flow<ProgrammeRecord> = flowOf()
        override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()
        override suspend fun playback(request: PlaybackRequest): PlaybackSpec = error("unused")
    }
}
