package com.yodesla.omniverse.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW

/**
 * Video output for a [Media3ExoEngine]. SurfaceView (not TextureView): hardware overlay path,
 * HDR and lower power on TV boxes (PLAN.md §4.1). Letterboxed (Fit) by default; [contentScale]
 * lets the viewer pick Stretch (FillBounds) or Zoom (Crop), e.g. for old 4:3 shows.
 *
 * The video frame itself never changes configuration (always FillBounds): switching picture mode
 * only resizes the box around it. Changing ContentFrame's own scaling rebuilt the surface and made
 * some streams reload from the start, so the mode must be a pure layout change.
 * Phones turn landscape while video is on screen (a no-op on TVs, which are always landscape).
 *
 * [frameRateMode] (Task 100) asks the display for the frame rate of the video now on this surface,
 * as the profile chose in Settings › Playback. It is applied here because this composable owns the
 * surface, and it is undone when the surface leaves.
 */
@OptIn(UnstableApi::class)
@Composable
fun EngineSurface(
    engine: Media3ExoEngine,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    frameRateMode: FrameRateMatchMode = FrameRateMatchMode.OFF,
) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (before != null) activity.requestedOrientation = before }
    }
    var aspect by remember(engine) { mutableFloatStateOf(videoAspect(engine.player.videoSize)) }
    val view = LocalView.current
    val tracks by engine.tracks.collectAsState(TrackInfo())
    val fps = tracks.videoFrameRate
    val frameRate = remember { VideoFrameRateApplier() }
    val latestFps by rememberUpdatedState(fps)
    val latestMode by rememberUpdatedState(frameRateMode)
    DisposableEffect(engine) {
        engine.setFrameRateMatch(frameRateMode)
        val listener = object : Player.Listener {
            // A re-prepared stream hands us a fresh surface: re-apply the rate the profile asked for.
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                aspect = videoAspect(videoSize)
                frameRate.request(view, latestFps, latestMode)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) frameRate.request(view, latestFps, latestMode)
            }
        }
        engine.player.addListener(listener)
        onDispose {
            engine.player.removeListener(listener)
            engine.setFrameRateMatch(FrameRateMatchMode.OFF)
            frameRate.clear(view)
        }
    }
    LaunchedEffect(frameRateMode, fps) { frameRate.request(view, fps, frameRateMode) }
    // Task 111 M2: the frame-rate pin (esp. the API 23-29 window preferredDisplayModeId) must not
    // survive backgrounding. Clear it on ON_STOP and re-request the profile's mode on ON_START.
    LifecycleStartEffect(Unit) {
        frameRate.request(view, latestFps, latestMode)
        onStopOrDispose { frameRate.clear(view) }
    }
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val (w, h) = pictureBox(maxWidth.value, maxHeight.value, aspect, contentScale)
        // The surface always stays full size; the mode is applied as a scale transform, so the
        // video layer is never resized or recreated (a resize caused a visible stutter).
        val sx = if (maxWidth.value > 0f) w / maxWidth.value else 1f
        val sy = if (maxHeight.value > 0f) h / maxHeight.value else 1f
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = sx; scaleY = sy }) {
            ContentFrame(player = engine.player, surfaceType = SURFACE_TYPE_SURFACE_VIEW, contentScale = ContentScale.FillBounds)
        }
    }
}

private fun videoAspect(v: VideoSize): Float =
    if (v.width > 0 && v.height > 0) v.width * v.pixelWidthHeightRatio / v.height else 0f

/**
 * Size of the box the video fills, for a [boxW] x [boxH] screen area and a video [aspect]
 * (0 = unknown yet: fill the area). Fit = whole picture visible, Stretch = the whole area,
 * Zoom = cover the area (the overflow is clipped).
 */
internal fun pictureBox(boxW: Float, boxH: Float, aspect: Float, scale: ContentScale): Pair<Float, Float> {
    if (aspect <= 0f || boxW <= 0f || boxH <= 0f || scale == ContentScale.FillBounds) return boxW to boxH
    val wide = aspect > boxW / boxH
    val fit = if (wide) boxW to boxW / aspect else boxH * aspect to boxH
    val cover = if (wide) boxH * aspect to boxH else boxW to boxW / aspect
    return if (scale == ContentScale.Crop) cover else fit
}
