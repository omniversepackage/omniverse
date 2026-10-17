package com.yodesla.omniverse.app

import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yodesla.omniverse.core.model.*
import com.yodesla.omniverse.designsystem.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One live favorites controller for browsing, overlays, mini-player and fullscreen playback. */
@Composable
internal fun ChannelFavoritesHost(graph: AppGraph, content: @Composable () -> Unit) {
    val keys by graph.userData.favorites(ContentKind.LIVE).collectAsStateWithLifecycle(emptyList())
    var menu by remember { mutableStateOf<FavoriteChannel?>(null) }
    val scope = rememberCoroutineScope()
    val mutex = remember { Mutex() }
    fun FavoriteChannel.key() = ContentKey(SourceId(source), ContentKind.LIVE, RemoteId(remote))
    val toggle: (FavoriteChannel) -> Unit = { channel -> scope.launch {
        mutex.withLock {
            val key = channel.key()
            val row = graph.catalog.channel(key)
            val visible = graph.favoritesVisibility.first()
            val hidden = graph.userData.hiddenCategoryKeys().first()
            if (row != null && visible("LIVE", key.sourceId.value, row.categoryId.value) &&
                "LIVE|${key.sourceId.value}|${row.categoryId.value}" !in hidden) {
                graph.userData.setFavorite(key, !graph.userData.isFavorite(key).first())
            }
        }
    } }
    CompositionLocalProvider(LocalChannelFavorites provides ChannelFavorites(
        contains = { it.key() in keys }, toggle = toggle, openMenu = { menu = it },
    )) {
        content()
        menu?.let { channel ->
            Dialog(onDismissRequest = { menu = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                TitleMenu(channel.name, "Live TV", channel.logo,
                    listOf(MenuAction(if (channel.key() in keys) "Remove channel from Favorites" else "Add channel to Favorites",
                        { toggle(channel); menu = null })), onDismiss = { menu = null })
            }
        }
    }
}
