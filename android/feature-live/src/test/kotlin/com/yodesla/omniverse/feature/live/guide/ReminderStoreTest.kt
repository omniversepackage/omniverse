package com.yodesla.omniverse.feature.live.guide

import com.yodesla.omniverse.core.data.reminders.Reminder
import com.yodesla.omniverse.core.data.reminders.ReminderStore

import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class ReminderStoreTest {
    private val settings = MutableStateFlow<Map<String, String>>(emptyMap())
    private var now = 1_000L
    private val store = ReminderStore(SettingsOnly(), Clock { now })
    private fun r(start: Long, title: String = "Match") = Reminder("src", "ch1", "cat", start, start + 1_800_000, title, "Sports HD")

    @Test fun toggleSetsThenClears() = runTest {
        assertTrue(store.toggle(r(10_000)))
        assertEquals(listOf("src|ch1|10000"), store.reminders.first().map { it.id })
        assertFalse(store.toggle(r(10_000)))
        assertEquals(emptyList<Reminder>(), store.reminders.first())
    }

    @Test fun endedProgrammesArePrunedOnWrite() = runTest {
        store.toggle(r(10_000))
        now = 10_000 + 1_800_001 // first one has ended
        store.toggle(r(5_000_000))
        assertEquals(listOf(5_000_000L), store.reminders.first().map { it.startMs })
    }

    @Test fun separatorCharactersInTitlesCannotCorruptTheList() = runTest {
        store.toggle(r(10_000, "Bad\u001Ftitle\u001Eend"))
        store.toggle(r(20_000))
        val list = store.reminders.first()
        assertEquals(2, list.size)
        assertEquals("Bad title end", list.first().title)
        assertEquals(emptyList<Reminder>(), ReminderStore.decode("garbage"))
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
