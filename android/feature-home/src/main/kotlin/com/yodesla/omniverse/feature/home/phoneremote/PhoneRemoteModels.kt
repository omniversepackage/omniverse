package com.yodesla.omniverse.feature.home.phoneremote

import androidx.compose.runtime.Immutable
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.RemoteId

/** What the TV shows in Settings > Phone remote and in the "Phone connected" chip. */
@Immutable
data class PhoneRemoteUiState(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val url: String? = null,
    val code: String? = null,
    val connected: Boolean = false,
    val error: String? = null,
)

/** One searchable result, flattened for the phone page. [action] is "channel", "movie" or "show". */
@Immutable
data class RemoteResult(
    val kind: String,
    val sourceId: String,
    val remoteId: String,
    val categoryId: String?,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val action: String,
)

enum class PhoneRemoteKey { Up, Down, Left, Right, Ok, Back, PlayPause }

/** A request from the phone, already parsed. Delivered to the shell for routing. */
sealed class PhoneRemoteAction {
    data class Key(val key: PhoneRemoteKey) : PhoneRemoteAction()
    data class PlayChannel(val key: ContentKey, val categoryId: RemoteId) : PhoneRemoteAction()
    data class PlayMovie(val key: ContentKey) : PhoneRemoteAction()
    data class OpenShow(val key: ContentKey) : PhoneRemoteAction()

    /** Task 91 Now Playing controls; routed to the active player, ignored when nothing plays. */
    data class Seek(val positionMs: Long) : PhoneRemoteAction()
    data class Skip(val deltaMs: Long) : PhoneRemoteAction()
    data class SelectAudio(val trackId: String) : PhoneRemoteAction()
    /** null = subtitles off. */
    data class SelectSubtitle(val trackId: String?) : PhoneRemoteAction()
}
