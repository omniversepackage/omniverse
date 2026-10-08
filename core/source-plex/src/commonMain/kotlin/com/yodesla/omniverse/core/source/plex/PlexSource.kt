package com.yodesla.omniverse.core.source.plex

import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.model.Capability
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ChannelRecord
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.Episode
import com.yodesla.omniverse.core.model.MimeHint
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.PlaybackSpec
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.Redact
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.Season
import com.yodesla.omniverse.core.model.SeriesDetail
import com.yodesla.omniverse.core.model.SeriesRecord
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.model.VodDetail
import com.yodesla.omniverse.core.model.VodRecord
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import com.yodesla.omniverse.core.net.RetryPolicy
import com.yodesla.omniverse.core.net.requireSuccess
import com.yodesla.omniverse.core.net.withRetry
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.source.RemoteProgress
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.core.source.SourceFactory
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okio.IOException

/**
 * Plex Media Server (server API, X-Plex-Token auth). Contract: ContentSource.kt header.
 * - The token rides in request HEADERS; the only URLs that contain it are artwork/playback
 *   URLs (query param), and those are always offered via Redact in logs.
 * - VOD + SERIES only; live/EPG are empty by design.
 * - One bad metadata row is skipped (diagnostics.skipped), never fatal.
 */
class PlexSource(
    private val config: SourceConfig.Plex,
    private val http: HttpClient,
    private val retry: RetryPolicy = RetryPolicy(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ContentSource {

    override val id: SourceId = config.id
    override val kind = SourceKind.PLEX
    override val capabilities = setOf(Capability.VOD, Capability.SERIES)

    private val base = config.server.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val headers = mapOf(
        "Accept" to "application/json",
        "X-Plex-Token" to config.token,
        "X-Plex-Client-Identifier" to config.clientId,
        "X-Plex-Product" to "Omniverse",
    )

    /** Remote watch intent per ratingKey (audit M10): newest state wins, applied one request at a time. */
    private val watchStateLock = Mutex()
    private val watchQueues = mutableMapOf<String, WatchQueue>()

    // ---------------------------------------------------------------- account

    override suspend fun accountInfo(): AccountInfo {
        val mc = (getJson("/identity")["MediaContainer"] as? JsonObject)
            ?: throw SourceException.BadResponse("Plex /identity had no MediaContainer")
        val machineId = mc.str("machineIdentifier")
            ?: throw SourceException.BadResponse("Plex /identity had no machineIdentifier")
        if (machineId != config.machineId) throw SourceException.BadResponse("Plex server identity did not match the selected server")
        // Local account: always active, never expires, no connection limit we can query.
        return AccountInfo(AccountStatus.ACTIVE, null, null, null, false, emptyList(), null, null)
    }

    // ---------------------------------------------------------------- live: none

    override fun liveCategories(): Flow<Category> = emptyFlow()

    override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> = emptyFlow()

    override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> =
        emptyFlow()

    override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()

    // ---------------------------------------------------------------- categories

    override fun vodCategories(): Flow<Category> = sectionCategories(ContentKind.VOD, "movie")

    override fun seriesCategories(): Flow<Category> = sectionCategories(ContentKind.SERIES, "show")

    private fun sectionCategories(kind: ContentKind, type: String): Flow<Category> = flow {
        var sort = 0
        for (s in sections()) {
            val key = s.str("key")
            val title = s.str("title")
            if (s.str("type") != type || key == null || title == null) continue
            emit(Category(id, kind, RemoteId(key), title, null, sort))
            sort++
        }
    }.flowOn(io)

    // ---------------------------------------------------------------- lists

    override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> = flow {
        var sort = 0
        for (s in sections()) {
            if (s.str("type") != "movie") continue
            val sectionKey = s.str("key") ?: continue
            var start = 0
            while (true) {
                val page = pageItems(1, sectionKey, start)
                for (m in page.items) {
                    val v = mapVod(m, sectionKey, sort)
                    if (v == null) diagnostics.skipped("vod", "unreadable item in section $sectionKey")
                    else {
                        emit(v)
                        sort++
                    }
                }
                if (page.items.isEmpty()) break
                start += page.items.size
                if (page.totalSize?.let { start >= it } ?: (page.items.size < PAGE_SIZE)) break
            }
        }
    }.flowOn(io)

    override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> = flow {
        var sort = 0
        for (s in sections()) {
            if (s.str("type") != "show") continue
            val sectionKey = s.str("key") ?: continue
            var start = 0
            while (true) {
                val page = pageItems(2, sectionKey, start)
                for (m in page.items) {
                    val v = mapSeries(m, sectionKey, sort)
                    if (v == null) diagnostics.skipped("series", "unreadable item in section $sectionKey")
                    else {
                        emit(v)
                        sort++
                    }
                }
                if (page.items.isEmpty()) break
                start += page.items.size
                if (page.totalSize?.let { start >= it } ?: (page.items.size < PAGE_SIZE)) break
            }
        }
    }.flowOn(io)

    // ---------------------------------------------------------------- details

    override suspend fun vodDetail(id: RemoteId): VodDetail {
        val m = firstMetadata("/library/metadata/${enc(id.value)}")
            ?: throw SourceException.NotFound("Movie ${id.value} not found")
        val record = mapVod(m, m.str("parentRatingKey") ?: "unknown", 0)
            ?: throw SourceException.BadResponse("Plex movie ${id.value} has no usable title")
        val roles = m.fieldList("Role", "tag")
        return VodDetail(
            record = record,
            plot = m.str("summary"),
            cast = roles.joinToString(", ").takeIf { it.isNotBlank() },
            director = m.fieldList("Director", "tag").joinToString(", ").takeIf { it.isNotBlank() } ?: m.str("Director"),
            genre = m.fieldList("Genre", "tag").joinToString(", ").takeIf { it.isNotBlank() },
            durationSec = m.long("duration")?.div(1000)?.toInt(),
            backdropUrls = m.str("art")?.let(::artUrl)?.let(::listOf) ?: emptyList(),
            releaseDate = m.str("originallyAvailableAt") ?: m.str("PremiDate") ?: m.str("year"),
            trailerUrl = m.arr("Trailer")?.mapNotNull { (it as? JsonObject)?.str("watchURL") }?.firstOrNull(),
            versions = playableParts(m).mapIndexed { index, (media, part) ->
                val resolution = media.str("videoResolution") ?: part.str("videoResolution")
                val quality = when (resolution) {
                    "2160", "4k" -> "4K"
                    "1080" -> "1080p"
                    "720" -> "720p"
                    else -> resolution?.takeIf { it.matches(Regex("[0-9]{3,4}")) }?.let { "${it}p" }
                }
                val container = (media.str("container") ?: part.str("container"))?.uppercase()?.takeIf { it.matches(Regex("[A-Z0-9]{2,8}")) }
                com.yodesla.omniverse.core.model.MediaVersion(mediaVersionId(media, part), index,
                    listOfNotNull(quality, container).joinToString(" · ").ifBlank { "Version ${index + 1}" })
            },
        )
    }

    override suspend fun seriesDetail(id: RemoteId): SeriesDetail {
        val m = firstMetadata("/library/metadata/${enc(id.value)}")
            ?: throw SourceException.NotFound("Series ${id.value} not found")
        val record = mapSeries(m, m.str("parentRatingKey") ?: "unknown", 0)
            ?: SeriesRecord(this.id, id, m.str("title") ?: id.value, null, emptyList(), emptyList(), null, null, null, null, null, 0)
        val seasonContainer = getJson("/library/metadata/${enc(id.value)}/children")["MediaContainer"] as? JsonObject
        val seasonsEl = (seasonContainer?.get("Metadata") ?: seasonContainer?.get("Directory")) as? JsonArray
            ?: throw SourceException.BadResponse("Plex series ${id.value} had no seasons")
        val seasons = mutableListOf<Season>()
        for (s in seasonsEl.filterIsInstance<JsonObject>()) {
            val sKey = s.str("ratingKey") ?: continue
            val sIndex = s.int("index") ?: 0
            val episodeContainer = getJson("/library/metadata/${enc(sKey)}/children")["MediaContainer"] as? JsonObject
            // Plex represents episodes as Metadata (Video). Some older responses use Directory.
            val epsEl = (episodeContainer?.get("Metadata") ?: episodeContainer?.get("Directory")) as? JsonArray
                ?: emptyList()
            val episodes = epsEl.filterIsInstance<JsonObject>().mapNotNull { e ->
                val eKey = e.str("ratingKey")
                val eTitle = e.str("title")
                if (eKey == null || eTitle == null) return@mapNotNull null
                Episode(
                    sourceId = this.id,
                    remoteId = RemoteId(eKey),
                    seriesId = id,
                    season = sIndex,
                    number = e.int("index") ?: 0,
                    title = eTitle,
                    plot = e.str("summary"),
                    durationSec = e.long("duration")?.div(1000)?.toInt(),
                    stillUrl = e.str("thumb")?.let(::artUrl),
                    containerExt = null,
                    rating = e.float("rating")?.takeIf { it in 0f..10f },
                )
            }
            seasons.add(Season(sIndex, s.str("title"), s.str("thumb")?.let(::artUrl), episodes))
        }
        return SeriesDetail(
            record,
            cast = m.fieldList("Role", "tag").joinToString(", ").takeIf { it.isNotBlank() },
            director = m.fieldList("Director", "tag").joinToString(", ").takeIf { it.isNotBlank() },
            seasons = seasons,
        )
    }

    // ---------------------------------------------------------------- playback

    override suspend fun playback(request: PlaybackRequest): PlaybackSpec {
        val (remoteId, containerExt) = when (request) {
            is PlaybackRequest.Vod -> request.vodId to request.containerExt
            is PlaybackRequest.EpisodeItem -> request.episodeId to request.containerExt
            else -> throw SourceException.Unsupported("Plex plays VOD and episodes only")
        }
        val m = firstMetadata("/library/metadata/${enc(remoteId.value)}?includeMarkers=1")
            ?: throw SourceException.NotFound("Item ${remoteId.value} not found")
        // A metadata item may contain multiple Media versions. An empty/broken first
        // version must not prevent playback of a later playable version.
        val parts = playableParts(m)
        val chosenId = (request as? PlaybackRequest.Vod)?.versionId
        val part = (if (chosenId == null) parts.firstOrNull() else parts.firstOrNull { (media, part) -> mediaVersionId(media, part) == chosenId })?.second
            ?: throw SourceException.NotFound("Item ${remoteId.value} has no playable part")
        val partKey = part.str("key")
            ?: throw SourceException.NotFound("Item ${remoteId.value} part has no key")
        val ext = partKey.substringBefore('?').substringAfterLast('.', "").ifBlank { containerExt.orEmpty() }
        val partUrl = "$base$partKey"
        val url = "$partUrl${if ('?' in partUrl) '&' else '?'}X-Plex-Token=${enc(config.token)}"
        // A fresh session per play: Plex refuses a session it terminated, so a re-resolve after
        // a 503 must not reuse it. Same id goes on the timeline heartbeats (reportPlayback).
        sessionId = newSessionId()
        return PlaybackSpec(url = url, headers = streamHeaders(), mimeHint = hintFor(ext), isLive = false, seekable = true,
            redacted = Redact.text(url), deliveryMode = com.yodesla.omniverse.core.model.DeliveryMode.DIRECT_PLAY,
            skipMarkers = skipMarkers(m))
    }

    @kotlin.concurrent.Volatile private var sessionId: String = newSessionId()

    private fun newSessionId(): String = (1..16).joinToString("") { kotlin.random.Random.nextInt(256).toString(16).padStart(2, '0') }

    private fun streamHeaders() = mapOf(
        "X-Plex-Client-Identifier" to config.clientId,
        "X-Plex-Product" to "Omniverse",
        "X-Plex-Session-Identifier" to sessionId,
    )

    /** /:/timeline like the official apps send every ~10 s; without it Plex decides we paused. */
    override suspend fun reportPlayback(request: PlaybackRequest, state: String, positionMs: Long, durationMs: Long?) {
        val key = when (request) {
            is PlaybackRequest.Vod -> request.vodId.value
            is PlaybackRequest.EpisodeItem -> request.episodeId.value
            else -> return
        }
        val q = "ratingKey=${enc(key)}&key=${enc("/library/metadata/$key")}&state=${enc(state)}" +
            "&time=${positionMs.coerceAtLeast(0)}" + (durationMs?.takeIf { it > 0 }?.let { "&duration=$it" } ?: "")
        runCatching {
            withContext(io) { http.get("$base/:/timeline?$q", headers + streamHeaders()).use { } }
        }
    }

    // ---------------------------------------------------------------- remote progress (onDeck)

    /**
     * GET /library/onDeck: what the viewer is mid-way through in any Plex app. Movies become VOD,
     * episodes become EPISODE with the show's grandparentRatingKey as parent. Items without a
     * viewOffset are skipped. Any HTTP/parse failure yields an empty list (never throws).
     */
    override suspend fun remoteProgress(): List<RemoteProgress> =
        runCatching { parseOnDeck(getJson("/library/onDeck")) }.getOrDefault(emptyList())

    private fun parseOnDeck(root: JsonObject): List<RemoteProgress> {
        val container = root["MediaContainer"] as? JsonObject ?: return emptyList()
        val metas = container["Metadata"] as? JsonArray ?: return emptyList()
        return metas.filterIsInstance<JsonObject>().mapNotNull { m ->
            val ratingKey = m.str("ratingKey") ?: return@mapNotNull null
            val viewOffset = m.long("viewOffset") ?: return@mapNotNull null
            val duration = m.long("duration")?.takeIf { it > 0 }
            val lastViewedSec = m.long("lastViewedAt") ?: return@mapNotNull null
            when (m.str("type")) {
                "movie" -> RemoteProgress(RemoteId(ratingKey), ContentKind.VOD, null, viewOffset, duration, lastViewedSec * 1000)
                "episode" -> m.str("grandparentRatingKey")?.let { show ->
                    RemoteProgress(RemoteId(ratingKey), ContentKind.EPISODE, RemoteId(show), viewOffset, duration, lastViewedSec * 1000)
                }
                else -> null
            }
        }
    }

    // ---------------------------------------------------------------- watch state

    /**
     * /:/scrobble and /:/unscrobble, exactly as the official Plex apps send them, so a title we
     * mark here is marked in Plex too. [id] is the item's ratingKey.
     *
     * Remote intent is serialized per ratingKey and coalesced (audit M10): while a request is in
     * flight the newest state asked for that key is remembered, so a rapid watched -> unwatched
     * cannot land in Plex in the wrong order, and identical repeated intents collapse. Status is
     * checked: 401 fails at once, 429/5xx get [retry]'s bounded backoff, and the surviving
     * [SourceException] is thrown so the caller can show it and offer a retry. Cancellation is
     * preserved, and an intent that never landed stays queued for the next attempt.
     */
    override suspend fun setWatched(id: RemoteId, watched: Boolean) {
        val queue = watchQueueFor(id.value)
        try {
            recordIntent(queue, watched)
            queue.sendLock.withLock {
                while (true) {
                    val desired = takeIntent(queue) ?: break
                    try {
                        scrobble(id.value, desired)
                    } catch (e: CancellationException) {
                        requeueIntent(queue, desired)
                        throw e
                    } catch (e: SourceException) {
                        requeueIntent(queue, desired)
                        throw e
                    }
                }
            }
        } finally {
            releaseWatchQueue(id.value, queue)
        }
    }

    private suspend fun scrobble(key: String, watched: Boolean) = withContext(io) {
        val path = if (watched) "/:/scrobble" else "/:/unscrobble"
        val url = "$base$path?key=${enc(key)}&identifier=com.plexapp.plugins.library"
        withRetry(retry) {
            http.get(url, headers).use { r ->
                if (r.status == 401) throw SourceException.AuthFailed("Plex rejected the token")
                r.requireSuccess(url)
            }
        }
    }

    /** One ratingKey's pending remote state. [sendLock] is held only while a request is in flight. */
    private class WatchQueue {
        val sendLock = Mutex()
        var latest: Boolean? = null
        var waiters = 0
    }

    private suspend fun watchQueueFor(key: String): WatchQueue = watchStateLock.withLock {
        watchQueues.getOrPut(key) { WatchQueue() }.also { it.waiters++ }
    }

    private suspend fun recordIntent(queue: WatchQueue, watched: Boolean) = watchState { queue.latest = watched }

    private suspend fun takeIntent(queue: WatchQueue): Boolean? = watchStateLock.withLock {
        queue.latest.also { queue.latest = null }
    }

    /** Keep an intent that never landed, unless a newer intent already replaced it. */
    private suspend fun requeueIntent(queue: WatchQueue, watched: Boolean) = watchState {
        if (queue.latest == null) queue.latest = watched
    }

    private suspend fun releaseWatchQueue(key: String, queue: WatchQueue) = watchState {
        queue.waiters--
        if (queue.waiters == 0 && queue.latest == null) watchQueues.remove(key)
    }

    /** Bookkeeping must also run while the caller is being cancelled, or a pending intent is lost. */
    private suspend fun watchState(block: suspend () -> Unit) = withContext(NonCancellable) { watchStateLock.withLock { block() } }

    // ---------------------------------------------------------------- internals

    private fun skipMarkers(m: JsonObject): List<com.yodesla.omniverse.core.model.SkipMarker> =
        m.arr("Marker")?.mapNotNull { it as? JsonObject }?.mapNotNull { marker ->
            val type = marker.str("type")?.lowercase()?.takeIf { it == "intro" || it == "credits" } ?: return@mapNotNull null
            val start = marker.long("startTimeOffset") ?: return@mapNotNull null
            val end = marker.long("endTimeOffset") ?: return@mapNotNull null
            if (start < 0 || end <= start) return@mapNotNull null
            com.yodesla.omniverse.core.model.SkipMarker(type, start, end)
        }?.sortedBy { it.startMs }.orEmpty()

    private fun playableParts(m: JsonObject): List<Pair<JsonObject, JsonObject>> =
        m.arr("Media")?.mapNotNull { it as? JsonObject }?.mapNotNull { media ->
            // Plex Parts can be sequential segments of one file, not separate versions.
            media.arr("Part")?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { !it.str("key").isNullOrBlank() }
                ?.let { media to it }
        }.orEmpty()

    private fun mediaVersionId(media: JsonObject, part: JsonObject): String {
        media.str("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }?.let { return "media-$it" }
        // No Plex Media id: hash the internal Part key rather than persisting a path.
        var hash = 0xcbf29ce484222325UL
        for (c in part.str("key").orEmpty()) hash = (hash xor c.code.toULong()) * 0x100000001b3UL
        return "part-${hash.toString(16)}"
    }

    private fun mapVod(m: JsonObject, sectionKey: String, sort: Int): VodRecord? {
        val ratingKey = m.str("ratingKey") ?: return null
        val title = m.str("title") ?: return null
        return VodRecord(
            sourceId = id,
            remoteId = RemoteId(ratingKey),
            name = title,
            posterUrl = m.str("thumb")?.let(::artUrl),
            categoryIds = listOf(RemoteId(sectionKey)),
            rating = (m.float("audienceRating") ?: m.float("rating"))?.takeIf { it in 0f..10f },
            year = m.int("year"),
            addedAtMs = m.long("addedAt")?.times(1000),
            containerExt = null,
            tmdbId = m.fieldList("Guid", "id").firstOrNull { it.startsWith("tmdb://") }?.removePrefix("tmdb://"),
            sortIndex = sort,
            genre = m.fieldList("Genre", "tag").joinToString(", ").takeIf { it.isNotBlank() },
        )
    }

    private fun mapSeries(m: JsonObject, sectionKey: String, sort: Int): SeriesRecord? {
        val ratingKey = m.str("ratingKey") ?: return null
        val title = m.str("title") ?: return null
        val genres = m.fieldList("Genre", "tag")
        return SeriesRecord(
            sourceId = id,
            remoteId = RemoteId(ratingKey),
            name = title,
            posterUrl = m.str("thumb")?.let(::artUrl),
            backdropUrls = m.str("art")?.let(::artUrl)?.let(::listOf) ?: emptyList(),
            categoryIds = listOf(RemoteId(sectionKey)),
            plot = m.str("summary"),
            genre = genres.joinToString(", ").takeIf { it.isNotBlank() },
            rating = (m.float("audienceRating") ?: m.float("rating"))?.takeIf { it in 0f..10f },
            year = m.int("year"),
            lastModifiedMs = (m.long("updatedAt") ?: m.long("addedAt"))?.times(1000),
            sortIndex = sort,
            tmdbId = m.fieldList("Guid", "id").firstOrNull { it.startsWith("tmdb://") }?.removePrefix("tmdb://"),
        )
    }

    private suspend fun sections(): List<JsonObject> {
        val container = getJson("/library/sections")["MediaContainer"] as? JsonObject
            ?: throw SourceException.BadResponse("Plex sections response had no MediaContainer")
        val dirs = container["Directory"] as? JsonArray
            ?: if (container.long("size") == 0L) return emptyList()
            else throw SourceException.BadResponse("Plex sections response had no Directory")
        return dirs.map { it as? JsonObject ?: throw SourceException.BadResponse("Plex section was not an object") }
    }

    private data class Page(val items: List<JsonObject>, val totalSize: Int?)

    private suspend fun pageItems(type: Int, sectionKey: String, start: Int): Page {
        val path = "/library/sections/${enc(sectionKey)}/all?type=$type&X-Plex-Container-Start=$start&X-Plex-Container-Size=$PAGE_SIZE"
        val container = getJson(path)["MediaContainer"] as? JsonObject
            ?: throw SourceException.BadResponse("Plex page had no MediaContainer")
        val total = container.long("totalSize")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
        val metas = container["Metadata"] as? JsonArray
            ?: if (container.long("size") == 0L && (total == null || start >= total)) return Page(emptyList(), total)
            else throw SourceException.BadResponse("Plex page had no Metadata")
        val items = metas.map { it as? JsonObject ?: throw SourceException.BadResponse("Plex item was not an object") }
        if (container.long("size")?.let { it != items.size.toLong() } == true ||
            (total != null && start + items.size > total) ||
            (total != null && start < total && items.isEmpty())) {
            throw SourceException.BadResponse("Plex page count was inconsistent")
        }
        return Page(items, total)
    }

    private suspend fun firstMetadata(path: String): JsonObject? {
        val arr = (getJson(path)["MediaContainer"] as? JsonObject)?.get("Metadata") as? JsonArray
        return arr?.filterIsInstance<JsonObject>()?.firstOrNull()
    }

    /** Plex artwork paths are relative; absolute URLs are kept as-is. */
    private fun artUrl(path: String): String =
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) path
        else "$base${if (path.startsWith("/")) path else "/$path"}".let { url ->
            "$url${if ('?' in url) '&' else '?'}X-Plex-Token=${enc(config.token)}"
        }

    private fun hintFor(ext: String): MimeHint = when (ext.lowercase()) {
        "m3u8" -> MimeHint.HLS
        "ts" -> MimeHint.MPEG_TS
        "mkv" -> MimeHint.MKV
        "mp4", "m4v", "mov" -> MimeHint.MP4
        else -> MimeHint.UNKNOWN
    }

    /**
     * Objects here are small (<= 200 metadata rows), so the whole request + read is retried:
     * a connection dropped mid-body becomes SourceException.Network, never a raw IOException.
     */
    private suspend fun getJson(path: String): JsonObject = withContext(io) {
        val url = "$base$path"
        withRetry(retry) {
            val r = http.get(url, headers)
            r.use {
                if (it.status == 401) throw SourceException.AuthFailed("Plex rejected the token")
                it.requireSuccess(url)
                val text = try {
                    it.body.readUtf8()
                } catch (e: IOException) {
                    throw SourceException.Network("Connection lost while reading ${Redact.text(url)}", e)
                }
                try {
                    json.parseToJsonElement(text) as? JsonObject
                        ?: throw SourceException.BadResponse("Plex answered $path with a non-object body")
                } catch (e: SerializationException) {
                    throw SourceException.BadResponse("Plex sent unreadable JSON for $path", e)
                }
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 200

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /** RFC 3986 percent-encoding for path segments. */
        fun enc(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = (b.toInt() and 0xFF)
                if (c < 128 && UNRESERVED.indexOf(c.toChar()) >= 0) append(c.toChar())
                else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            }
        }
    }
}

class PlexSourceFactory(private val http: HttpClient) : SourceFactory {
    override fun create(config: SourceConfig): ContentSource? =
        (config as? SourceConfig.Plex)?.let { PlexSource(it, http) }
}
