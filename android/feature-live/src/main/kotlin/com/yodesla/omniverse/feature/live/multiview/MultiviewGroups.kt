package com.yodesla.omniverse.feature.live.multiview

import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A named set of channels a viewer saved to watch together (Task 67). */
data class MultiviewGroup(val name: String, val channels: List<ContentKey>)

/**
 * Saved Multiview groups, stored per profile in the local settings table under [KEY] as a JSON
 * list of {name, channels}. No network, no schema change (same mechanism as guide reminders).
 * Channel keys are stored as {sourceId, remoteId} objects so ids containing separators survive a
 * round-trip; a corrupt value decodes to an empty list rather than throwing.
 */
class MultiviewGroupStore(private val userData: UserDataRepository) {

    val groups: Flow<List<MultiviewGroup>> = userData.setting(KEY).map(::decode)

    suspend fun current(): List<MultiviewGroup> = decode(userData.setting(KEY).first())

    /** "Group 1", "Group 2"… skipping names already taken, so a fresh save never collides. */
    suspend fun suggestedName(): String = defaultName(current())

    /** Saves [channels] (capped at [MultiviewLayout.MAX_TILES]) under [name]; blank names get the default. */
    suspend fun save(name: String, channels: List<ContentKey>): MultiviewGroup {
        val list = current()
        val group = MultiviewGroup(name.trim().ifEmpty { defaultName(list) }, channels.take(MultiviewLayout.MAX_TILES))
        userData.putSetting(KEY, encode((list + group).takeLast(MAX_GROUPS)))
        return group
    }

    /** Task 67: "Save as group" from the Multiview tile menu — saves the current grid under the next default name. */
    suspend fun saveAuto(channels: List<ContentKey>): MultiviewGroup = save("", channels)

    suspend fun rename(index: Int, name: String) {
        val list = current()
        if (index !in list.indices) return
        val next = list.mapIndexed { i, g -> if (i == index) g.copy(name = name.trim().ifEmpty { g.name }) else g }
        userData.putSetting(KEY, encode(next))
    }

    suspend fun delete(index: Int) {
        val list = current()
        if (index !in list.indices) return
        userData.putSetting(KEY, encode(list.filterIndexed { i, _ -> i != index }))
    }

    private fun defaultName(list: List<MultiviewGroup>): String = defaultNameFor(list)

    companion object {
        const val KEY = "multiview_groups"
        private const val MAX_GROUPS = 24

        /** "Group 1", "Group 2"… skipping names already taken, so a fresh save never collides. */
        fun defaultNameFor(list: List<MultiviewGroup>): String {
            val taken = list.map { it.name }.toSet()
            var n = 1
            while ("Group $n" in taken) n++
            return "Group $n"
        }

        fun encode(list: List<MultiviewGroup>): String = JsonArray(list.map { g ->
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(g.name),
                    "channels" to JsonArray(
                        g.channels.map { k ->
                            JsonObject(
                                mapOf(
                                    "sourceId" to JsonPrimitive(k.sourceId.value),
                                    "remoteId" to JsonPrimitive(k.remoteId.value),
                                )
                            )
                        }
                    ),
                )
            )
        }).toString()

        fun decode(raw: String?): List<MultiviewGroup> = runCatching {
            val root = Json.parseToJsonElement(raw ?: "[]") as? JsonArray ?: return emptyList()
            root.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val channels = (o["channels"] as? JsonArray)?.mapNotNull { c ->
                    val co = c as? JsonObject ?: return@mapNotNull null
                    val s = (co["sourceId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val r = (co["remoteId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    ContentKey(SourceId(s), ContentKind.LIVE, RemoteId(r))
                } ?: emptyList()
                MultiviewGroup(name, channels)
            }
        }.getOrDefault(emptyList())
    }
}
