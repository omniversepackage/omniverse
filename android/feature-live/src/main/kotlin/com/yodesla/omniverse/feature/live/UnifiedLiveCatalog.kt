package com.yodesla.omniverse.feature.live

import com.yodesla.omniverse.core.data.*
import com.yodesla.omniverse.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Source-qualified identifiers keep equal provider category/EPG IDs from colliding. */
internal fun liveScopedId(source: SourceId, id: String) = "__live:${source.value.length}:${source.value}$id"
internal fun liveScope(id: String, fallback: SourceId): Pair<SourceId, String> {
    if (!id.startsWith("__live:")) return fallback to id
    val split = id.indexOf(':', 7)
    val size = id.substring(7, split.takeIf { it >= 0 } ?: return fallback to id).toIntOrNull() ?: return fallback to id
    val rest = id.substring(split + 1)
    if (size !in 1..rest.length) return fallback to id
    return SourceId(rest.take(size)) to rest.drop(size)
}
internal fun liveEpgKey(row: ChannelRow): String? = row.epgKey?.let { liveScopedId(row.key.sourceId, it) }

/** Only presentation quality suffixes are ignored. Country, region, language and +1 stay distinct. */
private val liveQualitySuffix = Regex("(?:(?:\\s+|\\s*\\[)(?:sd|hd|fhd|uhd|4k|1080p|720p|2160p|hevc|h[ .]?26[45])\\]?)+$")
private val liveWhitespace = Regex("\\s+")
internal fun liveChannelIdentity(name: String): String = name.trim().lowercase()
    .replace(liveQualitySuffix, "")
    .trim().replace(liveWhitespace, " ")

internal fun groupedLiveChannels(rows: List<ChannelRow>): List<ChannelRow> =
    rows.distinctBy { liveChannelIdentity(it.name).takeIf(String::isNotBlank) ?: it.key.toString() }

internal data class LiveCategory(val source: SourceSummary, val category: Category, val id: String, val label: String)

@OptIn(ExperimentalCoroutinesApi::class)
internal class UnifiedLiveCatalog(private val sources: SourceRepository, private val catalog: CatalogRepository) {
    private val cacheLock = Mutex()
    private val alternativeCache = mutableMapOf<SourceId, Pair<Long, List<ChannelRow>>>()
    private suspend fun sourceRows(source: SourceId): List<ChannelRow> = cacheLock.withLock {
        val now = System.currentTimeMillis()
        alternativeCache[source]?.takeIf { now - it.first < 30_000 }?.second
            ?: catalog.channelListAll(source, emptyList()).also { alternativeCache[source] = now to it }
    }
    fun categories(): Flow<List<LiveCategory>> = sources.sources().flatMapLatest { saved ->
        val live = saved.filter { it.isLiveCapable() }
        if (live.isEmpty()) flowOf(emptyList()) else combine(live.map { source ->
            catalog.categories(source.id, ContentKind.LIVE).map { cats -> cats.map { cat ->
                LiveCategory(source, cat, if (live.size == 1) cat.remoteId.value else liveScopedId(source.id, cat.remoteId.value),
                    if (live.size == 1) cat.name else "${cat.name} · ${source.name}")
            } }
        }) { it.flatMap { list -> list } }
    }

    suspend fun rows(id: String, fallback: SourceId, visible: Visibility, excludedSources: Set<String> = emptySet()): List<ChannelRow> {
        val live = sources.sources().first().filter { it.isLiveCapable() && it.id.value !in excludedSources }
        val rows = if (id == LiveViewModel.ALL) live.flatMap { source ->
            val excluded = catalog.categories(source.id, ContentKind.LIVE).first()
                .filterNot { visible("LIVE", source.id.value, it.remoteId.value) }.map { it.remoteId.value }
            catalog.channelListAll(source.id, excluded)
        } else {
            val (source, category) = liveScope(id, fallback)
            if (source.value in excludedSources) return emptyList()
            catalog.channelList(source, RemoteId(category)).filter { visible("LIVE", it.key.sourceId.value, it.categoryId.value) }
        }
        // Off the caller's (main) thread: All channels can be tens of thousands of rows, and the
        // name normalisation behind the grouping froze the UI (ANRs) when it ran on Main.
        return if (rows.size > BIG_LIST) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { groupedLiveChannels(rows) } else groupedLiveChannels(rows)
    }

    suspend fun alternatives(row: ChannelRow, visible: Visibility): List<ChannelRow> =
        sources.sources().first().filter { it.isLiveCapable() }.flatMap { source ->
            sourceRows(source.id)
        }.filter { it.key != row.key && liveChannelIdentity(it.name) == liveChannelIdentity(row.name) &&
            visible("LIVE", it.key.sourceId.value, it.categoryId.value) }.distinctBy { it.key }
}

/** Lists longer than this are processed off the main thread; small ones stay inline (cheaper, deterministic). */
internal const val BIG_LIST = 2_000
