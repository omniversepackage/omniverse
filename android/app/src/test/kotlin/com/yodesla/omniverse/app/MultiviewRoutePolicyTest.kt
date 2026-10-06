package com.yodesla.omniverse.app

import androidx.paging.PagingSource
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.TimeWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * Task 61 / audit M1: Screen.Multiview used to fall through to `true` in routeIsVisible, so a
 * restricted channel group kept playing after the PIN session expired. The route survives only while
 * SOME channel passes the current LIVE visibility (a channel whose row is gone cannot be validated,
 * so it counts as denied); the view model removes the individual denied tiles.
 */
class MultiviewRoutePolicyTest {
    private val src = SourceId("s")
    private val general = RemoteId("general")
    private val adult = RemoteId("adult")

    private fun key(id: String) = ContentKey(src, ContentKind.LIVE, RemoteId(id))
    private val a = key("1")
    private val c = key("3")
    private val missing = key("9")

    private val rows = mapOf(
        a to ChannelRow(a, 1, "Ch 1", null, "e1", 0, general),
        c to ChannelRow(c, 3, "Ch 3", null, "e3", 0, adult),
    )
    private val catalog = FakeCatalog()

    private val allowAll: Visibility = { _, _, _ -> true }
    private val denyAdult: Visibility = { kind, _, cat -> !(kind == "LIVE" && cat == "adult") }
    private val denyAll: Visibility = { _, _, _ -> false }

    @Test
    fun everyChannelAllowedKeepsTheRouteOpen() = runBlocking {
        assertTrue(multiviewRouteAllowed(listOf(a, c), catalog, allowAll))
    }

    @Test
    fun oneDeniedTileDoesNotCloseTheRouteWhileAnotherIsAllowed() = runBlocking {
        assertTrue(multiviewRouteAllowed(listOf(a, c), catalog, denyAdult))
    }

    @Test
    fun everyChannelDeniedClosesTheRoute() = runBlocking {
        assertFalse(multiviewRouteAllowed(listOf(c), catalog, denyAdult))
        assertFalse(multiviewRouteAllowed(listOf(a, c), catalog, denyAll))
    }

    @Test
    fun aRemovedChannelCountsAsDenied() = runBlocking {
        assertFalse(multiviewRouteAllowed(listOf(missing), catalog, allowAll))
        assertFalse(multiviewRouteAllowed(listOf(missing, c), catalog, denyAdult))
        assertTrue(multiviewRouteAllowed(listOf(missing, a), catalog, denyAdult))
    }

    @Test
    fun anEmptyChannelListHasNothingToShow() = runBlocking {
        assertFalse(multiviewRouteAllowed(emptyList(), catalog, allowAll))
    }

    private inner class FakeCatalog : CatalogRepository {
        override fun categories(sourceId: SourceId, kind: ContentKind, includeHidden: Boolean): Flow<List<Category>> = flowOf(emptyList())
        override fun channels(sourceId: SourceId, categoryId: RemoteId): PagingSource<Int, ChannelRow> = error("unused")
        override fun channelsAll(sourceId: SourceId, excludedCategories: Collection<String>): PagingSource<Int, ChannelRow> = error("unused")
        override suspend fun channelListAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ChannelRow> = emptyList()
        override suspend fun channelOrderAll(sourceId: SourceId, excludedCategories: Collection<String>): List<ContentKey> = emptyList()
        override suspend fun channelList(sourceId: SourceId, categoryId: RemoteId): List<ChannelRow> = rows.values.toList()
        override suspend fun channelNeighbours(key: ContentKey, categoryId: RemoteId): Pair<ChannelRow?, ChannelRow?> = null to null
        override suspend fun channel(key: ContentKey): ChannelRow? = rows[key]
        override suspend fun channelOrder(sourceId: SourceId, categoryId: RemoteId): List<ContentKey> = rows.keys.toList()
        override fun vod(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = error("unused")
        override fun series(sourceId: SourceId, categoryId: RemoteId?): PagingSource<Int, PosterRow> = error("unused")
        override fun vodAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = error("unused")
        override fun seriesAll(excludedCategoryKeys: Collection<String>): PagingSource<Int, PosterRow> = error("unused")
        override fun recentlyAdded(kind: ContentKind, limit: Int): Flow<List<PosterRow>> = flowOf(emptyList())
        override suspend fun poster(key: ContentKey): PosterRow? = null
        override fun counts(sourceId: SourceId): Flow<Map<ContentKind, Long>> = flowOf(emptyMap())
    }
}
