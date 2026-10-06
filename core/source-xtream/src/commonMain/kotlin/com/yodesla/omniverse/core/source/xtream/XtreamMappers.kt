package com.yodesla.omniverse.core.source.xtream

import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ChannelRecord
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.Episode
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.Season
import com.yodesla.omniverse.core.model.SeriesDetail
import com.yodesla.omniverse.core.model.SeriesRecord
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.VodDetail
import com.yodesla.omniverse.core.model.VodRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

object XtreamMappers {

    fun category(o: JsonObject, kind: ContentKind, sourceId: SourceId, index: Int): Category? =
        runCatching {
            val id = o.str("category_id") ?: return@runCatching null
            val name = o.str("category_name") ?: return@runCatching null
            val parentId = o.str("parent_id")?.takeIf { it != "0" }
            Category(
                sourceId = sourceId,
                kind = kind,
                remoteId = RemoteId(id),
                name = name,
                parentId = parentId?.let { RemoteId(it) },
                sortIndex = index,
            )
        }.getOrNull()

    fun channel(o: JsonObject, sourceId: SourceId, index: Int): ChannelRecord? =
        runCatching {
            val id = o.str("stream_id") ?: return@runCatching null
            val name = o.str("name") ?: return@runCatching null
            val categoryIds =
                o.strList("category_ids").ifEmpty { o.strList("category_id") }
                    .ifEmpty { listOf("uncategorized") }
            val catchup = if (o.bool("tv_archive") == true) o.int("tv_archive_duration") ?: 0 else 0
            ChannelRecord(
                sourceId = sourceId,
                remoteId = RemoteId(id),
                number = o.int("num"),
                name = decodeHtmlEntities(name),
                logoUrl = o.str("stream_icon")?.takeIf { it.startsWith("http", ignoreCase = true) },
                epgChannelId = o.str("epg_channel_id"),
                categoryIds = categoryIds.map { RemoteId(it) },
                catchupDays = catchup,
                addedAtMs = o.long("added")?.times(1000),
                sortIndex = index,
            )
        }.getOrNull()

    fun vod(o: JsonObject, sourceId: SourceId, index: Int): VodRecord? =
        runCatching {
            val id = o.str("stream_id") ?: return@runCatching null
            val name = o.str("name") ?: return@runCatching null
            val rating = (o.float("rating") ?: o.float("rating_5based")?.times(2f))
                ?.takeIf { it in 0f..10f }
            val year = o.str("year")?.first4Digits() ?: name.trailingYear()
            val categoryIds =
                o.strList("category_ids").ifEmpty { o.strList("category_id") }
                    .ifEmpty { listOf("uncategorized") }
            VodRecord(
                sourceId = sourceId,
                remoteId = RemoteId(id),
                name = decodeHtmlEntities(name),
                posterUrl = o.str("stream_icon")?.takeIf { it.startsWith("http", ignoreCase = true) },
                categoryIds = categoryIds.map { RemoteId(it) },
                rating = rating,
                year = year,
                addedAtMs = o.long("added")?.times(1000),
                containerExt = o.str("container_extension"),
                tmdbId = o.str("tmdb") ?: o.str("tmdb_id"),
                sortIndex = index,
                genre = o.str("genre"),
            )
        }.getOrNull()

    fun series(o: JsonObject, sourceId: SourceId, index: Int): SeriesRecord? =
        runCatching {
            val id = o.str("series_id") ?: return@runCatching null
            val name = o.str("name") ?: return@runCatching null
            val year = o.str("releaseDate")?.first4Digits() ?: o.str("year")?.first4Digits()
            val categoryIds =
                o.strList("category_ids").ifEmpty { o.strList("category_id") }
                    .ifEmpty { listOf("uncategorized") }
            SeriesRecord(
                sourceId = sourceId,
                remoteId = RemoteId(id),
                name = decodeHtmlEntities(name),
                posterUrl = o.str("cover")?.takeIf { it.startsWith("http", ignoreCase = true) },
                backdropUrls = o.strList("backdrop_path").filter { it.startsWith("http", ignoreCase = true) },
                categoryIds = categoryIds.map { RemoteId(it) },
                plot = o.str("plot"),
                genre = o.str("genre"),
                rating = o.float("rating")?.takeIf { it in 0f..10f },
                year = year,
                lastModifiedMs = o.long("last_modified")?.times(1000),
                sortIndex = index,
                // Most panels call the series field "tmdb"; some use "tmdb_id" (movies already read both).
                tmdbId = (o.str("tmdb_id") ?: o.str("tmdb"))?.trim()?.takeIf { it.isNotBlank() && it != "0" },
            )
        }.getOrNull()

    fun vodDetail(root: JsonObject, fallback: VodRecord): VodDetail {
        val info = root.obj("info") ?: return fallback.toDetail()
        val data = root.obj("movie_data")
        val durationSec = info.int("duration_secs") ?: info.str("duration")?.hmsToSecs()
        val trailer = info.str("youtube_trailer")?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http", ignoreCase = true)) it
            else "https://www.youtube.com/watch?v=$it"
        }
        val dataCats = data?.let { d ->
            d.strList("category_ids").ifEmpty { d.strList("category_id") }
        }
        val record = VodRecord(
            sourceId = fallback.sourceId,
            remoteId = data?.str("stream_id")?.let { RemoteId(it) } ?: fallback.remoteId,
            name = data?.str("name")?.let { decodeHtmlEntities(it) } ?: fallback.name,
            posterUrl = info.str("movie_image")?.takeIf { it.startsWith("http", ignoreCase = true) }
                ?: fallback.posterUrl,
            categoryIds = if (dataCats.isNullOrEmpty()) fallback.categoryIds
            else dataCats.map { RemoteId(it) },
            rating = (info.float("rating") ?: fallback.rating)?.takeIf { it in 0f..10f },
            year = info.str("releasedate")?.first4Digits() ?: fallback.year,
            addedAtMs = fallback.addedAtMs,
            containerExt = data?.str("container_extension") ?: fallback.containerExt,
            tmdbId = info.str("tmdb_id") ?: info.str("tmdb") ?: fallback.tmdbId,
            sortIndex = fallback.sortIndex,
        )
        return VodDetail(
            record = record,
            plot = info.str("plot"),
            cast = info.str("cast"),
            director = info.str("director"),
            genre = info.str("genre"),
            durationSec = durationSec,
            backdropUrls = info.strList("backdrop_path").filter { it.startsWith("http", ignoreCase = true) },
            releaseDate = info.str("releasedate"),
            trailerUrl = trailer,
        )
    }

    fun seriesDetail(root: JsonObject, fallback: SeriesRecord): SeriesDetail {
        val info = root.obj("info")
        val infoCats = info?.let { i ->
            i.strList("category_ids").ifEmpty { i.strList("category_id") }
        }
        val record = if (info != null) {
            SeriesRecord(
                sourceId = fallback.sourceId,
                remoteId = info.str("series_id")?.let { RemoteId(it) } ?: fallback.remoteId,
                name = info.str("name")?.let { decodeHtmlEntities(it) } ?: fallback.name,
                posterUrl = info.str("cover")?.takeIf { it.startsWith("http", ignoreCase = true) }
                    ?: fallback.posterUrl,
                backdropUrls = info.strList("backdrop_path")
                    .filter { it.startsWith("http", ignoreCase = true) }
                    .ifEmpty { fallback.backdropUrls },
                categoryIds = if (infoCats.isNullOrEmpty()) fallback.categoryIds
                else infoCats.map { RemoteId(it) },
                plot = info.str("plot") ?: fallback.plot,
                genre = info.str("genre") ?: fallback.genre,
                rating = (info.float("rating") ?: fallback.rating)?.takeIf { it in 0f..10f },
                year = info.str("releaseDate")?.first4Digits()
                    ?: info.str("year")?.first4Digits() ?: fallback.year,
                lastModifiedMs = info.long("last_modified")?.times(1000) ?: fallback.lastModifiedMs,
                sortIndex = fallback.sortIndex,
                tmdbId = (info.str("tmdb_id") ?: info.str("tmdb"))?.trim()?.takeIf { it.isNotBlank() && it != "0" } ?: fallback.tmdbId,
            )
        } else fallback

        val seasonsMeta = root.arr("seasons")?.mapNotNull { el ->
            val so = el as? JsonObject ?: return@mapNotNull null
            so.int("season_number")?.let { n -> n to so }
        }?.toMap()

        val byNumber = LinkedHashMap<Int, MutableList<Episode>>()
        val episodesObj = root.obj("episodes")
        if (episodesObj != null) {
            for ((key, value) in episodesObj) {
                val seasonNum = key.toIntOrNull() ?: continue
                val list = value as? JsonArray ?: continue
                for (ep in list) {
                    val e = ep as? JsonObject ?: continue
                    val episode = episode(e, record, seasonNum) ?: continue
                    byNumber.getOrPut(seasonNum) { mutableListOf() }.add(episode)
                }
            }
        } else {
            val episodesArr = root.arr("episodes")
            if (episodesArr != null) {
                episodesArr.forEachIndexed { i, el ->
                    val list = el as? JsonArray ?: return@forEachIndexed
                    for (ep in list) {
                        val e = ep as? JsonObject ?: continue
                        val fallback = (e.int("season") ?: (i + 1))
                        val episode = episode(e, record, fallback) ?: continue
                        byNumber.getOrPut(episode.season) { mutableListOf() }.add(episode)
                    }
                }
            }
        }

        val seasons = byNumber.keys.sorted().map { n ->
            val meta = seasonsMeta?.get(n)
            Season(
                number = n,
                name = meta?.str("name") ?: "Season $n",
                posterUrl = meta?.str("cover")?.takeIf { it.startsWith("http", ignoreCase = true) },
                episodes = byNumber.getValue(n).sortedBy { it.number },
            )
        }
        return SeriesDetail(
            record = record,
            cast = info?.str("cast"),
            director = info?.str("director"),
            seasons = seasons,
        )
    }

    fun shortEpg(root: JsonObject, sourceId: SourceId, channelKey: String): List<ProgrammeRecord> {
        val listings = root.arr("epg_listings") ?: return emptyList()
        return shortEpgItems(listings, sourceId, channelKey)
    }

    fun shortEpgItems(listings: JsonArray, sourceId: SourceId, channelKey: String): List<ProgrammeRecord> =
        listings.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            runCatching {
                val title = o.str("title")?.let { lenientBase64(it) } ?: return@runCatching null
                val startMs = o.long("start_timestamp")?.times(1000)
                    ?: o.str("start")?.dateTimeToUtcMs()
                    ?: return@runCatching null
                val endMs = o.long("stop_timestamp")?.times(1000)
                    ?: o.str("end")?.dateTimeToUtcMs()
                    ?: startMs
                ProgrammeRecord(
                    sourceId = sourceId,
                    channelKey = o.str("epg_id") ?: channelKey,
                    startMs = startMs,
                    endMs = endMs,
                    title = title,
                    description = o.str("description")?.let { lenientBase64(it) },
                    hasArchive = o.bool("has_archive") ?: false,
                )
            }.getOrNull()
        }

    fun accountInfo(root: JsonObject): AccountInfo {
        val user = root.obj("user_info")
        val server = root.obj("server_info")
        val status = when {
            user?.bool("auth") == false -> AccountStatus.AUTH_FAILED
            else -> when (user?.str("status")?.trim()?.lowercase()) {
                "active" -> AccountStatus.ACTIVE
                "expired" -> AccountStatus.EXPIRED
                "banned" -> AccountStatus.BANNED
                "disabled" -> AccountStatus.DISABLED
                else -> AccountStatus.UNKNOWN
            }
        }
        return runCatching {
            AccountInfo(
                status = status,
                expiresAtMs = user?.str("exp_date")?.toLongOrNull()?.times(1000),
                maxConnections = user?.int("max_connections"),
                activeConnections = user?.int("active_cons"),
                isTrial = user?.bool("is_trial") ?: false,
                allowedOutputFormats = user?.strList("allowed_output_formats") ?: emptyList(),
                serverTimeZone = server?.str("timezone"),
                serverNowMs = server?.long("timestamp_now")?.times(1000),
            )
        }.getOrNull() ?: AccountInfo(
            status = status,
            expiresAtMs = null,
            maxConnections = null,
            activeConnections = null,
            isTrial = false,
            allowedOutputFormats = emptyList(),
            serverTimeZone = null,
            serverNowMs = null,
        )
    }

    private fun episode(e: JsonObject, series: SeriesRecord, seasonFallback: Int): Episode? =
        runCatching {
            val id = e.str("id") ?: e.str("episode_id") ?: return@runCatching null
            val number = e.int("episode_num") ?: return@runCatching null
            val season = e.int("season") ?: seasonFallback
            val title = e.str("title") ?: return@runCatching null
            val info = e.obj("info")
            Episode(
                sourceId = series.sourceId,
                remoteId = RemoteId(id),
                seriesId = series.remoteId,
                season = season,
                number = number,
                title = title,
                plot = info?.str("plot"),
                durationSec = info?.int("duration_secs"),
                stillUrl = info?.str("movie_image")?.takeIf { it.startsWith("http", ignoreCase = true) },
                containerExt = e.str("container_extension"),
                rating = info?.float("rating")?.takeIf { it in 0f..10f },
            )
        }.getOrNull()

    private fun VodRecord.toDetail() = VodDetail(
        record = this,
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

private val year4 = Regex("(\\d{4})")
private val yearTrailing = Regex("\\((\\d{4})\\)\\s*$")
private val hmsRegex = Regex("^(\\d+):(\\d{1,2})(?::(\\d{1,2}))?$")
private val dtRegex = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?$")

private fun String.first4Digits(): Int? = year4.find(this)?.groupValues?.get(1)?.toIntOrNull()

private fun String.trailingYear(): Int? =
    yearTrailing.find(this)?.groupValues?.get(1)?.toIntOrNull()

private fun String.hmsToSecs(): Int? {
    val m = hmsRegex.matchEntire(this.trim()) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: return null
    val min = m.groupValues[2].toIntOrNull() ?: return null
    val s = m.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
    return (h * 3600 + min * 60 + s).takeIf { it > 0 }
}

private fun String.dateTimeToUtcMs(): Long? {
    val m = dtRegex.matchEntire(this.trim()) ?: return null
    val g = m.groupValues
    val y = g[1].toIntOrNull() ?: return null
    val mo = g[2].toIntOrNull() ?: return null
    val d = g[3].toIntOrNull() ?: return null
    val h = g[4].toIntOrNull() ?: return null
    val mi = g[5].toIntOrNull() ?: return null
    val s = g[6].ifEmpty { "0" }.toIntOrNull() ?: return null
    val secs = prolepticDays(y, mo, d) * 86400L + h * 3600L + mi * 60L + s
    return secs * 1000
}

private fun prolepticDays(y: Int, m: Int, d: Int): Long {
    if (m < 1 || m > 12 || d < 1) return 0
    val yy = if (m <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val mp = (m + 9) % 12
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468
}
