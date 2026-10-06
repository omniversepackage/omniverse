package com.yodesla.omniverse.feature.live.multiview

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.ChannelRow
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.EpgRepository
import com.yodesla.omniverse.core.data.SourceRepository
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.PlaybackSpec
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.source.SourceException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One tile: what the channel is, what's on, the stream to open, and any resolution error. */
@Immutable
data class MultiviewTileUi(
    val key: ContentKey,
    val categoryId: RemoteId?,
    val number: Int?,
    val name: String,
    val logoUrl: String?,
    val nowTitle: String?,
    val spec: PlaybackSpec?,
    val error: String?,
    /** Bumped by [retry] so the tile's engine re-opens even when the resolved spec is identical. */
    val attempt: Int = 0,
)

@Immutable
data class MultiviewUiState(val tiles: List<MultiviewTileUi> = emptyList())

/**
 * Decides WHAT each Multiview tile plays (the engines themselves live in the UI, like
 * LivePlayerScreen's). Resolves every channel's live stream once, in parallel; a tile that can't
 * resolve carries its own error while the others keep their specs.
 *
 * M1: [visibility] is the SAME live policy the fullscreen Player route is gated by
 * (android:parental). No channel is resolved or played until it passes the CURRENT policy, and a
 * policy change (PIN session expiry, re-lock) removes already-denied tiles as it arrives. When no
 * allowed tile is left, [closed] goes true and the route must close.
 */
class MultiviewViewModel(
    private val sources: SourceRepository,
    private val catalog: CatalogRepository,
    private val epg: EpgRepository,
    private val clock: Clock,
    private val visibility: Flow<Visibility>,
    /** Task 84m: libraries the viewer switched off — their channels leave the tiles and the swap picker. */
    private val hiddenCategoryKeys: Flow<Set<String>> = flowOf(emptySet()),
) : ViewModel() {

    private val _state = MutableStateFlow(MultiviewUiState())
    val state: StateFlow<MultiviewUiState> = _state.asStateFlow()

    private val _closed = MutableStateFlow(false)
    /** True once every tile has been denied/removed: the route has nothing left to show. */
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    /** Latest live policy; null until the real one arrives (same rule as ParentalRouteGate). */
    private var policy: Visibility? = null
    private var basePolicy: Visibility? = null
    private var hiddenKeys: Set<String> = emptySet()

    init {
        // Two direct collects (not combine): combine's inner coroutines would land one dispatch
        // later, and M1 requires the policy to be in place before start() resolves any tile.
        viewModelScope.launch { visibility.collect { v -> basePolicy = v; applyPolicy() } }
        viewModelScope.launch { hiddenCategoryKeys.collect { h -> hiddenKeys = h; applyPolicy() } }
    }

    private fun applyPolicy() {
        val b = basePolicy ?: return
        val h = hiddenKeys
        val v: Visibility = { k, s, c -> b(k, s, c) && "$k|$s|$c" !in h }
        policy = v
        enforce(v)
    }

    fun start(channels: List<ContentKey>) {
        if (_state.value.tiles.map { it.key } == channels) return
        _closed.value = false
        _state.value = MultiviewUiState(channels.map { MultiviewTileUi(it, null, null, "", null, null, null, null) })
        channels.forEach { key -> viewModelScope.launch { resolveChannel(key) } }
    }

    /**
     * Task 67: swap one tile's channel for another. Only that slot changes — every other tile keeps
     * its key, spec and engine. The new channel is judged by the CURRENT policy exactly like a fresh
     * start (M1): a channel that is gone or now denied gets no stream and its slot is dropped.
     */
    fun swapChannel(index: Int, newKey: ContentKey) {
        val s = _state.value
        if (index !in s.tiles.indices) return
        val oldKey = s.tiles[index].key
        if (oldKey == newKey || newKey in s.tiles.map { it.key }) return
        _closed.value = false
        _state.value = s.copy(tiles = s.tiles.mapIndexed { i, t ->
            if (i == index) MultiviewTileUi(newKey, null, null, "", null, null, null, null) else t
        })
        viewModelScope.launch { resolveChannel(newKey) }
    }

    /** Task 67: restart only this tile's engine — re-resolve its stream and bump its attempt. */
    fun retry(index: Int) {
        val tile = _state.value.tiles.getOrNull(index) ?: return
        patch(tile.key) { it.copy(attempt = it.attempt + 1, error = null) }
        viewModelScope.launch { resolveChannel(tile.key) }
    }

    /**
     * Task 67: "Remove from Multiview" — drop just this tile (its engine is released by the UI when
     * it leaves composition). The session is updated by the caller; the route closes only if this was
     * the last tile.
     */
    fun removeTile(key: ContentKey) = drop(key)

    /** Channels this grid could swap a slot to: same source, not already on a tile, allowed by policy. */
    suspend fun swapCandidates(): List<ChannelRow> {
        val src = _state.value.tiles.firstOrNull()?.key?.sourceId ?: return emptyList()
        val taken = _state.value.tiles.map { it.key }.toSet()
        return catalog.channelListAll(src, emptyList())
            .filter { it.key !in taken && allowedNow(it.key, it.categoryId) }
    }

    /** Resolve one channel into its tile: catalog row, CURRENT policy, now/next, then the live stream. */
    private suspend fun resolveChannel(key: ContentKey) {
        val row = catalog.channel(key)
        // A channel that no longer exists can't be validated, so it gets no tile and no stream.
        if (row == null) {
            drop(key)
            return
        }
        // Category first: a policy emission landing mid-resolve must be able to judge this tile.
        patch(key) {
            it.copy(
                categoryId = row.categoryId, number = row.number, name = row.name, logoUrl = row.logoUrl,
            )
        }
        // M1: the CURRENT policy decides before anything is resolved or played.
        if (!allowedNow(key, row.categoryId)) {
            drop(key)
            return
        }
        val nn = row.epgKey?.let { epg.nowNext(key.sourceId, listOf(it), clock.nowMs())[it] }
        patch(key) { it.copy(nowTitle = nn?.now?.title) }
        val source = sources.contentSource(key.sourceId)
        if (source == null) {
            fail(key, "This source was removed.")
            return
        }
        val spec = try {
            source.playback(PlaybackRequest.Live(key.sourceId, key.remoteId))
        } catch (e: SourceException) {
            fail(key, "This channel can't be played right now.")
            null
        }
        if (spec != null) patch(key) { it.copy(spec = spec, error = null) }
    }

    /**
     * M2: the foreground-resume gate. A tile may re-open only if the CURRENT policy still allows
     * it — MainActivity locks the PIN session again on stop, so a channel unlocked when the app
     * backgrounded is denied here unless it was re-unlocked.
     */
    fun allows(key: ContentKey): Boolean =
        _state.value.tiles.firstOrNull { it.key == key }?.let { allowedNow(key, it.categoryId) } ?: false

    /** Specs to re-open on return to the foreground, in tile order, already policy-filtered. */
    fun tilesToResume(): List<Pair<ContentKey, PlaybackSpec>> =
        _state.value.tiles.filter { it.spec != null && allowedNow(it.key, it.categoryId) }
            .map { it.key to it.spec!! }

    private fun allowedNow(key: ContentKey, categoryId: RemoteId?): Boolean {
        val v = policy ?: return true
        return categoryId != null && v(ContentKind.LIVE.name, key.sourceId.value, categoryId.value)
    }

    /** A policy emission landed: drop every tile it now denies; close the route if none survive. */
    private fun enforce(v: Visibility) {
        val s = _state.value
        if (s.tiles.isEmpty()) return
        // Tiles still resolving (no category yet) are judged by start() once their row arrives.
        val kept = s.tiles.filter { it.categoryId == null || v(ContentKind.LIVE.name, it.key.sourceId.value, it.categoryId.value) }
        if (kept.size == s.tiles.size) return
        _state.value = s.copy(tiles = kept)
        if (kept.isEmpty()) _closed.value = true
    }

    /** Channel gone or denied mid-resolve: remove the tile; close the route if it was the last. */
    private fun drop(key: ContentKey) {
        val s = _state.value
        val kept = s.tiles.filterNot { it.key == key }
        if (kept.size == s.tiles.size) return
        _state.value = s.copy(tiles = kept)
        if (kept.isEmpty()) _closed.value = true
    }

    private fun patch(key: ContentKey, f: (MultiviewTileUi) -> MultiviewTileUi) {
        _state.update { s -> s.copy(tiles = s.tiles.map { if (it.key == key) f(it) else it }) }
    }

    private fun fail(key: ContentKey, message: String) = patch(key) { it.copy(error = message) }
}
