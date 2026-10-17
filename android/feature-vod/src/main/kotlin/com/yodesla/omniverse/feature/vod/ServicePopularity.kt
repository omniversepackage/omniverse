package com.yodesla.omniverse.feature.vod

import androidx.compose.runtime.*
import com.yodesla.omniverse.designsystem.CategoryBrand
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.metadata.DiscoveryFeed
import com.yodesla.omniverse.core.data.metadata.DiscoveryRepository
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.flow.flowOf

val LocalDiscovery = staticCompositionLocalOf<DiscoveryRepository?> { null }

@Composable
internal fun servicePopularity(brand: CategoryBrand, kindLabel: String, selection: Pair<SourceId?, String?>?, pool: List<PosterRow>, visible: (PosterRow) -> Boolean): List<PosterRow> {
    val repo = LocalDiscovery.current ?: return emptyList()
    val kind = if (kindLabel.lowercase().startsWith("mov")) ContentKind.VOD else ContentKind.SERIES
    val type = if (kind == ContentKind.VOD) "movie" else "tv"
    val feed by remember(repo, brand, type) { repo.feed("service_${brand.name}_$type") }.collectAsState(DiscoveryFeed())
    LaunchedEffect(repo, brand, kind) { repo.ensureService(brand.name, kind) }
    val rows by produceState<List<PosterRow>>(emptyList(), repo, feed, selection, pool, visible) {
        value = repo.resolve(feed, kind, { visible(it) && inBrowseCategory(it, selection) }, 10)
    }
    return rows
}

internal data class OfficialNetflix(val rows: List<PosterRow> = emptyList(), val ranks: Map<String, Int> = emptyMap(), val week: String = "")

@Composable
internal fun officialNetflix(kindLabel: String, selection: Pair<SourceId?, String?>?, pool: List<PosterRow>, visible: (PosterRow) -> Boolean): OfficialNetflix {
    val repo = LocalDiscovery.current ?: return OfficialNetflix()
    val kind = if (kindLabel.lowercase().startsWith("mov")) ContentKind.VOD else ContentKind.SERIES
    val type = if (kind == ContentKind.VOD) "movie" else "tv"
    val feed by remember(repo, type) { repo.feed("netflix_official_$type") }.collectAsState(DiscoveryFeed())
    val value by produceState(OfficialNetflix(), repo, feed, selection, pool, visible) {
        val rows = repo.resolve(feed, kind, { visible(it) && inBrowseCategory(it, selection) }, 10)
        value = OfficialNetflix(rows, feed.titles.associate { it.id to it.rank }, feed.titles.firstOrNull()?.date.orEmpty())
    }
    return value
}
