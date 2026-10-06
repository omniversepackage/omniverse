package com.yodesla.omniverse.feature.live.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.reminders.Reminder
import com.yodesla.omniverse.core.data.reminders.ReminderStore
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.requestFocusWhenReady
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * App-wide "Starting now" card. Appears (and takes focus) when a reminded programme
 * starts while the app is open; Watch tunes the channel, Dismiss clears it. Locked
 * channels never pop up. Place it as the last child of the root Box.
 */
@Composable
fun ReminderBanner(
    store: ReminderStore,
    clock: Clock,
    visibility: Flow<Visibility>,
    modifier: Modifier = Modifier,
    onWatch: (key: ContentKey, categoryId: RemoteId) -> Unit,
) {
    val reminders by store.reminders.collectAsState(initial = emptyList())
    var due by remember { mutableStateOf<Reminder?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reminders) {
        while (true) {
            val now = clock.nowMs()
            val allowed = visibility.first()
            val next = reminders.filter { it.endMs > now && allowed("LIVE", it.sourceId, it.categoryId) }.minByOrNull { it.startMs }
            if (next == null) { due = null; return@LaunchedEffect }
            val wait = next.startMs - now - LEAD_MS
            if (wait <= 0) { due = next; return@LaunchedEffect }
            delay(wait.coerceAtMost(60_000))
        }
    }
    val r = due ?: return
    val c = OmniTheme.colors
    val t = OmniTheme.type
    val watch = remember(r.id) { FocusRequester() }
    fun dismiss() { scope.launch { store.remove(r.id) }; due = null }
    LaunchedEffect(r.id) { watch.requestFocusWhenReady() }
    // A focusable popup is its own window: the screen underneath (Home rows loading, Sports
    // focusing its hero) can no longer pull focus away and strand the remote (Kory, 2026-10-01).
    // Back dismisses it.
    androidx.compose.ui.window.Popup(
        alignment = Alignment.BottomEnd,
        onDismissRequest = ::dismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Column(
            modifier.padding(OmniSpacing.xl).widthIn(max = 460.dp).clip(RoundedCornerShape(18.dp))
                .background(c.elevated.copy(alpha = 0.96f)).padding(OmniSpacing.l),
            verticalArrangement = Arrangement.spacedBy(OmniSpacing.xs),
        ) {
            Text("STARTING NOW", style = t.overline, color = c.accent)
            Text(r.title, style = t.title, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(r.channelName, style = t.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = OmniSpacing.s), horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m)) {
                OmniButton("Watch", {
                    scope.launch { store.remove(r.id) }
                    due = null
                    onWatch(ContentKey(SourceId(r.sourceId), ContentKind.LIVE, RemoteId(r.channelId)), RemoteId(r.categoryId))
                }, Modifier.focusRequester(watch), primary = true)
                OmniButton("Dismiss", ::dismiss)
            }
        }
    }
}

/** Show the card this long before the start time, so the viewer catches the opening. */
private const val LEAD_MS = 30_000L
