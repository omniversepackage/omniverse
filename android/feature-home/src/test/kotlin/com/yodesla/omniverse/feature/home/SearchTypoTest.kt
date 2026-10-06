package com.yodesla.omniverse.feature.home

import androidx.paging.PagingSource
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.SearchHit
import com.yodesla.omniverse.core.data.SearchRepository
import com.yodesla.omniverse.core.data.ShowEverything
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val typoSrc = SourceId("s")
private val typoKey = ContentKey(typoSrc, ContentKind.VOD, RemoteId("m1"))
private val typoPoster = PosterRow(typoKey, "The Matrix II", null, 1991, null, RemoteId("cat"))

@OptIn(ExperimentalCoroutinesApi::class)
class SearchTypoTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun resultIds(vm: SearchViewModel): List<String> =
        vm.state.value.rows.flatMap { row -> row.cards.map { it.open.remoteId.value } }

    @Test
    fun normalizedRetryRecoversATypoedTitle() = runTest(dispatcher) {
        // Only the normalized form ("matrix 2") is searchable; the raw query ("matrix ii") finds nothing.
        val vm = SearchViewModel(
            TypoFakeSearch(hitsFor = mapOf("matrix 2" to listOf(SearchHit(typoKey, "The Matrix II")))),
            TypoFakeCatalog(), ShowEverything,
        )
        vm.onQuery("matrix ii")
        advanceUntilIdle()
        assertEquals(listOf("m1"), resultIds(vm))
        assertNull(vm.state.value.didYouMean)
    }

    @Test
    fun didYouMeanOffersClosestVisibleTitle() = runTest(dispatcher) {
        val vm = SearchViewModel(
            TypoFakeSearch(hitsFor = emptyMap(), recent = listOf(typoPoster)),
            TypoFakeCatalog(), ShowEverything,
        )
        vm.onQuery("matrix 2")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), resultIds(vm))
        assertEquals("The Matrix II", vm.state.value.didYouMean)
    }

    @Test
    fun shortQueriesSkipTypoTolerance() = runTest(dispatcher) {
        val vm = SearchViewModel(
            TypoFakeSearch(hitsFor = emptyMap(), recent = listOf(typoPoster)),
            TypoFakeCatalog(), ShowEverything,
        )
        vm.onQuery("ma") // < 4 chars
        advanceUntilIdle()
        assertNull(vm.state.value.didYouMean)
    }
}

private class TypoFakeSearch(
    private val hitsFor: Map<String, List<SearchHit>>,
    private val recent: List<PosterRow> = emptyList(),
) : SearchRepository {
    override suspend fun search(query: String, limitPerKind: Int): Map<ContentKind, List<SearchHit>> {
        val hits = hitsFor[query] ?: return emptyMap()
        return mapOf(ContentKind.VOD to hits)
    }
    override suspend fun recentTitles(limit: Int): List<PosterRow> = recent
}

private class TypoFakeCatalog : CatalogRepository {
    override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> = TODO()
    override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = TODO()
    override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): PagingSource<Int, ChannelRow> = TODO()
    override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow> = emptyList()
    override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey> = emptyList()
    override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = TODO()
    override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = TODO()
    override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = TODO()
    override suspend fun channel(key: ContentKey): ChannelRow? = null
    override fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = TODO()
    override fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = TODO()
    override fun vodAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = TODO()
    override fun seriesAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = TODO()
    override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> = TODO()
    override suspend fun poster(key: ContentKey): PosterRow? = if (key == typoKey) typoPoster else null
    override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> = TODO()
}
