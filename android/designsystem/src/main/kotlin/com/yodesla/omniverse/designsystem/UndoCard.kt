package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * Task 90: the app-wide undo prompt. A bottom-centre glass card that names what was just removed
 * and offers a single [OmniButton] "Undo". It never steals focus (the viewer reaches it with Down
 * from the row they dismissed); Back dismisses it; it also fades on its own after [timeoutMs].
 *
 * Hosts align it with `Modifier.align(Alignment.BottomCenter)` inside their root Box.
 */
@Composable
fun UndoCard(
    message: String,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    timeoutMs: Long = 6_000L,
) {
    val t = OmniTheme.type
    LaunchedEffect(message) { delay(timeoutMs); onDismiss() }
    Row(
        modifier
            .glassSurface(corner = 18.dp, shadow = false)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back && e.type == KeyEventType.KeyUp) { onDismiss(); true } else false
            }
            .padding(horizontal = OmniSpacing.l, vertical = OmniSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.l),
    ) {
        Text(
            message,
            style = t.body.copy(shadow = null),
            color = OmniTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = OmniSpacing.xs),
        )
        OmniButton("Undo", onUndo)
    }
}
