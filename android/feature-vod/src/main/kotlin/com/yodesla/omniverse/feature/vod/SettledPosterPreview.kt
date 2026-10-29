package com.yodesla.omniverse.feature.vod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.designsystem.OmniMotion
import kotlinx.coroutines.delay

/** Focus changes invalidate only this preview, never the surrounding poster shelves. */
@Composable
internal fun SettledPosterPreview(
    focused: State<PosterRow?>,
    fallback: PosterRow?,
    content: @Composable (PosterRow?) -> Unit,
) {
    val candidate = focused.value ?: fallback
    val shown by produceState(candidate, candidate) {
        delay(OmniMotion.BACKDROP_DEBOUNCE_MS)
        value = candidate
    }
    content(shown)
}
