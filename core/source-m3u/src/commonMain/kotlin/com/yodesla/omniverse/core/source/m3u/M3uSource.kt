package com.yodesla.omniverse.core.source.m3u

import com.yodesla.omniverse.core.epg.XmltvParser
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
import com.yodesla.omniverse.core.model.SeriesDetail
import com.yodesla.omniverse.core.model.SeriesRecord
import com.yodesla.omniverse.core.model.Season
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
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.core.source.SourceFactory
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.BufferedSource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * M3U/M3U8 playlist provider. The playlist is the whole catalog, so it is fetched and parsed
 * once per sync and the compact result is cached for 10 minutes; every list method streams
 * from that cache. withRetry() wraps OPENING a request only. Every URL in an exception
 * message goes through Redact.
 */
@OptIn(ExperimentalTime::class)
class M3uSource(
    private val config: SourceConfig.M3u,
    private val http: HttpClient,
    private val retry: RetryPolicy = RetryPolicy(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ContentSource {

    override val id: SourceId = config.id
    override val kind = SourceKind.M3U
    override val capabilities: Set<Capability>
        get() {
            val base = setOf(Capability.LIVE, Capability.EPG, Capability.VOD, Capability.SERIES)
            return if (snapshot?.entries?.any { it.catchupConfigured } == true) base + Capability.CATCHUP else base
        }

    private val cacheMutex = Mutex()
    private var snapshot: Snapshot? = null

    // ---------------------------------------------------------------- account

    override suspend fun accountInfo(): AccountInfo {
        playlist(SyncDiagnostics.None) // throws AuthFailed on 401/403
        return AccountInfo(
            status = AccountStatus.ACTIVE,
            expiresAtMs = null,
            maxConnections = null,
            activeConnections = null,
            isTrial = false,
            allowedOutputFormats = emptyList(),
            serverTimeZone = null,
            serverNowMs = null,
        )
    }

    // ---------------------------------------------------------------- lists

    override fun liveCategories(): Flow<Category> =
        categoriesFlow(M3uKind.LIVE, ContentKind.LIVE)

    override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> = flow {
        val snap = playlist(diagnostics)
        for (e in snap.entries) {
            if (e.kind != M3uKind.LIVE) continue
            emit(
                ChannelRecord(
                    sourceId = id,
                    remoteId = e.remoteId,
                    number = e.chno,
                    name = e.name,
                    logoUrl = e.logo,
                    epgChannelId = e.tvgId ?: e.tvgName ?: e.name,
                    categoryIds = listOf(RemoteId(e.groupName)),
                    catchupDays = e.catchupDays ?: if (e.catchupConfigured) DEFAULT_CATCHUP_DAYS else 0,
                    addedAtMs = null,
                    sortIndex = e.sortIndex,
                ),
            )
        }
    }.flowOn(io)

    override fun vodCategories(): Flow<Category> =
        categoriesFlow(M3uKind.MOVIE, ContentKind.VOD)

    override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> = flow {
        val snap = playlist(diagnostics)
        for (e in snap.entries) {
            if (e.kind != M3uKind.MOVIE) continue
            emit(vodRecord(e))
        }
    }.flowOn(io)

    override fun seriesCategories(): Flow<Category> =
        categoriesFlow(M3uKind.SERIES, ContentKind.SERIES)

    override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> = flow {
        val snap = playlist(diagnostics)
        for ((show, first) in snap.shows()) {
            emit(seriesRecord(show, first))
        }
    }.flowOn(io)

    // ---------------------------------------------------------------- details

    override suspend fun vodDetail(id: RemoteId): VodDetail {
        val e = playlist(SyncDiagnostics.None).entries.firstOrNull { it.remoteId == id && it.kind == M3uKind.MOVIE }
            ?: throw SourceException.NotFound("Movie $id not found in playlist")
        return VodDetail(
            record = vodRecord(e),
            plot = null,
            cast = null,
            director = null,
            genre = null,
            durationSec = null,
            backdropUrls = emptyList(),
            releaseDate = null,
            trailerUrl = null,
        )
    }

    override suspend fun seriesDetail(id: RemoteId): SeriesDetail {
        val snap = playlist(SyncDiagnostics.None)
        val episodes = snap.entries.filter { it.kind == M3uKind.SERIES && it.seriesRemoteId() == id }
            .sortedBy { it.sortIndex }
        if (episodes.isEmpty()) throw SourceException.NotFound("Series $id not found in playlist")
        val bySeason = LinkedHashMap<Int, MutableList<Episode>>()
        val nextNumber = HashMap<Int, Int>()
        for (e in episodes) {
            val (season, parsedNumber) = episodeNumbers(e.name)
            val number = parsedNumber ?: nextNumber.getOrPut(season) { 1 }.also { nextNumber[season] = it + 1 }
            bySeason.getOrPut(season) { ArrayList() } += Episode(
                sourceId = this.id,
                remoteId = e.remoteId,
                seriesId = id,
                season = season,
                number = number,
                title = e.name,
                plot = null,
                durationSec = null,
                stillUrl = e.logo,
                containerExt = extFromUrl(e.url),
                rating = null,
            )
        }
        val seasons = bySeason.keys.sorted().map { n ->
            Season(number = n, name = null, posterUrl = null, episodes = bySeason.getValue(n).sortedBy { it.number })
        }
        val first = episodes.first()
        return SeriesDetail(record = seriesRecord(first.showName, first), cast = null, director = null, seasons = seasons)
    }

    override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = emptyList()

    // ---------------------------------------------------------------- guide

    override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = flow {
        val snap = playlist(diagnostics)
        val url = config.epgUrl?.takeIf { it.isNotBlank() } ?: snap.epgUrls.firstOrNull()
        if (url == null) return@flow
        open(url).use { r ->
            for (p in XmltvParser(diagnostics).parse(r.body, id, window, channelKeys)) emit(p)
        }
    }.flowOn(io)

    // ---------------------------------------------------------------- playback

    override suspend fun playback(request: PlaybackRequest): PlaybackSpec {
        // After an app restart the in-memory snapshot is gone; reload (cached 10 min) rather than fail.
        val snap = playlist(SyncDiagnostics.None)
        return when (request) {
            is PlaybackRequest.Live -> {
                val e = snap.entries.firstOrNull { it.remoteId == request.channelId }
                    ?: throw SourceException.NotFound("Channel ${request.channelId.value} not found in playlist")
                spec(e.url, headersFor(e), hintForUrl(e.url), live = true)
            }
            is PlaybackRequest.Vod -> {
                val e = snap.entries.firstOrNull { it.remoteId == request.vodId }
                    ?: throw SourceException.NotFound("Movie ${request.vodId.value} not found in playlist")
                spec(e.url, headersFor(e), hintForUrl(e.url), live = false)
            }
            is PlaybackRequest.EpisodeItem -> {
                val e = snap.entries.firstOrNull { it.remoteId == request.episodeId }
                    ?: throw SourceException.NotFound("Episode ${request.episodeId.value} not found in playlist")
                spec(e.url, headersFor(e), hintForUrl(e.url), live = false)
            }
            is PlaybackRequest.Catchup -> {
                val e = snap.entries.firstOrNull { it.remoteId == request.channelId }
                    ?: throw SourceException.NotFound("Channel ${request.channelId.value} not found in playlist")
                val start = request.startMs
                val end = start + request.durationMinutes * 60_000L
                val url = buildCatchupUrl(e.toM3uEntry(), start, end, nowMs())
                    ?: throw SourceException.Unsupported("No catch-up configured for channel ${request.channelId.value}")
                spec(url, headersFor(e), hintForUrl(url), live = false)
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private fun categoriesFlow(kind: M3uKind, contentKind: ContentKind): Flow<Category> = flow {
        val snap = playlist(SyncDiagnostics.None)
        val order = LinkedHashSet<String>()
        for (e in snap.entries) {
            if (e.kind == kind) order += e.groupName
        }
        order.forEachIndexed { i, name ->
            emit(Category(sourceId = id, kind = contentKind, remoteId = RemoteId(name), name = name, parentId = null, sortIndex = i))
        }
    }.flowOn(io)

    private fun vodRecord(e: CachedEntry): VodRecord = VodRecord(
        sourceId = id,
        remoteId = e.remoteId,
        name = e.name,
        posterUrl = e.logo,
        categoryIds = listOf(RemoteId(e.groupName)),
        rating = null,
        year = null,
        addedAtMs = null,
        containerExt = extFromUrl(e.url),
        tmdbId = null,
        sortIndex = e.sortIndex,
    )

    private fun seriesRecord(show: String, first: CachedEntry): SeriesRecord = SeriesRecord(
        sourceId = id,
        remoteId = RemoteId(fnv1a64Hex(show)),
        name = show,
        posterUrl = first.logo,
        backdropUrls = emptyList(),
        categoryIds = listOf(RemoteId(first.groupName)),
        plot = null,
        genre = null,
        rating = null,
        year = null,
        lastModifiedMs = null,
        sortIndex = first.sortIndex,
    )

    /** Compact cached copy of one playlist entry; nothing but these fields is kept. */
    private data class CachedEntry(
        val name: String,
        val url: String,
        val group: String?,
        val logo: String?,
        val tvgId: String?,
        val tvgName: String?,
        val chno: Int?,
        val catchup: String?,
        val catchupDays: Int?,
        val catchupSource: String?,
        val userAgent: String?,
        val referrer: String?,
        val kind: M3uKind,
        val remoteId: RemoteId,
        val sortIndex: Int,
    ) {
        val groupName: String get() = group ?: UNCATEGORIZED
        val catchupConfigured: Boolean get() = !catchup.isNullOrBlank() || !catchupSource.isNullOrBlank()
        val showName: String get() = showNameOf(name)
        fun seriesRemoteId(): RemoteId = RemoteId(fnv1a64Hex(showName))

        fun toM3uEntry(): M3uEntry = M3uEntry(
            name = name,
            url = url,
            durationSec = 0,
            attrs = emptyMap(),
            group = group,
            tvgId = tvgId,
            tvgName = tvgName,
            logo = logo,
            chno = chno,
            catchup = catchup,
            catchupDays = catchupDays,
            catchupSource = catchupSource,
            userAgent = userAgent,
            referrer = referrer,
            index = sortIndex,
        )
    }

    /** Parsed playlist: compact entries plus the header EPG urls and the fetch time. */
    private class Snapshot(
        val entries: List<CachedEntry>,
        val epgUrls: List<String>,
        val fetchedAtMs: Long,
    ) {
        /** Show name -> first entry, in first-seen order. */
        fun shows(): List<Pair<String, CachedEntry>> {
            val map = LinkedHashMap<String, CachedEntry>()
            for (e in entries) {
                if (e.kind != M3uKind.SERIES) continue
                map.putIfAbsent(e.showName, e)
            }
            return map.entries.map { it.key to it.value }
        }
    }

    /** Fetches + parses the playlist at most once per [CACHE_TTL_MS]; concurrent callers share one fetch. */
    private suspend fun playlist(diagnostics: SyncDiagnostics): Snapshot = withContext(io) {
        cacheMutex.withLock {
            val current = snapshot
            if (current != null && nowMs() - current.fetchedAtMs < CACHE_TTL_MS) return@withLock current
            val response = open(config.playlistUrl)
            val parsed = try {
                response.use { r -> parsePlaylist(r.body, diagnostics) }
            } catch (e: okio.IOException) {
                // Never let a raw IOException reach UI callers (details, playback).
                throw SourceException.Network("Connection lost while reading the playlist", e)
            }
            snapshot = parsed
            parsed
        }
    }

    private fun parsePlaylist(body: BufferedSource, diagnostics: SyncDiagnostics): Snapshot {
        var header = M3uHeader(emptyList(), null, null, null)
        val raw = M3uParser(diagnostics).parse(body) { header = it }.toList()
        val tvgCounts = raw.mapNotNull { it.tvgId?.takeIf { id -> id.isNotBlank() } }
            .groupingBy { it }.eachCount()
        val entries = ArrayList<CachedEntry>(raw.size)
        for (e in raw) {
            val tvgId = e.tvgId?.takeIf { it.isNotBlank() }
            val remoteId = if (tvgId != null && tvgCounts[tvgId] == 1) RemoteId(tvgId) else RemoteId(fnv1a64Hex(e.url))
            entries += CachedEntry(
                name = e.name.ifBlank { e.url },
                url = e.url,
                group = e.group?.takeIf { it.isNotBlank() },
                logo = e.logo?.takeIf { it.isNotBlank() },
                tvgId = tvgId,
                tvgName = e.tvgName?.takeIf { it.isNotBlank() },
                chno = e.chno,
                catchup = e.catchup?.takeIf { it.isNotBlank() },
                catchupDays = e.catchupDays,
                catchupSource = e.catchupSource?.takeIf { it.isNotBlank() },
                userAgent = e.userAgent?.takeIf { it.isNotBlank() },
                referrer = e.referrer?.takeIf { it.isNotBlank() },
                kind = classify(e),
                remoteId = remoteId,
                sortIndex = entries.size,
            )
        }
        return Snapshot(entries, header.epgUrls, nowMs())
    }

    private fun spec(url: String, headers: Map<String, String>, hint: MimeHint, live: Boolean) =
        PlaybackSpec(url = url, headers = headers, mimeHint = hint, isLive = live, seekable = !live, redacted = Redact.text(url))

    private fun headersFor(e: CachedEntry): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val ua = e.userAgent ?: config.userAgent
        if (ua != null) map["User-Agent"] = ua
        if (e.referrer != null) map["Referer"] = e.referrer
        return map
    }

    private fun hintForUrl(url: String): MimeHint {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") -> MimeHint.HLS
            path.endsWith(".ts") -> MimeHint.MPEG_TS
            path.endsWith(".mkv") -> MimeHint.MKV
            path.endsWith(".mp4") -> MimeHint.MP4
            else -> MimeHint.UNKNOWN
        }
    }

    /** Opens [url] with retry on transient failures; 401/403 become AuthFailed. Caller closes the response. */
    private suspend fun open(url: String): HttpResponse = try {
        withRetry(retry) {
            val r = http.get(url, requestHeaders())
            r.requireSuccess(url)
            r
        }
    } catch (e: SourceException.Http) {
        if (e.status == 401 || e.status == 403) throw SourceException.AuthFailed("HTTP ${e.status} for ${Redact.text(url)}")
        throw e
    }

    private fun requestHeaders(): Map<String, String> =
        config.userAgent?.takeIf { it.isNotBlank() }?.let { mapOf("User-Agent" to it) } ?: emptyMap()

    private companion object {
        const val CACHE_TTL_MS = 10 * 60_000L
        const val DEFAULT_CATCHUP_DAYS = 7
        const val UNCATEGORIZED = "Uncategorized"
    }

}

private val SHOW_SUFFIX = Regex("^(.*?)[\\s]+[sS]\\d{1,2}[eE]\\d{1,4}(?:[\\s]+.*)?$")
private val EPISODE_TAG = Regex("\\b[sS](\\d{1,2})[eE](\\d{1,4})\\b")

/** Show name with a trailing ` S01E02` tag (and any text after it) stripped. */
private fun showNameOf(name: String): String {
    val show = SHOW_SUFFIX.matchEntire(name)?.groupValues?.get(1)?.trim().orEmpty()
    return show.ifEmpty { name }
}

/** `SxxEyy` / `sxxexx` tag in [name] as (season, number); (1, null) when unparseable. */
private fun episodeNumbers(name: String): Pair<Int, Int?> {
    val m = EPISODE_TAG.find(name) ?: return 1 to null
    val season = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..999 } ?: 1
    val number = m.groupValues[2].toIntOrNull()?.takeIf { it in 1..9999 }
    return season to number
}

/** Lower-case file extension of the URL path, or null when there is none. */
private fun extFromUrl(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#')
    val seg = path.substringAfterLast('/')
    val dot = seg.lastIndexOf('.')
    if (dot <= 0 || dot == seg.length - 1) return null
    return seg.substring(dot + 1).lowercase()
}

/** 64-bit FNV-1a of [s] as lowercase hex (16 chars). */
internal fun fnv1a64Hex(s: String): String {
    var h = -3_750_763_034_362_895_579L // 0xcbf29ce484222325
    for (b in s.encodeToByteArray()) {
        h = (h xor (b.toLong() and 0xFF)) * 1_099_511_628_211L // 0x100000001b3
    }
    return h.toULong().toString(16).padStart(16, '0')
}

class M3uSourceFactory(private val http: HttpClient) : SourceFactory {
    override fun create(config: SourceConfig): ContentSource? =
        (config as? SourceConfig.M3u)?.let { M3uSource(it, http) }
}
