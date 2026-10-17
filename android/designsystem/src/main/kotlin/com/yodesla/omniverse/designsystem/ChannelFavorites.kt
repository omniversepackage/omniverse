package com.yodesla.omniverse.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** UI-only channel identity; persistence and profile policy are supplied by the app. */
data class FavoriteChannel(val source: String, val remote: String, val name: String, val logo: String? = null)
class ChannelFavorites(
    val contains: (FavoriteChannel) -> Boolean,
    val toggle: (FavoriteChannel) -> Unit,
    val openMenu: (FavoriteChannel) -> Unit,
)
val LocalChannelFavorites = staticCompositionLocalOf<ChannelFavorites?> { null }

@Composable
fun channelFavoriteMenu(source: String, remote: String, name: String, logo: String? = null): () -> Unit {
    val favorites = LocalChannelFavorites.current
    return { favorites?.openMenu?.invoke(FavoriteChannel(source, remote, name, logo)) }
}

@Composable
fun ChannelFavoriteButton(channel: FavoriteChannel, modifier: Modifier = Modifier, compact: Boolean = false) {
    val favorites = LocalChannelFavorites.current ?: return
    OmniButton(if (favorites.contains(channel)) { if (compact) "★ Saved" else "★ Remove from Favorites" } else { if (compact) "☆ Favorite" else "☆ Add to Favorites" },
        { favorites.toggle(channel) }, modifier)
}
