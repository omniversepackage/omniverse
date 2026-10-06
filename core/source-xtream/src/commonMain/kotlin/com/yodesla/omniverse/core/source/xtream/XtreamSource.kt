package com.yodesla.omniverse.core.source.xtream

import com.yodesla.omniverse.core.epg.XmltvParser
import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.model.Capability
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ChannelRecord
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.MimeHint
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.PlaybackSpec
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.Redact
import com.yodesla.omniverse.core.model.RemoteId
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
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.okio.decodeBufferedSourceToSequence
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.json.put
import okio.BufferedSource
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Xtream Codes provider (PLAN.md Appendix A). Contract: ContentSource.kt header.
 * - Lists stream item-by-item straight off the socket; memory stays flat for 100k+ items.
 * - withRetry() wraps OPENING a request only; a half-read list is never re-read.
 * - Every URL in an exception message goes through Redact.
 */
@OptIn(ExperimentalSerializationApi::class)
class XtreamSource(
    private val config: SourceConfig.Xtream,
    private val http: HttpClient,
    private val retry: RetryPolicy = RetryPolicy(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ContentSource {

    override val id: SourceId = config.id
    override val kind = SourceKind.XTREAM
    override val capabilities = setOf(Capability.LIVE, Capability.EPG, Capability.VOD, Capability.SERIES, Capability.CATCHUP)

    private val base = config.server.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Provider time zone, learned from accountInfo(); catch-up URLs use provider-local time. */
    private var serverTimeZone: String? = null

    // ---------------------------------------------------------------- account

    override suspend fun accountInfo(): AccountInfo {
        val info = getObject(api(null), "account") ?: throw SourceException.BadResponse("Provider returned no account data")
        val account = XtreamMappers.accountInfo(info)
        if (account.status == AccountStatus.AUTH_FAILED) throw SourceException.AuthFailed()
        serverTimeZone = account.serverTimeZone
        return account
    }

    // ---------------------------------------------------------------- lists

    override fun liveCategories(): Flow<Category> =
        list("get_live_categories", SyncDiagnostics.None) { o, i -> XtreamMappers.category(o, ContentKind.LIVE, id, i) }

    override fun liveChannels(diagnostics: SyncDiagnostics): Flow<ChannelRecord> =
        list("get_live_streams", diagnostics) { o, i -> XtreamMappers.channel(o, id, i) }

    override fun vodCategories(): Flow<Category> =
        list("get_vod_categories", SyncDiagnostics.None) { o, i -> XtreamMappers.category(o, ContentKind.VOD, id, i) }

    override fun vodItems(diagnostics: SyncDiagnostics): Flow<VodRecord> =
        list("get_vod_streams", diagnostics) { o, i -> XtreamMappers.vod(o, id, i) }

    override fun seriesCategories(): Flow<Category> =
        list("get_series_categories", SyncDiagnostics.None) { o, i -> XtreamMappers.category(o, ContentKind.SERIES, id, i) }

    override fun series(diagnostics: SyncDiagnostics): Flow<SeriesRecord> =
        list("get_series", diagnostics) { o, i -> XtreamMappers.series(o, id, i) }

    // ---------------------------------------------------------------- details

    override suspend fun vodDetail(id: RemoteId): VodDetail {
        val root = getObject(api("get_vod_info", "vod_id" to id.value), "get_vod_info")
        val info = root?.get("info") as? JsonObject
        if (root == null || info == null || info.isEmpty()) throw SourceException.NotFound("Movie ${id.value} not found")
        val movieData = root["movie_data"] as? JsonObject ?: JsonObject(emptyMap())
        val fallback = XtreamMappers.vod(merge(movieData, info, "stream_id" to id.value), this.id, 0)
            ?: VodRecord(this.id, id, info.str("name") ?: "", null, emptyList(), null, null, null, movieData.str("container_extension"), null, 0)
        return XtreamMappers.vodDetail(root, fallback)
    }

    override suspend fun seriesDetail(id: RemoteId): SeriesDetail {
        val root = getObject(api("get_series_info", "series_id" to id.value), "get_series_info")
        val info = root?.get("info") as? JsonObject
        if (root == null || info == null || info.isEmpty()) throw SourceException.NotFound("Series ${id.value} not found")
        val fallback = XtreamMappers.series(merge(info, JsonObject(emptyMap()), "series_id" to id.value), this.id, 0)
            ?: SeriesRecord(this.id, id, info.str("name") ?: "", null, emptyList(), emptyList(), null, null, null, null, null, 0)
        return XtreamMappers.seriesDetail(root, fallback)
    }

    override suspend fun shortEpg(channelId: RemoteId, limit: Int): List<ProgrammeRecord> = withContext(io) {
        val url = api("get_short_epg", "stream_id" to channelId.value, "limit" to limit.toString())
        val key = "xt:${channelId.value}"
        // Standard shape is {"epg_listings":[...]}; some panels answer with the bare array.
        open(url).use { r ->
            val el = when (firstSignificantByte(r.body)) {
                '{'.code, '['.code -> try {
                    json.decodeFromBufferedSource(JsonElement.serializer(), r.body)
                } catch (e: SerializationException) {
                    throw SourceException.BadResponse("Provider sent unreadable data for get_short_epg", e)
                }
                -1 -> return@use emptyList()
                else -> throw SourceException.BadResponse("Provider returned HTML/invalid data for get_short_epg")
            }
            when (el) {
                is JsonObject -> XtreamMappers.shortEpg(el, id, key)
                is kotlinx.serialization.json.JsonArray -> XtreamMappers.shortEpgItems(el, id, key)
                else -> emptyList()
            }
        }
    }

    // ---------------------------------------------------------------- guide

    override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics): Flow<ProgrammeRecord> = flow {
        val url = config.epgUrlOverride ?: "$base/xmltv.php?username=${enc(config.username)}&password=${enc(config.password)}"
        val response = open(url)
        response.use { r ->
            // XmltvParser un-gzips transparently and streams; memory stays flat for 500 MB guides.
            for (p in XmltvParser(diagnostics).parse(r.body, id, window, channelKeys)) emit(p)
        }
    }.flowOn(io)

    // ---------------------------------------------------------------- playback

    override suspend fun playback(request: PlaybackRequest): PlaybackSpec {
        val u = encPath(config.username)
        val p = encPath(config.password)
        return when (request) {
            is PlaybackRequest.Live -> {
                val fmt = if (config.liveFormat.equals("m3u8", ignoreCase = true)) "m3u8" else "ts"
                spec("$base/live/$u/$p/${request.channelId.value}.$fmt", hintFor(fmt), live = true)
            }
            is PlaybackRequest.Vod -> {
                val ext = request.containerExt?.ifBlank { null } ?: "mp4"
                spec("$base/movie/$u/$p/${request.vodId.value}.$ext", hintFor(ext), live = false)
            }
            is PlaybackRequest.EpisodeItem -> {
                val ext = request.containerExt?.ifBlank { null } ?: "mp4"
                spec("$base/series/$u/$p/${request.episodeId.value}.$ext", hintFor(ext), live = false)
            }
            is PlaybackRequest.Catchup -> {
                val start = formatServerTime(request.startMs)
                spec("$base/timeshift/$u/$p/${request.durationMinutes}/$start/${request.channelId.value}.ts", MimeHint.MPEG_TS, live = false)
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private fun spec(url: String, hint: MimeHint, live: Boolean) =
        PlaybackSpec(url = url, mimeHint = hint, isLive = live, seekable = !live, redacted = Redact.text(url))

    private fun hintFor(ext: String) = when (ext.lowercase()) {
        "m3u8" -> MimeHint.HLS
        "ts" -> MimeHint.MPEG_TS
        "mkv" -> MimeHint.MKV
        "mp4", "m4v", "mov" -> MimeHint.MP4
        else -> MimeHint.UNKNOWN
    }

    @OptIn(ExperimentalTime::class)
    private fun formatServerTime(utcMs: Long): String {
        val tz = serverTimeZone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.UTC
        val t = Instant.fromEpochMilliseconds(utcMs).toLocalDateTime(tz)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${t.year}-${two(t.month.ordinal + 1)}-${two(t.day)}:${two(t.hour)}-${two(t.minute)}"
    }

    private fun api(action: String?, vararg params: Pair<String, String>): String = buildString {
        append(base).append("/player_api.php?username=").append(enc(config.username))
        append("&password=").append(enc(config.password))
        if (action != null) append("&action=").append(action)
        for ((k, v) in params) append('&').append(k).append('=').append(enc(v))
    }

    /** Opens [url] with retry on transient failures; the caller must close the response. */
    private suspend fun open(url: String): HttpResponse = withRetry(retry) {
        val r = http.get(url)
        r.requireSuccess(url)
        r
    }

    /**
     * Single objects are small, so the whole request + read is retried: a connection dropped
     * mid-body becomes SourceException.Network (never a raw IOException, which would crash callers).
     */
    private suspend fun getObject(url: String, what: String): JsonObject? = withContext(io) {
        withRetry(retry) {
            val r = http.get(url)
            r.use {
                r.requireSuccess(url)
                try {
                    when (firstSignificantByte(r.body)) {
                        '{'.code -> json.decodeFromBufferedSource(JsonElement.serializer(), r.body) as? JsonObject
                        '['.code, -1 -> null
                        else -> throw SourceException.BadResponse("Provider returned HTML/invalid data for $what")
                    }
                } catch (e: SerializationException) {
                    throw SourceException.BadResponse("Provider sent unreadable data for $what", e)
                } catch (e: okio.IOException) {
                    throw SourceException.Network("Connection lost while reading $what", e)
                }
            }
        }
    }

    private fun <T : Any> list(action: String, diagnostics: SyncDiagnostics, map: (JsonObject, Int) -> T?): Flow<T> = flow {
        val url = api(action)
        open(url).use { r ->
            when (firstSignificantByte(r.body)) {
                '['.code -> {
                    var index = 0
                    try {
                        for (el in json.decodeBufferedSourceToSequence(r.body, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)) {
                            val o = el as? JsonObject
                            val item = o?.let { map(it, index) }
                            if (item == null) diagnostics.skipped(action, "unreadable item #$index") else emit(item)
                            index++
                        }
                    } catch (e: SerializationException) {
                        throw SourceException.BadResponse("Provider data for $action was cut off or malformed after $index items", e)
                    } catch (e: okio.IOException) {
                        // Connection dropped mid-body: a network problem (retryable), not bad data.
                        throw SourceException.Network("Connection lost while reading $action after $index items", e)
                    }
                }
                -1 -> Unit // an empty body = "no items"
                '{'.code -> {
                    // Panels answer {} for "no items", but also send an object for errors
                    // ({"user_info":{"auth":0}}, {"error":...}). Only a truly empty object means empty:
                    // treating an error as an empty list would let sync mark the whole catalog removed.
                    val obj = try {
                        json.decodeFromBufferedSource(JsonElement.serializer(), r.body) as? JsonObject
                    } catch (e: SerializationException) {
                        throw SourceException.BadResponse("Provider sent unreadable data for $action", e)
                    } catch (e: okio.IOException) {
                        throw SourceException.Network("Connection lost while reading $action", e)
                    }
                    if (!obj.isNullOrEmpty()) {
                        val auth = (obj["user_info"] as? JsonObject)?.get("auth")?.toString()?.trim('"')
                        if (auth == "0") throw SourceException.AuthFailed()
                        throw SourceException.BadResponse("Provider answered $action with an error object")
                    }
                }
                else -> throw SourceException.BadResponse("Provider returned HTML/invalid data for $action")
            }
        }
    }.flowOn(io)

    private fun merge(a: JsonObject, b: JsonObject, vararg extra: Pair<String, String>): JsonObject = buildJsonObject {
        for ((k, v) in b) put(k, v)
        for ((k, v) in a) put(k, v)
        for ((k, v) in extra) put(k, v)
    }

    private companion object {
        /** First non-whitespace byte without consuming anything; -1 when the body is empty. */
        fun firstSignificantByte(body: BufferedSource): Int {
            val peek = body.peek()
            while (peek.request(1)) {
                val b = peek.readByte().toInt() and 0xFF
                if (b != ' '.code && b != '\n'.code && b != '\r'.code && b != '\t'.code && b != 0xEF && b != 0xBB && b != 0xBF) return b
            }
            return -1
        }

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /** RFC 3986 percent-encoding for query values. */
        fun enc(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = (b.toInt() and 0xFF)
                if (c < 128 && UNRESERVED.indexOf(c.toChar()) >= 0) append(c.toChar())
                else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
            }
        }

        /** Same encoding for path segments (Xtream puts user/pass in the path). */
        fun encPath(s: String) = enc(s)
    }
}

class XtreamSourceFactory(private val http: HttpClient) : SourceFactory {
    override fun create(config: SourceConfig): ContentSource? =
        (config as? SourceConfig.Xtream)?.let { XtreamSource(it, http) }
}
