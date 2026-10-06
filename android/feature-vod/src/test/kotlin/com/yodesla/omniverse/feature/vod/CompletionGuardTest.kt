package com.yodesla.omniverse.feature.vod

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M3 regression. VodPlayer decides an item's final progress once (before navigating) and turns
 * every later teardown/dispose save into a no-op, so a stale engine position can never overwrite a
 * completion. CompletionGuard is that single decision point; a fake player drives the credits-skip
 * disposal race. Correctness must not depend on coroutine launch order.
 */
class CompletionGuardTest {

    /** Records what the fake player writes, mirroring UserDataRepositoryImpl's 92% completion rule. */
    private class Recorder {
        val saves = mutableListOf<Triple<Long, Long?, Boolean>>()
        val heartbeats = mutableListOf<Triple<String, Long, Long?>>()
        fun saveProgress(positionMs: Long, durationMs: Long?) {
            val completed = durationMs != null && durationMs > 0 && positionMs >= durationMs * 0.92
            saves += Triple(positionMs, durationMs, completed)
        }
        fun setWatched() { saves += Triple(1L, null, true) }
        fun heartbeat(state: String, positionMs: Long, durationMs: Long?) { heartbeats += Triple(state, positionMs, durationMs) }
    }

    /** Mirrors VodPlayer.save()/heartbeat()/finalizeCompleted() wired through CompletionGuard. */
    private class FakePlayer(
        val guard: CompletionGuard,
        val rec: Recorder,
        var enginePos: Long,
        var engineDur: Long?,
        val knownDur: Long?,
        val trackProgress: Boolean = true,
    ) {
        fun save() {
            if (!guard.allowTeardownSave()) return
            if (trackProgress && enginePos > 5_000) rec.saveProgress(enginePos, engineDur)
        }
        fun heartbeat(state: String) {
            val (pos, dur) = guard.heartbeatPosition(enginePos, engineDur)
            rec.heartbeat(state, pos, dur)
        }
        fun finalizeCompleted() {
            if (guard.isDecided) return
            val final = guard.decideCompleted(enginePos, engineDur, knownDur)
            if (!trackProgress) return
            if (final.durationMs != null) rec.saveProgress(final.positionMs, final.durationMs) else rec.setWatched()
        }
    }

    @Test
    fun creditsSkipCompletionSurvivesTeardownSave() {
        val guard = CompletionGuard()
        val rec = Recorder()
        val player = FakePlayer(guard, rec, enginePos = 85_000, engineDur = 100_000, knownDur = 100_000)
        player.finalizeCompleted()   // skip decides + writes completed before navigating
        player.save()                // disposal save using the stale 85% engine position -> no-op
        player.heartbeat("stopped")  // final heartbeat
        assertEquals(1, rec.saves.size)
        assertTrue(rec.saves.single().third)
        assertEquals(100_000L, rec.saves.single().first)
        assertEquals(100_000L, rec.saves.single().second)
        val hb = rec.heartbeats.single()
        assertEquals("stopped", hb.first)
        assertEquals(100_000L, hb.second)
        assertEquals(100_000L, hb.third)
    }

    @Test
    fun teardownSaveAllowedBeforeCompletion() {
        val guard = CompletionGuard()
        val rec = Recorder()
        val player = FakePlayer(guard, rec, enginePos = 40_000, engineDur = 100_000, knownDur = 100_000)
        player.save()
        assertEquals(1, rec.saves.size)
        assertFalse(rec.saves.single().third)
        player.heartbeat("playing")
        val hb = rec.heartbeats.single()
        assertEquals("playing", hb.first)
        assertEquals(40_000L, hb.second)
        assertEquals(100_000L, hb.third)
    }

    @Test
    fun unknownDurationCreditsSkipMarksWatchedNotZero() {
        val guard = CompletionGuard()
        val rec = Recorder()
        val player = FakePlayer(guard, rec, enginePos = 85_000, engineDur = null, knownDur = null)
        player.finalizeCompleted()
        player.save()
        player.heartbeat("stopped")
        assertEquals(1, rec.saves.size)
        assertTrue(rec.saves.single().third)
        assertNull(rec.saves.single().second)
        assertEquals(1L, rec.saves.single().first) // setWatched stores position 1 for unknown length
        val hb = rec.heartbeats.single()
        assertEquals("stopped", hb.first)
        assertEquals(85_000L, hb.second) // real position, not fabricated to 0
        assertNull(hb.third)
    }

    @Test
    fun completionIsDecidedOnceRegardlessOfOrder() {
        val guard = CompletionGuard()
        val rec = Recorder()
        val player = FakePlayer(guard, rec, enginePos = 85_000, engineDur = 100_000, knownDur = 100_000)
        player.save()                // a periodic 85% save already landed (completed = false)
        player.finalizeCompleted()   // skip decides completed
        player.save()                // disposal save -> no-op (guard suppresses it)
        player.heartbeat("stopped")
        assertEquals(2, rec.saves.size) // periodic + completed; disposal added nothing
        assertTrue(rec.saves.last().third)
        val hb = rec.heartbeats.single()
        assertEquals("stopped", hb.first)
        assertEquals(100_000L, hb.second)
        val again = guard.decideCompleted(10_000, 100_000, 100_000)
        assertEquals(100_000L, again.positionMs) // idempotent: first decision wins
    }

    @Test
    fun heartbeatReportsCompletedStateAfterDecision() {
        val guard = CompletionGuard()
        val before = guard.heartbeatPosition(40_000, 100_000)
        assertEquals(40_000L, before.first)
        assertEquals(100_000L, before.second)
        guard.decideCompleted(85_000, 100_000, 100_000)
        val after = guard.heartbeatPosition(85_000, 100_000)
        assertEquals(100_000L, after.first)
        assertEquals(100_000L, after.second)
        assertFalse(guard.allowTeardownSave())
    }
}
