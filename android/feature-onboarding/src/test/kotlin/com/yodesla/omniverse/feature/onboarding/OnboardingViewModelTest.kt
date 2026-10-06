package com.yodesla.omniverse.feature.onboarding

import com.yodesla.omniverse.core.brand.ExperienceMode
import com.yodesla.omniverse.core.brand.FeatureFlags
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.SourceSummary
import com.yodesla.omniverse.core.data.SyncEngine
import com.yodesla.omniverse.core.data.SyncProgress
import com.yodesla.omniverse.core.data.SyncScope
import com.yodesla.omniverse.core.data.SyncStage
import com.yodesla.omniverse.core.data.UserDataRepository
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
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val src = SourceId("test-id")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private data class Behavior(
        val accountResult: Result<AccountInfo> = Result.success(
            AccountInfo(AccountStatus.ACTIVE, expiresAtMs = 1_893_456_000_000L, maxConnections = 3, null, false, listOf("ts"), null, null),
        ),
        var syncFlow: Flow<SyncProgress> = emptyFlow(),
    )

    private lateinit var fake: Fake

    private fun vm(features: FeatureFlags = FeatureFlags(allowFullControlMode = false), behavior: Behavior = Behavior(), linker: PlexLinker? = null): OnboardingViewModel {
        fake = Fake(behavior)
        return OnboardingViewModel(fake, fake, fake, features, { "test-id" }, plexLinker = linker)
    }

    private fun fillForm(vm: OnboardingViewModel, server: String, user: String, pass: String) {
        vm.onServerChange(server)
        vm.onUsernameChange(user)
        vm.onPasswordChange(pass)
    }

    // ------------------------------------------------- welcome

    @Test
    fun modeChoiceStoresSettingAndAdvances() = runTest(dispatcher) {
        val vm = vm(features = FeatureFlags())
        assertEquals(Step.WELCOME, vm.state.value.step)

        vm.chooseMode(ExperienceMode.SIMPLE)
        advanceUntilIdle()
        assertEquals("simple", fake.settings["experience_mode"])
        assertEquals(Step.ADD_SOURCE, vm.state.value.step)
        assertEquals(ExperienceMode.SIMPLE, vm.state.value.mode)

        val vm2 = vm(features = FeatureFlags())
        vm2.chooseMode(ExperienceMode.FULL)
        advanceUntilIdle()
        assertEquals("full", fake.settings["experience_mode"])
        assertEquals(ExperienceMode.FULL, vm2.state.value.mode)
    }

    @Test
    fun fullControlDisabledSkipsWelcome() = runTest(dispatcher) {
        val vm = vm(features = FeatureFlags(allowFullControlMode = false))
        assertEquals(Step.ADD_SOURCE, vm.state.value.step)
        assertEquals(ExperienceMode.SIMPLE, vm.state.value.mode)
        assertEquals(null, fake.settings["experience_mode"])
    }

    // ------------------------------------------------- url normalization

    @Test
    fun normalizeAddsSchemeWhenMissing() {
        val x = UrlNormalize.parseXtream("example.com:8080", "u", "p")
        assertEquals("http://example.com:8080", x.server)
        assertEquals("http://example.com:8080", UrlNormalize.normalize("example.com:8080"))
    }

    @Test
    fun normalizeStripsApiSuffixAndLiftsCredentials() {
        val x = UrlNormalize.parseXtream("http://h:80/player_api.php?username=a&password=b", "", "")
        assertEquals("http://h:80", x.server)
        assertEquals("a", x.username)
        assertEquals("b", x.password)
    }

    @Test
    fun normalizeStripsTrailingSlash() {
        assertEquals("http://h:80", UrlNormalize.normalize("http://h:80/"))
        assertEquals("http://h:80", UrlNormalize.parseXtream("  http://h:80/  ", "u", "p").server)
    }

    @Test
    fun normalizeDoesNotOverwriteTypedCredentials() {
        val x = UrlNormalize.parseXtream("http://h/get.php?username=a&password=b", "typed-user", "typed-pass")
        assertEquals("typed-user", x.username)
        assertEquals("typed-pass", x.password)
    }

    @Test
    fun validationReportsMissingFields() {
        val vm = vm()
        fillForm(vm, "", "u", "p")
        assertEquals(R.string.onboarding_err_server, vm.validate())
        fillForm(vm, "ftp://h", "u", "p")
        assertEquals(R.string.onboarding_err_scheme, vm.validate())
        fillForm(vm, "http://h", "", "p")
        assertEquals(R.string.onboarding_err_username, vm.validate())
        fillForm(vm, "http://h", "u", "")
        assertEquals(R.string.onboarding_err_password, vm.validate())
        fillForm(vm, "http://h", "u", "p")
        assertEquals(null, vm.validate())
    }

    @Test
    fun m3uTabOnlyWhenAllowed() {
        val vm = vm(features = FeatureFlags(allowM3u = false))
        vm.setTab(SourceTab.M3U)
        assertEquals(SourceTab.XTREAM, vm.state.value.form.tab)
        val vm2 = vm()
        vm2.setTab(SourceTab.M3U)
        assertEquals(SourceTab.M3U, vm2.state.value.form.tab)
    }

    // ------------------------------------------------- validating

    @Test
    fun authFailedRemovesSourceAndKeepsFields() = runTest(dispatcher) {
        val vm = vm(behavior = Behavior(accountResult = Result.failure(SourceException.AuthFailed())))
        fillForm(vm, "http://h:80", "user", "pass")
        vm.submit()
        advanceUntilIdle()

        assertTrue(fake.added.isEmpty(), "a rejected login is never saved")
        assertEquals(Step.ADD_SOURCE, vm.state.value.step)
        assertEquals(R.string.onboarding_err_auth, vm.state.value.error)
        assertEquals("user", vm.state.value.form.username)
        assertEquals("pass", vm.state.value.form.password)
    }

    @Test
    fun networkErrorShowsNetworkMessage() = runTest(dispatcher) {
        val vm = vm(behavior = Behavior(accountResult = Result.failure(SourceException.Network("dns"))))
        fillForm(vm, "http://h:80", "user", "pass")
        vm.submit()
        advanceUntilIdle()

        assertTrue(fake.added.isEmpty())
        assertEquals(R.string.onboarding_err_network, vm.state.value.error)
    }

    @Test
    fun httpErrorShowsHttpMessage() = runTest(dispatcher) {
        val vm = vm(behavior = Behavior(accountResult = Result.failure(SourceException.Http(404, "nf"))))
        fillForm(vm, "http://h:80", "user", "pass")
        vm.submit()
        advanceUntilIdle()

        assertTrue(fake.added.isEmpty())
        assertEquals(R.string.onboarding_err_http, vm.state.value.error)
    }

    @Test
    fun invalidFormDoesNotAddSource() = runTest(dispatcher) {
        val vm = vm()
        fillForm(vm, "", "", "")
        vm.submit()
        advanceUntilIdle()

        assertEquals(Step.ADD_SOURCE, vm.state.value.step)
        assertEquals(R.string.onboarding_err_server, vm.state.value.error)
        assertEquals(0, fake.added.size)
    }

    // ------------------------------------------------- syncing

    @Test
    fun successGoesToSyncingThenDone() = runTest(dispatcher) {
        val events = MutableSharedFlow<SyncProgress>(extraBufferCapacity = 16)
        val vm = vm(behavior = Behavior(syncFlow = events))
        fillForm(vm, "http://h:80", "user", "pass")
        vm.submit()
        advanceUntilIdle()

        assertEquals(Step.SYNCING, vm.state.value.step)
        assertEquals(AccountUi(1_893_456_000_000L, 3), vm.state.value.account)

        events.emit(SyncProgress(src, SyncStage.LIVE_CHANNELS, 12_480))
        advanceUntilIdle()
        assertEquals(R.string.onboarding_stage_channels, vm.state.value.progress?.stageText)
        assertEquals(12_480, vm.state.value.progress?.done)
        assertFalse(vm.state.value.progress?.canStartWatching == true)

        events.emit(SyncProgress(src, SyncStage.EPG, 0))
        advanceUntilIdle()
        assertEquals(R.string.onboarding_stage_guide, vm.state.value.progress?.stageText)
        assertTrue(vm.state.value.progress?.canStartWatching == true)

        vm.startWatching()
        assertEquals(Step.DONE, vm.state.value.step)
    }

    @Test
    fun finishedEventAutoAdvancesToDone() = runTest(dispatcher) {
        val events = MutableSharedFlow<SyncProgress>(extraBufferCapacity = 16)
        val vm = vm(behavior = Behavior(syncFlow = events))
        fillForm(vm, "http://h:80", "user", "pass")
        vm.submit()
        advanceUntilIdle()

        events.emit(SyncProgress(src, SyncStage.LIVE_CHANNELS, 5))
        events.emit(SyncProgress(src, SyncStage.FINALIZE, 0, finished = true))
        advanceUntilIdle()
        assertEquals(Step.DONE, vm.state.value.step)
    }

    @Test
    fun stageErrorShowsRetryLaterNote() = runTest(dispatcher) {
        val events = MutableSharedFlow<SyncProgress>(extraBufferCapacity = 16)
        val vm = vm(behavior = Behavior(syncFlow = events))
        fillForm(vm, "http://h:80", "user", "pass")
        vm.submit()
        advanceUntilIdle()

        events.emit(SyncProgress(src, SyncStage.VOD, 0, error = SourceException.Http(503, "busy")))
        advanceUntilIdle()
        assertEquals(R.string.onboarding_note_retry_later, vm.state.value.progress?.note)
        assertEquals(Step.SYNCING, vm.state.value.step)

        events.emit(SyncProgress(src, SyncStage.FINALIZE, 0, finished = true))
        advanceUntilIdle()
        assertEquals(Step.DONE, vm.state.value.step)
    }

    @Test
    fun fatalSyncErrorShowsErrorCardAndTryAgain() = runTest(dispatcher) {
        val behavior = Behavior(
            syncFlow = flow {
                emit(SyncProgress(src, SyncStage.LIVE_CHANNELS, 5))
                throw SourceException.AuthFailed()
            },
        )
        val vm = vm(behavior = behavior)
        fillForm(vm, "http://h:80", "user", "pass")
        vm.submit()
        advanceUntilIdle()

        assertEquals(Step.SYNCING, vm.state.value.step)
        assertEquals(R.string.onboarding_err_auth, vm.state.value.error)

        vm.retryFromSync()
        advanceUntilIdle()
        assertEquals(Step.ADD_SOURCE, vm.state.value.step)
        assertEquals(null, vm.state.value.error)
        assertEquals("user", vm.state.value.form.username)
    }

    @Test
    fun authZeroStatusIsRejectedBeforeSync() = runTest(dispatcher) {
        // Real Xtream panels answer a bad login with HTTP 200 + auth=0, i.e. a status, not an exception.
        val rejected = AccountInfo(AccountStatus.AUTH_FAILED, null, null, null, false, emptyList(), null, null)
        val vm = vm(behavior = Behavior(accountResult = Result.success(rejected)))
        fillForm(vm, "http://h:80", "user", "wrong")
        vm.submit()
        advanceUntilIdle()

        assertEquals(Step.ADD_SOURCE, vm.state.value.step)
        assertEquals(R.string.onboarding_err_auth, vm.state.value.error)
        assertTrue(fake.added.isEmpty())
    }

    @Test
    fun fatalFinishedEventNeverReachesDone() = runTest(dispatcher) {
        // The real engine reports a fatal stage as an event with finished=true (it doesn't throw).
        val behavior = Behavior(syncFlow = flowOf(SyncProgress(src, SyncStage.ACCOUNT, 0, error = SourceException.Expired(), finished = true)))
        val vm = vm(behavior = behavior)
        fillForm(vm, "http://h:80", "user", "pass")
        vm.submit()
        advanceUntilIdle()

        assertEquals(Step.ADD_SOURCE, vm.state.value.step)
        assertEquals(R.string.onboarding_err_expired, vm.state.value.error)
        assertTrue(src in fake.removed)
    }

    @Test
    fun m3uSubmitBuildsM3uConfig() = runTest(dispatcher) {
        val events = MutableSharedFlow<SyncProgress>(extraBufferCapacity = 16)
        val vm = vm(behavior = Behavior(syncFlow = events))
        vm.setTab(SourceTab.M3U)
        vm.onPlaylistChange("h.example/pl.m3u/")
        vm.onEpgChange("")
        vm.submit()
        advanceUntilIdle()

        val config = fake.added.single()
        assertTrue(config is SourceConfig.M3u)
        assertEquals("http://h.example/pl.m3u", (config as SourceConfig.M3u).playlistUrl)
        assertEquals(null, config.epgUrl)
        assertEquals("h.example", config.displayName)
        assertEquals(Step.SYNCING, vm.state.value.step)
    }

    @Test
    fun plexLinkAddsEverySelectedSharedServer() = runTest(dispatcher) {
        val choices = listOf(
            PlexServerChoice("Kory", SourceConfig.Plex(SourceId("plex-own"), "Kory", "http://own.test", "token", "own", "client")),
            PlexServerChoice("Friend", SourceConfig.Plex(SourceId("plex-friend"), "Friend", "http://friend.test", "token", "friend", "client")),
        )
        val linker = object : PlexLinker {
            override suspend fun createCode() = "ABCD"
            override suspend fun poll() = choices
        }
        val vm = vm(linker = linker)
        vm.setTab(SourceTab.PLEX)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.plex.servers.size)
        choices.forEach(vm::togglePlexServer)
        vm.addSelectedPlexServers()
        advanceUntilIdle()
        assertEquals(choices.map { it.config.id }, fake.added.map { it.id })
        assertEquals(2, vm.state.value.plex.addedCount)
        assertEquals(Step.DONE, vm.state.value.step)
    }

    // ---------------- fakes ----------------

    private class Fake(private val behavior: Behavior) : SourceRepository, SyncEngine, UserDataRepository {
        val added = mutableListOf<SourceConfig>()
        val removed = mutableListOf<SourceId>()
        val settings = mutableMapOf<String, String>()

        private val source = object : ContentSource {
            override val id: SourceId = SourceId("test-id")
            override val kind = SourceKind.XTREAM
            override val capabilities = setOf(Capability.LIVE)
            override suspend fun accountInfo(): AccountInfo = behavior.accountResult.getOrThrow()
            override fun liveCategories(): Flow<Category> = emptyFlow()
            override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> = emptyFlow()
            override fun vodCategories(): Flow<Category> = emptyFlow()
            override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> = emptyFlow()
            override fun seriesCategories(): Flow<Category> = emptyFlow()
            override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> = emptyFlow()
            override suspend fun vodDetail(id: RemoteId): VodDetail = error("unused")
            override suspend fun seriesDetail(id: RemoteId): SeriesDetail = error("unused")
            override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = emptyFlow()
            override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()
            override suspend fun playback(request: PlaybackRequest): PlaybackSpec = error("unused")
        }

        override fun sources(): Flow<List<SourceSummary>> = flowOf(added.map {
            SourceSummary(it.id, when (it) {
                is SourceConfig.Plex -> SourceKind.PLEX
                is SourceConfig.M3u -> SourceKind.M3U
                is SourceConfig.Xtream -> SourceKind.XTREAM
            }, it.displayName, null, null, null)
        })
        override suspend fun add(config: SourceConfig): SourceId {
            added += config
            return config.id
        }
        override suspend fun update(config: SourceConfig) = Unit
        override suspend fun remove(id: SourceId) {
            removed += id
        }
        override suspend fun config(id: SourceId): SourceConfig? = added.lastOrNull { it.id == id }
        override suspend fun probe(config: SourceConfig): AccountInfo = source.accountInfo()
        override suspend fun contentSource(id: SourceId): ContentSource = source

        override fun sync(sourceId: SourceId, scope: SyncScope, force: Boolean): Flow<SyncProgress> = behavior.syncFlow
        override suspend fun isStale(sourceId: SourceId, scope: SyncScope): Boolean = false

        override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
        override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
        override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
        override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = Unit
        override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
        override suspend fun progress(key: ContentKey): Progress? = null
        override suspend fun recordChannelWatched(key: ContentKey) = Unit
        override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
        override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
        override fun setting(key: String): Flow<String?> = flowOf(settings[key])
        override suspend fun putSetting(key: String, value: String) {
            settings[key] = value
        }
    }
}
