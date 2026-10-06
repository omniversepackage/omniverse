package com.yodesla.omniverse.feature.live.multiview

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.player.PlayerEngine

/**
 * M2: ownership bookkeeping for the Multiview tile engines, kept out of Compose so the contract is
 * unit-testable. Every engine registers when its tile enters composition and unregisters (releasing
 * exactly once) when it leaves; backgrounding stops every live upstream connection so no decoder or
 * provider stream survives the Home button; final disposal releases each engine exactly once.
 *
 * Resume is NOT done here: the screen re-opens tiles through the view model's current parental
 * policy, so a channel locked while backgrounded never streams again.
 */
class MultiviewEngineRegistry {
    private val engines = LinkedHashMap<ContentKey, PlayerEngine>()

    /** Engines still owned here (released ones are gone). */
    val liveKeys: Set<ContentKey> get() = engines.keys.toSet()

    /** True while backgrounded: a newly registered engine must not stream either. */
    var stopped: Boolean = false
        private set

    fun register(key: ContentKey, engine: PlayerEngine) {
        engines[key] = engine
        if (stopped) engine.stop()
    }

    /** Tile left composition (policy removal, exit, or the screen going away): release once. */
    fun unregister(key: ContentKey) {
        engines.remove(key)?.release()
    }

    /** ON_STOP: close every live connection. The engines stay reusable for the resume zap. */
    fun stopAll() {
        stopped = true
        engines.values.forEach(PlayerEngine::stop)
    }

    /** Screen destroyed: release every engine still owned, exactly once. */
    fun releaseAll() {
        val all = engines.values.toList()
        engines.clear()
        all.forEach(PlayerEngine::release)
    }
}
