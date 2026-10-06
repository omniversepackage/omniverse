package com.yodesla.omniverse.player

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * Draws the selected subtitle track over the video (PlayerSurface draws pixels only).
 * Place it above [EngineSurface], same bounds.
 *
 * [style] is the profile's resolved appearance (null = the system caption style + the cues' own
 * styling). [offsetMs] shifts when cues are shown: positive delays them (the common "subs too fast"
 * fix); Media3 1.11 hands cues to us at their start time, so a negative offset can only ever show a
 * line as soon as it arrives, never before. VOD passes a per-session delay, live passes 0.
 */
@OptIn(UnstableApi::class)
@Composable
fun EngineSubtitles(
    engine: Media3ExoEngine,
    modifier: Modifier = Modifier,
    style: SubtitleStyleSpec? = null,
    offsetMs: Long = 0L,
) {
    val context = LocalContext.current
    val view = remember { SubtitleView(context) }
    val currentOffset by rememberUpdatedState(offsetMs)
    val lastGroup = remember { arrayOfNulls<CueGroup>(1) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    LaunchedEffect(style) { applyStyle(view, style) }

    DisposableEffect(engine) {
        val listener = object : Player.Listener {
            // Broadcast captions (CEA-608) often send rows of spaces; drawn with their window
            // background they show up as empty grey bars over the picture. Drop blank text cues.
            override fun onCues(cueGroup: CueGroup) {
                lastGroup[0] = cueGroup
                schedule(view, handler, cueGroup, currentOffset)
            }
        }
        engine.player.addListener(listener)
        onDispose {
            engine.player.removeListener(listener)
            handler.removeCallbacksAndMessages(null)
            view.setCues(null)
        }
    }

    // Re-apply a changed delay to the line already on screen instead of waiting for the next cue.
    LaunchedEffect(offsetMs) { schedule(view, handler, lastGroup[0], offsetMs) }

    AndroidView(factory = { view }, modifier = modifier)
}

private fun applyStyle(view: SubtitleView, style: SubtitleStyleSpec?) {
    if (style == null) {
        view.setUserDefaultStyle()
        view.setUserDefaultTextSize()
        view.setApplyEmbeddedStyles(true)
        return
    }
    view.setApplyEmbeddedStyles(style.applyEmbeddedStyles)
    view.setStyle(
        CaptionStyleCompat(
            style.textColorArgb,
            style.backgroundColorArgb,
            style.windowColorArgb,
            style.edgeType,
            style.edgeColorArgb,
            null,
        ),
    )
    view.setFractionalTextSize(style.fractionalTextSize)
    view.setBottomPaddingFraction(style.bottomPaddingFraction)
}

private fun schedule(view: SubtitleView, handler: Handler, group: CueGroup?, offsetMs: Long) {
    handler.removeCallbacksAndMessages(null)
    val cues = group?.cues?.filter { it.bitmap != null || !it.text.isNullOrBlank() }
    if (offsetMs <= 0L) {
        view.setCues(cues)
    } else {
        handler.postDelayed({ view.setCues(cues) }, offsetMs)
    }
}
