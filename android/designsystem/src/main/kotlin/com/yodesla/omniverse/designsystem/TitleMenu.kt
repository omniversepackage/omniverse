package com.yodesla.omniverse.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

/** One row in a [TitleMenu]. Destructive rows sit last and read in the live/red colour. */
@Immutable
data class MenuAction(val label: String, val onClick: () -> Unit, val destructive: Boolean = false)

/**
 * Hold-OK menu for a movie or show: poster + title on the left, actions on the right. Back (or
 * any action) closes it. Callers decide which actions apply (e.g. "Remove from Continue
 * Watching" only for a Continue Watching card).
 */
@Composable
fun TitleMenu(title: String, subtitle: String?, posterUrl: String?, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val first = remember(title) { FocusRequester() }
    // The menu opens while OK is still held (hold-to-open). Ignore OK until it has been pressed DOWN
    // again inside the menu, so releasing the hold doesn't instantly pick the first action.
    val armed = remember(title) { androidx.compose.runtime.mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC03060C))
            .onPreviewKeyEvent { e ->
                when {
                    e.key == Key.Back -> { if (e.type == KeyEventType.KeyUp) onDismiss(); true }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) armed.value = true
                        !armed.value // swallow the held key's repeats and its release until a fresh press
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.widthIn(max = 820.dp).clip(RoundedCornerShape(22.dp)).background(c.elevated).padding(OmniSpacing.xl),
            horizontalArrangement = Arrangement.spacedBy(OmniSpacing.xl),
        ) {
            Box(Modifier.width(170.dp).height(255.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)) {
                if (!posterUrl.isNullOrBlank()) AsyncImage(model = posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.widthIn(min = 320.dp, max = 460.dp), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                Text(title, style = t.headline, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = t.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                actions.forEachIndexed { i, a ->
                    FocusCard(onClick = { onDismiss(); a.onClick() },
                        modifier = Modifier.fillMaxWidth().height(52.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier)) {
                        Box(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.l), contentAlignment = Alignment.CenterStart) {
                            Text(a.label, style = t.body.copy(fontWeight = FontWeight.SemiBold), color = if (a.destructive) c.live else c.textPrimary)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(title) { first.requestFocusWhenReady() }
}

/**
 * Task 84m: the hold-OK menu for a category rail entry — a compact glass card (no poster) anchored
 * next to the rail via [modifier], same armed-OK/Back behavior as [TitleMenu]. Callers pass
 * Move / Hide category / Cancel.
 */
@Composable
fun CategoryMenu(title: String, actions: List<MenuAction>, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val first = remember(title) { FocusRequester() }
    // The menu opens while OK is still held (hold-to-open): ignore OK until it is pressed down again
    // inside the menu, so releasing the hold doesn't instantly pick the first action.
    val armed = remember(title) { androidx.compose.runtime.mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC03060C))
            .onPreviewKeyEvent { e ->
                when {
                    e.key == Key.Back -> { if (e.type == KeyEventType.KeyUp) onDismiss(); true }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) armed.value = true
                        !armed.value // swallow the held key's repeats and its release until a fresh press
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(22.dp)).background(c.elevated).padding(OmniSpacing.l),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.s),
        ) {
            Text(title, style = t.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions.forEachIndexed { i, a ->
                FocusCard(onClick = { onDismiss(); a.onClick() },
                    modifier = Modifier.fillMaxWidth().height(52.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier)) {
                    Box(Modifier.fillMaxSize().padding(horizontal = OmniSpacing.l), contentAlignment = Alignment.CenterStart) {
                        Text(a.label, style = t.body.copy(fontWeight = FontWeight.SemiBold), color = if (a.destructive) c.live else c.textPrimary)
                    }
                }
            }
        }
    }
    LaunchedEffect(title) { first.requestFocusWhenReady() }
}

/** Task 84m: one-shot "Category hidden · Undo" toast state, carried in the screen's UI state. */
@Immutable
data class HiddenNotice(val categoryId: String, val name: String)

/**
 * Task 84m: the small glass toast after a category is hidden. Focus stays where the viewer is (the
 * next category); Undo is reachable by D-pad, and the card dismisses itself after [ttlMs].
 */
@Composable
fun UndoNotice(message: String, actionLabel: String, onAction: () -> Unit, onDismiss: () -> Unit, ttlMs: Long = 6_000L, modifier: Modifier = Modifier) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    LaunchedEffect(message) { kotlinx.coroutines.delay(ttlMs); onDismiss() }
    Row(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(c.elevated).padding(horizontal = OmniSpacing.l, vertical = OmniSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OmniSpacing.l),
    ) {
        Text(message, style = t.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OmniButton(actionLabel, onAction)
    }
}

/** Builds the standard action list; [onRemoveContinue] null hides that row (not a Continue Watching card). */
fun titleMenuActions(
    resumeLabel: String?,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onDetails: () -> Unit,
    inMyList: Boolean,
    onToggleMyList: () -> Unit,
    inQueue: Boolean,
    onToggleQueue: () -> Unit,
    onRemoveContinue: (() -> Unit)?,
): List<MenuAction> = buildList {
    if (resumeLabel != null) add(MenuAction(resumeLabel, onResume))
    add(MenuAction(if (resumeLabel != null) "Start over" else "Play", onStartOver))
    add(MenuAction("Details", onDetails))
    add(MenuAction(if (inMyList) "Remove from My List" else "Add to My List", onToggleMyList))
    add(MenuAction(if (inQueue) "Remove from Play next" else "Add to Play next", onToggleQueue))
    if (onRemoveContinue != null) add(MenuAction("Remove from Continue Watching", onRemoveContinue, destructive = true))
}
