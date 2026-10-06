package com.yodesla.omniverse.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.util.Log
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.progressPosterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.plus

/** Deep links from the Android TV home screen ("omniverse://open?s=…&k=…&r=…") into a detail page. */
object DeepLinks {
    /** Set by MainActivity, consumed (and cleared) by TvRoot. */
    val pending = MutableStateFlow<ContentKey?>(null)

    fun uriFor(key: ContentKey): Uri = Uri.Builder().scheme("omniverse").authority("open")
        .appendQueryParameter("s", key.sourceId.value)
        .appendQueryParameter("k", key.kind.name)
        .appendQueryParameter("r", key.remoteId.value)
        .build()

    /** Only VOD/SERIES detail pages are reachable; anything else is ignored. */
    fun parse(intent: Intent?): ContentKey? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "omniverse" || uri.authority != "open") return null
        val s = uri.getQueryParameter("s")?.takeIf { it.isNotBlank() } ?: return null
        val r = uri.getQueryParameter("r")?.takeIf { it.isNotBlank() } ?: return null
        val kind = when (uri.getQueryParameter("k")) { "VOD" -> ContentKind.VOD; "SERIES" -> ContentKind.SERIES; else -> return null }
        return ContentKey(SourceId(s), kind, RemoteId(r))
    }
}

/**
 * Mirrors the active profile's Continue Watching into the Android TV home screen's
 * "Play Next" / Watch Next row (platform TvContract, API 26+; no extra library).
 * Never publishes a title from a parental-locked category (even during an unlocked
 * session) or from a library the viewer switched off. Only our own rows are touched.
 */
class WatchNextPublisher(private val context: Context, private val graph: AppGraph) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
        combine(
            graph.userData.continueWatching(MAX_ITEMS),
            graph.parental.lockedKeys(),
            graph.parental.enabled,
            graph.userData.hiddenCategoryKeys(),
        ) { progress, locked, pinOn, off -> Snapshot(progress, if (pinOn) locked else emptySet(), off) }
            .debounce(1_500)
            // A failed sync must not end the flow: the next Continue Watching change retries.
            .onEach { snap -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { publish(snap) }.onFailure { Log.w(TAG, "watch next sync failed", it) } }
            .catch { Log.w(TAG, "watch next stopped", it) }
            .launchIn(scope + Dispatchers.IO)
    }

    private data class Snapshot(
        val progress: List<com.yodesla.omniverse.core.data.Progress>,
        val locked: Set<String>,
        val off: Set<String>,
    )

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private suspend fun publish(s: Snapshot) {
        val wanted = LinkedHashMap<String, ContentValues>()
        for (p in s.progress) {
            val posterKey = progressPosterKey(p.key, p.parentId) ?: continue
            val poster = graph.catalog.poster(posterKey) ?: continue
            val cat = poster.categoryId?.value
            if (cat != null) {
                val k = "${posterKey.kind.name}|${posterKey.sourceId.value}|$cat"
                if (k in s.locked || k in s.off) continue
            }
            val id = "${posterKey.sourceId.value}|${posterKey.kind.name}|${posterKey.remoteId.value}"
            if (id in wanted) continue
            wanted[id] = ContentValues().apply {
                put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, id)
                put(TvContract.WatchNextPrograms.COLUMN_TYPE,
                    if (posterKey.kind == ContentKind.SERIES) TvContract.WatchNextPrograms.TYPE_TV_SERIES else TvContract.WatchNextPrograms.TYPE_MOVIE)
                put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                put(TvContract.WatchNextPrograms.COLUMN_TITLE, poster.name)
                poster.posterUrl?.takeIf { it.startsWith("http") }?.let {
                    put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, it)
                    put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_2_3)
                }
                p.durationMs?.takeIf { it > 0 }?.let { d ->
                    put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, d.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, p.positionMs.coerceIn(0, d).toInt())
                }
                put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, p.updatedMs)
                put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, Intent(Intent.ACTION_VIEW, DeepLinks.uriFor(posterKey))
                    .setPackage(context.packageName).toUri(Intent.URI_INTENT_SCHEME))
            }
        }
        val resolver = context.contentResolver
        val existing = HashMap<String, Long>()
        resolver.query(TvContract.WatchNextPrograms.CONTENT_URI,
            arrayOf(TvContract.WatchNextPrograms._ID, TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(1)?.let { existing[it] = c.getLong(0) }
        }
        for ((id, rowId) in existing) if (id !in wanted) {
            resolver.delete(TvContract.buildWatchNextProgramUri(rowId), null, null)
        }
        for ((id, values) in wanted) {
            val rowId = existing[id]
            if (rowId != null) resolver.update(TvContract.buildWatchNextProgramUri(rowId), values, null, null)
            else if (resolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values) == null) Log.w(TAG, "insert rejected for $id")
        }
        Log.i(TAG, "synced ${wanted.size} item(s), removed ${existing.keys.count { it !in wanted }}")
    }

    private companion object {
        const val TAG = "WatchNext"
        const val MAX_ITEMS = 10
    }
}
