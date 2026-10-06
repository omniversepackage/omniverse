package com.yodesla.omniverse.app

import android.service.dreams.DreamService
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yodesla.omniverse.core.data.CatalogRepository
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.data.Visibility
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.designsystem.ClockFormat
import com.yodesla.omniverse.designsystem.CosmicBackdrop
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.OmniverseTheme
import com.yodesla.omniverse.designsystem.parseAccent
import com.yodesla.omniverse.designsystem.rememberTimeText
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** One artwork slot before the crossfade to the next (task 70: ~12 s). */
internal const val SCREENSAVER_SLOT_MS = 12_000

/** The Ken Burns push never asks for more than 30 fps: no video, cheap on old TV boxes. */
private const val FRAME_BUDGET_NS = 1_000_000_000L / 30L

/**
 * Android TV screensaver (DreamService). A slow Ken-Burns pan/zoom over catalog artwork of
 * Continue Watching + recently added titles the viewer is allowed to see, crossfading every
 * [SCREENSAVER_SLOT_MS], with a large clock/date bottom-right and the title name bottom-left.
 * Only artwork URLs the catalog already holds are loaded (Coil); nothing is asked of a provider.
 * With no art for a slot the cosmic backdrop simply shows on its own.
 */
class OmniverseDreamService : DreamService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private var view: ComposeView? = null

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        ClockFormat.use24h = android.text.format.DateFormat.is24HourFormat(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setFullscreen(true)
        // Any remote press ends the dream; the screensaver never takes input.
        setInteractive(false)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        val compose = ComposeView(this)
        // DreamService is a plain Service: Compose needs its owners wired by hand.
        compose.setViewTreeLifecycleOwner(this)
        compose.setViewTreeViewModelStoreOwner(this)
        compose.setViewTreeSavedStateRegistryOwner(this)
        setContentView(compose)
        val app = application as? OmniverseApp
        val graph = app?.graph
        if (graph != null) {
            val accent = parseAccent(app.brand.accentColor)
            compose.setContent { OmniverseTheme(accent = accent, publicBuild = graph.publicBuild) { ScreensaverRoute(graph) } }
        }
        view = compose
    }

    override fun onDetachedFromWindow() {
        view = null
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }
}

/** Catalog artwork the screensaver may show right now, in rotation order. */
class ScreensaverViewModel(
    catalog: CatalogRepository,
    userData: UserDataRepository,
    visibility: Flow<Visibility>,
) : ViewModel() {
    private val _items = MutableStateFlow<List<ScreensaverItem>>(emptyList())
    val items: StateFlow<List<ScreensaverItem>> = _items.asStateFlow()

    init {
        combine(
            userData.continueWatching(20),
            catalog.recentlyAdded(ContentKind.VOD, 30),
            catalog.recentlyAdded(ContentKind.SERIES, 30),
            visibility,
        ) { progress, movies, shows, vis ->
            pickScreensaverArt(progress, movies, shows, vis) { key -> catalog.poster(key) }
        }.onEach { list -> _items.value = list }.launchIn(viewModelScope)
    }
}

@Composable
private fun ScreensaverRoute(graph: AppGraph) {
    val vm: ScreensaverViewModel = viewModel { ScreensaverViewModel(graph.catalog, graph.userData, graph.browseVisibility) }
    val items by vm.items.collectAsStateWithLifecycle()
    ScreensaverUi(items)
}

@Composable
internal fun ScreensaverUi(items: List<ScreensaverItem>) {
    val c = OmniTheme.colors
    CosmicBackdrop(Modifier.fillMaxSize()) {
        var index by remember { mutableIntStateOf(0) }
        LaunchedEffect(items.size) {
            if (items.size < 2) return@LaunchedEffect
            while (true) {
                delay(SCREENSAVER_SLOT_MS.toLong())
                index = (index + 1) % items.size
            }
        }
        val current = items.getOrNull(index)
        Crossfade(current, animationSpec = tween(SCREENSAVER_SLOT_MS), label = "dream-art", modifier = Modifier.fillMaxSize()) { item ->
            ArtFrame(item)
        }
        // Bottom scrim so the clock and the name stay readable over any artwork.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to c.scrim)))
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = OmniSpacing.tvSide, bottom = OmniSpacing.tvTopBottom),
            horizontalAlignment = Alignment.End,
        ) {
            Text(rememberTimeText(), style = OmniTheme.type.display, color = c.textPrimary)
            Text(rememberDateText(), style = OmniTheme.type.caption, color = c.textSecondary)
        }
        Text(
            current?.title ?: " ",
            style = OmniTheme.type.title, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = OmniSpacing.tvSide, bottom = OmniSpacing.tvTopBottom),
        )
    }
}

/** One slot: the artwork with its slow push, or nothing (the cosmic backdrop) when there is no art. */
@Composable
private fun ArtFrame(item: ScreensaverItem?) {
    if (item?.image == null) return
    var zoom by remember(item.key) { mutableFloatStateOf(1.06f) }
    var pan by remember(item.key) { mutableFloatStateOf(-0.02f) }
    LaunchedEffect(item.key) {
        val startNs = withFrameNanos { it }
        var lastNs = 0L
        while (true) {
            val nowNs = withFrameNanos { it }
            if (lastNs != 0L) {
                val spent = nowNs - lastNs
                if (spent < FRAME_BUDGET_NS) delay((FRAME_BUDGET_NS - spent) / 1_000_000L)
            }
            lastNs = nowNs
            val t = ((nowNs - startNs) / 1_000_000_000f / (SCREENSAVER_SLOT_MS / 1000f)).coerceIn(0f, 1f)
            zoom = 1.06f + 0.06f * t
            pan = 0.02f * (2f * t - 1f)
        }
    }
    AsyncImage(
        model = item.image, contentDescription = null,
        contentScale = ContentScale.Crop, alignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationY = pan * size.height },
    )
}

/** Date under the clock; refreshed on the same minute boundary as [rememberTimeText]. */
@Composable
private fun rememberDateText(): String {
    fun today(): String = DateFormat.getDateInstance(DateFormat.LONG).format(Date())
    var text by remember { mutableStateOf(today()) }
    LaunchedEffect(Unit) {
        while (true) {
            val c = Calendar.getInstance()
            val msToNextMinute = 60_000L - (c.get(Calendar.SECOND) * 1_000L + c.get(Calendar.MILLISECOND))
            delay(msToNextMinute + 50)
            text = today()
        }
    }
    return text
}
