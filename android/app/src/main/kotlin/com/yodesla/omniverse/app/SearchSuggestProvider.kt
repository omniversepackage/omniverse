package com.yodesla.omniverse.app

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import android.util.Log
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.ContentKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SearchSuggest"
private const val MAX_SUGGEST_ROWS = 10
private const val TIMEOUT_MS = 1_500L
private const val FETCH_PER_KIND = 24

/** One global-search suggestion row (pure data so the mapping is unit-testable without Android). */
data class SuggestRow(
    val id: Long,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val intentData: String,
    val year: Int?,
    val contentType: String,
)

/**
 * Task 80: search hits → suggestion rows. Pure function (no android.*): the CURRENT profile's
 * browse visibility filters first (kids/parental + switched-off libraries never leak), then one
 * row per merged title — the exact-TMDB rule the in-app Search uses — then the [maxRows] cap.
 * Deep links use the exact omniverse://open?s=&k=&r= shape DeepLinks.parse accepts.
 */
fun suggestRows(results: List<PosterRow>, visibility: Visibility, maxRows: Int = MAX_SUGGEST_ROWS): List<SuggestRow> =
    results
        .filter { p -> p.categoryId?.let { visibility(p.key.kind.name, p.key.sourceId.value, it.value) } ?: true }
        .distinctBy { p -> p.tmdbId?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" } ?: "key:${p.key.sourceId.value}:${p.key.remoteId.value}" }
        .take(maxRows)
        .mapIndexed { i, p ->
            val show = p.key.kind == ContentKind.SERIES
            SuggestRow(
                id = (i + 1).toLong(),
                title = p.name,
                subtitle = listOfNotNull(p.year?.toString(), if (show) "Show" else "Movie").joinToString(" · "),
                imageUrl = p.posterUrl?.takeIf { it.startsWith("http") },
                intentData = suggestDeepLink(p),
                year = p.year,
                contentType = if (show) "tvShow" else "movie",
            )
        }

/** Same link the Watch Next cards build (DeepLinks.uriFor), as a plain string so the mapping stays testable. */
private fun suggestDeepLink(p: PosterRow): String =
    "omniverse://open?s=${suggestEncode(p.key.sourceId.value)}&k=${p.key.kind.name}&r=${suggestEncode(p.key.remoteId.value)}"

/** Uri.encode equivalent without android.* (percent-encodes everything outside RFC 3986 unreserved). */
private fun suggestEncode(v: String): String = buildString {
    for (ch in v) {
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') append(ch)
        else for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
            append('%')
            append(HEX[(b.toInt() shr 4) and 0xF])
            append(HEX[b.toInt() and 0xF])
        }
    }
}

private const val HEX = "0123456789ABCDEF"

/**
 * Android TV / Google TV global search suggestions (task 80). Read-only, exported, readable only
 * by the system search (android:readPermission GLOBAL_SEARCH). Answers search_suggest_query with
 * at most [MAX_SUGGEST_ROWS] merged titles the CURRENT profile may see — the same repository and
 * the same browseVisibility the in-app Search uses. Runs synchronously with a [TIMEOUT_MS] cap;
 * any error returns an empty cursor, never throws.
 */
class SearchSuggestProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        // content://<authority>/search_suggest_query/<term> — matched on the path so the authority
        // string itself never has to be reconstructed here.
        val segments = uri.pathSegments
        val term = if (segments.size == 2 && segments[0] == SearchManager.SUGGEST_URI_PATH_QUERY) segments[1].takeIf { it.isNotBlank() } else null
        if (term == null) return emptyCursor()
        return try {
            val graph = (context?.applicationContext as? OmniverseApp)?.graph ?: return emptyCursor()
            val rows = runBlocking {
                withTimeoutOrNull(TIMEOUT_MS) {
                    val visibility = graph.browseVisibility.first()
                    val hits = graph.search.search(term, limitPerKind = FETCH_PER_KIND)
                    val posters = ArrayList<PosterRow>()
                    for (kind in listOf(ContentKind.VOD, ContentKind.SERIES)) {
                        for (hit in hits[kind].orEmpty()) graph.catalog.poster(hit.key)?.let { posters += it }
                    }
                    suggestRows(posters, visibility)
                }
            } ?: emptyList()
            MatrixCursor(COLUMNS, rows.size).apply {
                for (r in rows) addRow(arrayOf<Any?>(r.id, r.title, r.subtitle, r.imageUrl, ACTION_VIEW, r.intentData, r.year, r.contentType))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "search suggestions failed", t)
            emptyCursor()
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    private fun emptyCursor(): Cursor = MatrixCursor(COLUMNS, 0)

    private companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
        )
    }
}
