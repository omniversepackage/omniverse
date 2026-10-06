package com.yodesla.omniverse.feature.live.sports

import com.yodesla.omniverse.core.data.sports.HighlightClip

/**
 * The hero's takeover state machine: normal → playing a clip → back to exactly what was there
 * before. Playing parks the hero's previous content (the game and its state); stopping hands it
 * back unchanged. Picking a second clip while one plays keeps the original parked content.
 */
class HeroPlayback {
    var clip: HighlightClip? = null
        private set
    var parked: GameUi? = null
        private set

    val isPlaying get() = clip != null

    /** Take the hero over with [clip], parking whatever it showed ([heroWas]). */
    fun play(clip: HighlightClip, heroWas: GameUi?) {
        if (clip == this.clip) return
        this.clip = clip
        parked = parked ?: heroWas
    }

    /** Back or the clip ended: give the hero back what it had, and return to normal. */
    fun stop(): GameUi? {
        val was = parked
        clip = null
        parked = null
        return was
    }
}
