package com.yodesla.omniverse.feature.home.phoneremote

import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.ProgrammeHit
import com.yodesla.omniverse.core.data.SearchHit
import com.yodesla.omniverse.core.data.SearchRepository
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind

/**
 * The phone page's search: the same repositories and the same parental/library visibility the TV
 * browse screens use, so a phone can never surface a title the current profile is not allowed to see.
 * [buildResults] is pure (no repositories) so visibility filtering is unit-testable with fakes.
 */
object PhoneRemoteSearch {
    suspend fun run(
        search: SearchRepository,
        catalog: CatalogRepository,
        visibility: Visibility,
        query: String,
        nowMs: Long,
    ): List<RemoteResult> {
        if (query.isBlank()) return emptyList()
        val hits = search.search(query, limitPerKind = 20)
        val programmes = search.searchProgrammes(query, nowMs)
        return buildResults(
            liveHits = hits[ContentKind.LIVE].orEmpty(),
            vodHits = hits[ContentKind.VOD].orEmpty(),
            seriesHits = hits[ContentKind.SERIES].orEmpty(),
            programmes = programmes,
            channelOf = { catalog.channel(it) },
            posterOf = { catalog.poster(it) },
            vis = visibility,
        )
    }

    suspend fun buildResults(
        liveHits: List<SearchHit>,
        vodHits: List<SearchHit>,
        seriesHits: List<SearchHit>,
        programmes: List<ProgrammeHit>,
        channelOf: suspend (ContentKey) -> ChannelRow?,
        posterOf: suspend (ContentKey) -> PosterRow?,
        vis: Visibility,
    ): List<RemoteResult> {
        val out = ArrayList<RemoteResult>()
        for (h in liveHits) {
            val ch = channelOf(h.key) ?: continue
            if (!vis("LIVE", ch.key.sourceId.value, ch.categoryId.value)) continue
            out += RemoteResult("LIVE", ch.key.sourceId.value, ch.key.remoteId.value, ch.categoryId.value,
                ch.name, ch.number?.toString(), ch.logoUrl, "channel")
        }
        for (p in programmes) {
            if (!vis("LIVE", p.channel.sourceId.value, p.categoryId.value)) continue
            out += RemoteResult("LIVE", p.channel.sourceId.value, p.channel.remoteId.value, p.categoryId.value,
                p.title, p.channelName, p.logoUrl, "channel")
        }
        for ((kind, hits) in listOf(ContentKind.VOD to vodHits, ContentKind.SERIES to seriesHits)) {
            val label = if (kind == ContentKind.VOD) "movie" else "show"
            val kindName = kind.name
            val seen = HashSet<String>()
            for (h in hits) {
                val poster = posterOf(h.key) ?: continue
                val cat = poster.categoryId
                if (cat != null && !vis(kindName, poster.key.sourceId.value, cat.value)) continue
                val dedupe = poster.tmdbId?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" }
                    ?: "key:${poster.key.sourceId.value}:${poster.key.remoteId.value}"
                if (!seen.add(dedupe)) continue
                out += RemoteResult(kindName, poster.key.sourceId.value, poster.key.remoteId.value, cat?.value,
                    poster.name, poster.year?.toString(), poster.posterUrl, label)
            }
        }
        return out
    }
}
