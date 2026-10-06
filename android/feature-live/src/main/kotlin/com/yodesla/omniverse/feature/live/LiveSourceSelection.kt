package com.yodesla.omniverse.feature.live

import com.yodesla.omniverse.core.data.SourceSummary
import com.yodesla.omniverse.core.model.SourceKind

/**
 * Task 120: which configured sources can actually drive Live/Guide. Only the IPTV kinds provide live
 * channels + EPG (PLAN §5.1); Plex is VOD/series-only here, so a Plex source that sorts first must
 * not become the guide/live source.
 */
private val liveCapableKinds = setOf(SourceKind.XTREAM, SourceKind.M3U)

fun SourceSummary.isLiveCapable(): Boolean = kind in liveCapableKinds

/** First live-capable source in saved order, skipping non-live providers (e.g. Plex). */
fun List<SourceSummary>.firstLiveCapable(): SourceSummary? = firstOrNull { it.isLiveCapable() }
