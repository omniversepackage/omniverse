package com.yodesla.omniverse.feature.live.multiview

import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

/**
 * Task 67: saved Multiview groups persist as a JSON list under `multiview_groups` — save/load,
 * rename, delete, default naming, and a corrupt value decoding to empty instead of throwing.
 */
class MultiviewGroupStoreTest {
    private val settings = MutableStateFlow<Map<String, String>>(emptyMap())
    private val store = MultiviewGroupStore(SettingsOnly())

    private val s1 = SourceId("src1")
    private val s2 = SourceId("src2")
    private fun key(src: SourceId, id: String) = ContentKey(src, ContentKind.LIVE, RemoteId(id))
    private val a = key(s1, "1")
    private val b = key(s1, "2")
    private val c = key(s2, "3")

    @Test fun saveThenLoadRoundTrips() = runTest {
        store.save("Game night", listOf(a, b, c))
        val groups = store.groups.first()
        assertEquals(1, groups.size)
        assertEquals("Game night", groups.first().name)
        assertEquals(listOf(a, b, c), groups.first().channels)
    }

    @Test fun blankNameGetsGroup1ThenGroup2() = runTest {
        assertEquals("Group 1", store.save("   ", listOf(a, b)).name)
        assertEquals("Group 2", store.save("", listOf(c)).name)
    }

    @Test fun suggestedNameSkipsNamesAlreadyTaken() = runTest {
        store.save("Group 1", listOf(a, b))
        store.save("Game night", listOf(c))
        assertEquals("Group 2", store.suggestedName())
    }

    @Test fun renameKeepsChannelsAndOnlyChangesTheName() = runTest {
        store.save("A", listOf(a, b))
        store.save("B", listOf(c))
        store.rename(0, "Night")
        val groups = store.groups.first()
        assertEquals(listOf("Night", "B"), groups.map { it.name })
        assertEquals(listOf(a, b), groups[0].channels)
        assertEquals(listOf(c), groups[1].channels)
    }

    @Test fun deleteRemovesJustThatGroup() = runTest {
        store.save("A", listOf(a, b))
        store.save("B", listOf(c))
        store.delete(0)
        val groups = store.groups.first()
        assertEquals(listOf("B"), groups.map { it.name })
        assertEquals(listOf(c), groups.first().channels)
    }

    @Test fun channelsFromDifferentSourcesSurviveTheRoundTrip() = runTest {
        store.save("Mixed", listOf(a, c))
        val loaded = store.groups.first().first().channels
        assertEquals(s1, loaded[0].sourceId)
        assertEquals(s2, loaded[1].sourceId)
        assertEquals(ContentKind.LIVE, loaded[1].kind)
    }

    @Test fun storedValueIsAJsonList() = runTest {
        store.save("Game night", listOf(a, b))
        val raw = settings.value[MultiviewGroupStore.KEY].orEmpty()
        assertTrue(raw.startsWith("["))
        assertTrue(raw.contains("\"Game night\""))
    }

    @Test fun corruptValueDecodesToEmpty() {
        assertEquals(emptyList(), MultiviewGroupStore.decode("not json at all"))
        assertEquals(emptyList(), MultiviewGroupStore.decode(null))
    }

    @Test fun saveCapsAtFourChannels() = runTest {
        val many = (1..6).map { key(s1, it.toString()) }
        val saved = store.save("Big", many)
        assertEquals(MultiviewLayout.MAX_TILES, saved.channels.size)
    }

    private inner class SettingsOnly : UserDataRepository {
        override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
        override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
        override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
        override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = Unit
        override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
        override suspend fun progress(key: ContentKey): Progress? = null
        override suspend fun recordChannelWatched(key: ContentKey) = Unit
        override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
        override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
        override fun setting(key: String): Flow<String?> = settings.map { it[key] }
        override suspend fun putSetting(key: String, value: String) { settings.value = settings.value + (key to value) }
    }
}
