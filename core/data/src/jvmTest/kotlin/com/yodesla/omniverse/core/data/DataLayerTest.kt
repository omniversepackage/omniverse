package com.yodesla.omniverse.core.data

import androidx.paging.PagingSource
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.CatalogRepositoryImpl
import com.yodesla.omniverse.core.data.impl.EpgRepositoryImpl
import com.yodesla.omniverse.core.data.impl.SearchRepositoryImpl
import com.yodesla.omniverse.core.data.impl.SourceRepositoryImpl
import com.yodesla.omniverse.core.data.impl.SyncEngineImpl
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.model.Capability
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ChannelRecord
import com.yodesla.omniverse.core.model.ContentKey
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
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.core.source.SourceFactory
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataLayerTest {
    private val io = UnconfinedTestDispatcher()
    private var now = 1_790_424_000_000L // 2026-09-26 12:00 UTC
    private val clock = Clock { now }
    private val fake = FakeSource()

    private val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
    private val sources = SourceRepositoryImpl(db, listOf(SourceFactory { fake }), io, newId = { "src1" })
    private val sync = SyncEngineImpl(db, sources, clock, SyncPolicy(batchSize = 1_000, stageRetryDelayMs = 0), io)
    private val catalog = CatalogRepositoryImpl(db, io)
    private val epg = EpgRepositoryImpl(db, io)
    private val search = SearchRepositoryImpl(db, io)
    private val user = UserDataRepositoryImpl(db, io, clock)

    private suspend fun addSource(): SourceId =
        sources.add(SourceConfig.Xtream(SourceId(""), "Test", "http://h", "u", "p"))

    @Test
    fun fullSyncWritesEverythingInBatchesAndIsSearchable() = runTest(io) {
        val id = addSource()
        val events = sync.sync(id, force = true).toList()
        assertTrue(events.last().finished)
        assertTrue(events.none { it.error != null }, "errors: ${events.mapNotNull { it.error }}")
        // 5,000 channels at batchSize 1,000 → at least 5 LIVE_CHANNELS progress events with done > 0.
        val liveEvents = events.filter { it.stage == SyncStage.LIVE_CHANNELS }.map { it.done }
        assertTrue(liveEvents.count { it > 0 } >= 5, "LIVE_CHANNELS progress: $liveEvents")
        // Stages arrive in state-machine order.
        val order = events.map { it.stage }.distinct()
        assertEquals(order.sortedBy { it.ordinal }, order)

        val counts = catalog.counts(id).first()
        assertEquals(5_000L, counts[ContentKind.LIVE])
        assertEquals(3_000L, counts[ContentKind.VOD])
        assertEquals(200L, counts[ContentKind.SERIES])

        val hits = search.search("news 12")
        val searchRows = db.readQueries.searchByKind("news*", "LIVE", 5).executeAsList()
        assertTrue(hits[ContentKind.LIVE].orEmpty().any { it.title == "News 12" }, "hits=$hits raw=$searchRows")
        search.search("news\" OR \"x NEAR(") // must not throw
    }

    @Test
    fun resyncMarksDroppedChannelsRemovedButKeepsFavorites() = runTest(io) {
        val id = addSource()
        sync.sync(id, force = true).toList()
        val dropped = ContentKey(id, ContentKind.LIVE, RemoteId("3"))
        user.setFavorite(dropped, true)

        fake.channelCount = 4_990 // provider drops channels 4990..4999
        now += 60_000
        sync.sync(id, force = true).toList()
        assertEquals(4_990L, catalog.counts(id).first()[ContentKind.LIVE])
        // Favorite rows are never touched by sync.
        assertEquals(listOf(dropped), user.favorites(ContentKind.LIVE).first())
    }

    @Test
    fun failedStageNeverDeletesAndKeepsItsTimestamp() = runTest(io) {
        val id = addSource()
        sync.sync(id, force = true).toList()
        val before = db.storeQueries.sourceById(id.value).executeAsOne()

        fake.failVod = true
        fake.vodCount = 10 // would drop 2,990 items if the failed stage were allowed to mark removals
        now += 60_000
        val events = sync.sync(id, force = true).toList()
        assertTrue(events.any { it.stage == SyncStage.VOD && it.error is SourceException.Network })
        assertEquals(3, fake.vodAttempts, "VOD stage retried twice before giving up")
        assertTrue(events.any { it.stage == SyncStage.SERIES && it.done > 0 }, "series still ran")
        assertEquals(3_000L, catalog.counts(id).first()[ContentKind.VOD])
        val after = db.storeQueries.sourceById(id.value).executeAsOne()
        assertEquals(before.last_sync_vod_ms, after.last_sync_vod_ms)
        assertEquals(now, after.last_sync_live_ms)
    }

    @Test
    fun authFailureIsFatalAndWritesNothing() = runTest(io) {
        val id = addSource()
        fake.authFails = true
        val events = sync.sync(id, force = true).toList()
        assertEquals(1, events.count { it.error != null })
        assertTrue(events.last().finished)
        assertEquals(null, catalog.counts(id).first()[ContentKind.LIVE]?.takeIf { it > 0 })
    }

    // ---- Audit 2026-09-27 regressions ----

    @Test
    fun emptyListNeverWipesARealCatalog() = runTest(io) {
        val id = addSource()
        sync.sync(id, force = true).toList()
        fake.vodCount = 0 // provider hiccup: a "successful" empty list
        now += 60_000
        val events = sync.sync(id, force = true).toList()
        assertEquals(3_000L, catalog.counts(id).first()[ContentKind.VOD], "kept, not marked removed")
        assertTrue(events.any { it.stage == SyncStage.VOD && it.error is SourceException.BadResponse }, "and reported")
    }

    @Test
    fun ninetyPercentCollapseIsTreatedAsAHiccup() = runTest(io) {
        val id = addSource()
        sync.sync(id, force = true).toList()
        fake.channelCount = 100 // 5,000 -> 100
        now += 60_000
        sync.sync(id, force = true).toList()
        assertEquals(5_000L, catalog.counts(id).first()[ContentKind.LIVE])
    }

    @Test
    fun shiftedScheduleLeavesNoGhostProgrammes() = runTest(io) {
        val id = addSource()
        sync.sync(id, force = true).toList()
        now += 15 * 60_000 // the provider's slots now start 15 min later
        sync.sync(id, force = true).toList()
        val list = epg.programmes(id, "e1", TimeWindow(now - 3_600_000L, now + 5 * 3_600_000L))
        for (i in 1 until list.size) {
            assertTrue(list[i].startMs >= list[i - 1].endMs, "overlap left behind: ${list[i - 1]} / ${list[i]}")
        }
    }

    @Test
    fun guideIsScopedToItsSource() = runTest(io) {
        val t = now
        for ((src, title) in listOf("a" to "From A", "b" to "From B")) {
            db.storeQueries.upsertProgramme(src, "bbc1.uk", t, t + 3_600_000L, title, null, null, null, null, null, 0)
        }
        assertEquals("From A", epg.nowNext(SourceId("a"), listOf("bbc1.uk"), t + 1)["bbc1.uk"]?.now?.title)
        assertEquals("From B", epg.programmes(SourceId("b"), "bbc1.uk", TimeWindow(t, t + 1)).single().title)
    }

    /** Reversible stand-in for the Keystore cipher: good enough to prove nothing is stored in clear. */
    private val fakeBox = object : SecretBox {
        override fun seal(plain: String) = SecretBox.PREFIX + plain.reversed()
        override fun open(sealed: String) = if (sealed.endsWith("#broken")) null else sealed.removePrefix(SecretBox.PREFIX).reversed()
    }

    @Test
    fun providerPasswordsAreSealedAtRest() = runTest(io) {
        val repo = SourceRepositoryImpl(db, listOf(SourceFactory { fake }), io, newId = { "sealed1" }, secrets = fakeBox)
        val id = repo.add(SourceConfig.Xtream(SourceId(""), "T", "http://h", "user", "hunter2secret"))
        val stored = db.storeQueries.sourceById(id.value).executeAsOne().config_json
        assertTrue(stored.startsWith(SecretBox.PREFIX) && "hunter2secret" !in stored, "stored in clear: $stored")
        assertEquals("hunter2secret", (repo.config(id) as SourceConfig.Xtream).password)
    }

    @Test
    fun legacyPlaintextConfigLoadsAndIsResealed() = runTest(io) {
        val plainRepo = SourceRepositoryImpl(db, listOf(SourceFactory { fake }), io, newId = { "legacy1" })
        val id = plainRepo.add(SourceConfig.Xtream(SourceId(""), "Old", "http://h", "user", "hunter2secret"))
        val repo = SourceRepositoryImpl(db, listOf(SourceFactory { fake }), io, newId = { "x" }, secrets = fakeBox)
        assertEquals("hunter2secret", (repo.config(id) as SourceConfig.Xtream).password)
        assertTrue(db.storeQueries.sourceById(id.value).executeAsOne().config_json.startsWith(SecretBox.PREFIX))
    }

    @Test
    fun undecryptableConfigIsNullNotACrash() = runTest(io) {
        db.catalogQueries.upsertSource("bad", "XTREAM", "Bad", SecretBox.PREFIX + "#broken", 0, null, null, null, null, 0)
        val repo = SourceRepositoryImpl(db, listOf(SourceFactory { fake }), io, newId = { "x" }, secrets = fakeBox)
        assertNull(repo.config(SourceId("bad")))
    }

    @Test
    fun staleSkipAndForce() = runTest(io) {
        val id = addSource()
        sync.sync(id, force = true).toList()
        now += 60_000
        assertTrue(!sync.isStale(id))
        val events = sync.sync(id).toList()
        assertTrue(events.none { it.stage == SyncStage.LIVE_CHANNELS }, "fresh live data is skipped")
        now += 13 * 3_600_000L
        assertTrue(sync.isStale(id, SyncScope.LIVE_AND_EPG))
    }

    @Test
    fun nowNextPagingAndNeighbours() = runTest(io) {
        val id = addSource()
        sync.sync(id, force = true).toList()
        val nn = epg.nowNext(id, listOf("e1", "e2"), now + 10 * 60_000)
        assertEquals("Show on e1 at 0", nn["e1"]?.now?.title)
        assertEquals("Show on e1 at 1", nn["e1"]?.next?.title)

        val page = catalog.channels(id, RemoteId("cat0")).load(PagingSource.LoadParams.Refresh(null, 60, false))
        page as PagingSource.LoadResult.Page
        assertEquals(60, page.data.size)
        assertNotNull(page.nextKey)

        val first = page.data.first().key
        val (prev, next) = catalog.channelNeighbours(first, RemoteId("cat0"))
        assertNotNull(prev)
        assertEquals(page.data[1].key, next?.key)
        assertNull(catalog.channel(ContentKey(id, ContentKind.LIVE, RemoteId("nope"))))
    }

    @Test
    fun userDataBasics() = runTest(io) {
        val k = ContentKey(SourceId("s"), ContentKind.VOD, RemoteId("m1"))
        user.saveProgress(k, null, 50_000, 100_000)
        assertEquals(1, user.continueWatching().first().size)
        user.saveProgress(k, null, 95_000, 100_000) // ≥ 92% → completed
        assertEquals(0, user.continueWatching().first().size)
        user.putSetting("experience_mode", "full")
        assertEquals("full", user.setting("experience_mode").first())
    }

    /** Scriptable in-memory provider. */
    private inner class FakeSource : ContentSource {
        var channelCount = 5_000
        var vodCount = 3_000
        var failVod = false
        var vodAttempts = 0
        var authFails = false
        override val id = SourceId("src1")
        override val kind = SourceKind.XTREAM
        override val capabilities = setOf(Capability.LIVE, Capability.EPG, Capability.VOD, Capability.SERIES)

        override suspend fun accountInfo() = AccountInfo(
            if (authFails) AccountStatus.AUTH_FAILED else AccountStatus.ACTIVE,
            null, 2, 0, false, listOf("ts"), "UTC", now,
        )

        private fun cats(kind: ContentKind) = flow {
            repeat(5) { emit(Category(id, kind, RemoteId("cat$it"), "Cat $it", null, it)) }
        }

        override fun liveCategories() = cats(ContentKind.LIVE)
        override fun vodCategories() = cats(ContentKind.VOD)
        override fun seriesCategories() = cats(ContentKind.SERIES)

        override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> = flow {
            repeat(channelCount) { i ->
                emit(ChannelRecord(id, RemoteId("$i"), i + 1, "News $i", null, "e$i", listOf(RemoteId("cat${i % 5}")), 0, null, i))
            }
        }

        override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> = flow {
            repeat(vodCount) { i ->
                if (failVod && i == 5) { vodAttempts++; throw SourceException.Network("boom") }
                emit(VodRecord(id, RemoteId("v$i"), "Movie $i", null, listOf(RemoteId("cat${i % 5}")), 7f, 2020, now - i, "mp4", null, i))
            }
        }

        override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> = flow {
            repeat(200) { i ->
                emit(SeriesRecord(id, RemoteId("s$i"), "Show $i", null, emptyList(), listOf(RemoteId("cat0")), null, null, null, null, null, i))
            }
        }

        override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = flow {
            for (k in listOf("e1", "e2")) {
                repeat(4) { n ->
                    val s = now + n * 3_600_000L
                    emit(ProgrammeRecord(id, k, s, s + 3_600_000L, "Show on $k at $n"))
                }
            }
        }

        override suspend fun vodDetail(id: RemoteId): VodDetail = error("unused")
        override suspend fun seriesDetail(id: RemoteId): SeriesDetail = error("unused")
        override suspend fun shortEpg(channelId: RemoteId, limit: Int) = emptyList<ProgrammeRecord>()
        override suspend fun playback(request: PlaybackRequest): PlaybackSpec = error("unused")
    }
}
