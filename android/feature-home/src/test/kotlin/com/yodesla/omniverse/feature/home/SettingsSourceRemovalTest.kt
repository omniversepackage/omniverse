package com.yodesla.omniverse.feature.home

import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.SourceSummary
import com.yodesla.omniverse.core.data.SyncEngine
import com.yodesla.omniverse.core.data.SyncProgress
import com.yodesla.omniverse.core.data.SyncScope
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.source.SourceConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Task 101: Settings > Sources > Remove. The confirmation (Keep it / Yes, remove) is the safeguard
 * that stops a single mis-press deleting a provider, so the view-model contract behind it is locked
 * here: arming never deletes, cancelling keeps the source, and confirming deletes exactly once and
 * dismisses itself. The D-pad focus fix lives in the pane (SettingsPanes.sourcesRows) and is covered
 * by manual TV verification; these JVM tests guard the state machine the pane drives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSourceRemovalTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun summary(id: String) = SourceSummary(SourceId(id), SourceKind.PLEX, "Source $id", null, null, null)

    private fun TestScope.vm(initial: List<SourceSummary>): Pair<SettingsViewModel, FakeSourceRepository> {
        val repo = FakeSourceRepository(initial)
        val model = SettingsViewModel(repo, FakeSyncEngine(), FakeUserDataRepository(), Clock { 0L })
        advanceUntilIdle()
        return model to repo
    }

    @Test
    fun askRemoveArmsTheConfirmationWithoutDeleting() = runTest(dispatcher) {
        val s = summary("s1")
        val (model, repo) = vm(listOf(s))

        assertNull(model.state.value.confirmRemove)
        model.askRemove(s.id)

        assertEquals(s.id, model.state.value.confirmRemove)
        assertTrue(repo.removed.isEmpty())
        assertEquals(listOf(s.id), model.state.value.sources.map { it.summary.id })
    }

    @Test
    fun keepItCancelsAndKeepsTheSource() = runTest(dispatcher) {
        val s = summary("s1")
        val (model, repo) = vm(listOf(s))

        model.askRemove(s.id)
        model.askRemove(null)

        assertNull(model.state.value.confirmRemove)
        assertTrue(repo.removed.isEmpty())
        assertEquals(listOf(s.id), model.state.value.sources.map { it.summary.id })
    }

    @Test
    fun yesRemoveDeletesOnceAndDismissesTheConfirmation() = runTest(dispatcher) {
        val s = summary("s1")
        val (model, repo) = vm(listOf(s))

        model.askRemove(s.id)
        model.remove(s.id)
        advanceUntilIdle()

        assertEquals(listOf(s.id), repo.removed)
        assertNull(model.state.value.confirmRemove)
        assertTrue(model.state.value.sources.none { it.summary.id == s.id })
    }

    @Test
    fun removingOneSourceLeavesTheOthersIntact() = runTest(dispatcher) {
        val a = summary("s1")
        val b = summary("s2")
        val (model, repo) = vm(listOf(a, b))

        model.askRemove(a.id)
        model.remove(a.id)
        advanceUntilIdle()

        assertEquals(listOf(a.id), repo.removed)
        assertEquals(listOf(b.id), model.state.value.sources.map { it.summary.id })
    }
}

private class FakeSourceRepository(initial: List<SourceSummary>) : SourceRepository {
    private val flow = MutableStateFlow(initial)
    val removed = mutableListOf<SourceId>()

    override fun sources(): Flow<List<SourceSummary>> = flow
    override suspend fun add(config: SourceConfig): SourceId = error("unused")
    override suspend fun update(config: SourceConfig): Unit = error("unused")
    override suspend fun remove(id: SourceId) {
        removed += id
        flow.value = flow.value.filterNot { it.id == id }
    }
    override suspend fun config(id: SourceId): SourceConfig? = null
    override suspend fun contentSource(id: SourceId): ContentSource? = null
    override suspend fun probe(config: SourceConfig): AccountInfo = error("unused")
}

private class FakeSyncEngine : SyncEngine {
    override fun sync(sourceId: SourceId, scope: SyncScope, force: Boolean): Flow<SyncProgress> = flowOf()
    override suspend fun isStale(sourceId: SourceId, scope: SyncScope): Boolean = false
}

private class FakeUserDataRepository : UserDataRepository {
    override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
    override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
    override suspend fun setFavorite(key: ContentKey, favorite: Boolean) {}
    override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) {}
    override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
    override suspend fun progress(key: ContentKey): Progress? = null
    override suspend fun recordChannelWatched(key: ContentKey) {}
    override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
    override suspend fun setHidden(key: ContentKey, hidden: Boolean) {}
    override fun setting(key: String): Flow<String?> = flowOf(null)
    override suspend fun putSetting(key: String, value: String) {}
}
