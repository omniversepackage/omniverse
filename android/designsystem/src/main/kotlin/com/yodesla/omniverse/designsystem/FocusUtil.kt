package com.yodesla.omniverse.designsystem

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Requests focus once the target is actually laid out. A lazy row's first item often isn't
 * attached in the frame its data arrives; a single requestFocus() then fails silently and the
 * screen starts with NO focus, so the user's first D-pad press is swallowed. Retries for up to
 * [maxFrames] frames; returns true once focus was granted.
 */
suspend fun FocusRequester.requestFocusWhenReady(maxFrames: Int = 30): Boolean {
    repeat(maxFrames) {
        withFrameNanos { }
        val granted = runCatching { requestFocus() }.isSuccess
        if (granted) return true
    }
    return false
}
