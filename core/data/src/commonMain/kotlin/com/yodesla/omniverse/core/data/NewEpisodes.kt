package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.Episode
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.progressPosterKey
import com.yodesla.omniverse.core.source.ContentSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * "New episodes" (task 71): a Home row for shows the viewer watches that gained an episode they
 * have not seen.
 *
 * Detection is snapshot-based and per profile. After each completed catalog sync
 * [NewEpisodesDetector] re-reads the episode list of every show the profile watches (progress in
 * the last [NewEpisodes.WATCH_WINDOW_MS], or the show is in My List) and compares it with the
 * stored snapshot — so a show that merely reappears in the catalog never gets a false card, and
 * the first time a profile sees a show's episode list only stores the snapshot.
 *
 * Both records are viewer data, so they use profile-scoped settings keys (see
 * `UserDataRepositoryImpl.isProfileSetting`): the snapshot under
 * [NewEpisodes.snapshotKey], the pending card under [NewEpisodes.noticeKey].
 */
object NewEpisodes {
    /** A show counts as "watched" with progress this recent, or when it is in My List. */
    const val WATCH_WINDOW_MS = 60L * 86_400_000L

    /** A new-episode card stops showing this long after its episode was first flagged. */
    const val NOTICE_TTL_MS = 14L * 86_400_000L

    /** Most shows re-checked for one source per completed sync (newest-watched first). */
    const val MAX_SHOWS_PER_SYNC = 40

    /** Most saved-progress rows scanned when picking the shows to re-check. */
    const val MAX_PROGRESS_ROWS = 200

    /** Settings key holding a show's newest-seen episode (per profile). */
    fun snapshotKey(sourceId: String, seriesId: String) = "episode_snapshot_${sourceId}_$seriesId"

    /** Settings key holding the unseen new episode pending for a show (per profile). */
    fun noticeKey(sourceId: String, seriesId: String) = "new_episodes_${sourceId}_$seriesId"
}

/** One show's episode list as the profile last saw it: newest episode plus the total count. */
@Serializable
data class EpisodeSnapshot(val season: Int, val number: Int, val count: Int) {
    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun decode(value: String?): EpisodeSnapshot? =
            value?.let { runCatching { Json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * Episodes the profile has not seen yet in a show it watches: the earliest one plus how many are
 * waiting. [detectedMs] is when that episode was first flagged, which is what ages the card out
 * after [NewEpisodes.NOTICE_TTL_MS]; readers drop expired notices, the detector never clears one
 * on expiry (that would let the same episode be flagged again forever).
 */
@Serializable
data class NewEpisodesNotice(
    val episodeId: String,
    val season: Int,
    val number: Int,
    val count: Int,
    val detectedMs: Long,
) {
    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun decode(value: String?): NewEpisodesNotice? =
            value?.let { runCatching { Json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * Episodes in [ordered] (seasons by number, episodes by number) that come strictly after
 * [snapshot]. Empty when nothing is unseen or the show has never been snapshotted; when the
 * snapshotted episode is gone from the list (renumbered or removed) the season/episode numbers
 * decide instead of the position.
 */
fun newEpisodesAfter(ordered: List<Episode>, snapshot: EpisodeSnapshot?): List<Episode> {
    val base = snapshot ?: return emptyList()
    val at = ordered.indexOfFirst { it.season == base.season && it.number == base.number }
    if (at >= 0) return ordered.drop(at + 1)
    return ordered.filter { it.season > base.season || (it.season == base.season && it.number > base.number) }
}

/**
 * Shows to re-check for new episodes: those with progress at or after [sinceMs] (an episode's
 * parent show, via [progressPosterKey]) plus the shows in My List, newest-watched first,
 * de-duplicated, capped at [limit]. [sourceId] restricts the list to one provider.
 */
fun watchedShowKeys(
    progress: List<Progress>,
    favorites: List<ContentKey>,
    sinceMs: Long,
    limit: Int,
    sourceId: SourceId? = null,
): List<ContentKey> {
    val seen = mutableSetOf<ContentKey>()
    val out = mutableListOf<ContentKey>()
    fun add(key: ContentKey) {
        if (sourceId != null && key.sourceId != sourceId) return
        if (seen.add(key) && out.size < limit) out += key
    }
    progress.forEach { p ->
        if (p.updatedMs < sinceMs) return@forEach
        val series = progressPosterKey(p.key, p.parentId) ?: return@forEach
        if (series.kind == ContentKind.SERIES) add(series)
    }
    favorites.forEach { key -> if (key.kind == ContentKind.SERIES) add(key) }
    return out
}

/** Card subtitle: one episode names itself ("New · S3 · E5"), more of them just count themselves. */
fun newEpisodesLabel(count: Int, season: Int, number: Int): String =
    if (count <= 1) "New · S$season · E$number" else "$count new episodes"

/**
 * Writes the per-profile snapshot/notice records after a completed sync (task 71).
 *
 * - First sight of a show's episode list stores the snapshot and flags nothing.
 * - Episodes after the snapshot become a notice naming the earliest unseen one.
 * - Progress on a flagged episode rolls the snapshot forward over every consecutive new episode
 *   the viewer has already started or finished, so catching up clears the card and the next
 *   unseen episode takes its place.
 * - A provider that fails mid-refresh is left alone: whatever notice the profile already has
 *   stays, and the next sync tries again (the same "never cache a failure" rule as
 *   [UpNextResolver]).
 */
class NewEpisodesDetector(
    private val sources: SourceRepository,
    private val userData: UserDataRepository,
    private val clock: Clock = Clock { System.currentTimeMillis() },
) {
    /** Re-check every show this profile watches on [sourceId]; once per completed sync. */
    suspend fun refresh(sourceId: SourceId) {
        val candidates = watchedShowKeys(
            userData.savedProgress(NewEpisodes.MAX_PROGRESS_ROWS).first(),
            userData.favorites().first(),
            clock.nowMs() - NewEpisodes.WATCH_WINDOW_MS,
            NewEpisodes.MAX_SHOWS_PER_SYNC,
            sourceId,
        )
        if (candidates.isEmpty()) return
        val source = try {
            sources.contentSource(sourceId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            null
        } ?: return
        for (key in candidates) {
            try {
                refreshOne(source, key)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // One show's provider hiccup must not stop the rest of the sweep.
            }
        }
    }

    private suspend fun refreshOne(source: ContentSource, seriesKey: ContentKey) {
        val ordered = try {
            source.seriesDetail(seriesKey.remoteId).seasons.sortedBy { it.number }
                .flatMap { season -> season.episodes.sortedBy { it.number } }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return // provider down or show gone: keep whatever the profile already has
        }
        val snapshotKey = NewEpisodes.snapshotKey(seriesKey.sourceId.value, seriesKey.remoteId.value)
        val noticeKey = NewEpisodes.noticeKey(seriesKey.sourceId.value, seriesKey.remoteId.value)
        val snapshot = EpisodeSnapshot.decode(userData.setting(snapshotKey).first())
        val notice = NewEpisodesNotice.decode(userData.setting(noticeKey).first())
        val newest = ordered.lastOrNull()
        if (newest == null) {
            if (notice != null) userData.putSetting(noticeKey, CLEARED)
            return
        }
        if (snapshot == null) {
            // First time this profile sees this show: store it, flag nothing (no false "new").
            userData.putSetting(snapshotKey, EpisodeSnapshot(newest.season, newest.number, ordered.size).encode())
            if (notice != null) userData.putSetting(noticeKey, CLEARED)
            return
        }
        var base = snapshot
        var pending = newEpisodesAfter(ordered, base)
        while (pending.isNotEmpty() && hasProgress(seriesKey, pending.first())) {
            val seen = pending.first()
            base = EpisodeSnapshot(seen.season, seen.number, ordered.size)
            pending = newEpisodesAfter(ordered, base)
        }
        if (pending.isEmpty()) {
            userData.putSetting(snapshotKey, EpisodeSnapshot(newest.season, newest.number, ordered.size).encode())
            if (notice != null) userData.putSetting(noticeKey, CLEARED)
            return
        }
        val first = pending.first()
        // The same episode staying unseen keeps its original detection time, so the 14-day clock
        // does not restart on every sync.
        val detectedMs = if (notice != null && notice.episodeId == first.remoteId.value) notice.detectedMs else clock.nowMs()
        // The snapshot only moves when the viewer actually caught up; an episode still waiting
        // must leave it alone, or the notice would never be comparable to what was seen.
        if (base != snapshot) userData.putSetting(snapshotKey, base.copy(count = ordered.size).encode())
        userData.putSetting(
            noticeKey,
            NewEpisodesNotice(first.remoteId.value, first.season, first.number, pending.size, detectedMs).encode(),
        )
    }

    private suspend fun hasProgress(seriesKey: ContentKey, episode: Episode): Boolean =
        userData.progress(ContentKey(seriesKey.sourceId, ContentKind.EPISODE, episode.remoteId)) != null

    private companion object {
        /** Settings have no delete; an empty value decodes to "nothing stored". */
        const val CLEARED = ""
    }
}
