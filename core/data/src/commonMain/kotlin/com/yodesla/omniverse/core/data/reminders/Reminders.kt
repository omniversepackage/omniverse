package com.yodesla.omniverse.core.data.reminders

import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.UserDataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A "remind me when this starts" entry for one programme on one channel. */
data class Reminder(
    val sourceId: String,
    val channelId: String,
    val categoryId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val channelName: String,
) {
    val id: String get() = "$sourceId|$channelId|$startMs"
}

/**
 * Programme reminders, stored per profile in the local settings table (no network,
 * no system alarms). Ended programmes are pruned on every write.
 */
class ReminderStore(private val userData: UserDataRepository, private val clock: Clock) {
    val reminders: Flow<List<Reminder>> = userData.setting(KEY).map(::decode)

    /** Adds the reminder, or removes it when already set. Returns true when it is now set. */
    suspend fun toggle(r: Reminder): Boolean {
        val now = clock.nowMs()
        val current = decode(userData.setting(KEY).first()).filter { it.endMs > now }
        val exists = current.any { it.id == r.id }
        val next = if (exists) current.filterNot { it.id == r.id } else (current + r).sortedBy { it.startMs }.takeLast(MAX)
        userData.putSetting(KEY, encode(next))
        return !exists
    }

    suspend fun remove(id: String) {
        val now = clock.nowMs()
        userData.putSetting(KEY, encode(decode(userData.setting(KEY).first()).filter { it.id != id && it.endMs > now }))
    }

    companion object {
        const val KEY = "guide_reminders"
        private const val MAX = 50
        private const val FIELD = '\u001F'
        private const val RECORD = '\u001E'

        private fun clean(s: String) = s.replace(FIELD, ' ').replace(RECORD, ' ')

        fun encode(list: List<Reminder>): String = list.joinToString(RECORD.toString()) { r ->
            listOf(r.sourceId, r.channelId, r.categoryId, r.startMs.toString(), r.endMs.toString(), r.title, r.channelName)
                .joinToString(FIELD.toString(), transform = ::clean)
        }

        fun decode(raw: String?): List<Reminder> = raw.orEmpty().split(RECORD).mapNotNull { rec ->
            val f = rec.split(FIELD)
            if (f.size != 7) return@mapNotNull null
            Reminder(f[0], f[1], f[2], f[3].toLongOrNull() ?: return@mapNotNull null, f[4].toLongOrNull() ?: return@mapNotNull null, f[5], f[6])
        }
    }
}
