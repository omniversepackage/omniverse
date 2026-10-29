package com.yodesla.omniverse.feature.home

import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.metadata.Tmdb
import com.yodesla.omniverse.core.data.metadata.TmdbEnricher
import com.yodesla.omniverse.core.data.metadata.WikidataMetadata
import com.yodesla.omniverse.core.data.metadata.landscapeBackdrop
import com.yodesla.omniverse.core.model.ContentKind

/** Decorative art for a focused title: landscape backdrop (never the poster), TMDB title logo, overview. */
internal data class TitleArt(val backdrop: String?, val logo: String?, val overview: String?)

/**
 * Shared by Home's preview and For You's hero. TMDB's verified backdrop wins; the source's own
 * lightweight preview art is the fallback (Plex lists can omit GUIDs and backdrops, so only this
 * item's metadata is fetched — never seriesDetail, which loads every episode).
 */
internal suspend fun resolveTitleArt(card: HomeCard, sources: SourceRepository?, tmdb: TmdbEnricher?): TitleArt {
    val identity = card.titleIdentity?.takeIf { it.namespace == "tmdb" }?.externalId
    val sourceArt = try {
        sources?.contentSource(card.open.sourceId)?.previewArtwork(card.open.kind, card.open.remoteId)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    val kind = if (card.open.kind == ContentKind.SERIES) WikidataMetadata.Kind.SERIES else WikidataMetadata.Kind.MOVIE
    val meta = tmdb?.meta(kind, identity ?: sourceArt?.tmdbId)
    return TitleArt(
        backdrop = landscapeBackdrop(Tmdb.backdropUrl(meta?.backdropPath), sourceArt?.backdropUrl ?: card.backdropUrl, card.image),
        logo = Tmdb.logoUrl(meta?.logoPath),
        overview = meta?.overview,
    )
}
