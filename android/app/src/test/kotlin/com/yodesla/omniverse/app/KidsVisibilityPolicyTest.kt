package com.yodesla.omniverse.app

import androidx.paging.PagingSource
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * Audit 73 M1+M2: Live / Guide / Multiview are wired to graph.vodVisibility — parental PIN AND the
 * Kids hard filter — the exact object the route gate uses. These tests pin the semantics of that
 * shared policy: on a Kids profile a locked category stays denied even during a PIN-unlocked
 * session (the unlock belongs to the parental policy, never to Kids), and the gate judges a
 * Multiview route by the same rule the tiles will be judged by.
 */
class KidsVisibilityPolicyTest {
    private val locked = setOf("LIVE|s|adult")
    private val kids = kidsHardFilter(isKids = true, lockedKeys = locked)

    private fun key(id: String) = ContentKey(SourceId("s"), ContentKind.LIVE, RemoteId(id))
    private val generalChannel = key("1")
    private val adultChannel = key("3")

    @Test
    fun kidsHardFilterDeniesLockedCategoriesWithNoUnlockOffered() {
        assertFalse(kids("LIVE", "s", "adult"), "a Kids profile never sees a locked category, PIN session or not")
        assertTrue(kids("LIVE", "s", "general"))
        assertTrue(
            kidsHardFilter(isKids = false, lockedKeys = locked)("LIVE", "s", "adult"),
            "an adult profile keeps the parental policy alone — the Kids filter never locks for adults",
        )
    }

    @Test
    fun theGateRefusesAMultiviewRouteWhoseChannelsAreAllLockedForKids() = runBlocking {
        assertFalse(multiviewRouteAllowed(listOf(adultChannel), FakeCatalog(), kids))
        assertTrue(multiviewRouteAllowed(listOf(adultChannel, generalChannel), FakeCatalog(), kids))
    }

    @Test
    fun theMultiviewEntryIsHiddenWhileAKidsBlockIsUp() {
        assertTrue(multiviewEntryAllowed(kidsBlocked = false))
        assertFalse(multiviewEntryAllowed(kidsBlocked = true))
    }

    private inner class FakeCatalog : CatalogRepository {
        private val rows = mapOf(
            generalChannel to ChannelRow(generalChannel, 1, "Ch 1", null, "e1", 0, RemoteId("general")),
            adultChannel to ChannelRow(adultChannel, 3, "Ch 3", null, "e3", 0, RemoteId("adult")),
        )
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
