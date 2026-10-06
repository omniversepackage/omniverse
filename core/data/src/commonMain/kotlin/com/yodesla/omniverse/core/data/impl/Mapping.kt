package com.yodesla.omniverse.core.data.impl

import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.database.Channel as ChannelDb
import com.yodesla.omniverse.core.database.Programme as ProgrammeDb
import com.yodesla.omniverse.core.database.Series as SeriesDb
import com.yodesla.omniverse.core.database.Vod as VodDb

internal const val DEFAULT_PROFILE = "default"

internal fun ChannelDb.toRow() = ChannelRow(
    key = ContentKey(SourceId(source_id), ContentKind.LIVE, RemoteId(remote_id)),
    number = number?.toInt(),
    name = name,
    logoUrl = logo_url,
    epgKey = epg_channel_id,
    catchupDays = catchup_days.toInt(),
    categoryId = RemoteId(primary_category_id),
)

@Suppress("LongParameterList")
internal fun channelRow(
    sourceId: String, remoteId: String, number: Long?, name: String, logoUrl: String?, epgKey: String?,
    categoryId: String, catchupDays: Long,
) = ChannelRow(
    key = ContentKey(SourceId(sourceId), ContentKind.LIVE, RemoteId(remoteId)),
    number = number?.toInt(),
    name = name,
    logoUrl = logoUrl,
    epgKey = epgKey,
    catchupDays = catchupDays.toInt(),
    categoryId = RemoteId(categoryId),
)

internal fun VodDb.toPoster() = PosterRow(
    key = ContentKey(SourceId(source_id), ContentKind.VOD, RemoteId(remote_id)),
    name = name,
    posterUrl = poster_url,
    year = year?.toInt(),
    rating = rating?.toFloat(),
    categoryId = RemoteId(primary_category_id),
    tmdbId = tmdb_id?.takeIf(String::isNotBlank),
    genre = genre?.takeIf(String::isNotBlank),
)

internal fun SeriesDb.toPoster() = PosterRow(
    key = ContentKey(SourceId(source_id), ContentKind.SERIES, RemoteId(remote_id)),
    name = name,
    posterUrl = poster_url,
    year = year?.toInt(),
    rating = rating?.toFloat(),
    categoryId = RemoteId(primary_category_id),
    tmdbId = tmdb_id?.takeIf(String::isNotBlank),
    backdropUrl = backdrop_url?.takeIf(String::isNotBlank),
    plot = plot?.takeIf(String::isNotBlank),
    genre = genre?.takeIf(String::isNotBlank),
)

internal fun ProgrammeDb.toRecord() = ProgrammeRecord(
    sourceId = SourceId(source_id),
    channelKey = channel_key,
    startMs = start_ms,
    endMs = end_ms,
    title = title,
    subtitle = subtitle,
    description = description,
    category = category,
    episodeNum = episode_num,
    iconUrl = icon_url,
    hasArchive = has_archive != 0L,
)
