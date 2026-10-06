package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.Capability
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ChannelRecord
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.Episode
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.PlaybackSpec
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.Season
import com.yodesla.omniverse.core.model.SeriesDetail
import com.yodesla.omniverse.core.model.SeriesRecord
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.model.VodDetail
import com.yodesla.omniverse.core.model.VodRecord
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** Task 71 "New episodes": snapshot detection, per-profile storage, and the card label. */
private val src = SourceId("plex")
private val seriesKey = ContentKey(src, ContentKind.SERIES, RemoteId("s1"))

class NewEpisodesTest {
    private val io = UnconfinedTestDispatcher()
    private var now = 1_790_424_000_000L
    private val clock = Clock { now }

    private fun ep(season: Int, number: Int) =
        Episode(src, RemoteId("e$season-$number"), RemoteId("s1"), season, number, "E$number", null, 60, null, "mp4", null)

    private fun detail(vararg seasons: Pair<Int, List<Episode>>) = SeriesDetail(
        SeriesRecord(src, RemoteId("s1"), "Show", null, emptyList(), emptyList(), null, null, null, null, null, 0, null),
        null, null, seasons.map { (n, eps) -> Season(n, "Season $n", null, eps) },
    )

    private class FakeSource(var detail: SeriesDetail, var fail: Boolean = false) : ContentSource {
        var calls = 0
        override val id = src
        override val kind = SourceKind.XTREAM
        override val capabilities: Set<Capability> = emptySet()
        override suspend fun accountInfo(): AccountInfo = TODO()
        override fun liveCategories(): Flow<Category> = emptyFlow()
        override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> = emptyFlow()
        override fun vodCategories(): Flow<Category> = emptyFlow()
        override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> = emptyFlow()
        override fun seriesCategories(): Flow<Category> = emptyFlow()
        override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> = emptyFlow()
        override suspend fun vodDetail(id: RemoteId): VodDetail = TODO()
        override suspend fun seriesDetail(id: RemoteId): SeriesDetail {
            calls++
            if (fail) error("provider down")
            return detail
        }
        override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = emptyFlow()
        override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()
        override suspend fun playback(request: PlaybackRequest): PlaybackSpec = TODO()
    }

    private class FakeSources(private val source: ContentSource?) : SourceRepository {
        override fun sources(): Flow<List<SourceSummary>> = flowOf(emptyList())
        override suspend fun add(config: SourceConfig): SourceId = TODO()
        override suspend fun update(config: SourceConfig) = TODO()
        override suspend fun remove(id: SourceId) = TODO()
        override suspend fun config(id: SourceId): SourceConfig? = null
        override suspend fun contentSource(id: SourceId): ContentSource? = source
        override suspend fun probe(config: SourceConfig): AccountInfo = TODO()
    }

    private fun newDb() = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })

    /** One profile's view: its own user data over a shared DB, plus the provider it syncs from. */
    private inner class Fixture(detail: SeriesDetail, private val profile: String = "default", val db: OmniverseDb = newDb()) {
        val source = FakeSource(detail)
        val user = UserDataRepositoryImpl(db, io, clock, profileId = { profile })
        val detector = NewEpisodesDetector(FakeSources(source), user, clock)

        /** A partial watch on one episode, which is all "seen" needs. */
        suspend fun watch(season: Int, number: Int) {
            now += 1_000
            user.saveProgress(ContentKey(src, ContentKind.EPISODE, RemoteId("e$season-$number")), seriesKey.remoteId, 30_000, 60_000)
        }

        suspend fun snapshot(): EpisodeSnapshot? =
            EpisodeSnapshot.decode(user.setting(NewEpisodes.snapshotKey(src.value, "s1")).first())

        suspend fun notice(): NewEpisodesNotice? =
            NewEpisodesNotice.decode(user.setting(NewEpisodes.noticeKey(src.value, "s1")).first())
    }

    private val s1 = listOf(ep(1, 1), ep(1, 2), ep(1, 3))
    private val s2 = listOf(ep(2, 1), ep(2, 2), ep(2, 3))

    @Test
    fun firstSightStoresTheSnapshotAndFlagsNothing() = runTest(io) {
        val f = Fixture(detail(1 to s1))
        f.watch(1, 1)
        f.detector.refresh(src)
        assertEquals(EpisodeSnapshot(1, 3, 3), f.snapshot())
        assertNull(f.notice(), "a show seen for the first time must not claim new episodes")
    }

    @Test
    fun anEpisodeNewerThanTheSnapshotBecomesANotice() = runTest(io) {
        val f = Fixture(detail(1 to s1))
        f.watch(1, 1)
        f.detector.refresh(src)
        f.source.detail = detail(1 to s1, 2 to listOf(ep(2, 1)))
        f.detector.refresh(src)
        val notice = assertNotNull(f.notice())
        assertEquals("e2-1", notice.episodeId)
        assertEquals(2, notice.season)
        assertEquals(1, notice.number)
        assertEquals(1, notice.count)
        assertEquals(EpisodeSnapshot(1, 3, 3), f.snapshot(), "the snapshot stays until the viewer catches up")
    }

    @Test
    fun severalNewEpisodesCountTogetherAndNameTheFirstUnseen() = runTest(io) {
        val f = Fixture(detail(1 to s1))
        f.watch(1, 1)
        f.detector.refresh(src)
        f.source.detail = detail(1 to s1, 2 to s2)
        f.detector.refresh(src)
        val notice = assertNotNull(f.notice())
        assertEquals("e2-1", notice.episodeId)
        assertEquals(3, notice.count)
    }

    @Test
    fun watchingTheFlaggedEpisodeRollsTheSnapshotForward() = runTest(io) {
        val f = Fixture(detail(1 to s1))
        f.watch(1, 1)
        f.detector.refresh(src)
        f.source.detail = detail(1 to s1, 2 to s2)
        f.detector.refresh(src)
        f.watch(2, 1)
        f.detector.refresh(src)
        val notice = assertNotNull(f.notice())
        assertEquals("e2-2", notice.episodeId, "the next unseen episode takes the card")
        assertEquals(2, notice.count)
        assertEquals(EpisodeSnapshot(2, 1, 6), f.snapshot())
    }

    @Test
    fun catchingUpOnEveryNewEpisodeClearsTheNotice() = runTest(io) {
        val f = Fixture(detail(1 to s1))
        f.watch(1, 1)
        f.detector.refresh(src)
        f.source.detail = detail(1 to s1, 2 to s2)
        f.detector.refresh(src)
        f.watch(2, 1); f.watch(2, 2); f.watch(2, 3)
        f.detector.refresh(src)
        assertNull(f.notice())
        assertEquals(EpisodeSnapshot(2, 3, 6), f.snapshot())
    }

    @Test
    fun theDetectionTimeOfAnEpisodeStillWaitingDoesNotRestart() = runTest(io) {
        val f = Fixture(detail(1 to s1))
        f.watch(1, 1)
        f.detector.refresh(src)
        f.source.detail = detail(1 to s1, 2 to s2)
        f.detector.refresh(src)
        val first = assertNotNull(f.notice())
        now += 10L * 86_400_000L // 10 syncs' worth of time, still inside the 14-day window
        f.detector.refresh(src)
        assertEquals(first.detectedMs, assertNotNull(f.notice()).detectedMs, "the 14-day clock runs from the first detection")
    }

    @Test
    fun providerFailureKeepsTheNoticeTheProfileAlreadyHas() = runTest(io) {
        val f = Fixture(detail(1 to s1))
        f.watch(1, 1)
        f.detector.refresh(src)
        f.source.detail = detail(1 to s1, 2 to s2)
        f.detector.refresh(src)
        val before = assertNotNull(f.notice())
        f.source.fail = true
        f.detector.refresh(src)
        assertEquals(before, f.notice())
    }

    @Test
    fun episodesVanishingFromTheShowClearsTheNoticeAndRefreshesTheSnapshot() = runTest(io) {
        val f = Fixture(detail(1 to s1))
        f.watch(1, 1)
        f.detector.refresh(src)
        f.source.detail = detail(1 to s1, 2 to s2)
        f.detector.refresh(src)
        assertNotNull(f.notice())
        f.source.detail = detail(1 to listOf(ep(1, 1), ep(1, 2)))
        f.detector.refresh(src)
        assertNull(f.notice())
        assertEquals(EpisodeSnapshot(1, 2, 2), f.snapshot())
    }

    @Test
    fun showsOutsideTheWatchWindowThatAreNotInMyListAreNeverChecked() = runTest(io) {
        val f = Fixture(detail(1 to s1))
        f.watch(1, 1)
        now += NewEpisodes.WATCH_WINDOW_MS + 5_000 // the watch is now older than 60 days
        f.detector.refresh(src)
        assertEquals(0, f.source.calls, "a show the profile no longer watches must not be fetched")
        assertNull(f.snapshot())
    }

    @Test
    fun myListShowsAreCheckedEvenWithoutAnyProgress() = runTest(io) {
        val f = Fixture(detail(1 to s1))
        f.user.setFavorite(seriesKey, true)
        f.detector.refresh(src)
        assertEquals(EpisodeSnapshot(1, 3, 3), f.snapshot())
    }

    @Test
    fun snapshotsAndNoticesBelongToTheProfileThatMadeThem() = runTest(io) {
        val db = newDb()
        val a = Fixture(detail(1 to s1), profile = "a", db = db)
        val b = Fixture(detail(1 to s1), profile = "b", db = db)
        a.watch(1, 1)
        a.detector.refresh(src)
        a.source.detail = detail(1 to s1, 2 to s2)
        a.source.fail = false
        a.detector.refresh(src)
        assertNotNull(a.notice())
        // B watches the same show on the same DB but has only ever seen season 1.
        b.watch(1, 2)
        b.detector.refresh(src)
        assertNull(b.notice(), "A's new season is not news to a profile that has not synced it yet")
        assertEquals(EpisodeSnapshot(1, 3, 3), b.snapshot())
        b.source.detail = detail(1 to s1, 2 to s2)
        b.detector.refresh(src)
        assertEquals("e2-1", assertNotNull(b.notice()).episodeId)
    }

    @Test
    fun newEpisodesAfterSkipsEverythingTheSnapshotAlreadyCovers() {
        val ordered = s1 + s2
        assertEquals(emptyList(), newEpisodesAfter(ordered, null))
        assertEquals(emptyList(), newEpisodesAfter(ordered, EpisodeSnapshot(2, 3, 6)))
        assertEquals(listOf(ep(1, 3)) + s2, newEpisodesAfter(ordered, EpisodeSnapshot(1, 2, 5)))
        assertEquals(s2, newEpisodesAfter(ordered, EpisodeSnapshot(1, 3, 3)))
        // Renumbered/removed snapshot episode: the numbers decide, not the position.
        assertEquals(listOf(ep(2, 1)), newEpisodesAfter(listOf(ep(1, 1), ep(2, 1)), EpisodeSnapshot(1, 9, 9)))
    }

    @Test
    fun watchedShowKeysMixesRecentProgressWithMyList() {
        val episode = ContentKey(src, ContentKind.EPISODE, RemoteId("e1-1"))
        val old = Progress(episode, seriesKey.remoteId, 1_000L, 60_000L, updatedMs = 10L)
        val recent = Progress(episode, seriesKey.remoteId, 1_000L, 60_000L, updatedMs = 1_000L)
        val movie = ContentKey(src, ContentKind.VOD, RemoteId("m1"))
        val other = ContentKey(SourceId("other"), ContentKind.SERIES, RemoteId("s9"))
        val keys = watchedShowKeys(
            listOf(old, recent, Progress(movie, null, 1_000L, 60_000L, updatedMs = 2_000L)),
            listOf(movie, other, seriesKey),
            sinceMs = 1_000L,
            limit = 10,
            sourceId = src,
        )
        assertEquals(listOf(seriesKey), keys, "old progress drops, My List keeps the show, movies and other providers do not")
        assertEquals(emptyList(), watchedShowKeys(listOf(old), emptyList(), sinceMs = 1_000L, limit = 10))
        assertTrue(watchedShowKeys(emptyList(), listOf(other), sinceMs = 0L, limit = 10, sourceId = src).isEmpty(), "sourceId filters")
    }

    @Test
    fun oneNewEpisodeNamesItselfAndMoreOfThemJustCount() {
        assertEquals("New · S3 · E5", newEpisodesLabel(1, 3, 5))
        assertEquals("3 new episodes", newEpisodesLabel(3, 3, 5))
    }

    @Test
    fun recordsRoundTripAndGarbageReadsAsNothingStored() {
        val snapshot = EpisodeSnapshot(3, 5, 42)
        assertEquals(snapshot, EpisodeSnapshot.decode(snapshot.encode()))
        val notice = NewEpisodesNotice("e3-5", 3, 5, 2, 123L)
        assertEquals(notice, NewEpisodesNotice.decode(notice.encode()))
        assertNull(EpisodeSnapshot.decode(null))
        assertNull(EpisodeSnapshot.decode(""))
        assertNull(EpisodeSnapshot.decode("not json"))
        assertNull(NewEpisodesNotice.decode("not json"))
    }
}
