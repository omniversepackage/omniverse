package com.yodesla.omniverse.core.data.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.CompletedShow
import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.ItemOverride
import com.yodesla.omniverse.core.data.TitleIdentity
import com.yodesla.omniverse.core.data.TitleOverride
import com.yodesla.omniverse.core.data.LibraryCollection
import com.yodesla.omniverse.core.data.HiddenContinueWatching
import com.yodesla.omniverse.core.data.UndoToken
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UserDataRepositoryImpl(
    private val db: OmniverseDb,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val profileIds: Flow<String>? = null,
    private val profileId: () -> String = { DEFAULT_PROFILE },
) : UserDataRepository {

    private val q get() = db.userOpsQueries
    private val base get() = db.userDataQueries
    private val collectionRevision = MutableStateFlow(0L)

    private fun <T> perProfile(block: (String) -> Flow<T>): Flow<T> =
        (profileIds ?: flowOf(profileId())).flatMapLatest(block)

    private fun key(sid: String, kind: String, rid: String) =
        ContentKey(SourceId(sid), ContentKind.valueOf(kind), RemoteId(rid))

    override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = perProfile { profile ->
        q.favoritesByKind(profile, kind?.name).asFlow().mapToList(io).map { rows ->
            rows.mapNotNull { runCatching { key(it.source_id, it.kind, it.remote_id) }.getOrNull() }
        }
    }

    override fun isFavorite(key: ContentKey): Flow<Boolean> = perProfile { profile ->
        q.isFavorite(profile, key.sourceId.value, key.kind.name, key.remoteId.value).asFlow().mapToOne(io).map { it > 0 }
    }

    override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = withContext(io) {
        val p = profileId()
        if (favorite) {
            db.transaction {
                val sort = (q.maxFavoriteSort(p, LIST).executeAsOneOrNull()?.max ?: -1L) + 1
                base.addFavorite(p, LIST, key.sourceId.value, key.kind.name, key.remoteId.value, sort, clock.nowMs())
            }
        } else {
            q.removeFavorite(p, LIST, key.sourceId.value, key.kind.name, key.remoteId.value)
        }
        Unit
    }

    override fun playNext(): Flow<List<ContentKey>> = perProfile { profile ->
        q.playNext(profile).asFlow().mapToList(io).map { rows ->
            rows.mapNotNull { runCatching { key(it.source_id, it.kind, it.remote_id) }.getOrNull() }
        }
    }

    override fun isQueued(key: ContentKey): Flow<Boolean> = perProfile { profile ->
        q.isInPlayNext(profile, key.sourceId.value, key.kind.name, key.remoteId.value).asFlow().mapToOne(io).map { it > 0 }
    }

    override suspend fun setQueued(key: ContentKey, queued: Boolean) = withContext(io) {
        val p = profileId()
        if (queued) {
            db.transaction {
                // Re-adding must not move an already-queued title to the back.
                if (q.isInPlayNext(p, key.sourceId.value, key.kind.name, key.remoteId.value).executeAsOne() == 0L) {
                    val sort = (q.maxFavoriteSort(p, PLAY_NEXT).executeAsOneOrNull()?.max ?: -1L) + 1
                    base.addFavorite(p, PLAY_NEXT, key.sourceId.value, key.kind.name, key.remoteId.value, sort, clock.nowMs())
                }
            }
        } else {
            q.removeFavorite(p, PLAY_NEXT, key.sourceId.value, key.kind.name, key.remoteId.value)
        }
        Unit
    }

    override suspend fun moveQueued(key: ContentKey, up: Boolean) = withContext(io) {
        val p = profileId()
        db.transaction {
            val rows = q.playNext(p).executeAsList()
            val i = rows.indexOfFirst {
                it.source_id == key.sourceId.value && it.kind == key.kind.name && it.remote_id == key.remoteId.value
            }
            val j = if (up) i - 1 else i + 1
            if (i >= 0 && j in rows.indices) {
                // Swap the two rows' sort_index; queue sorts are unique (assigned max+1).
                q.updateFavoriteSort(rows[i].sort_index, p, PLAY_NEXT, rows[j].source_id, rows[j].kind, rows[j].remote_id)
                q.updateFavoriteSort(rows[j].sort_index, p, PLAY_NEXT, rows[i].source_id, rows[i].kind, rows[i].remote_id)
            }
        }
        Unit
    }

    override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = withContext(io) {
        val completed = durationMs != null && durationMs > 0 && positionMs >= durationMs * COMPLETE_FRACTION
        base.upsertProgress(
            profileId(), key.sourceId.value, key.kind.name, key.remoteId.value, parentId?.value,
            positionMs, durationMs, clock.nowMs(), if (completed) 1 else 0,
        )
        Unit
    }

    override fun continueWatching(limit: Int): Flow<List<Progress>> = perProfile { profile ->
        base.continueWatching(profile, limit.toLong()).asFlow().mapToList(io).map { rows ->
            rows.mapNotNull { r ->
                runCatching {
                    Progress(key(r.source_id, r.kind, r.remote_id), r.parent_id?.let(::RemoteId), r.position_ms, r.duration_ms, r.updated_ms)
                }.getOrNull()
            }
        }
    }

    // Task 89: one CW row per exact title (see UserDataRepository.continueWatchingDeduped). The SQL
    // resolves each in-progress row to its title's exact TMDB id and keeps only the newest visible
    // copy, so every CW surface reads the same deduped source instead of collapsing in the UI.
    override fun continueWatchingDeduped(limit: Int, excludedCategoryKeys: Collection<String>): Flow<List<Progress>> =
        perProfile { profile ->
            q.continueWatchingDeduped(profile, excludedCategoryKeys, limit.toLong()).asFlow().mapToList(io).map { rows ->
                rows.mapNotNull { r ->
                    runCatching {
                        Progress(key(r.source_id, r.kind, r.remote_id), r.parent_id?.let(::RemoteId), r.position_ms, r.duration_ms, r.updated_ms)
                    }.getOrNull()
                }
            }
        }

    override fun savedProgress(limit: Int): Flow<List<Progress>> = perProfile { profile ->
        q.savedProgress(profile, limit.toLong()).asFlow().mapToList(io).map { rows ->
            rows.mapNotNull { r ->
                runCatching {
                    Progress(
                        key(r.source_id, r.kind, r.remote_id), r.parent_id?.let(::RemoteId),
                        r.position_ms, r.duration_ms, r.updated_ms, r.completed != 0L,
                    )
                }.getOrNull()
            }
        }
    }

    override fun completedShows(sinceMs: Long, limit: Int): Flow<List<CompletedShow>> = perProfile { profile ->
        q.completedShows(profile, sinceMs, limit.toLong()).asFlow().mapToList(io).map { rows ->
            rows.mapNotNull { r ->
                runCatching {
                    CompletedShow(
                        key(r.source_id, ContentKind.SERIES.name, r.parent_id),
                        key(r.source_id, ContentKind.EPISODE.name, r.remote_id),
                        r.updated_ms,
                    )
                }.getOrNull()
            }
        }
    }

    override suspend fun progress(key: ContentKey): Progress? = withContext(io) {
        q.progressByKey(profileId(), key.sourceId.value, key.kind.name, key.remoteId.value).executeAsOneOrNull()?.let { r ->
            Progress(key, r.parent_id?.let(::RemoteId), r.position_ms, r.duration_ms, r.updated_ms, r.completed != 0L)
        }
    }

    override suspend fun setWatched(key: ContentKey, parentId: RemoteId?, watched: Boolean, durationMs: Long?) = withContext(io) {
        if (watched) {
            // A completed row: full position and completed = 1, so continueWatching (completed = 0) skips it.
            val dur = durationMs?.takeIf { it > 0 }
            base.upsertProgress(
                profileId(), key.sourceId.value, key.kind.name, key.remoteId.value, parentId?.value,
                dur ?: 1L, dur, clock.nowMs(), 1,
            )
        } else {
            q.deleteProgressByKey(profileId(), key.sourceId.value, key.kind.name, key.remoteId.value)
        }
        Unit
    }

    override suspend fun importRemoteProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?, atMs: Long) = withContext(io) {
        // L9 (audit 73): resolve the viewer ONCE per operation — a profile switch mid-sync must
        // not make this read from one profile and write to the next.
        val p = profileId()
        // Keep a local watch: only import when there is no local row or the remote one is newer.
        val local = q.progressByKey(p, key.sourceId.value, key.kind.name, key.remoteId.value).executeAsOneOrNull()
        if (local != null && local.updated_ms >= atMs) return@withContext
        if (dismissedSince(key, parentId, atMs, p)) return@withContext
        val completed = durationMs != null && durationMs > 0 && positionMs >= durationMs * COMPLETE_FRACTION
        base.upsertProgress(
            p, key.sourceId.value, key.kind.name, key.remoteId.value, parentId?.value,
            positionMs, durationMs, atMs, if (completed) 1 else 0,
        )
        Unit
    }

    override suspend fun recordChannelWatched(key: ContentKey) = withContext(io) {
        q.upsertRecent(profileId(), key.sourceId.value, key.remoteId.value, clock.nowMs())
        Unit
    }

    override fun recentChannels(limit: Int): Flow<List<ContentKey>> = perProfile { profile ->
        q.recentChannels(profile, limit.toLong()).asFlow().mapToList(io).map { rows ->
            rows.map { ContentKey(SourceId(it.source_id), ContentKind.LIVE, RemoteId(it.remote_id)) }
        }
    }

    override suspend fun setHidden(key: ContentKey, hidden: Boolean) = withContext(io) {
        if (hidden) q.addHidden(profileId(), key.sourceId.value, key.kind.name, key.remoteId.value)
        else q.removeHidden(profileId(), key.sourceId.value, key.kind.name, key.remoteId.value)
        Unit
    }

    override suspend fun removeFromContinueWatching(key: ContentKey): UndoToken = withContext(io) {
        val p = profileId()
        val atMs = clock.nowMs()
        // Task 63 (M6): Continue Watching collapses every copy of a trustworthy title identity into
        // one card, so removing that card must dismiss the WHOLE title at once: every copy's own
        // progress plus the episodes / media variants hanging under each copy, atomically. Progress
        // stays per playable variant otherwise — only the dismissal is title-level.
        val tmdb = trustedTmdbId(key)
        // Task 90: capture the exact rows this dismissal is about to delete (and the card's own row)
        // BEFORE deleting, so undo and the Settings hidden list can put them back verbatim.
        val ownRow = q.progressByKey(p, key.sourceId.value, key.kind.name, key.remoteId.value).executeAsOneOrNull()
        val deleted = LinkedHashMap<String, Progress>()
        q.progressForDismiss(p, key.sourceId.value, key.kind.name, key.remoteId.value).executeAsList()
            .forEach { deleted[progressRowId(it)] = toProgress(it) }
        if (tmdb != null) q.progressForTitleDismiss(p, tmdb).executeAsList()
            .forEach { deleted[progressRowId(it)] = toProgress(it) }
        db.transaction {
            q.deleteProgressFor(p, key.sourceId.value, key.kind.name, key.remoteId.value)
            if (tmdb != null) q.deleteProgressForTitle(p, tmdb)
        }
        // Remember the removal, so a source's own "in progress" list (Plex onDeck) can't bring it
        // straight back on the next sync. Reappearance policy: only progress made AFTER this moment
        // is imported again. The concrete entry covers the dismissed copy; the "T:" entry covers
        // every other copy sharing the same exact title identity.
        // L9 (audit 73): the tombstone belongs to the profile whose progress was just deleted.
        val k = dismissedKey(p)
        val entries = base.getSetting(k).executeAsOneOrNull()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val entry = "${key.sourceId.value}|${key.kind.name}|${key.remoteId.value}|$atMs"
        val titleEntry = tmdb?.let { "T:${key.kind.name}|$it|$atMs" }
        val titlePrefix = titleEntry?.let { it.substringBeforeLast('|') + "|" }
        val kept = entries.filterNot {
            it.startsWith(entry.substringBeforeLast('|') + "|") || (titlePrefix != null && it.startsWith(titlePrefix))
        }
        val written = listOf(entry) + listOfNotNull(titleEntry)
        base.putSetting(k, (kept + written).takeLast(200).joinToString("\n"))
        // Task 90: keep a Settings-visible record of the dismissed card so it can be restored later.
        addHiddenSnapshot(p, key, ownRow)
        UndoToken.ContinueWatching(key, deleted.values.toList(), written)
    }

    override suspend fun restoreContinueWatching(token: UndoToken) = withContext(io) {
        if (token !is UndoToken.ContinueWatching) return@withContext
        val p = profileId()
        db.transaction {
            // Re-insert every deleted row at its exact position / duration / completed flag / time.
            token.rows.forEach { r ->
                base.upsertProgress(
                    p, r.key.sourceId.value, r.key.kind.name, r.key.remoteId.value, r.parentId?.value,
                    r.positionMs, r.durationMs, r.updatedMs, if (r.completed) 1 else 0,
                )
            }
            // Clear exactly the tombstone lines this dismissal wrote, so sync stops suppressing them.
            val k = dismissedKey(p)
            val lines = base.getSetting(k).executeAsOneOrNull()?.lines()?.filter { it.isNotBlank() }.orEmpty()
            val drop = token.tombstones.toSet()
            base.putSetting(k, lines.filterNot { it in drop }.joinToString("\n"))
            // Drop the Settings hidden-list record for the dismissed card.
            val hk = hiddenKey(p)
            val hlines = base.getSetting(hk).executeAsOneOrNull()?.lines()?.filter { it.isNotBlank() }.orEmpty()
            val prefix = "${token.key.sourceId.value}|${token.key.kind.name}|${token.key.remoteId.value}"
            base.putSetting(hk, hlines.filterNot { it == prefix || it.startsWith("$prefix|") }.joinToString("\n"))
        }
        Unit
    }

    override suspend fun removeFromMyList(key: ContentKey): UndoToken = withContext(io) {
        val p = profileId()
        val row = q.favoriteRow(p, LIST, key.sourceId.value, key.kind.name, key.remoteId.value).executeAsOneOrNull()
            ?: return@withContext UndoToken.None
        q.removeFavorite(p, LIST, key.sourceId.value, key.kind.name, key.remoteId.value)
        UndoToken.Favorite(key, row.sort_index, row.added_ms)
    }

    override suspend fun restoreMyList(token: UndoToken) = withContext(io) {
        if (token !is UndoToken.Favorite) return@withContext
        base.addFavorite(
            profileId(), LIST, token.key.sourceId.value, token.key.kind.name, token.key.remoteId.value,
            token.sortIndex, token.addedMs,
        )
        Unit
    }

    override fun hiddenContinueWatching(limit: Int): Flow<List<HiddenContinueWatching>> = perProfile { profile ->
        base.getSetting(hiddenKey(profile)).asFlow().mapToOneOrNull(io).map { value ->
            value?.lines()?.filter { it.isNotBlank() }?.mapNotNull(::decodeHidden)?.asReversed()?.take(limit) ?: emptyList()
        }
    }

    override suspend fun restoreHiddenContinueWatching(key: ContentKey) = withContext(io) {
        val p = profileId()
        val hk = hiddenKey(p)
        val lines = base.getSetting(hk).executeAsOneOrNull()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val prefix = "${key.sourceId.value}|${key.kind.name}|${key.remoteId.value}"
        val line = lines.firstOrNull { it == prefix || it.startsWith("$prefix|") } ?: return@withContext
        val hidden = decodeHidden(line)
        db.transaction {
            hidden?.progress?.let { r ->
                base.upsertProgress(
                    p, r.key.sourceId.value, r.key.kind.name, r.key.remoteId.value, r.parentId?.value,
                    r.positionMs, r.durationMs, r.updatedMs, if (r.completed) 1 else 0,
                )
            }
            base.putSetting(hk, lines.filterNot { it == prefix || it.startsWith("$prefix|") }.joinToString("\n"))
        }
        clearTombstone(p, key)
        Unit
    }

    override suspend fun restoreAllHiddenContinueWatching() = withContext(io) {
        val p = profileId()
        val hk = hiddenKey(p)
        val lines = base.getSetting(hk).executeAsOneOrNull()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        db.transaction {
            lines.forEach { line ->
                decodeHidden(line)?.progress?.let { r ->
                    base.upsertProgress(
                        p, r.key.sourceId.value, r.key.kind.name, r.key.remoteId.value, r.parentId?.value,
                        r.positionMs, r.durationMs, r.updatedMs, if (r.completed) 1 else 0,
                    )
                }
            }
            base.putSetting(hk, "")
        }
        for (line in lines) decodeHidden(line)?.let { clearTombstone(p, it.key) }
        Unit
    }

    private fun hiddenKey(profile: String) = "cw_hidden_$profile"

    private fun progressRowId(r: com.yodesla.omniverse.core.database.Progress) = "${r.source_id}|${r.kind}|${r.remote_id}"

    private fun toProgress(r: com.yodesla.omniverse.core.database.Progress): Progress =
        Progress(key(r.source_id, r.kind, r.remote_id), r.parent_id?.let(::RemoteId), r.position_ms, r.duration_ms, r.updated_ms, r.completed != 0L)

    private suspend fun addHiddenSnapshot(profile: String, key: ContentKey, row: com.yodesla.omniverse.core.database.Progress?) {
        val k = hiddenKey(profile)
        val lines = base.getSetting(k).executeAsOneOrNull()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val prefix = "${key.sourceId.value}|${key.kind.name}|${key.remoteId.value}"
        val kept = lines.filterNot { it == prefix || it.startsWith("$prefix|") }
        val line = if (row == null) prefix else
            "$prefix|${row.parent_id ?: ""}|${row.position_ms}|${row.duration_ms ?: ""}|${row.updated_ms}|${row.completed}"
        base.putSetting(k, (kept + line).takeLast(200).joinToString("\n"))
    }

    private fun decodeHidden(line: String): HiddenContinueWatching? {
        val p = line.split('|')
        if (p.size < 3) return null
        val key = runCatching { key(p[0], p[1], p[2]) }.getOrNull() ?: return null
        if (p.size == 3) return HiddenContinueWatching(key, null)
        val parent = p[3].ifBlank { null }?.let(::RemoteId)
        val position = p[4].toLongOrNull() ?: return null
        val duration = p[5].toLongOrNull()
        val updated = p[6].toLongOrNull() ?: 0L
        val completed = (p[7].toLongOrNull() ?: 0L) != 0L
        return HiddenContinueWatching(key, Progress(key, parent, position, duration, updated, completed))
    }

    private suspend fun clearTombstone(profile: String, key: ContentKey) {
        val k = dismissedKey(profile)
        val lines = base.getSetting(k).executeAsOneOrNull()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val concretePrefix = "${key.sourceId.value}|${key.kind.name}|${key.remoteId.value}|"
        val titlePrefix = trustedTmdbId(key)?.let { "T:${key.kind.name}|$it|" }
        val kept = lines.filterNot { it.startsWith(concretePrefix) || (titlePrefix != null && it.startsWith(titlePrefix)) }
        base.putSetting(k, kept.joinToString("\n"))
    }

    private fun dismissedKey(profile: String) = "cw_dismissed_$profile"

    /** The exact TMDB id of a VOD/SERIES catalog item, or null when it carries no trustworthy identity. */
    private fun trustedTmdbId(key: ContentKey): String? {
        if (key.kind != ContentKind.VOD && key.kind != ContentKind.SERIES) return null
        return q.itemTmdbId(key.kind.name, key.sourceId.value, key.remoteId.value).executeAsOneOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** True when the viewer removed [key] (or, for an episode, its show/title) from Continue Watching at or after [atMs]. */
    private suspend fun dismissedSince(key: ContentKey, parentId: RemoteId?, atMs: Long, profile: String): Boolean {
        val lines = base.getSetting(dismissedKey(profile)).executeAsOneOrNull()?.lines().orEmpty()
        val concrete = lines.any { line ->
            val p = line.split('|')
            if (p.size != 4 || p[0] != key.sourceId.value) return@any false
            val same = (p[1] == key.kind.name && p[2] == key.remoteId.value) ||
                (key.kind == ContentKind.EPISODE && p[1] == ContentKind.SERIES.name && p[2] == parentId?.value)
            same && (p[3].toLongOrNull() ?: 0L) >= atMs
        }
        if (concrete) return true
        // Title-level tombstone: the dismissed card may have been another source's copy of the same title.
        val identityKind = if (key.kind == ContentKind.EPISODE) ContentKind.SERIES else key.kind
        val tmdb = when {
            key.kind == ContentKind.VOD || key.kind == ContentKind.SERIES -> trustedTmdbId(key)
            key.kind == ContentKind.EPISODE && parentId != null ->
                trustedTmdbId(ContentKey(key.sourceId, ContentKind.SERIES, parentId))
            else -> null
        } ?: return false
        val prefix = "T:$identityKind|$tmdb|"
        return lines.any { it.startsWith(prefix) && (it.substringAfterLast('|').toLongOrNull() ?: 0L) >= atMs }
    }

    override suspend fun setCategoryHidden(sourceId: SourceId, kind: ContentKind, categoryId: String, hidden: Boolean) = withContext(io) {
        val k = "CATEGORY_${kind.name}"
        if (hidden) q.addHidden(profileId(), sourceId.value, k, categoryId)
        else q.removeHidden(profileId(), sourceId.value, k, categoryId)
        Unit
    }

    override fun hiddenCategoryKeys(): Flow<Set<String>> = perProfile { profile ->
        q.hiddenCategories(profile).asFlow().mapToList(io).map { rows ->
            rows.map { "${it.kind.removePrefix("CATEGORY_")}|${it.source_id}|${it.remote_id}" }.toSet()
        }
    }

    override fun setting(key: String): Flow<String?> = if (isProfileSetting(key)) {
        perProfile { profile -> base.getSetting(profileSettingKey(key, profile)).asFlow().mapToOneOrNull(io) }
    } else base.getSetting(key).asFlow().mapToOneOrNull(io)

    override suspend fun putSetting(key: String, value: String) = withContext(io) {
        base.putSetting(if (isProfileSetting(key)) profileSettingKey(key, profileId()) else key, value)
        Unit
    }

    /** Move pre-profile viewer settings to the default profile once; safe and idempotent. */
    suspend fun migrateLegacyProfileSettings() = withContext(io) {
        val legacy = q.backupSettings().executeAsList().filter { isProfileSetting(it.key) }
        db.transaction {
            legacy.forEach { row ->
                val scoped = profileSettingKey(row.key, DEFAULT_PROFILE)
                if (base.getSetting(scoped).executeAsOneOrNull() == null) base.putSetting(scoped, row.value_)
                q.deleteSettingByKey(row.key)
            }
        }
    }

    /**
     * Drop leftover per-source sync stages (L4). A `src_sync_*` row only means "a sync is running in
     * this process"; if the app died mid-sync the row survives and the source would read "Updating…"
     * forever, because nothing is actually running. Called once at startup, before any sync can start.
     */
    suspend fun clearStaleSyncStages() = withContext(io) {
        db.transaction {
            q.backupSettings().executeAsList().filter { it.key.startsWith("src_sync_") }
                .forEach { q.deleteSettingByKey(it.key) }
        }
        Unit
    }

    override suspend fun itemOverride(key: ContentKey): ItemOverride? = withContext(io) {
        q.itemOverrideByKey(profileId(), key.sourceId.value, key.kind.name, key.remoteId.value).executeAsOneOrNull()?.let {
            ItemOverride(it.display_title, it.sort_title, it.edition_label, it.custom_poster_url)
        }
    }

    override fun itemOverrides(): Flow<Map<ContentKey, ItemOverride>> = perProfile { profile ->
        q.itemOverrides(profile).asFlow().mapToList(io).map { rows ->
            rows.mapNotNull { row ->
                runCatching { key(row.source_id, row.kind, row.remote_id) }.getOrNull()?.let { item ->
                    item to ItemOverride(row.display_title, row.sort_title, row.edition_label, row.custom_poster_url)
                }
            }.toMap()
        }
    }

    override suspend fun setItemOverride(key: ContentKey, value: ItemOverride) = withContext(io) {
        if (value == ItemOverride()) q.removeItemOverride(profileId(), key.sourceId.value, key.kind.name, key.remoteId.value)
        else q.upsertItemOverride(profileId(), key.sourceId.value, key.kind.name, key.remoteId.value,
            value.displayTitle?.trim()?.takeIf(String::isNotEmpty), value.sortTitle?.trim()?.takeIf(String::isNotEmpty),
            value.editionLabel?.trim()?.takeIf(String::isNotEmpty), value.customPosterUrl?.trim()?.takeIf(String::isNotEmpty))
        Unit
    }

    override suspend fun titleOverride(identity: TitleIdentity): TitleOverride? = withContext(io) {
        q.titleOverrideByKey(profileId(), identity.kind.name, identity.namespace, identity.externalId).executeAsOneOrNull()?.let {
            TitleOverride(it.display_title, it.sort_title, it.custom_poster_url)
        }
    }

    override fun titleOverrides(): Flow<Map<TitleIdentity, TitleOverride>> = perProfile { profile ->
        q.titleOverrides(profile).asFlow().mapToList(io).map { rows ->
            rows.mapNotNull { row ->
                val kind = runCatching { ContentKind.valueOf(row.content_kind) }.getOrNull()
                if (kind != ContentKind.VOD && kind != ContentKind.SERIES) null
                else TitleIdentity(kind, row.id_namespace, row.external_id) to
                    TitleOverride(row.display_title, row.sort_title, row.custom_poster_url)
            }.toMap()
        }
    }

    override suspend fun setTitleOverride(identity: TitleIdentity, value: TitleOverride) = withContext(io) {
        require(identity.kind == ContentKind.VOD || identity.kind == ContentKind.SERIES)
        require(identity.namespace.isNotBlank() && identity.externalId.isNotBlank())
        val clean = TitleOverride(value.displayTitle?.trim()?.takeIf(String::isNotEmpty),
            value.sortTitle?.trim()?.takeIf(String::isNotEmpty), value.customPosterUrl?.trim()?.takeIf(String::isNotEmpty))
        if (clean == TitleOverride()) q.removeTitleOverride(profileId(), identity.kind.name, identity.namespace, identity.externalId)
        else q.upsertTitleOverride(profileId(), identity.kind.name, identity.namespace, identity.externalId,
            clean.displayTitle, clean.sortTitle, clean.customPosterUrl)
        Unit
    }

    override fun collections(): Flow<List<LibraryCollection>> = perProfile { profile ->
        q.listCollections(profile).asFlow().mapToList(io).combine(collectionRevision) { rows, _ -> rows }.map { rows -> rows.mapNotNull { row ->
            val kind = runCatching { ContentKind.valueOf(row.content_kind) }.getOrNull() ?: return@mapNotNull null
            LibraryCollection(row.id, row.name, kind, row.pinned_home != 0L, row.mode, row.smart_filter_json, row.sort_order)
        } }
    }

    override suspend fun saveCollection(value: LibraryCollection) = withContext(io) {
        require(value.contentKind == ContentKind.VOD || value.contentKind == ContentKind.SERIES)
        require(value.mode == "manual" || value.mode == "smart")
        require(value.name.trim().isNotEmpty())
        val p = profileId()
        if (q.getCollection(value.id, p).executeAsOneOrNull() == null) {
            q.insertCollection(value.id, p, value.name.trim(), value.contentKind.name, if (value.pinnedHome) 1 else 0,
                value.mode, value.smartFilterJson, value.sortOrder)
        } else {
            q.updateCollection(value.name.trim(), value.contentKind.name, if (value.pinnedHome) 1 else 0,
                value.mode, value.smartFilterJson, value.sortOrder, value.id, p)
        }
        Unit
    }

    override suspend fun deleteCollection(id: String) = withContext(io) {
        val p = profileId()
        db.transaction {
            q.removeAllCollectionMemberships(p, id)
            q.removeAllCollectionTitleMemberships(p, id)
            q.deleteCollection(id, p)
        }
        collectionRevision.value++
        Unit
    }

    override suspend fun collectionItems(id: String): List<ContentKey> = withContext(io) {
        q.collectionMemberships(profileId(), id).executeAsList().mapNotNull { row ->
            runCatching { key(row.source_id, row.kind, row.remote_id) }.getOrNull()
        }
    }

    override suspend fun addToCollection(id: String, key: ContentKey) = withContext(io) {
        val p = profileId()
        val collection = q.getCollection(id, p).executeAsOneOrNull() ?: return@withContext
        require(collection.mode == "manual" && collection.content_kind == key.kind.name)
        db.transaction {
            val sort = (q.maxMembershipSort(p, id).executeAsOneOrNull()?.max ?: -1L) + 1
            q.addCollectionMembership(p, id, key.sourceId.value, key.kind.name, key.remoteId.value, sort)
        }
        collectionRevision.value++
    }

    override suspend fun removeFromCollection(id: String, key: ContentKey) = withContext(io) {
        q.removeCollectionMembership(profileId(), id, key.sourceId.value, key.kind.name, key.remoteId.value)
        collectionRevision.value++
        Unit
    }

    private fun requireTitleIdentity(identity: TitleIdentity) {
        require(identity.kind == ContentKind.VOD || identity.kind == ContentKind.SERIES)
        require(identity.namespace.isNotBlank() && identity.externalId.isNotBlank())
    }

    override suspend fun collectionTitles(id: String): List<TitleIdentity> = withContext(io) {
        q.collectionTitleMemberships(profileId(), id).executeAsList().mapNotNull { row ->
            val kind = runCatching { ContentKind.valueOf(row.content_kind) }.getOrNull()
            if (kind == ContentKind.VOD || kind == ContentKind.SERIES) {
                TitleIdentity(kind, row.id_namespace, row.external_id)
            } else null
        }
    }

    override suspend fun addTitleToCollection(id: String, identity: TitleIdentity) = withContext(io) {
        requireTitleIdentity(identity)
        val p = profileId()
        val collection = q.getCollection(id, p).executeAsOneOrNull() ?: return@withContext
        require(collection.mode == "manual" && collection.content_kind == identity.kind.name)
        db.transaction {
            val current = q.collectionTitleMemberships(p, id).executeAsList().firstOrNull {
                it.content_kind == identity.kind.name && it.id_namespace == identity.namespace &&
                    it.external_id == identity.externalId
            }
            val sort = current?.sort_index ?: (q.maxTitleMembershipSort(p, id).executeAsOneOrNull()?.max ?: -1L) + 1
            q.addCollectionTitleMembership(p, id, identity.kind.name, identity.namespace, identity.externalId, sort)
        }
        collectionRevision.value++
        Unit
    }

    override suspend fun removeTitleFromCollection(id: String, identity: TitleIdentity) = withContext(io) {
        requireTitleIdentity(identity)
        q.removeCollectionTitleMembership(profileId(), id, identity.kind.name, identity.namespace, identity.externalId)
        collectionRevision.value++
        Unit
    }

    override fun localSkipPoints(key: ContentKey): Flow<Map<String, Long>> = perProfile { profile ->
        q.localSkipPoints(profile, key.sourceId.value, key.kind.name, key.remoteId.value)
            .asFlow().mapToList(io).map { rows -> rows.associate { it.point_type to it.position_ms } }
    }

    override suspend fun setLocalSkipPoint(key: ContentKey, type: String, positionMs: Long?) = withContext(io) {
        require(key.kind == ContentKind.VOD || key.kind == ContentKind.EPISODE || key.kind == ContentKind.SERIES)
        require(type == "intro_end" || type == "credits_start" || (key.kind == ContentKind.SERIES && type == "credits_remaining"))
        if (positionMs == null) q.removeLocalSkipPoint(profileId(), key.sourceId.value, key.kind.name, key.remoteId.value, type)
        else {
            require(positionMs >= 5_000)
            q.upsertLocalSkipPoint(profileId(), key.sourceId.value, key.kind.name, key.remoteId.value, type, positionMs)
        }
        Unit
    }

    private companion object {
        const val LIST = "favorites"
        const val PLAY_NEXT = "playnext"
        const val COMPLETE_FRACTION = 0.92
        fun isProfileSetting(key: String) = key == "search_history" || key.startsWith("category_order_") ||
            key.startsWith("episode_snapshot_") || key.startsWith("new_episodes_") || key.startsWith("tracks_") ||
            key.startsWith("edition_pick_") ||
            key == "home_layout" || key == "home_density" || key == "text_size" ||
            key.startsWith("category_lang_") || key.startsWith("browse_filter_") ||
            key.startsWith("subtitle_style_") || key == "preload_next_channel" ||
            key == "guide_filter" || key == "match_frame_rate" || key == "crash_notice" ||
            key == "src_alert_dismissed" || key.startsWith("kids_") ||
            // Task 103: who you follow and whether scores are shown are per viewer. ("sports_online"
            // stays global on purpose — it is an app-wide switch, not a viewing preference.)
            key == "sports_followed_teams" || key == "sports_hide_scores" ||
            // Task 111 H3: spoiler protection and back-from-search are viewing preferences, not
            // device-wide switches — each profile keeps its own. Existing global rows migrate to the
            // default profile once via migrateLegacyProfileSettings().
            key == "spoiler_free" || key == "spoiler_free_hide_titles" || key == "back_from_search"
        fun profileSettingKey(key: String, profile: String) = "$key::$profile"
    }
}
