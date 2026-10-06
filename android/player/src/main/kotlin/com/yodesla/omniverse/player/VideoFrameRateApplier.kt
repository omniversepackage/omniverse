package com.yodesla.omniverse.player

import android.os.Build
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.annotation.RequiresApi

/**
 * Task 100: asks the display for the frame rate of the video currently on the playback surface.
 *
 * - API 30+: [Surface.setFrameRate] with `FRAME_RATE_COMPATIBILITY_FIXED_SOURCE` on the SurfaceView's
 *   surface (the video output Media3 builds inside [EngineSurface]); API 31+ also passes the mode's
 *   change strategy — `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS` vs `CHANGE_FRAME_RATE_ALWAYS`.
 * - API 23-29, "Always" only: `WindowManager.LayoutParams.preferredDisplayModeId` pinned to a
 *   same-resolution mode whose refresh rate matches (see [chooseDisplayMode]).
 * - [clear] undoes both: the surface request is dropped and the window's original display mode goes
 *   back when the player leaves.
 *
 * Every call is best-effort. A device that refuses the switch must keep playing, so failures are
 * swallowed and only reported through the return value.
 */
class VideoFrameRateApplier {
    private var window: Window? = null
    private var originalModeId = 0
    private var pinnedModeId = 0
    private var surface: Surface? = null
    private var appliedFps = 0f

    /** Requests [fps] on the video surface under [host] (and, below API 30, on the window). */
    fun request(host: View?, fps: Float, mode: FrameRateMatchMode): Boolean {
        if (mode == FrameRateMatchMode.OFF || !isRequestableFrameRate(fps)) return false
        if (Build.VERSION.SDK_INT >= 31) return requestSurface31(host, fps, mode)
        if (Build.VERSION.SDK_INT >= 30) return requestSurface30(host, fps)
        return shouldUseDisplayModeFallback(mode, Build.VERSION.SDK_INT) && requestDisplayMode(host, fps)
    }

    /** Leaves the player: drop the surface request and put the original display mode back. */
    fun clear(host: View?) {
        val s = surface
        surface = null
        appliedFps = 0f
        if (s != null && Build.VERSION.SDK_INT >= 30) clearSurface(s)
        if (pinnedModeId != 0) {
            pinnedModeId = 0
            restoreDisplayMode()
        }
        window = null
    }

    @RequiresApi(30)
    private fun requestSurface30(host: View?, fps: Float): Boolean {
        val s = playbackSurface(host) ?: return false
        // setFrameRate is void and best-effort by contract: no exception means the request was made.
        val accepted = runCatching { s.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }.isSuccess
        if (accepted) record(s, fps)
        return accepted
    }

    @RequiresApi(31)
    private fun requestSurface31(host: View?, fps: Float, mode: FrameRateMatchMode): Boolean {
        val s = playbackSurface(host) ?: return false
        val accepted = runCatching {
            s.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, surfaceChangeFrameRateStrategy(mode))
        }.isSuccess
        if (accepted) record(s, fps)
        return accepted
    }

    @RequiresApi(30)
    private fun clearSurface(s: Surface) {
        runCatching { if (appliedFps > 0f) s.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
    }

    private fun record(s: Surface, fps: Float) {
        surface = s
        appliedFps = fps
    }

    /** The SurfaceView Media3 builds inside [EngineSurface] and the surface the video is drawn on. */
    private fun playbackSurface(host: View?): Surface? =
        runCatching { findSurfaceView(host)?.holder?.surface }.getOrNull()

    private fun requestDisplayMode(host: View?, fps: Float): Boolean = runCatching {
        val display = host?.display ?: return@runCatching false
        val w = window ?: activityWindowOf(host).also { window = it } ?: return@runCatching false
        val attrs = w.attributes ?: return@runCatching false
        val modes = display.supportedModes
            .map { DisplayModeInfo(it.modeId.toString(), it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val cur = display.mode
        val current = DisplayModeInfo(cur.modeId.toString(), cur.physicalWidth, cur.physicalHeight, cur.refreshRate)
        val id = chooseDisplayMode(fps, modes, current)?.toIntOrNull() ?: return@runCatching false
        if (attrs.preferredDisplayModeId != id) {
            if (pinnedModeId == 0) originalModeId = attrs.preferredDisplayModeId
            attrs.preferredDisplayModeId = id
            w.attributes = attrs
        }
        pinnedModeId = id
        true
    }.getOrDefault(false)

    private fun restoreDisplayMode() {
        runCatching {
            val w = window ?: return@runCatching
            val attrs = w.attributes ?: return@runCatching
            if (attrs.preferredDisplayModeId != originalModeId) {
                attrs.preferredDisplayModeId = originalModeId
                w.attributes = attrs
            }
        }
    }
}

/** The activity window behind a composed view (display-mode switching needs a Window, not a View). */
fun activityWindowOf(view: View?): Window? {
    var context: android.content.Context? = view?.context
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) return context.window
        context = context.baseContext
    }
    return null
}

/** Media3 creates the video output as a SurfaceView inside the surface composable: find it. */
private fun findSurfaceView(root: View?): SurfaceView? {
    if (root is SurfaceView) return root
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            findSurfaceView(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}
