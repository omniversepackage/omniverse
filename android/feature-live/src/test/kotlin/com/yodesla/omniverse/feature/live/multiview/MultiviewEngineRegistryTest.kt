package com.yodesla.omniverse.feature.live.multiview

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.MimeHint
import com.yodesla.omniverse.core.model.PlaybackSpec
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.player.PlaybackDiagnostics
import com.yodesla.omniverse.player.PlayerEngine
import com.yodesla.omniverse.player.PlayerState
import com.yodesla.omniverse.player.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Task 61 / audit M2: the tile-engine ownership contract. Backgrounding must close every live
 * connection, and every engine must be released exactly once — whether its tile is removed by the
 * revalidated policy or the whole screen is disposed.
 */
class MultiviewEngineRegistryTest {
    private val src = SourceId("s")
    private fun key(id: String) = ContentKey(src, ContentKind.LIVE, RemoteId(id))
    private val a = key("1")
    private val b = key("2")
    private val c = key("3")
    private val spec = PlaybackSpec("http://x/1.ts", mimeHint = MimeHint.MPEG_TS, isLive = true, seekable = false, redacted = "x")

    @Test
    fun backgroundingStopsEveryLiveEngineExactlyOnce() {
        val registry = MultiviewEngineRegistry()
        val engines = listOf(a, b, c).associateWith { FakeEngine() }
        engines.forEach { (k, e) -> registry.register(k, e) }

        registry.stopAll()

        assertTrue(registry.stopped)
        assertEquals(listOf(1, 1, 1), engines.values.map { it.stops })
        assertEquals(listOf(0, 0, 0), engines.values.map { it.releases })
    }

    @Test
    fun aTileRemovedWhileBackgroundedIsReleasedAndNeverStoppedAgain() {
        val registry = MultiviewEngineRegistry()
        val ea = FakeEngine()
        val eb = FakeEngine()
        registry.register(a, ea)
        registry.register(b, eb)
        registry.stopAll()

        registry.unregister(a) // policy removed the tile while the app was backgrounded

        assertEquals(1, ea.releases)
        assertEquals(listOf(b), registry.liveKeys.toList())
        registry.stopAll() // a second stop pass must not touch the released engine
        assertEquals(1, ea.stops)
        assertEquals(2, eb.stops)
    }

    @Test
    fun finalDisposalReleasesEachEngineExactlyOnce() {
        val registry = MultiviewEngineRegistry()
        val engines = listOf(a, b, c).associateWith { FakeEngine() }
        engines.forEach { (k, e) -> registry.register(k, e) }

        registry.releaseAll()
        registry.releaseAll() // screen-dispose and tile-dispose both run: no double release

        assertEquals(listOf(1, 1, 1), engines.values.map { it.releases })
        assertTrue(registry.liveKeys.isEmpty())
    }

    @Test
    fun unregisterThenReleaseAllDoesNotReleaseTwice() {
        val registry = MultiviewEngineRegistry()
        val ea = FakeEngine()
        val eb = FakeEngine()
        registry.register(a, ea)
        registry.register(b, eb)

        registry.unregister(a)
        registry.releaseAll()

        assertEquals(1, ea.releases)
        assertEquals(1, eb.releases)
    }

    @Test
    fun aTileBuiltWhileBackgroundedDoesNotStream() {
        val registry = MultiviewEngineRegistry()
        registry.stopAll()

        val e = FakeEngine()
        registry.register(a, e)

        assertFalse(e.zapped)
        assertEquals(1, e.stops)
        assertTrue(registry.liveKeys.contains(a))
    }

    private class FakeEngine : PlayerEngine {
        var stops = 0
        var releases = 0
        var zapped = false
        override val state: StateFlow<PlayerState> = MutableStateFlow<PlayerState>(PlayerState.Idle)
        override val tracks: StateFlow<TrackInfo> = MutableStateFlow(TrackInfo())
        override val diagnostics: StateFlow<PlaybackDiagnostics> = MutableStateFlow(PlaybackDiagnostics())
        override fun play(spec: PlaybackSpec, startPositionMs: Long) = Unit
        override fun zap(spec: PlaybackSpec) { zapped = true }
        override fun pause() = Unit
        override fun resume() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun stop() { stops++ }
        override fun release() { releases++ }
        override fun selectAudio(trackId: String?) = Unit
        override fun selectSubtitle(trackId: String?) = Unit
        override val positionMs: Long get() = 0L
        override val durationMs: Long? get() = null
    }
}
