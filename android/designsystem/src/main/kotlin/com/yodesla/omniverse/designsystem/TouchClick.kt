package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * androidx.tv.material3 Card/Button/Surface only react to D-pad/Enter, never to touch.
 * Every clickable designsystem component adds this so the same screens work on phones.
 */
@Composable
fun Modifier.touchClick(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    return pointerInput(Unit) {
        detectTapGestures(
            onTap = { click() },
            onLongPress = { longClick?.invoke() },
        )
    }
}
