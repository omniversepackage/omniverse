package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.model.ContentKey

/**
 * Everything needed to put back exactly what a Continue Watching / My List removal took, captured
 * BEFORE the change so undo restores the original rows (progress position, dismissal tombstone,
 * favourite order + added time) rather than a fresh approximation. Pure data: no DB, no coroutine,
 * safe to hold in UI state for the length of the undo window.
 */
sealed class UndoToken {
    /** Nothing was removed; undo is a no-op. */
    data object None : UndoToken()

    /**
     * A Continue Watching dismissal. [rows] are the exact progress rows the dismissal deleted
     * (the dismissed card's own row plus every episode / media variant hanging under it);
     * [tombstones] are the re-import suppression lines it wrote, which undo must clear.
     */
    data class ContinueWatching(val key: ContentKey, val rows: List<Progress>, val tombstones: List<String>) : UndoToken()

    /** A My List (favourite) removal: the exact row's order and added time, so undo keeps its slot. */
    data class Favorite(val key: ContentKey, val sortIndex: Long, val addedMs: Long) : UndoToken()
}

/**
 * A title the viewer dismissed from Continue Watching, kept so Settings can list it and put it back.
 * [progress] is the dismissed card's own saved row (null when the card carried no local progress),
 * which restore re-inserts so the title returns to Continue Watching at its old position.
 */
data class HiddenContinueWatching(val key: ContentKey, val progress: Progress?)

/**
 * UI-facing undo prompt: a human-readable [message] naming what was removed plus the [token] that
 * restores it. View models hold the latest one while the undo card is on screen.
 */
data class UndoUi(val message: String, val token: UndoToken)
