package com.yodesla.omniverse.feature.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.yodesla.omniverse.core.data.*
import com.yodesla.omniverse.designsystem.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val LIVE_HIDDEN_PROVIDERS = "live_hidden_providers"
internal data class LiveProviderChoice(val id: String, val name: String, val shown: Boolean)

/** Browsing preference only; backup streams still have access to the configured providers. */
internal class LiveProviderFilter(sources: SourceRepository, private val user: UserDataRepository?) {
    private val local = MutableStateFlow<String?>(null)
    private val saved = user?.setting(LIVE_HIDDEN_PROVIDERS) ?: local
    private val lock = Mutex()
    val hiddenIds = saved.map { it.orEmpty().lineSequence().filter(String::isNotBlank).toSet() }
    val visibility: Flow<Visibility> = hiddenIds.map { hidden -> { _, source, _ -> source !in hidden } }
    val choices = combine(sources.sources(), hiddenIds) { all, hidden ->
        all.filter { it.isLiveCapable() }.map { LiveProviderChoice(it.id.value, it.name, it.id.value !in hidden) }
    }
    private suspend fun write(hidden: Set<String>) {
        val value = hidden.sorted().joinToString("\n")
        if (user == null) local.value = value else user.putSetting(LIVE_HIDDEN_PROVIDERS, value)
    }
    suspend fun toggle(id: String) = lock.withLock {
        val hidden = hiddenIds.first()
        write(if (id in hidden) hidden - id else hidden + id)
    }
    suspend fun showAll() = lock.withLock { write(emptySet()) }
}

@Composable
internal fun LiveProviderButton(filter: LiveProviderFilter) {
    val choices by filter.choices.collectAsStateWithLifecycle(initialValue = emptyList())
    if (choices.size < 2 && choices.all { it.shown }) return
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shown = choices.count { it.shown }
    val label = if (shown == choices.size) "All providers" else "Providers: $shown/${choices.size}"
    val margin = with(LocalDensity.current) { 16.dp.roundToPx() }
    val configuration = LocalConfiguration.current
    val position = remember(margin) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val below = anchorBounds.bottom + margin / 2
            val top = if (below + popupContentSize.height <= windowSize.height - margin) below else anchorBounds.top - popupContentSize.height - margin / 2
            return IntOffset((anchorBounds.right - popupContentSize.width).coerceIn(margin, maxOf(margin, windowSize.width - popupContentSize.width - margin)),
                top.coerceIn(margin, maxOf(margin, windowSize.height - popupContentSize.height - margin)))
        }
    } }
    Box {
        OmniButton("$label ▾", { open = !open })
        if (open) Popup(popupPositionProvider = position, onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true)) {
            val first = remember { FocusRequester() }
            Column(Modifier.width(minOf(340, configuration.screenWidthDp - 32).coerceAtLeast(120).dp)
                .heightIn(max = minOf(380, configuration.screenHeightDp - 32).coerceAtLeast(120).dp).glassSurface().verticalScroll(rememberScrollState())
                .padding(OmniSpacing.l), verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                Text("Browse providers", style = OmniTheme.type.title, color = OmniTheme.colors.textPrimary)
                OmniButton("Show all providers", { scope.launch { filter.showAll() } }, Modifier.focusRequester(first))
                choices.forEach { choice ->
                    OmniButton("${if (choice.shown) "☑" else "☐"} ${choice.name}", { scope.launch { filter.toggle(choice.id) } })
                }
                if (shown == 0) Text("Select a provider to show channels.", style = OmniTheme.type.caption, color = OmniTheme.colors.textSecondary)
                OmniButton("Done", { open = false })
            }
            LaunchedEffect(Unit) { first.requestFocusWhenReady() }
        }
    }
}
